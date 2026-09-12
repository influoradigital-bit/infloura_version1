"""Wave 2 round 2 money-path tests (Kabir red-team FAILs #1/#2).

Priya's fix moves the AI-credit charge to the SEND gate (influora-api,
`MeeraSessionService#doSendTurn` -> `AICreditService#tryConsumeForTurn`), keyed
on the server-minted `messageId`. `app/routes/chat.py`'s job in this new model
is narrower but security-critical:

  1. Use the stream token's VERIFIED `messageId` claim as the turn id for both
     the success write-back and the failure refund -- NEVER the client-supplied
     `body["turn_id"]` (Kabir FAIL 2: a client used to be able to pin `turn_id`
     to a constant across many turns to zero-charge everything after the
     first).
  2. On a genuine PROVIDER failure (an `error` SSE event, a `ToolLoopCapExceeded`,
     an unexpected exception, or a `done` with no assistant text at all) --
     call `spring.release_turn_credit(...)` to refund the send-time charge.
  3. On a plain CLIENT DISCONNECT -- do NOT call release. The browser already
     received every `token` event it read before hanging up, so the charge
     correctly stays. This distinction is the entire fix for Kabir FAIL 1 (the
     disconnect-farm exploit): the OLD code only charged in the write-back, so
     reading tokens then disconnecting got a free turn. The charge already
     happened at send now, so a disconnect no longer costs anything to leave
     charged -- but it must also never trigger a REFUND, which would reopen a
     different flavor of the same exploit (disconnect after reading tokens to
     force a refund while still keeping value from the interaction).

These tests drive `chat()` end-to-end (real StreamingResponse, drained), with
`run_tool_loop` replaced by scripted async generators and `_get_spring`
replaced by a mock client -- same pattern as
`tests/routes/test_chat_tool_result_data.py`.
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.costs import spend_tracker
from app.routes import chat as chat_route
from app.tools.loop import LoopEvent, ToolLoopCapExceeded

_TOOL_RESULT_DATA = {"creators": [{"handle": "@example"}]}

WORKSPACE_ID = "ws-money-path-001"
CONVERSATION_ID = "conv-money-path-1"
STREAM_MESSAGE_ID = "01HMESSAGE_SERVER_MINTED_AAAA"


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


def _body(turn_id: str | None = "client-supplied-turn-id") -> dict:
    b = {
        "workspace_id": WORKSPACE_ID,
        "conversation_id": CONVERSATION_ID,
        "stream_token": "irrelevant-because-verify_token_async-is-mocked",
        "conversation": [],
        "onbehalf_jwt": "onbehalf-jwt-value",
    }
    if turn_id is not None:
        b["turn_id"] = turn_id
    return b


def _verified_stream_token() -> VerifiedToken:
    """A genuine chat:stream token -- carries the server-minted messageId claim
    (see StreamTokenService.mint in influora-api), which is what the fix reads
    instead of body['turn_id']."""
    return VerifiedToken(
        workspace_id=WORKSPACE_ID,
        scope="chat:stream",
        subject="user-1",
        conversation_id=CONVERSATION_ID,
        # Fix round 1: StreamTokenService.mint writes userType; a chat:stream
        # token without it is refused 403 audience_unverified.
        claims={"messageId": STREAM_MESSAGE_ID, "userType": "BRAND"},
    )


def _verified_service_token() -> VerifiedToken:
    """A service token -- no per-turn binding, no messageId claim."""
    return VerifiedToken(
        workspace_id=WORKSPACE_ID, scope="service", subject="spring-service", conversation_id=None, claims={}
    )


def _mock_spring() -> MagicMock:
    spring = MagicMock()
    spring.persist_assistant_message = AsyncMock()
    spring.release_turn_credit = AsyncMock()
    return spring


async def _fake_success_loop(**kwargs):
    yield LoopEvent(type="token", text="Here are three creators.")
    yield LoopEvent(type="done", finish_reason="stop", usage={"input_tokens": 10, "output_tokens": 5})


async def _fake_error_event_loop(**kwargs):
    yield LoopEvent(type="token", text="partial reply before the provider died")
    yield LoopEvent(type="error", error_code="provider_error")


async def _fake_error_event_no_text_loop(**kwargs):
    yield LoopEvent(type="error", error_code="provider_error")


async def _fake_empty_done_loop(**kwargs):
    yield LoopEvent(type="done", finish_reason="pending_human_confirm", usage=None)


async def _fake_tool_result_then_error_loop(**kwargs):
    """A data-returning tool call (creator list / budget calc) delivers its
    payload to the client, Claude narrates nothing, and the provider then dies
    on the next iteration -- zero assistant text, but real data was streamed."""
    yield LoopEvent(type="tool_start", tool_name="find_creators", tool_input={})
    yield LoopEvent(
        type="tool_result", tool_name="find_creators", tool_status="ok", tool_result_data=_TOOL_RESULT_DATA
    )
    yield LoopEvent(type="error", error_code="provider_error")


async def _fake_tool_result_then_empty_done_loop(**kwargs):
    """Same as above but the loop reaches a clean (empty) `done` instead of an
    `error` event -- still zero assistant text, tool_result was delivered."""
    yield LoopEvent(type="tool_start", tool_name="find_creators", tool_input={})
    yield LoopEvent(
        type="tool_result", tool_name="find_creators", tool_status="ok", tool_result_data=_TOOL_RESULT_DATA
    )
    yield LoopEvent(type="done", finish_reason="stop", usage=None)


@pytest.fixture(autouse=True)
async def _reset_state(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()


async def _drain(response):
    raw = b""
    async for chunk in response.body_iterator:
        raw += chunk if isinstance(chunk, bytes) else chunk.encode()
    return raw.decode()


@pytest.mark.asyncio
async def test_success_persists_with_verified_message_id_never_client_turn_id():
    """Kabir FAIL 2 fix: even though the client supplied its own turn_id in the
    body, the write-back must use the STREAM TOKEN'S verified messageId claim
    instead. release_turn_credit must never be called on a clean success."""
    request = _make_request(_body(turn_id="attacker-constant-turn-id"))
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_success_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.persist_assistant_message.assert_awaited_once()
    _, kwargs = spring.persist_assistant_message.call_args
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID
    assert kwargs["turn_id"] != "attacker-constant-turn-id"
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_provider_error_after_text_streamed_keeps_charge_and_persists():
    """MUST-FIX #1 (Kabir red-team follow-up, item 6 -- the refund-path leak):
    Claude can stream a full/partial answer and THEN the provider throws on a
    later loop iteration (an `error` SSE event arriving AFTER `token` events
    already flushed to the browser). The old code refunded on `provider_failed`
    without checking whether usable text had already reached the client --
    letting the client keep the answer AND get the turn refunded for free.
    Net rule: refund iff nothing usable reached the client. Since text WAS
    delivered here, the charge must stay and the reply must be persisted,
    exactly like the clean-success path."""
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_error_event_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.persist_assistant_message.assert_awaited_once()
    _, kwargs = spring.persist_assistant_message.call_args
    assert kwargs["content"] == "partial reply before the provider died"
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_provider_error_with_empty_reply_still_releases_credit():
    """Confirms the pre-existing empty-reply refund path still works: a
    provider `error` event with NO text streamed beforehand (distinct from the
    done-with-no-text case below) must still call release_turn_credit keyed on
    the verified messageId, and must NOT call persist_assistant_message."""
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_error_event_no_text_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_awaited_once()
    _, kwargs = spring.release_turn_credit.call_args
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_tool_loop_cap_exceeded_releases_credit():
    async def _capped_loop(**kwargs):
        if False:
            yield  # pragma: no cover -- makes this an async generator
        raise ToolLoopCapExceeded()

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _capped_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_awaited_once()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_unexpected_exception_releases_credit():
    async def _raising_loop(**kwargs):
        if False:
            yield  # pragma: no cover
        raise RuntimeError("provider blew up")

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _raising_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_awaited_once()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_done_with_no_assistant_text_releases_credit():
    """A `done` event that carries no assistant text at all (e.g.
    pending_human_confirm with nothing said yet) is treated as a failure for
    money purposes -- refund rather than silently keep a charge for a turn the
    brand got nothing out of."""
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_empty_done_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_awaited_once()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_tool_result_delivered_then_provider_error_keeps_charge_no_refund():
    """Kabir red-team residual (LOW): the refund decision used to key ONLY on
    assistant TEXT. A data-returning tool (creator list, budget calc) can
    stream its `tool_result` payload to the client with zero narration text,
    and then the provider dies on a later iteration -- the old code saw empty
    `final_text` and refunded the send-time charge despite the client having
    received real, usable output. Net rule now: refund iff NEITHER text NOR a
    delivered tool_result reached the client. Since a tool_result WAS
    delivered here, the charge must stay and no refund may fire."""
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_tool_result_then_error_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_not_awaited()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_tool_result_delivered_then_empty_done_keeps_charge_no_refund():
    """Same edge, reached via a clean empty `done` instead of an `error`
    event: zero assistant text but a delivered tool_result must still keep
    the charge rather than falling into the empty-reply refund path."""
    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_tool_result_then_empty_done_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_not_awaited()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_client_disconnect_never_releases_credit_even_after_reading_tokens():
    """Kabir FAIL 1 fix, the critical negative case: a client that reads every
    `token` event and then disconnects must NOT trigger a release. The charge
    already happened at send and correctly stays -- neither persist NOR
    release may be called on this path."""
    request = _make_request(_body())
    # Disconnect on the SECOND poll (after the loop has already produced a
    # token) -- simulates "read the tokens, then hang up".
    request.is_disconnected = AsyncMock(side_effect=[False, True])
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_success_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.release_turn_credit.assert_not_awaited()
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_service_token_path_falls_back_to_body_turn_id():
    """A service token (Spring-proxied /chat) carries no messageId claim --
    this path is not reachable by an untrusted browser client, so falling back
    to the body-supplied turn_id (or request_id) matches pre-existing
    behavior; it is not the path Kabir FAIL 2 exploited."""
    request = _make_request(_body(turn_id="spring-supplied-turn-id"))
    request.is_disconnected = AsyncMock(return_value=False)
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(chat_route, "run_tool_loop", _fake_success_loop), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    spring.persist_assistant_message.assert_awaited_once()
    _, kwargs = spring.persist_assistant_message.call_args
    assert kwargs["turn_id"] == "spring-supplied-turn-id"


# ----------------------------------------------------------------------------------------------
# P1-6 -- "we bill the brand for our own failures".
#
# influora-api charges 1 AI credit at SEND (`MeeraSessionService#doSendTurn` ->
# `AICreditService#tryConsumeForTurn`), BEFORE this route is ever contacted. Until this fix, the
# ONLY caller of `spring.release_turn_credit` was `release_charge()`, defined inside
# `event_stream()` -- so every terminal path that ends `chat()` before the generator is iterated
# left the decrement stranded:
#
#   1. the spend gate's 503 (kill switch / daily ceiling / per-workspace hard cap) -- OUR
#      ceiling refusing OUR turn, the single highest-volume case;
#   2. the brand-context 403 (Spring refused or could not serve the on-behalf context fetch);
#   3. the `audience_unverified` 403 (a stale or forged stream-token shape);
#   4. the conversation-binding 403;
#   5. an exception thrown assembling the prompt or constructing the provider clients, which
#      escaped as an unhandled 500 with no generator ever created.
#
# In every one of them the browser rendered MeeraChatPanel's generic "Didn't catch that -- try
# again?", the brand retried, and burned another credit -- with the client deliberately NOT
# re-POSTing automatically (MeeraChatPanel.tsx:621-623) precisely because that would double-charge.
#
# (A sixth path -- influora-ai unreachable from the browser entirely -- cannot be covered here by
# construction; `MeeraSessionService#releaseStaleUnansweredTurn` is the backstop for that one.)
# ----------------------------------------------------------------------------------------------


def _blocked_gate() -> MagicMock:
    gate = MagicMock()
    gate.allowed = False
    gate.error_code = "AI_KILL_SWITCH_ACTIVE"
    gate.error_message = "AI provider calls are temporarily disabled (kill switch active)"
    gate.reservation = None
    return gate


def _verified_creator_stream_token() -> VerifiedToken:
    return VerifiedToken(
        workspace_id=WORKSPACE_ID,
        scope="chat:stream",
        subject="creator-1",
        conversation_id=CONVERSATION_ID,
        claims={"messageId": STREAM_MESSAGE_ID, "userType": "CREATOR"},
    )


@pytest.mark.asyncio
async def test_spend_gate_block_releases_the_send_time_charge():
    """P1-6, the headline path: the brand's credit balance must be unchanged by a turn that dies
    at OUR spend gate.

    The brand was charged 1 credit at send. The gate then refuses the turn with a 503 before a
    single provider token exists -- a failure entirely on our side. `release_turn_credit` keyed on
    the token's server-minted messageId is what makes the balance whole again.

    WHY THIS FAILS AGAINST THE BUGGY CODE: the pre-fix route did
    `return _error_response(503, ...)` straight out of the `if not gate.allowed:` block.
    `StreamingResponse` was never constructed, `event_stream()` was never iterated, and
    `release_charge()` -- the sole caller of `release_turn_credit` in the entire service -- lived
    inside that generator. So `release_turn_credit` was never awaited and this assertion reads
    "Expected 'release_turn_credit' to have been awaited once. Awaited 0 times."
    """
    request = _make_request(_body())
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(
        chat_route, "check_spend_gate", AsyncMock(return_value=_blocked_gate())
    ), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 503
    spring.release_turn_credit.assert_awaited_once()
    _, kwargs = spring.release_turn_credit.call_args
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID
    assert kwargs["conversation_id"] == CONVERSATION_ID
    # Nothing was streamed, so there is nothing to persist -- and persisting would make the refund
    # a no-op server-side (AICreditService#release refuses to refund a turn whose reply landed).
    spring.persist_assistant_message.assert_not_awaited()


@pytest.mark.asyncio
async def test_brand_context_unauthorized_releases_the_send_time_charge():
    """Path 2. Spring refused (or could not serve) the on-behalf context fetch, so the turn ends
    with a 403 and no provider call. Pre-fix this returned straight out of the `brand_context is
    None` block -- the daily spend reservation was released there, the brand's AI credit was not."""
    request = _make_request(_body())
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(
        chat_route, "_fetch_brand_context", AsyncMock(return_value=(None, "context_unauthorized"))
    ), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 403
    spring.release_turn_credit.assert_awaited_once()
    _, kwargs = spring.release_turn_credit.call_args
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID


