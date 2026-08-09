"""Round-7 pin gaps: two more fix-halves that survived mutation with 627 green.

- **F-11f** — `ip_str = str(sockaddr[0])` in `resolve_and_pin`. `getaddrinfo`
  types `sockaddr[0]` as `str | int`. Dropping the coercion left the suite green
  because CPython always hands back a `str` for AF_INET/AF_INET6 — but the value
  then flows into `PinnedTarget.ip`, which `_authority()` and the Host header
  interpolate. The contract the coercion buys is *`PinnedTarget.ip` is always a
  `str`, and anything unparsable is blocked*; nothing asserted either half.
- **F-13g** — the `bytes`/`bytearray` branch of `_redact_for_log`. Removing it
  left the suite green because the object fallback below it also refuses to
  render the payload. But bytes have no `__dict__`, so they collapsed to a bare
  `{"type": "bytes"}` and the `len` an operator needs to debug an audio upload
  disappeared. The branch is the difference between a useful shape and a label.
"""

from __future__ import annotations

import socket
from unittest.mock import patch

import pytest

from app.security.redaction import _redact_for_log
from app.security.ssrf_guard import SsrfBlockedError, resolve_and_pin

# ---------------------------------------------------------------------------
# F-11f — the resolved address is coerced, and fail-closed survives coercion
# ---------------------------------------------------------------------------


def _addrinfo(first_field):
    return [(socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (first_field, 443))]


def test_f11f_pinned_ip_is_always_a_str():
    """A non-`str` sockaddr address must not reach `PinnedTarget.ip` — every
    downstream consumer (`_authority`, the Host header) interpolates it."""

    class _AddrLike:
        """A stand-in for the non-`str` half of getaddrinfo's `str | int` type."""

        def __str__(self) -> str:
            return "93.184.216.34"

    with patch.object(socket, "getaddrinfo", return_value=_addrinfo(_AddrLike())):
        target = resolve_and_pin("https://example.test/")

    assert isinstance(target.ip, str), "PinnedTarget.ip must be a str, not a sockaddr object"
    assert target.ip == "93.184.216.34"


def test_f11f_a_non_str_that_renders_to_a_private_address_is_still_blocked():
    """Fail-closed must survive the coercion: an address object whose string
    form is loopback is blocked exactly as the bare string would be."""

    class _LoopbackLike:
        def __str__(self) -> str:
            return "127.0.0.1"

    with patch.object(socket, "getaddrinfo", return_value=_addrinfo(_LoopbackLike())):
        with pytest.raises(SsrfBlockedError) as exc:
            resolve_and_pin("https://example.test/")

    assert "127.0.0.1" in str(exc.value)


def test_f11f_an_unparsable_resolved_address_fails_closed():
    """The other half of the contract: something that is neither a valid IP nor
    coercible into one is blocked, never passed through as a pinned target."""

    class _Garbage:
        def __str__(self) -> str:
            return "not-an-ip"

    with patch.object(socket, "getaddrinfo", return_value=_addrinfo(_Garbage())):
        with pytest.raises(SsrfBlockedError):
            resolve_and_pin("https://example.test/")


# ---------------------------------------------------------------------------
# F-13g — bytes are described by length, not reduced to a bare type label
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("payload", [b"\x00\x01audio-frames", bytearray(b"\x00\x01audio-frames")])
def test_f13g_bytes_are_logged_as_a_shape_with_a_length(payload):
    """An audio upload reaching the logger must render as {"type","len"} — the
    length is the whole diagnostic value, and it is what the object fallback
    below this branch cannot produce (bytes have no `__dict__`)."""
    # NOTE: the key must NOT be one of _REDACT_KEYS ("audio", "audio_bytes", …)
    # — those are handled by `shape_of` before `_redact_for_log` recurses, so a
    # sensitive key would pin the wrong function entirely.
    out = _redact_for_log({"upload_body": payload})
    assert out["upload_body"] == {"type": "bytes", "len": len(payload)}


