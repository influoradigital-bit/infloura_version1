"""P2-17 §5 acceptance criteria, exercised at the route level (not just the
gate function in isolation) -- reuses tests/routes/test_brand_safety.py's
auth-mocking helpers so these tests hit the real `brand_safety()` route
function, same as that module does.

Covers:
- kill-switch blocks the route with zero provider (Claude) calls
- daily ceiling breach blocks the route with zero provider calls
- a normal request under the ceiling proceeds and records spend correctly
  (spend_tracker's global total reflects the estimated cost afterward)
"""

from __future__ import annotations

import json
import time
from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import Request

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.config import CLAUDE_MODEL, get_settings
from app.costs import spend_tracker
from app.costs.pricing import estimate_cost_usd
from app.providers.claude import ClaudeToolResult
from app.routes import brand_safety as brand_safety_route
from app.tools.schemas import GARM_CATEGORIES


def _make_request(body: dict[str, Any], authorization: str | None = None) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    headers = []
    if authorization is not None:
        headers.append((b"authorization", authorization.encode()))

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/internal/brand-safety",
        "headers": headers,
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _gen_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


class _StaticKey:
    def __init__(self, key):
        self.key = key


class _FakeJwksSource:
    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        return _StaticKey(self._legitimate_public_pem)


LEGIT_PRIVATE_PEM, LEGIT_PUBLIC_PEM = _gen_rsa_keypair()
WORKSPACE_ID = "ws-spend-gate-001"


@pytest.fixture(autouse=True)
async def _install_fake_jwks_and_reset_state(monkeypatch):
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    reset_jwks_source()
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


def _mint_service_token() -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 240,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "workspace_id": WORKSPACE_ID,
        "sub": "spring-service",
    }
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "test-kid"})


def _body() -> dict[str, Any]:
    return {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "m1", "caption": "hello"}],
    }


def _valid_model_output() -> dict[str, Any]:
    return {
        "items": [
            {
                "content_id": "m1",
                "garm_flags": [{"category": cat, "risk": "floor", "rationale": "ok"} for cat in GARM_CATEGORIES],
                "content_sentiment": "positive",
                "sentiment_score": 0.4,
                "brand_safety_score": 95.0,
                "overall_rationale": "fine",
            }
        ]
    }


@pytest.mark.asyncio
async def test_kill_switch_blocks_with_zero_provider_calls(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 503
        detail = getattr(exc_info.value, "detail", None)
        assert isinstance(detail, dict)
        assert detail.get("code") == "AI_KILL_SWITCH_ACTIVE"
        mock_get_claude.assert_not_called()

    assert await spend_tracker.get_global_total_today() == Decimal(0)


@pytest.mark.asyncio
async def test_ceiling_breach_blocks_with_zero_provider_calls(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "1.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("1.00"), workspace_id=None)

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 503
        detail = getattr(exc_info.value, "detail", None)
        assert isinstance(detail, dict)
        assert detail.get("code") == "AI_SPEND_CEILING_REACHED"
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_normal_request_under_ceiling_proceeds_and_records_spend(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")
    usage = {"input_tokens": 1000, "output_tokens": 500}
    mock_result = ClaudeToolResult(ok=True, tool_input=_valid_model_output(), usage=usage)

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(return_value=mock_result)
        mock_get_claude.return_value = mock_claude

        response = await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert response["success"] is True
    mock_claude.complete_with_forced_tool.assert_awaited_once()

    expected_cost = estimate_cost_usd(CLAUDE_MODEL, usage)
    assert expected_cost > Decimal(0)
    assert await spend_tracker.get_global_total_today() == expected_cost
