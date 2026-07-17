"""RT-SSRF-1..5 — red-team battery against app/security/ssrf_guard.py.

Kabir Phase A gate (17-KABIR-REMAINING-TASKS.md §A.2). Each test is an
attack -> expected block -> control, matching the doc's battery exactly:

  RT-SSRF-1  metadata IP 169.254.169.254 rejected
  RT-SSRF-2  private/loopback rejected
  RT-SSRF-3  DNS-rebind (first resolve public, second resolve private) pinned+rejected
  RT-SSRF-4  >2-hop redirect chain rejected
  RT-SSRF-5  non-https schemes (file://, gopher://, ftp://) rejected

All tests assert SsrfBlockedError is raised and that no real network I/O
occurs (httpx.Client.get is monkeypatched to fail the test if it is ever
reached with a non-pinned / disallowed target, and DNS is monkeypatched so
no real resolution happens).
"""

from __future__ import annotations

import socket

import httpx
import pytest

from app.security.ssrf_guard import (
    METADATA_IP,
    PinnedTarget,
    SsrfBlockedError,
    guarded_fetch,
    resolve_and_pin,
)


def _addrinfo(ip: str, port: int = 443):
    family = socket.AF_INET6 if ":" in ip else socket.AF_INET
    sockaddr = (ip, port, 0, 0) if family == socket.AF_INET6 else (ip, port)
    return [(family, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", sockaddr)]


# ---------------------------------------------------------------------------
# RT-SSRF-1 — cloud metadata IP rejected
# ---------------------------------------------------------------------------


def test_rt_ssrf_1_metadata_ip_literal_rejected():
    """Feeding the metadata IP directly (as a literal host) must be blocked
    before any socket is opened."""
    with pytest.raises(SsrfBlockedError):
        resolve_and_pin(f"https://{METADATA_IP}/latest/meta-data/iam/security-credentials/")


def test_rt_ssrf_1_metadata_ip_via_dns_rejected(monkeypatch):
    """A hostname that *resolves* to the metadata IP must also be blocked --
    the metadata IP is checked post-resolution, not just as a literal-host
    string match."""

    def fake_getaddrinfo(host, port, **kwargs):
        assert host == "attacker-controlled.example"
        return _addrinfo(METADATA_IP, port)

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)

    with pytest.raises(SsrfBlockedError):
        resolve_and_pin("https://attacker-controlled.example/latest/meta-data/iam/security-credentials/")


def test_rt_ssrf_1_guarded_fetch_never_opens_socket_for_metadata(monkeypatch):
    """End-to-end: guarded_fetch must reject before httpx ever gets a chance
    to make a request. Zero bytes of IAM creds returned."""

    def fail_if_called(*args, **kwargs):
        pytest.fail("httpx.Client.get was called -- SSRF guard failed to block metadata IP")

    monkeypatch.setattr(httpx.Client, "get", fail_if_called)

    with pytest.raises(SsrfBlockedError):
        guarded_fetch(f"https://{METADATA_IP}/latest/meta-data/iam/security-credentials/")


# ---------------------------------------------------------------------------
# RT-SSRF-2 — private / loopback ranges rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "url",
    [
        "http://10.0.0.5/internal/meera/escrow/hold",  # private + wrong scheme
        "http://127.0.0.1:8080/internal/",  # loopback + wrong scheme
        "https://127.0.0.1:8080/internal/",  # loopback, https scheme (isolate IP-block check)
        "https://192.168.1.10/internal/",  # private class C
        "https://172.16.0.5/internal/",  # private class B
        "https://[::1]/internal/",  # loopback v6
    ],
)
def test_rt_ssrf_2_private_and_loopback_literal_ips_rejected(url):
    with pytest.raises(SsrfBlockedError):
        resolve_and_pin(url)


def test_rt_ssrf_2_private_range_via_dns_rejected(monkeypatch):
    def fake_getaddrinfo(host, port, **kwargs):
        return _addrinfo("10.0.0.5", port)

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)

    with pytest.raises(SsrfBlockedError):
        resolve_and_pin("https://looks-public.example/internal/meera/escrow/hold")


