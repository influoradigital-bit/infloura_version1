"""SSRF guard — launch-blocking control for `app/routes/analyze_site.py`.

Every outbound fetch to a brand-supplied URL MUST go through `guarded_fetch()`.
This module implements, for real (it will be red-teamed):

1. Scheme allow-list: https only.
2. DNS resolved FIRST; private/loopback/link-local/CGNAT ranges blocked, plus the
   cloud-metadata IP 169.254.169.254 blocked explicitly (it is also within the
   169.254/16 block, but we check it by name too for clarity/defense-in-depth).
3. DNS-rebind protection: resolve once, PIN the first-resolved IP, and connect to
   that pinned IP for every request on this fetch (including redirect hops to the
   same host) — never re-resolve mid-flight.
4. Redirect cap <= 2, and EVERY hop is re-validated against the same rules
   (scheme, IP-block checks) before being followed; the pinned-IP rule applies
   per-hop (each new host gets its own fresh resolve + pin + validate).
5. Response size cap and timeout cap.

Blocked ranges: 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, ::1, fc00::/7,
plus 0.0.0.0/8 and multicast/reserved as defense-in-depth.
"""

from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from urllib.parse import urlparse

import httpx

from app.config import get_settings

METADATA_IP = "169.254.169.254"

# Explicit blocked networks (Kabir-specified list + standard defense-in-depth extras).
_BLOCKED_NETWORKS: tuple[ipaddress._BaseNetwork, ...] = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("169.254.0.0/16"),  # includes 169.254.169.254
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("fe80::/10"),  # link-local v6, defense-in-depth
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("100.64.0.0/10"),  # CGNAT, defense-in-depth
    ipaddress.ip_network("224.0.0.0/4"),  # multicast
)


class SsrfBlockedError(Exception):
    """Raised whenever a URL/IP/redirect fails SSRF validation. Callers must treat
    this as a hard reject — never fall through to a real network call."""


@dataclass(frozen=True)
class PinnedTarget:
    """A hostname pinned to a single validated IP for the lifetime of one fetch hop."""

    host: str
    port: int
    ip: str
    scheme: str


def _is_blocked_ip(ip_str: str) -> bool:
    if ip_str == METADATA_IP:
        return True
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # unparsable -> fail closed
    if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
        return True
    if ip.is_unspecified:
        return True
    for net in _BLOCKED_NETWORKS:
        if ip in net:
            return True
    return False


def _validate_scheme(url: str) -> str:
    settings = get_settings()
    parsed = urlparse(url)
    if parsed.scheme not in settings.ssrf_allowed_schemes:
        raise SsrfBlockedError(f"scheme not allowed: {parsed.scheme!r}")
    if not parsed.hostname:
        raise SsrfBlockedError("URL has no hostname")
    return parsed.scheme


def resolve_and_pin(url: str) -> PinnedTarget:
    """Resolve DNS for `url`'s host, validate every resolved address, and pin the
    first *valid* (non-blocked) address. Raises SsrfBlockedError if the host has
    no valid address or scheme is disallowed.
    """
    scheme = _validate_scheme(url)
    parsed = urlparse(url)
    host = parsed.hostname
    if not host:  # _validate_scheme already enforces this; narrows the type too
        raise SsrfBlockedError("URL has no hostname")
    port = parsed.port or (443 if scheme == "https" else 80)

    try:
        addr_infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except socket.gaierror as exc:
        raise SsrfBlockedError(f"DNS resolution failed for {host!r}: {exc}") from None

    if not addr_infos:
        raise SsrfBlockedError(f"no addresses resolved for {host!r}")

    # Reject the whole host if ANY resolved address is private/blocked. A host that
    # resolves to a mix of public+private addresses is a rebind/multi-homing risk.
    #
    # F-11: `sockaddr[0]` is typed `str | int` (AF_UNIX sockaddrs are ints), which
    # is what mypy flagged here; for the INET families it is always the address
    # string, and str() keeps _is_blocked_ip's fail-closed contract for anything
    # else — an unparsable value is blocked, never passed through.
    resolved_ips: list[str] = []
    for _family, _type, _proto, _canon, sockaddr in addr_infos:
        ip_str = str(sockaddr[0])
        resolved_ips.append(ip_str)
        if _is_blocked_ip(ip_str):
            raise SsrfBlockedError(f"resolved address {ip_str} for {host!r} is blocked")

    pinned_ip = resolved_ips[0]
    return PinnedTarget(host=host, port=port, ip=pinned_ip, scheme=scheme)


