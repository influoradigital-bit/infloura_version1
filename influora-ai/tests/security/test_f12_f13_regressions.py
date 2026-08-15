"""Regression battery for F-12 (trend_tag auth ordering) and F-13 (log redaction).

Both are from the 2026-08-08 deep audit. Each test fails against the pre-fix
code — the point of a gate is that it can go red.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass

import pytest
from fastapi import HTTPException

import app.routes.trend_tag as trend_tag_route
from app.config import get_settings
from app.security.redaction import (
    RedactionJsonFormatter,
    _redact_for_log,
    scrub_text,
    shape_of,
)


class _Req:
    def __init__(self, host="1.2.3.4", body=None):
        self.client = type("C", (), {"host": host})()
        self._body = body or {}

    async def json(self):
        return self._body


@pytest.fixture(autouse=True)
def _clear_rl():
    trend_tag_route._rl_hits.clear()
    yield
    trend_tag_route._rl_hits.clear()
    get_settings.cache_clear()


# ---------------------------------------------------------------------------
# F-12 — the rate limiter ran AFTER the secret check
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f12_failed_auth_attempts_consume_the_rate_limit(monkeypatch):
    """A wrong secret raised 401 before `_rate_limited` was ever reached, so no
    hit was recorded. The module docstring lists this limiter as one of three
    compensating controls justifying the static-secret exception — it only ever
    limited callers who ALREADY held the secret, leaving the secret
    brute-forceable at unlimited rate."""
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "the-real-secret")
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "5")
    get_settings.cache_clear()

    statuses = []
    for _ in range(12):
        try:
            await trend_tag_route.trendspark_tag(_Req(), authorization="Bearer wrong-guess")
        except HTTPException as exc:
            statuses.append(exc.status_code)

    assert 429 in statuses, "unlimited wrong-secret guesses — brute force is still free"
    assert statuses.count(401) <= 5, f"got {statuses.count(401)} unthrottled guesses"


@pytest.mark.asyncio
async def test_f12_non_ascii_bearer_is_401_not_an_unhandled_500(monkeypatch):
    """`hmac.compare_digest` raises TypeError on non-ASCII `str` input, so
    `Authorization: Bearer é` produced an unhandled 500 on an auth path
    documented as 'fails closed'."""
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "the-real-secret")
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")
    get_settings.cache_clear()

    for value in ("Bearer é", "Bearer 🔑", "Bearer naïve-secret"):
        with pytest.raises(HTTPException) as exc:
            await trend_tag_route.trendspark_tag(_Req(), authorization=value)
        assert exc.value.status_code == 401, f"{value!r} did not fail closed"


@pytest.mark.asyncio
async def test_f12_correct_secret_still_works(monkeypatch):
    """The reordering must not break the legitimate n8n caller."""
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "the-real-secret")
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")
    get_settings.cache_clear()

    result = await trend_tag_route.trendspark_tag(
        _Req(body={"trend_text": ""}), authorization="Bearer the-real-secret"
    )
    assert result["success"] is True


# ---------------------------------------------------------------------------
# F-13 — objects/tuples passed through untouched; shape_of leaked dict keys
# ---------------------------------------------------------------------------


@dataclass
class _RequestModel:
    prompt: str
    token: str


def test_f13_an_object_never_reaches_the_log_as_a_stringified_payload():
    """`_redact_for_log` handled dict and list; everything else fell through to
    `json.dumps(default=str)`, so a request model landed in stdout as
    `str(model)` — full prompt and bearer token, zero scrubbing."""
    payload = {"req": _RequestModel(prompt="the entire brand prompt", token="sk-live-ABCDEFGHIJKLMNOP")}
    rendered = json.dumps(_redact_for_log(payload), default=str)

    assert "the entire brand prompt" not in rendered
    assert "sk-live-ABCDEFGHIJKLMNOP" not in rendered
    assert "_RequestModel" in rendered  # the shape is still useful


def test_f13_tuples_and_sets_are_redacted_like_lists():
    payload = {"items": ("sk-live-ABCDEFGHIJKLMNOP", "ok"), "ids": {"sk-live-QRSTUVWXYZ123456"}}
    rendered = json.dumps(_redact_for_log(payload), default=str)
    assert "sk-live-ABCDEFGHIJKLMNOP" not in rendered
    assert "sk-live-QRSTUVWXYZ123456" not in rendered


def test_f13_brand_catalog_key_names_are_not_logged():
    """`shape_of` on a dict returned its KEYS — and a brand catalog is keyed BY
    PRODUCT NAME, so `{"brand_catalog": {"Chanel No 5 (SKU 991)": 1}}` logged
    the catalog contents this module promises never to log."""
    payload = {"brand_catalog": {"Chanel No 5 (SKU 991)": 1, "Dior Sauvage 100ml": 2}}
    rendered = json.dumps(_redact_for_log(payload), default=str)

    assert "Chanel" not in rendered
    assert "SKU 991" not in rendered
    assert "Dior" not in rendered
    assert '"key_count": 2' in rendered


def test_f13_non_sensitive_dict_keys_are_still_useful_and_scrubbed():
    assert shape_of({"a": 1, "b": 2}) == {"type": "dict", "keys": ["a", "b"]}
    assert shape_of({"a": 1}, reveal_keys=False) == {"type": "dict", "key_count": 1}


def test_f13_a_decimal_cost_is_not_scrubbed_as_a_phone_number():
    """Inverse failure: `_PHONE_RE` matched any 10-digit run, so
    `cost_usd=0.0123456789` scrubbed to `[REDACTED_PHONE]` and the CFO's daily
    cost reports read back nothing."""
    assert scrub_text("cost_usd=0.0123456789") == "cost_usd=0.0123456789"
    assert scrub_text("total 1234.5678901234 usd") == "total 1234.5678901234 usd"
    assert "[REDACTED_PHONE]" not in scrub_text("spend 0.0000123456 today")


def test_f13_a_real_phone_number_is_still_scrubbed():
    """The decimal fix must not blunt the control it belongs to."""
    assert "[REDACTED_PHONE]" in scrub_text("call me on 9876543210 please")
    assert "[REDACTED_PHONE]" in scrub_text("contact +91 9876543210")


def test_f13_formatter_end_to_end_does_not_emit_a_raw_object(caplog):
    record = logging.LogRecord(
        name="t", level=logging.INFO, pathname="x", lineno=1,
        msg="event", args=(), exc_info=None,
    )
    record.fields = {"req": _RequestModel(prompt="secret prompt text", token="sk-live-ZZZZZZZZZZZZ")}
    line = RedactionJsonFormatter().format(record)
    assert "secret prompt text" not in line
    assert "sk-live-ZZZZZZZZZZZZ" not in line