@pytest.mark.asyncio
async def test_failure_before_the_stream_starts_releases_the_send_time_charge():
    """Path 5, and the widest of them: anything that throws in the straight-line stretch between
    the context fetch and `StreamingResponse(...)`. Pre-fix this escaped `chat()` as an unhandled
    500 -- no generator, no refund, and no structured error for the client either."""
    request = _make_request(_body())
    spring = _mock_spring()

    def _exploding_assemble_prompt(*args, **kwargs):
        raise RuntimeError("prompt assembly blew up")

    brand_context = {"workspace_id": WORKSPACE_ID, "audience": "BRAND", "conversation": []}

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_stream_token())
    ), patch.object(
        chat_route, "_fetch_brand_context", AsyncMock(return_value=(brand_context, None))
    ), patch.object(
        chat_route, "assemble_prompt", _exploding_assemble_prompt
    ), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 503
    spring.release_turn_credit.assert_awaited_once()
    _, kwargs = spring.release_turn_credit.call_args
    assert kwargs["turn_id"] == STREAM_MESSAGE_ID


@pytest.mark.asyncio
async def test_early_release_never_fires_on_a_client_supplied_turn_id():
    """P1-6 guard, and the reason `release_early` keys on `verified.claims["messageId"]` rather
    than on `turn_id`: on the service-token path there is no server-minted messageId and `turn_id`
    falls back to the CLIENT-supplied `body["turn_id"]`. Refunding against that value would hand a
    client a lever to name someone else's turn -- or its own still-streaming one -- which is Kabir
    FAIL 2 wearing a different hat. No verified messageId, no early refund."""
    request = _make_request(_body(turn_id="attacker-constant-turn-id"))
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_service_token())
    ), patch.object(
        chat_route, "check_spend_gate", AsyncMock(return_value=_blocked_gate())
    ), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 503
    spring.release_turn_credit.assert_not_awaited()


@pytest.mark.asyncio
async def test_early_release_never_fires_on_a_creator_turn():
    """P1-6 guard: a CREATOR turn is never charged against the brand AI-credit ledger at all
    (`MeeraSessionService#doSendTurn`'s `isCreatorTurn` branch skips `tryConsumeForTurn`), so
    there is nothing to give back and calling release would only log a spurious "never charged at
    send" WARN on the Spring side for every blocked creator turn."""
    request = _make_request(_body())
    spring = _mock_spring()

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified_creator_stream_token())
    ), patch.object(
        chat_route, "check_spend_gate", AsyncMock(return_value=_blocked_gate())
    ), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")

    assert response.status_code == 503
    spring.release_turn_credit.assert_not_awaited()