def _authority(ip: str, port: int) -> str:
    """`ip:port` for a URL authority, with IPv6 literals bracketed.

    F-11: this used to be a bare f"{ip}:{port}". For an IPv6 address that
    produces `https://2606:4700::1111:443/...`, which httpx rejects with
    `httpx.InvalidURL` — NOT a subclass of `httpx.HTTPError`, so it escaped the
    `except` below and surfaced as an unhandled 500. Any host whose AAAA record
    is ordered first by getaddrinfo (the RFC 6724 default on a dual-stack host)
    took that path, so the guard did not fail closed on IPv6 — it fell over.
    """
    try:
        parsed_ip = ipaddress.ip_address(ip)
    except ValueError:
        return f"{ip}:{port}"
    if parsed_ip.version == 6:
        return f"[{ip}]:{port}"
    return f"{ip}:{port}"


def guarded_fetch(url: str, *, max_bytes: int | None = None, timeout: float | None = None) -> tuple[bytes, str]:
    """Fetch `url` through the SSRF guard end-to-end, including manual redirect
    handling (cap <= 2, every hop re-validated + re-pinned).

    Returns (body_bytes, final_url). Raises SsrfBlockedError on any violation.
    """
    settings = get_settings()
    max_bytes = max_bytes or settings.ssrf_max_response_bytes
    timeout = timeout or settings.ssrf_fetch_timeout_seconds

    current_url = url
    hops = 0
    max_hops = settings.ssrf_max_redirects

    while True:
        target = resolve_and_pin(current_url)
        parsed = urlparse(current_url)
        path_qs = parsed.path or "/"
        if parsed.query:
            path_qs += f"?{parsed.query}"

        # Connect directly to the pinned IP; send the original Host header and TLS
        # SNI so certificate validation still matches the intended hostname, but
        # the TCP connection itself can never be swapped out from under us by a
        # DNS answer that changes between resolution and connection (rebind).
        with httpx.Client(
            transport=httpx.HTTPTransport(retries=0),
            timeout=timeout,
            follow_redirects=False,
            verify=True,
        ) as client:
            pinned_url = f"{target.scheme}://{_authority(target.ip, target.port)}{path_qs}"
            headers = {"Host": target.host}
            # F-11: STREAM the response and stop reading the moment the cap is
            # exceeded. The previous `client.get()` was non-streaming, so
            # `response.content` buffered the ENTIRE body into memory before
            # `len(body) > max_bytes` was ever evaluated — a measured 78MB was
            # fully buffered against a 5MB cap, and a chunked response with no
            # Content-Length could OOM the pod. The cap is now enforced during
            # the read, not after it.
            #
            # `httpx.InvalidURL` is caught alongside `httpx.HTTPError` because it
            # is NOT a subclass of it (see _authority) — anything the client
            # raises here must fail CLOSED as an SSRF rejection, never escape as
            # an unhandled 500.
            try:
                with client.stream(
                    "GET",
                    pinned_url,
                    headers=headers,
                    extensions={"sni_hostname": target.host},
                ) as response:
                    status_code = response.status_code
                    response_headers = response.headers

                    if status_code in (301, 302, 303, 307, 308):
                        body = b""
                    else:
                        content_length = response_headers.get("content-length")
                        if content_length:
                            try:
                                declared = int(content_length)
                            except ValueError:
                                declared = -1
                            if declared > max_bytes:
                                raise SsrfBlockedError("response exceeds max size cap")

                        chunks: list[bytes] = []
                        total = 0
                        for chunk in response.iter_bytes():
                            total += len(chunk)
                            if total > max_bytes:
                                # Abort mid-stream: the connection is closed by the
                                # context manager and the rest is never read.
                                raise SsrfBlockedError("response exceeds max size cap")
                            chunks.append(chunk)
                        body = b"".join(chunks)
            except SsrfBlockedError:
                raise
            except (httpx.HTTPError, httpx.InvalidURL) as exc:
                raise SsrfBlockedError(f"fetch failed: {exc}") from exc

        if status_code in (301, 302, 303, 307, 308):
            hops += 1
            if hops > max_hops:
                raise SsrfBlockedError(f"too many redirects (> {max_hops})")
            location = response_headers.get("location")
            if not location:
                raise SsrfBlockedError("redirect with no Location header")
            # Resolve relative redirects against the current URL.
            current_url = str(httpx.URL(current_url).join(location))
            continue  # re-validate + re-pin the new hop from scratch

        return body, current_url


# F-11 (Priya round 6): a `guarded_fetch_async` wrapper used to live here and
# was dead — the real offload is inlined at `app/routes/analyze_site.py`, which
# keeps `guarded_fetch` patchable by the route's own tests. Its docstring
# presented it as the F-11 fix, so anyone reading this module would have
# believed the offload lived here. Deleted; read the call site instead.
