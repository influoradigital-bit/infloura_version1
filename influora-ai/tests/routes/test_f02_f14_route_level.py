"""Route-level regression tests for F-02 and F-14 (2026-08-08 deep audit).

Priya's sign-off review mutation-tested every finding: revert the fix, re-run
the suite. F-02 and F-14 were fixed correctly in code and NOTHING caught the
revert — the tests that existed asserted properties of `asyncio` and of the
pricing function, never of `chat()`. A fix nothing catches is a fix one
refactor away from gone.

These drive the real `chat()` end-to-end (real StreamingResponse, drained),
same harness as tests/routes/test_chat_money_path.py. Each MUST fail when its
fix is reverted.
"""

from __future__ import annotations

import asyncio
import json
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.costs import spend_tracker
from app.routes import chat as chat_route
from app.tools.loop import LoopEvent

WORKSPACE_ID = "ws-f02-f14-001"
CONVERSATION_ID = "conv-f02-f14-1"
STREAM_MESSAGE_ID = "01HMESSAGE_SERVER_MINTED_BBBB"


def _make_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http", "method": "POST", "path": "/chat", "headers": [],
        "query_string": b"", "client": ("test", 0),
    }
    return Request(scope, receive)


def _body() -> dict:
    return {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": CONVERSATION_ID,
        "stream_token": "irrelevant-verify_token_async-is-mocked",
        "conversation": [],
        "onbehalf_jwt": "onbehalf-jwt-value",
    }


def _verified() -> VerifiedToken:
    return VerifiedToken(
        workspace_id=WORKSPACE_ID, scope="chat:stream", subject="user-1",
        conversation_id=CONVERSATION_ID, claims={"messageId": STREAM_MESSAGE_ID},
    )


def _mock_spring() -> MagicMock:
    spring = MagicMock()
    spring.persist_assistant_message = AsyncMock()
    spring.release_turn_credit = AsyncMock()
    return spring


async def _drain(response) -> str:
    raw = b""
    async for chunk in response.body_iterator:
        raw += chunk if isinstance(chunk, bytes) else chunk.encode()
    return raw.decode()


@pytest.fixture(autouse=True)
async def _reset_state(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()


# ---------------------------------------------------------------------------
# F-02 — client disconnect burns tokens and records zero spend
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f02_a_client_disconnect_records_the_spend_the_provider_billed():
    """The attack: POST /chat, read 200 tokens, hang up. Anthropic bills the
    full cached prefix and everything generated; `get_global_total_today()`
    never moved, so a loop of connect-and-disconnect drove unbounded real spend
    while the ceiling read zero.

    This drives the ROUTE'S OWN disconnect path — `request.is_disconnected()`
    goes True while the loop is mid-await, so the route must set `disconnected`,
    give the loop one bounded chance to flush the usage the provider already
    billed, and record it before returning. Removing that drain must fail this
    test.
    """
    polls = {"n": 0}

    async def is_disconnected():
        polls["n"] += 1
        return polls["n"] > 1  # connected for the first poll, gone by the second

    async def _loop_that_gets_hung_up_on(**kwargs):
        is_cancelled = kwargs["is_cancelled"]
        yield LoopEvent(type="token", text="Scanning creators")
        # The provider keeps generating (and billing) while the client is gone.
        for _ in range(50):
            await asyncio.sleep(0.005)
            if is_cancelled():
                break
        assert is_cancelled(), "test premise: the route must have marked the turn cancelled"
        # This is the flush claude.py performs on cancellation.
        yield LoopEvent(
            type="error",
            error_code="client_disconnected",
            usage={"input_tokens": 200, "output_tokens": 300,
                   "cache_read_input_tokens": 8000, "partial": True},
        )

    request = _make_request(_body())
    request.is_disconnected = is_disconnected
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _loop_that_gets_hung_up_on), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    assert polls["n"] >= 2, "test premise: the route must have polled is_disconnected twice"
    recorded = await spend_tracker.get_global_total_today()
    assert recorded > Decimal(0), (
        "the client hung up and the turn recorded $0 — connect-and-disconnect is "
        "still free unlimited provider spend"
    )
    # 200 in + 300 out + 8000 cache-read on Sonnet.
    assert recorded == Decimal("0.0075"), recorded
    # A disconnect must never trigger a refund (Kabir FAIL 1 stays fixed).
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_f02_usage_on_an_error_event_is_recorded_too():
    """The other half of F-02: an `error` event can carry the usage the provider
    already billed. `if final_usage:` used to be reachable only from `done`."""

    async def _error_with_usage(**kwargs):
        yield LoopEvent(type="token", text="partial")
        yield LoopEvent(type="error", error_code="provider_error",
                        usage={"input_tokens": 200, "output_tokens": 300})

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _error_with_usage), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=_mock_spring())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    assert await spend_tracker.get_global_total_today() == Decimal("0.0051")