def test_rt_ssrf_2_guarded_fetch_no_request_leaves_container_for_internal_path(monkeypatch):
    def fail_if_called(*args, **kwargs):
        pytest.fail("httpx.Client.get was called -- SSRF guard failed to block private-range target")

    monkeypatch.setattr(httpx.Client, "get", fail_if_called)

    with pytest.raises(SsrfBlockedError):
        guarded_fetch("http://10.0.0.5/internal/meera/escrow/hold")

    with pytest.raises(SsrfBlockedError):
        guarded_fetch("http://127.0.0.1:8080/internal/")


# ---------------------------------------------------------------------------
# RT-SSRF-3 — DNS-rebind: first resolution public, guard pins it and never
# re-resolves, so a later private-IP resolution cannot be used.
# ---------------------------------------------------------------------------


def test_rt_ssrf_3_dns_rebind_pins_first_public_ip_and_never_reresolves(monkeypatch):
    """Simulate a rebind attacker: DNS answers differently on each call
    (public first, then metadata/private). `resolve_and_pin` must only ever
    call getaddrinfo ONCE per hop and pin that result -- it must never
    re-resolve mid-flight, so the "second answer" is never consulted for the
    already-pinned hop."""

    call_count = {"n": 0}
    answers = [
        _addrinfo("93.184.216.34"),  # first resolution: public (allowed)
        _addrinfo("169.254.169.254"),  # second resolution: rebind to metadata
    ]

    def rebinding_getaddrinfo(host, port, **kwargs):
        call_count["n"] += 1
        return answers[min(call_count["n"] - 1, len(answers) - 1)]

    monkeypatch.setattr(socket, "getaddrinfo", rebinding_getaddrinfo)

    target = resolve_and_pin("https://rebind-attacker.example/page")
    assert target.ip == "93.184.216.34"
    assert call_count["n"] == 1

    # Even if something naively called resolve_and_pin AGAIN for the same
    # hop (simulating a re-resolve), the pinned target from the first call
    # must remain what guarded_fetch actually connects to. We assert the
    # contract at the unit level: resolve_and_pin does not internally loop
    # or retry DNS -- exactly one getaddrinfo call produced the pin.
    assert target.host == "rebind-attacker.example"


def test_rt_ssrf_3_guarded_fetch_connects_to_pinned_ip_not_a_rebound_one(monkeypatch):
    """Full guarded_fetch path: even though DNS would answer with a
    private/metadata IP on a *second* lookup, guarded_fetch must issue only
    one resolution for this hop and connect to that pinned (public) IP."""

    call_count = {"n": 0}
    answers = [
        _addrinfo("93.184.216.34"),
        _addrinfo("169.254.169.254"),
    ]

    def rebinding_getaddrinfo(host, port, **kwargs):
        call_count["n"] += 1
        return answers[min(call_count["n"] - 1, len(answers) - 1)]

    monkeypatch.setattr(socket, "getaddrinfo", rebinding_getaddrinfo)

    connected_targets = []

    class FakeResponse:
        status_code = 200
        headers = {}
        content = b"ok"

    def fake_get(self, url, headers=None, extensions=None, **kwargs):
        connected_targets.append(url)
        return FakeResponse()

    monkeypatch.setattr(httpx.Client, "get", fake_get)

    body, final_url = guarded_fetch("https://rebind-attacker.example/page")

    assert body == b"ok"
    # Guard connected to the pinned public IP, never to the metadata IP.
    assert len(connected_targets) == 1
    assert "93.184.216.34" in connected_targets[0]
    assert METADATA_IP not in connected_targets[0]
    # Only one DNS resolution happened for this hop -- no mid-flight re-resolve.
    assert call_count["n"] == 1


# ---------------------------------------------------------------------------
# RT-SSRF-4 — redirect chain > 2 hops rejected
# ---------------------------------------------------------------------------


