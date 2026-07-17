"""Guardrail test: the browser-direct SSE stream token is minted single-use
and bound to a single conversationId (04-AI-SERVICE-SPEC §1.1 /
02-API-CONTRACT-BRAND §218, §226). `app/routes/chat.py` must assert
`verified.conversation_id == body["conversation_id"]` before any provider
call -- otherwise a stream token issued for conversation A could be replayed
against conversation B in the same workspace (tenant/workspace check alone is
not sufficient).

These tests exercise `chat()` directly with `verify_token` monkeypatched, so
no real JWKS/secrets/provider calls are needed -- only the gating logic added
in this fix is under test.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import AuthError, VerifiedToken
from app.routes import chat as chat_route


def _make_request(body: dict) -> Request:
    """Builds a minimal ASGI Request whose `.json()` returns `body`, without
    spinning up a real HTTP server."""
    import json

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


def _verified_token(*, conversation_id: str | None, workspace_id: str = "ws-victim"):
    return VerifiedToken(
        workspace_id=workspace_id,
        scope="chat:stream",
        subject="user-1",
        conversation_id=conversation_id,
        claims={},
    )


@pytest.mark.asyncio
async def test_stream_token_conversation_mismatch_rejected_before_provider_call():
    """A stream token minted for conversation A, replayed against conversation
    B in the request body, must be rejected 401/403 before any provider call
    -- not silently accepted because workspace_id still matches."""
    body = {
        "workspace_id": "ws-victim",
        "conversation_id": "conv-B-other-conversation",
        "stream_token": "irrelevant-because-verify_token-is-mocked",
    }
    request = _make_request(body)

    with patch.object(
        chat_route,
        "verify_token",
        return_value=_verified_token(conversation_id="conv-A-original"),
    ), patch.object(chat_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await chat_route.chat(request, authorization="Bearer whatever")

        # Must fail closed with an HTTPException-shaped 401/403, and must
        # never have reached for a Claude provider instance.
        assert getattr(exc_info.value, "status_code", None) in (401, 403)
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_stream_token_matching_conversation_id_is_allowed_through_the_gate():
    """Sanity check: when the stream token's conversation_id matches the
    request body, the new gate must NOT raise (the existing behavior for the
    legitimate single-conversation case is preserved)."""
    body = {
        "workspace_id": "ws-victim",
        "conversation_id": "conv-A-original",
        "stream_token": "irrelevant-because-verify_token-is-mocked",
        "conversation": [],
    }
    request = _make_request(body)

    with patch.object(
        chat_route,
        "verify_token",
        return_value=_verified_token(conversation_id="conv-A-original"),
    ):
        # Should not raise for conversation-id mismatch. It may proceed to
        # build a StreamingResponse (we don't drain the generator here -- that
        # would require a live provider -- we only assert the gate passes).
        response = await chat_route.chat(request, authorization="Bearer whatever")
        assert response is not None


@pytest.mark.asyncio
async def test_service_token_with_no_conversation_binding_is_not_gated():
    """A service token (conversation_id is None -- it legitimately spans
    conversations) must not be rejected by the new conversation-binding
    check; only scoped stream tokens carry a conversation_id to enforce."""
    body = {
        "workspace_id": "ws-victim",
        "conversation_id": "any-conversation-at-all",
        "service_token": "irrelevant-because-verify_token-is-mocked",
        "conversation": [],
    }
    request = _make_request(body)

    with patch.object(
        chat_route,
        "verify_token",
        return_value=_verified_token(conversation_id=None),
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        assert response is not None