def test_f13g_bytes_payload_is_never_rendered():
    """No regression on the property the branch exists for: the bytes
    themselves never appear anywhere in the redacted structure."""
    secret = b"sk-live-DEADBEEF-should-never-be-logged"
    out = _redact_for_log({"blob": secret})
    assert "DEADBEEF" not in repr(out)


# ---------------------------------------------------------------------------
# F-13b — tuples and sets are containers too
# ---------------------------------------------------------------------------


def test_f13b_a_tuple_of_secrets_is_redacted_element_by_element():
    """`_redact_for_log` handled `list` for years and let `tuple`/`set` fall
    through to the formatter's `json.dumps(default=str)`. A tuple is what a
    zip()/dict.items() pass produces, and `str(tuple)` renders every element
    verbatim — the F-13 leak with different brackets."""
    out = _redact_for_log({"pairs": ("sk-live-DEADBEEF", "+919876543210")})
    assert isinstance(out["pairs"], list)
    assert "DEADBEEF" not in repr(out)
    assert "9876543210" not in repr(out)


def test_f13b_a_set_is_redacted_element_by_element():
    out = _redact_for_log({"tokens": {"sk-live-DEADBEEF"}})
    assert isinstance(out["tokens"], list)
    assert "DEADBEEF" not in repr(out)


def test_f13b_a_tuple_recurses_into_nested_objects():
    """The recursion must be the same one lists get, not a shallow str()."""

    class _Model:
        def __init__(self):
            self.token = "sk-live-DEADBEEF"

    out = _redact_for_log({"models": (_Model(),)})
    assert isinstance(out["models"], list)
    assert out["models"][0]["type"] == "_Model"
    assert "DEADBEEF" not in repr(out)


# ---------------------------------------------------------------------------
# Round-7, Priya advisories 3 and 5 — the two remaining F-13 holes
# ---------------------------------------------------------------------------


def test_f13_a_dict_key_is_scrubbed_as_well_as_its_value():
    """Advisory 5: only VALUES were scrubbed. A dict keyed by catalog entries or
    by a phone number — `{"by_phone": {"9876543210": 3}}` — is a mapping whose
    KEYS are the sensitive payload, and `catalog`/`by_phone` are not in
    _REDACT_KEYS, so every key went to stdout verbatim."""
    from app.security.redaction import scrub_text

    out = _redact_for_log({"by_phone": {"9876543210": 3}})
    assert "9876543210" not in repr(out), "the sensitive value was the KEY, and it leaked"
    assert out["by_phone"] == {scrub_text("9876543210"): 3}


def test_f13_a_secret_shaped_key_is_scrubbed_at_any_depth():
    out = _redact_for_log({"outer": {"inner": {"sk-live-DEADBEEFDEADBEEFDEADBEEF": 1}}})
    assert "DEADBEEF" not in repr(out)


def test_f13_an_ordinary_key_is_left_alone():
    """No regression: scrubbing keys must not mangle ordinary field names, or
    every log line becomes unreadable."""
    out = _redact_for_log({"workspace_id": "ws-1", "request_id": "req-7", "count": 3})
    assert set(out) == {"workspace_id", "request_id", "count"}
    assert out["count"] == 3


def test_f13_a_ten_digit_integer_part_of_a_decimal_is_not_a_phone_number():
    r"""Advisory 3: the trailing guard is `(?![\d.,]*\d)`, not `(?!\d)`. A
    decimal whose INTEGER part is ten digits — a paise-precision rupee figure
    like `1234567890.12` in a cost report — is not a phone number, and
    scrubbing it blanks the number the CFO is reading."""
    from app.security.redaction import scrub_text

    assert scrub_text("total_inr=1234567890.12") == "total_inr=1234567890.12"
    assert scrub_text("cost_usd=0.0123456789") == "cost_usd=0.0123456789"


def test_f13_a_real_phone_number_is_still_scrubbed():
    """The other half: loosening the guard must not stop scrubbing actual
    phone numbers, with or without a country code."""
    from app.security.redaction import scrub_text

    assert "9876543210" not in scrub_text("contact 9876543210 for details")
    assert "9876543210" not in scrub_text("contact +919876543210 for details")
