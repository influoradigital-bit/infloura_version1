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
    port = parsed.port or (443 if scheme == "https" else 80)

    try:
        addr_infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except socket.gaierror as exc:
        raise SsrfBlockedError(f"DNS resolution failed for {host!r}: {exc}") from None

    if not addr_infos:
        raise SsrfBlockedError(f"no addresses resolved for {host!r}")

    # Reject the whole host if ANY resolved address is private/blocked. A host that
    # resolves to a mix of public+private addresses is a rebind/multi-homing risk.
    resolved_ips: list[str] = []
    for family, _type, _proto, _canon, sockaddr in addr_infos:
        ip_str = sockaddr[0]
        resolved_ips.append(ip_str)
        if _is_blocked_ip(ip_str):
            raise SsrfBlockedError(f"resolved address {ip_str} for {host!r} is blocked")

    pinned_ip = resolved_ips[0]
    return PinnedTarget(host=host, port=port, ip=pinned_ip, scheme=scheme)


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
            netloc = f"{target.ip}:{target.port}"
            pinned_url = f"{target.scheme}://{netloc}{path_qs}"
            headers = {"Host": target.host}
            try:
                response = client.get(
                    pinned_url,
                    headers=headers,
                    extensions={"sni_hostname": target.host},
                )
            except httpx.HTTPError as exc:
                raise SsrfBlockedError(f"fetch failed: {exc}") from exc

        if response.status_code in (301, 302, 303, 307, 308):
            hops += 1
            if hops > max_hops:
                raise SsrfBlockedError(f"too many redirects (> {max_hops})")
            location = response.headers.get("location")
            if not location:
                raise SsrfBlockedError("redirect with no Location header")
            # Resolve relative redirects against the current URL.
            current_url = str(httpx.URL(current_url).join(location))
            continue  # re-validate + re-pin the new hop from scratch

        content_length = response.headers.get("content-length")
        if content_length and int(content_length) > max_bytes:
            raise SsrfBlockedError("response exceeds max size cap")

        body = response.content
        if len(body) > max_bytes:
            raise SsrfBlockedError("response exceeds max size cap")

        return body, current_url
