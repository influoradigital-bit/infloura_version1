"""Tests for POST /internal/trendspark/tag — the Trend-Spark LLM Recovery Tagger.

Covers the full ASGI path (registration + static-secret auth + rate limit +
spend gate + closed-vocab validation + fail-closed drop). The Anthropic API is
never called — the provider is mocked at the same `_get_claude` seam the route
uses, exactly like tests/routes/test_trendspark_registration.py.

The static-secret auth is the deliberate exception documented in
app/routes/trend_tag.py; these tests pin every compensating control:
  - unconfigured secret        -> 503 (fails closed, never open)
  - missing / wrong bearer     -> 401 (constant-time compare)
  - over the rate limit        -> 429
  - hallucinated / off-vocab   -> stripped; row dropped if nothing valid survives
  - malformed / provider-down  -> drop (recovered: false), never a 5xx
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.providers.claude import ClaudeTextResult

TAG_PATH = "/internal/trendspark/tag"
SECRET = "test-ingest-secret-ABC123"


@pytest.fixture(autouse=True)
def _configure_secret(monkeypatch):
    """Set the ingest secret + a disabled rate limit for most tests, and reset
    the lru_cached settings + the per-process rate-limit buckets around each test."""
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", SECRET)
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")  # disabled by default
    get_settings.cache_clear()
    import app.routes.trend_tag as route_mod

    route_mod._rl_hits.clear()
    yield
    get_settings.cache_clear()
    route_mod._rl_hits.clear()


def _client() -> TestClient:
    """TestClient WITHOUT lifespan — skips the boot-secret startup hook (orthogonal
    to routing/auth), same rationale as test_trendspark_registration.py."""
    from app.main import app

    return TestClient(app)


def _auth(secret: str = SECRET) -> dict[str, str]:
    return {"Authorization": f"Bearer {secret}"}


def _mock_claude(text: str | None, *, ok: bool = True) -> AsyncMock:
    m = AsyncMock()
    m.complete_text = AsyncMock(return_value=ClaudeTextResult(ok=ok, text=text, usage=None))
    return m


# ── registration ─────────────────────────────────────────────────────────────


def test_router_is_registered_on_app():
    """Guards the C-17 failure mode: app/main.py wraps the trend_tag import in a
    bare `except Exception` that only logs, so a broken import degrades to a
    silent 404. We assert the route RESOLVES by hitting it and requiring a
    non-404 (a registered route returns 401 without auth, never 404). This is
    version-robust — newer FastAPI registers included routers lazily, so scanning
    `app.routes` for `.path` is unreliable, but a real request always is."""
    resp = _client().post(TAG_PATH, json={"trend_text": "x"})
    assert resp.status_code != 404, (
        f"{TAG_PATH} is not registered — app/main.py swallowed the trend_tag import error."
    )


# ── auth ─────────────────────────────────────────────────────────────────────


def test_missing_bearer_is_401():
    resp = _client().post(TAG_PATH, json={"trend_text": "anything"})
    assert resp.status_code == 401, resp.text


def test_wrong_secret_is_401():
    resp = _client().post(TAG_PATH, json={"trend_text": "anything"}, headers=_auth("wrong-secret"))
    assert resp.status_code == 401, resp.text


def test_unconfigured_secret_fails_closed_503(monkeypatch):
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "")
    get_settings.cache_clear()
    resp = _client().post(TAG_PATH, json={"trend_text": "anything"}, headers=_auth("anything"))
    assert resp.status_code == 503, resp.text


# ── happy path + closed-vocab enforcement ────────────────────────────────────


def test_recovers_valid_trend():
    mock = _mock_claude(json.dumps({"themes": ["victory", "pride"], "campaign_type": "PRIDE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(
            TAG_PATH,
            json={"trend_text": "India lifts the trophy in a last-ball thriller", "source": ["news"]},
            headers=_auth(),
        )
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["recovered"] is True
    assert data["themes"] == ["victory", "pride"]
    assert data["campaign_type"] == "PRIDE"
    assert data["peak_window_days"] == 1  # PRIDE typical from the rulebook
    mock.complete_text.assert_awaited_once()


def test_hallucinated_themes_are_dropped_but_valid_ones_kept():
    """Off-vocab themes ('boxoffice', 'blockbuster') are stripped; the row still
    recovers on the surviving in-vocab themes."""
    mock = _mock_claude(
        json.dumps({"themes": ["boxoffice", "energy", "blockbuster", "action"], "campaign_type": "HYPE"})
    )
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(
            TAG_PATH, json={"trend_text": "Stree 2 storms the box office"}, headers=_auth()
        )
    data = resp.json()["data"]
    assert data["recovered"] is True
    assert data["themes"] == ["energy", "action"]  # only closed-vocab survivors, order preserved
    assert data["campaign_type"] == "HYPE"
    assert data["peak_window_days"] == 3


def test_all_themes_off_vocab_drops_row():
    mock = _mock_claude(json.dumps({"themes": ["boxoffice", "blockbuster"], "campaign_type": "HYPE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "some unmappable text"}, headers=_auth())
    data = resp.json()["data"]
    assert data["recovered"] is False
    assert data["themes"] == []
    assert data["campaign_type"] is None


def test_invalid_campaign_type_drops_row():
    mock = _mock_claude(json.dumps({"themes": ["energy"], "campaign_type": "VIRAL_NONSENSE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "text"}, headers=_auth())
    assert resp.json()["data"]["recovered"] is False


def test_theme_count_capped():
    seven = ["strength", "action", "energy", "power", "victory", "pride", "discipline"]
    mock = _mock_claude(json.dumps({"themes": seven, "campaign_type": "PRIDE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "text"}, headers=_auth())
    assert len(resp.json()["data"]["themes"]) == 6  # trend_tag_max_themes default


# ── fail-closed behaviours ───────────────────────────────────────────────────


def test_malformed_model_output_drops_row():
    mock = _mock_claude("not json at all, just prose")
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "text"}, headers=_auth())
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["recovered"] is False


def test_provider_failure_drops_row_not_500():
    mock = _mock_claude(None, ok=False)
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "text"}, headers=_auth())
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["recovered"] is False


def test_empty_trend_text_drops_without_model_call():
    mock = _mock_claude(json.dumps({"themes": ["energy"], "campaign_type": "HYPE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        resp = _client().post(TAG_PATH, json={"trend_text": "   "}, headers=_auth())
    assert resp.json()["data"]["recovered"] is False
    mock.complete_text.assert_not_awaited()


# ── rate limit ───────────────────────────────────────────────────────────────


def test_rate_limit_returns_429(monkeypatch):
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "2")
    get_settings.cache_clear()
    mock = _mock_claude(json.dumps({"themes": ["energy"], "campaign_type": "HYPE"}))
    with patch("app.routes.trend_tag._get_claude", return_value=mock):
        client = _client()
        r1 = client.post(TAG_PATH, json={"trend_text": "a"}, headers=_auth())
        r2 = client.post(TAG_PATH, json={"trend_text": "b"}, headers=_auth())
        r3 = client.post(TAG_PATH, json={"trend_text": "c"}, headers=_auth())
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r3.status_code == 429, r3.text
