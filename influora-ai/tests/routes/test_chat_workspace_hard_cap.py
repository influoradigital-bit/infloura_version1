"""Kabir red-team FIX 3 — end-to-end coverage that app/routes/chat.py actually
wires `workspace_id` into `check_spend_gate()`, not just that the gate
function itself supports the parameter (see tests/costs/test_gate.py for the
gate-in-isolation coverage). `verify_token_async` is mocked (same pattern as
tests/routes/test_chat_conversation_binding.py) so no real JWT/JWKS setup is
needed -- only the gate wiring is under test here, and the real
`check_spend_gate` / `spend_tracker` run for real.
"""

from __future__ import annotations

import json
from decimal import Decimal
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.config import get_settings
from app.costs import spend_tracker
from app.routes import chat as chat_route

WORKSPACE_ID = "ws-chat-hard-cap-001"


def _make_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/chat",
        "headers": [],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _verified_service_token() -> VerifiedToken:
    return VerifiedToken(
        workspace_id=WORKSPACE_ID, scope="service", subject="spring-service", conversation_id=None, claims={}
    )


def _body() -> dict:
    return {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": "conv-1",
        "service_token": "irrelevant-because-verify_token_async-is-mocked",
        "conversation": [],
    }


@pytest.fixture(autouse=True)
async def _reset_state(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_chat_blocked_when_this_workspace_is_over_the_hard_cap(monkeypatch):
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "3.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("3.00"), workspace_id=WORKSPACE_ID)

    request = _make_request(_body())

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(chat_route, "_get_claude") as mock_get_claude:
        response = await chat_route.chat(request, authorization="Bearer whatever")
        assert response.status_code == 503
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_chat_not_blocked_when_a_different_workspace_is_over_the_hard_cap(monkeypatch):
    """The blocking check must be scoped to THIS request's workspace_id, not
    accidentally check some other workspace's (or the global) total."""
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "3.0")
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("100.00"), workspace_id="ws-some-other-workspace")

    request = _make_request(_body())

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        # Not blocked -- a StreamingResponse (200), not the 503 JSONResponse
        # the gate returns when it blocks.
        assert response.status_code == 200


@pytest.mark.asyncio
async def test_chat_hard_cap_unset_never_blocks_even_with_heavy_workspace_spend(monkeypatch):
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("500.00"), workspace_id=WORKSPACE_ID)

    request = _make_request(_body())

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        assert response.status_code == 200
