"""EV-044 — the two client-supplied-size guards, enforced on the SERVER.

1. `POST /chat` refuses a conversation history that is too long (turns) or too
   big (characters) before it assembles a prompt or touches a provider.
2. The ASGI middleware in `app/main.py` refuses an oversized request body
   before FastAPI parses it, on every route, authenticated or not.

Neither guard may be satisfiable from the browser: the point is that a client
holding a perfectly valid token still cannot buy an unbounded prompt for the
one AI credit influora-api charges per send.

`verify_token_async` is mocked in the route tests (same pattern as
tests/routes/test_chat_workspace_hard_cap.py) so no JWT/JWKS setup is needed;
the limit under test runs for real.
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.config import get_settings
from app.costs import spend_tracker
from app.routes import chat as chat_route

WORKSPACE_ID = "ws-ev044-limits"


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
        workspace_id=WORKSPACE_ID,
        scope="service",
        subject="spring-service",
        conversation_id=None,
        claims={},
    )


def _body(conversation: list) -> dict:
    return {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": "conv-1",
        "service_token": "irrelevant-because-verify_token_async-is-mocked",
        "conversation": conversation,
    }


def _code(response) -> str:
    return json.loads(bytes(response.body))["error"]["code"]


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in (
        "AI_SPEND_KILL_SWITCH",
        "WORKSPACE_DAILY_HARD_CAP_USD",
        "AI_MAX_HISTORY_TURNS",
        "AI_MAX_HISTORY_CHARS",
        "REDIS_URL",
    ):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


# ---------------------------------------------------------------------------
# 1. conversation history -- turns
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_too_many_turns_is_refused_before_any_provider_call(monkeypatch):
    monkeypatch.setenv("AI_MAX_HISTORY_TURNS", "5")
    get_settings.cache_clear()
    conversation = [{"role": "user", "content": "hi"} for _ in range(6)]

    request = _make_request(_body(conversation))

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(chat_route, "_get_claude") as mock_get_claude, patch.object(
        chat_route, "_get_spring"
    ) as mock_get_spring:
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 413
    assert _code(response) == "AI_HISTORY_TOO_LARGE"
    mock_get_claude.assert_not_called()
    # Refused before Block B is even fetched -- no work of any kind is done.
    mock_get_spring.assert_not_called()


@pytest.mark.asyncio
async def test_exactly_at_the_turn_limit_is_served(monkeypatch):
    """Boundary, the other side: the guard must refuse MORE than the limit,
    not AT it -- otherwise it is off by one against every real conversation."""
    monkeypatch.setenv("AI_MAX_HISTORY_TURNS", "5")
    get_settings.cache_clear()
    conversation = [{"role": "user", "content": "hi"} for _ in range(5)]

    request = _make_request(_body(conversation))

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 200


# ---------------------------------------------------------------------------
# 2. conversation history -- characters
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_too_many_characters_is_refused_even_within_the_turn_limit(monkeypatch):
    """FALSIFICATION of a turn-count-only guard: two turns is well inside any
    turn limit, and this payload is still enormous. A guard that only counted
    turns would pass it straight through to the model."""
    monkeypatch.setenv("AI_MAX_HISTORY_TURNS", "100")
    monkeypatch.setenv("AI_MAX_HISTORY_CHARS", "1000")
    get_settings.cache_clear()
    conversation = [
        {"role": "user", "content": "x" * 600},
        {"role": "assistant", "content": "y" * 600},
    ]

    request = _make_request(_body(conversation))

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(chat_route, "_get_claude") as mock_get_claude:
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 413
    assert _code(response) == "AI_HISTORY_TOO_LARGE"
    mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_tool_call_payloads_count_toward_the_character_limit(monkeypatch):
    """FALSIFICATION of a `content`-only character count. `tool_calls` is
    rendered into the replayed assistant text by
    `assembler._replayed_tool_calls_summary`, so a history whose bulk lives
    there costs real tokens. A guard that measured only `content` would see
    this turn as ~0 characters."""
    monkeypatch.setenv("AI_MAX_HISTORY_CHARS", "1000")
    get_settings.cache_clear()
    conversation = [
        {
            "role": "assistant",
            "content": "",
            "tool_calls": [{"name": "show_creators", "input": {"pad": "z" * 5000}}],
        }
    ]

    request = _make_request(_body(conversation))

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(chat_route, "_get_claude") as mock_get_claude:
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 413
    assert _code(response) == "AI_HISTORY_TOO_LARGE"
    mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_the_limits_have_safe_non_zero_defaults_with_no_env_set(monkeypatch):
    """EV-044's own rule applied to these two settings: a missing env var must
    not mean unbounded. Asserted against a payload sized from the defaults
    themselves, so raising a default cannot silently un-cover this."""
    get_settings.cache_clear()
    settings = get_settings()
    assert settings.ai_max_history_turns > 0
    assert settings.ai_max_history_chars > 0

    conversation = [
        {"role": "user", "content": "x"} for _ in range(settings.ai_max_history_turns + 1)
    ]
    request = _make_request(_body(conversation))

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 413
    assert _code(response) == "AI_HISTORY_TOO_LARGE"


# ---------------------------------------------------------------------------
# 3. request body size -- the ASGI middleware
# ---------------------------------------------------------------------------


def _client(monkeypatch, max_bytes: int):
    """A TestClient over a freshly imported app, so the middleware picks up the
    monkeypatched limit (it reads settings once, at import)."""
    import importlib
    import sys

    from fastapi.testclient import TestClient

    monkeypatch.setenv("AI_MAX_REQUEST_BODY_BYTES", str(max_bytes))
    get_settings.cache_clear()
    # monkeypatch.delitem, not `del`: the ORIGINAL app.main is put back in sys.modules after the
    # test. A bare `del` left this 500-byte-limit app behind for every later test (a voice test
    # then got 413 on a tiny body, and test_cors could not reload its own app.main).
    monkeypatch.delitem(sys.modules, "app.main", raising=False)
    main = importlib.import_module("app.main")
    return TestClient(main.app), main


def test_oversized_body_is_refused_with_413_before_the_route_runs(monkeypatch):
    client, main = _client(monkeypatch, 500)
    try:
        response = client.post("/chat", content=b"x" * 2000)
        assert response.status_code == 413
        assert response.json()["error"]["code"] == main.REQUEST_TOO_LARGE_CODE
    finally:
        get_settings.cache_clear()


def test_a_body_within_the_limit_still_reaches_the_route(monkeypatch):
    """FALSIFICATION of a middleware that just 413s everything: a small body
    must get through to the route and fail there on its own terms (missing
    workspace_id -> 400), not at the size guard."""
    client, _ = _client(monkeypatch, 500)
    try:
        response = client.post("/chat", json={})
        assert response.status_code != 413
        assert response.status_code == 400
    finally:
        get_settings.cache_clear()


def test_a_chunked_body_with_no_content_length_is_still_bounded(monkeypatch):
    """FALSIFICATION of a Content-Length-only check. An attacker simply omits
    the header: httpx sends `Transfer-Encoding: chunked` for a generator body,
    so nothing declares a length and a header-only guard sees nothing to
    compare."""
    client, main = _client(monkeypatch, 500)

    def _chunks():
        for _ in range(20):
            yield b"x" * 200

    try:
        response = client.post("/chat", content=_chunks())
        assert response.status_code == 413
        assert response.json()["error"]["code"] == main.REQUEST_TOO_LARGE_CODE
    finally:
        get_settings.cache_clear()


def test_healthz_is_unaffected(monkeypatch):
    """The guard must not become an availability problem of its own."""
    client, _ = _client(monkeypatch, 500)
    try:
        assert client.get("/healthz").status_code == 200
    finally:
        get_settings.cache_clear()