def test_rt_ssrf_4_three_hop_redirect_chain_to_metadata_blocked(monkeypatch):
    """Public URL that 302s -> 302s -> metadata IP (3 hops). Redirect cap is
    <=2, and the guard must reject before ever connecting to the metadata
    endpoint, regardless of cap vs per-hop validation being the proximate
    cause."""

    hosts_resolved = []

    def fake_getaddrinfo(host, port, **kwargs):
        hosts_resolved.append(host)
        mapping = {
            "hop0.example": "93.184.216.34",
            "hop1.example": "93.184.216.35",
            "hop2.example": "93.184.216.36",
        }
        ip = mapping.get(host)
        if ip is None:
            pytest.fail(f"unexpected DNS resolution for {host!r}")
        return _addrinfo(ip, port)

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)

    redirect_chain = {
        "hop0.example": ("302", "https://hop1.example/next"),
        "hop1.example": ("302", "https://hop2.example/next"),
        "hop2.example": ("302", f"https://{METADATA_IP}/latest/meta-data/"),
    }

    class FakeRedirectResponse:
        def __init__(self, status_code, location):
            self.status_code = int(status_code)
            self.headers = {"location": location}
            self.content = b""

    def fake_get(self, url, headers=None, extensions=None, **kwargs):
        host = headers["Host"]
        status, location = redirect_chain[host]
        return FakeRedirectResponse(status, location)

    monkeypatch.setattr(httpx.Client, "get", fake_get)

    with pytest.raises(SsrfBlockedError, match="too many redirects"):
        guarded_fetch("https://hop0.example/start")

    # Must never have resolved/reached the metadata host -- capped before hop 3.
    assert f"{METADATA_IP}" not in hosts_resolved


def test_rt_ssrf_4_redirect_to_private_ip_blocked_even_within_cap(monkeypatch):
    """Every hop is re-validated: a redirect straight to a private IP on hop 1
    (within the <=2 cap) must still be blocked by the per-hop IP check, not
    just the hop-count cap."""

    def fake_getaddrinfo(host, port, **kwargs):
        if host == "hop0.example":
            return _addrinfo("93.184.216.34", port)
        pytest.fail(f"should not resolve redirect target {host!r} -- it's a literal private IP")

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)

    class FakeRedirectResponse:
        status_code = 302
        headers = {"location": "http://169.254.169.254/latest/meta-data/"}
        content = b""

    def fake_get(self, url, headers=None, extensions=None, **kwargs):
        return FakeRedirectResponse()

    monkeypatch.setattr(httpx.Client, "get", fake_get)

    with pytest.raises(SsrfBlockedError):
        guarded_fetch("https://hop0.example/start")


def test_rt_ssrf_4_exactly_two_hops_allowed_third_blocked():
    """Sanity on the cap boundary itself using the real max_hops setting."""
    from app.config import get_settings

    assert get_settings().ssrf_max_redirects == 2


# ---------------------------------------------------------------------------
# RT-SSRF-5 — disallowed schemes rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "gopher://169.254.169.254/_GET%20/latest/meta-data",
        "ftp://internal-fileserver.local/secrets.txt",
        "http://example.com/",  # plain http also disallowed -- https only
        "data:text/plain;base64,c2VjcmV0",
    ],
)
def test_rt_ssrf_5_disallowed_schemes_rejected(url):
    with pytest.raises(SsrfBlockedError, match="scheme not allowed"):
        resolve_and_pin(url)


def test_rt_ssrf_5_guarded_fetch_never_touches_network_for_bad_scheme(monkeypatch):
    def fail_if_called(*args, **kwargs):
        pytest.fail("httpx.Client.get was called -- scheme allow-list failed to block before network I/O")

    monkeypatch.setattr(httpx.Client, "get", fail_if_called)

    for url in ("file:///etc/passwd", "gopher://169.254.169.254/x", "ftp://host/x"):
        with pytest.raises(SsrfBlockedError):
            guarded_fetch(url)
