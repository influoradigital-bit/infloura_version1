"""App-level registration + end-to-end nudge tests for /internal/trendspark/nudge (W4-3).

Gap these close: every other Trend-Spark test (tests/eval/test_trendspark_nudge.py)
calls `trendspark_route.trendspark_nudge(...)` DIRECTLY with a hand-built Request,
so none of them touch the `app` object. But `app/main.py:50-55` wraps the
trendspark import + include_router in a bare `except Exception:` that only LOGS.

That means a regression which breaks `app.routes.trendspark`'s import (e.g. a
missing TRENDSPARK_* config constant) degrades to "route silently not registered"
-> production 404 -> Java's TrendSparkAiClient falls back forever, while the whole
suite stays green. This is not hypothetical: main.py's own comment records C-17,
where exactly this happened and neither internal route was registered at all.

These tests assert against the real ASGI app, so the swallowed-import failure mode
is caught. The Anthropic API is never called — the provider is mocked at the same
`_get_claude` seam the route uses.

Note on BrandProfile: the Python service has NO BrandProfile dependency by design —
the Java side (TrendSparkNudgeService, which requires a BrandProfile before it calls
out) decides everything and sends a fully-resolved request. "A profiled workspace"
therefore materializes here as the request body Java sends once a profile exists.
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.providers.claude import ClaudeTextResult

# Reuse the RSA/JWKS scaffolding + token minting rather than duplicating ~60 lines.
from tests.eval.test_trendspark_nudge import (
    LEGIT_PUBLIC_PEM,
    WORKSPACE_ID,
    _FakeJwksSource,
    _mint_service_token,
)

NUDGE_PATH = "/internal/trendspark/nudge"


@pytest.fixture(autouse=True)
def _install_fake_jwks():
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    yield
    reset_jwks_source()


def _client() -> TestClient:
    """TestClient WITHOUT the lifespan context manager on purpose.

    `app/main.py`'s startup hook (`_refuse_boot_on_missing_secrets`) raises when
    real provider secrets are absent, which is correct for production but
    orthogonal to routing/auth. Not entering the context manager skips lifespan
    while still driving the full ASGI request path these tests care about.
    """
    from app.main import app

    return TestClient(app)


# The request Java's TrendSparkAiClient sends once a BrandProfile exists for the
# workspace (mode/videos already resolved by TrendSparkNudgeService).
PROFILED_WORKSPACE_BODY = {
    "workspace_id": WORKSPACE_ID,
    "brand_name": "Chai Point",
    "campaign_type": "festive",
    "trend_text": "monsoon chai cravings trending in Mumbai",
    "mode": "SNAPSBY",
    "videos": [
        {"video_id": "vid-101", "title": "Monsoon chai pour", "themes": ["monsoon", "chai"]},
        {"video_id": "vid-102", "title": "Rainy day cutting chai", "themes": ["rain", "cozy"]},
    ],
}


def test_trendspark_router_is_registered_on_app():
    """main.py swallows a trendspark import failure — assert it actually registered.

    Without this, a broken import silently unregisters the route and every other
    trendspark test still passes (they bypass `app` entirely).
    """
    from app.main import app

    paths = {getattr(route, "path", None) for route in app.routes}
    assert NUDGE_PATH in paths, (
        f"{NUDGE_PATH} is not registered on the app — app/main.py's try/except "
        "swallowed the trendspark import error. Check app/routes/trendspark.py imports."
    )


def test_nudge_returns_for_profiled_workspace_through_asgi_stack():
    """Full path: ASGI -> route registration -> service-token auth -> mocked provider
    -> parse/validate -> 200 {success, data:{message, video_ids}}."""
    mock_claude = AsyncMock()
    mock_claude.complete_text = AsyncMock(
        return_value=ClaudeTextResult(
            ok=True,
            text=json.dumps(
                {
                    "message": (
                        "Chai Point, monsoon chai cravings are all over Mumbai right now. "
                        "I found a few ready videos that fit. Want a peek?"
                    ),
                    "video_ids": ["vid-101"],
                }
            ),
            usage=None,
        )
    )

    token = _mint_service_token()
    with patch("app.routes.trendspark._get_claude", return_value=mock_claude):
        response = _client().post(
            NUDGE_PATH,
            json=PROFILED_WORKSPACE_BODY,
            headers={"Authorization": f"Bearer {token}"},
        )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["success"] is True
    message = payload["data"]["message"]
    assert message.strip(), "a profiled workspace must get a non-empty nudge"
    assert "Chai Point" in message
    # video_ids must be a subset of what we sent (hallucination kill-switch).
    assert set(payload["data"]["video_ids"]) <= {"vid-101", "vid-102"}
    mock_claude.complete_text.assert_awaited_once()


def test_nudge_still_returns_a_message_when_provider_fails_through_asgi_stack():
    """Provider down -> deterministic fallback, still 200 with a usable nudge.
    Java must never see a 5xx for a phrasing miss."""
    mock_claude = AsyncMock()
    mock_claude.complete_text = AsyncMock(
        return_value=ClaudeTextResult(ok=False, text=None, usage=None)
    )

    token = _mint_service_token()
    with patch("app.routes.trendspark._get_claude", return_value=mock_claude):
        response = _client().post(
            NUDGE_PATH,
            json=PROFILED_WORKSPACE_BODY,
            headers={"Authorization": f"Bearer {token}"},
        )

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["success"] is True
    assert "Chai Point" in payload["data"]["message"]


def test_nudge_rejects_unauthenticated_caller_through_asgi_stack():
    """The registered route must still enforce the service token (no bypass via ASGI)."""
    response = _client().post(NUDGE_PATH, json=PROFILED_WORKSPACE_BODY)
    assert response.status_code == 401, response.text
