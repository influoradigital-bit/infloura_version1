"""Tests for POST /analyze-site's P2-17 AI spend gate wiring.

This route never imported `app.costs` before (H-25 follow-up audit) — despite
`app/costs/gate.py`'s own docstring already listing `analyze_site.py`'s
classify_site path as an in-scope call site. These tests pin the fix at the
route level, reusing the same RSA/JWKS scaffolding + gate-testing shape as
tests/routes/test_ai_spend_gate.py and tests/routes/test_brand_safety.py:

- kill-switch blocks the route with zero Gemini calls (and zero SSRF fetches)
- daily ceiling breach blocks the route the same way
- a normal request under the ceiling proceeds and records spend correctly,
  using GEMINI_MODEL and the usage `GeminiProvider.classify_site` now returns
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
from app.config import GEMINI_MODEL, get_settings
from app.costs import spend_tracker
from app.costs.pricing import estimate_cost_usd
from app.providers.gemini import ClassifyResult
from app.routes import analyze_site as analyze_site_route


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
        "path": "/analyze-site",
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
WORKSPACE_ID = "ws-analyze-site-spend-gate-001"


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
    return {"workspace_id": WORKSPACE_ID, "url": "https://brand-example.test/"}


@pytest.mark.asyncio
async def test_kill_switch_blocks_with_zero_gemini_calls(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    with patch.object(analyze_site_route, "guarded_fetch") as mock_fetch:
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            with pytest.raises(Exception) as exc_info:
                await analyze_site_route.analyze_site(request, authorization=f"Bearer {token}")

            assert getattr(exc_info.value, "status_code", None) == 503
            detail = getattr(exc_info.value, "detail", None)
            assert isinstance(detail, dict)
            assert detail.get("code") == "AI_KILL_SWITCH_ACTIVE"
            mock_get_gemini.assert_not_called()
        mock_fetch.assert_not_called()

    assert await spend_tracker.get_global_total_today() == Decimal("0")


@pytest.mark.asyncio
async def test_ceiling_breach_blocks_with_zero_gemini_calls(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "1.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("1.00"), workspace_id=None)

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    with patch.object(analyze_site_route, "guarded_fetch") as mock_fetch:
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            with pytest.raises(Exception) as exc_info:
                await analyze_site_route.analyze_site(request, authorization=f"Bearer {token}")

            assert getattr(exc_info.value, "status_code", None) == 503
            detail = getattr(exc_info.value, "detail", None)
            assert isinstance(detail, dict)
            assert detail.get("code") == "AI_SPEND_CEILING_REACHED"
            mock_get_gemini.assert_not_called()
        mock_fetch.assert_not_called()


@pytest.mark.asyncio
async def test_normal_request_under_ceiling_proceeds_and_records_spend(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    usage = {"input_tokens": 2000, "output_tokens": 300}
    mock_result = ClassifyResult(
        ok=True,
        niche_tags=["skincare"],
        tone_dial={"formality": 0.5},
        brand_color="#123456",
        product_catalog=[],
        usage=usage,
    )

    with patch.object(
        analyze_site_route, "guarded_fetch", return_value=(b"<html><body>hello brand</body></html>", "https://brand-example.test/")
    ):
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            mock_gemini = AsyncMock()
            mock_gemini.classify_site = AsyncMock(return_value=mock_result)
            mock_get_gemini.return_value = mock_gemini

            response = await analyze_site_route.analyze_site(request, authorization=f"Bearer {token}")

    assert response["success"] is True
    mock_gemini.classify_site.assert_awaited_once()

    expected_cost = estimate_cost_usd(GEMINI_MODEL, usage)
    assert expected_cost > Decimal("0")
    assert await spend_tracker.get_global_total_today() == expected_cost


@pytest.mark.asyncio
async def test_classify_failure_still_records_spend_when_usage_present(monkeypatch):
    """A parseable-but-unusable Gemini response (ok=False) can still carry
    usage_metadata -- the tokens were spent regardless of parse success, so
    spend must still be recorded even though the route degrades to
    "paste_a_link" for the caller."""
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()

    token = _mint_service_token()
    request = _make_request(_body(), authorization=f"Bearer {token}")

    usage = {"input_tokens": 500, "output_tokens": 10}
    mock_result = ClassifyResult(ok=False, error="unparseable_response", usage=usage)

    with patch.object(
        analyze_site_route, "guarded_fetch", return_value=(b"<html><body>hello brand</body></html>", "https://brand-example.test/")
    ):
        with patch.object(analyze_site_route, "_get_gemini") as mock_get_gemini:
            mock_gemini = AsyncMock()
            mock_gemini.classify_site = AsyncMock(return_value=mock_result)
            mock_get_gemini.return_value = mock_gemini

            response = await analyze_site_route.analyze_site(request, authorization=f"Bearer {token}")

    assert response["success"] is False
    expected_cost = estimate_cost_usd(GEMINI_MODEL, usage)
    assert await spend_tracker.get_global_total_today() == expected_cost