@pytest.mark.asyncio
async def test_f02_a_normal_turn_still_records_exactly_once():
    """Guard against the disconnect drain double-counting a clean turn."""

    async def _clean_loop(**kwargs):
        yield LoopEvent(type="token", text="Here are three creators.")
        yield LoopEvent(type="done", finish_reason="stop",
                        usage={"input_tokens": 200, "output_tokens": 300})

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _clean_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=_mock_spring())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    assert await spend_tracker.get_global_total_today() == Decimal("0.0051")


# ---------------------------------------------------------------------------
# F-14 — the SSE heartbeat destroys the tool loop it exists to keep alive
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f14_a_slow_tool_still_delivers_result_and_done_through_the_route(monkeypatch):
    """The trigger is ordinary: a brand pastes a product URL, Claude calls
    analyze_site, the fetch outlasts one heartbeat interval. Under `wait_for`
    the timeout CANCELLED the loop task, the next `__anext__()` raised
    StopAsyncIteration, and the route `break`ed as if the turn ended normally —
    the client received `tool_start` and then nothing: no result, no `done`, no
    `error`.

    Drive the real route with a heartbeat far shorter than the tool call.
    """
    monkeypatch.setenv("SSE_HEARTBEAT_SECONDS", "0.05")
    from app.config import get_settings

    get_settings.cache_clear()

    async def _slow_tool_loop(**kwargs):
        yield LoopEvent(type="tool_start", tool_name="analyze_site", tool_input={"url": "x"})
        await asyncio.sleep(0.30)  # SSRF 15s/hop + Gemini 20s read, scaled down
        yield LoopEvent(type="tool_result", tool_name="analyze_site",
                        tool_status="ok", tool_result_data={"products": []})
        yield LoopEvent(type="token", text="That page sells skincare.")
        yield LoopEvent(type="done", finish_reason="stop",
                        usage={"input_tokens": 10, "output_tokens": 5})

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _slow_tool_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        body = await _drain(response)

    assert "event: tool_start" in body
    assert "event: tool_result" in body, (
        "the heartbeat killed the tool loop: client got tool_start and then silence"
    )
    assert "event: done" in body, "the turn never reached `done`"
    assert ": ping" in body, "test premise: the tool must outlast at least one heartbeat"
    # And the turn was billed and persisted like the success it is.
    assert await spend_tracker.get_global_total_today() > Decimal(0)
    spring.persist_assistant_message.assert_awaited_once()
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_f14_the_heartbeat_is_actually_delivered_during_the_wait(monkeypatch):
    """The heartbeat's entire purpose is to keep the connection alive DURING a
    long provider wait. Under `wait_for` it could never be delivered there."""
    monkeypatch.setenv("SSE_HEARTBEAT_SECONDS", "0.05")
    from app.config import get_settings

    get_settings.cache_clear()

    async def _slow_loop(**kwargs):
        yield LoopEvent(type="tool_start", tool_name="analyze_site", tool_input={})
        await asyncio.sleep(0.25)
        yield LoopEvent(type="done", finish_reason="stop", usage=None)

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _slow_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=_mock_spring())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        body = await _drain(response)

    assert body.count(": ping") >= 3, (
        f"only {body.count(': ping')} heartbeats delivered across a 250ms tool call"
    )
    assert "event: done" in body
