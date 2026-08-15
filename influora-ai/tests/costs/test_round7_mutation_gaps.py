"""Round-7: the money-path fix-halves the full 82-mutation sweep found unpinned.

Each test below corresponds to one mutation that reverted a shipped fix and left
the whole suite green. A fix nothing catches is a fix one refactor away from
gone — the same standard Priya applied in rounds 3 and 6.

- **F-01c** — the Gemini row's `cache_read_usd`/`cache_write_usd`. Set to the
  input rate deliberately (Google has no billed cache path on this integration)
  precisely so an unexpected cache field can never bill at $0 the way F-01 billed
  Anthropic's. Zeroing them left 640 tests green.
- **F-03c** — `record_spend`'s own `max(redis_total, memory_total)`. The existing
  F-03 test pins `get_global_total_today` and `get_workspace_total_today`; the
  return value of `record_spend` is what every route logs as `spend_today_usd`
  and it had its own, unpinned `max()`.
- **F-05f** — the release of an unsettled reservation on a turn that records no
  spend. Without it, every failed chat turn's hold sits against the ceiling until
  its TTL expires — a slow, self-inflicted denial of service on the budget.
- **F-06g** — a multi-chunk TTS reply that fails on a LATER chunk. Earlier chunks
  were already POSTed and billed by Sarvam; the failure path must report them.
"""

from __future__ import annotations

import json
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.config import GEMINI_MODEL
from app.costs import spend_tracker
from app.costs.pricing import estimate_cost_usd
from app.routes import chat as chat_route
from app.tools.loop import LoopEvent

# ---------------------------------------------------------------------------
# F-01c — Gemini cache tokens are never free
# ---------------------------------------------------------------------------


def test_f01c_gemini_cache_tokens_are_billed_at_the_input_rate():
    """This integration has no billed Google cache path, so the rates are set to
    the INPUT rate rather than zero: if a cache field ever does appear in a
    Gemini usage payload it is over-billed, never billed at $0. Zeroing the two
    rates is the exact shape of the original F-01 defect, one provider over."""
    plain = {"input_tokens": 1000, "output_tokens": 0}
    cached = {"input_tokens": 1000, "output_tokens": 0, "cache_read_input_tokens": 8000}

    plain_cost = estimate_cost_usd(GEMINI_MODEL, plain)
    cached_cost = estimate_cost_usd(GEMINI_MODEL, cached)

    assert cached_cost > plain_cost, "8k cached Gemini tokens billed at $0"
    # 8000 extra tokens at the $0.30/Mtok input rate.
    assert cached_cost - plain_cost == Decimal("0.0024")


def test_f01c_gemini_cache_write_tokens_are_billed_too():
    plain = {"input_tokens": 1000, "output_tokens": 0}
    written = {"input_tokens": 1000, "output_tokens": 0, "cache_creation_input_tokens": 8000}
    assert estimate_cost_usd(GEMINI_MODEL, written) > estimate_cost_usd(GEMINI_MODEL, plain)


# ---------------------------------------------------------------------------
# F-03c — record_spend's OWN max(), not just the two getters
# ---------------------------------------------------------------------------


async def _value(v):
    return v


@pytest.mark.asyncio
async def test_f03c_record_spend_returns_the_larger_of_the_two_stores(monkeypatch):
    """`record_spend`'s return value is what every route logs as
    `spend_today_usd` and what an operator reads during an incident. A Redis
    that lagged through an outage must not be able to under-report it."""
    await spend_tracker.reset_for_testing()
    monkeypatch.setattr(spend_tracker, "_redis_configured", lambda: True)
    # Redis is reachable but stuck behind: it returns a total from before the
    # outage, while memory has accumulated every spend.
    monkeypatch.setattr(spend_tracker, "_record_spend_redis", lambda *a, **k: _value(Decimal("3.00")))

    await spend_tracker.record_spend(Decimal("6.00"), workspace_id="ws1")
    reported = await spend_tracker.record_spend(Decimal("6.00"), workspace_id="ws1")

    assert reported == Decimal("12.00"), (
        f"record_spend reported {reported} while $12.00 of real spend has accrued"
    )


@pytest.mark.asyncio
async def test_f03c_a_higher_redis_total_still_wins_on_record(monkeypatch):
    """max(), not "always memory" — another worker's spend must still count."""
    await spend_tracker.reset_for_testing()
    monkeypatch.setattr(spend_tracker, "_redis_configured", lambda: True)
    monkeypatch.setattr(spend_tracker, "_record_spend_redis", lambda *a, **k: _value(Decimal("40.00")))
    assert await spend_tracker.record_spend(Decimal("1.00"), workspace_id="ws1") == Decimal("40.00")


# ---------------------------------------------------------------------------
# F-05f — an unsettled reservation is always released
# ---------------------------------------------------------------------------

WORKSPACE_ID = "ws-round7-f05f"
CONVERSATION_ID = "conv-round7-f05f"


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
        conversation_id=CONVERSATION_ID, claims={"messageId": "01HMSG-ROUND7-F05F"},
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


@pytest.mark.asyncio
async def test_f05f_a_turn_that_records_no_spend_releases_its_reservation(monkeypatch):
    """The provider dies before emitting any usage, so the `if final_usage:`
    settlement block never runs. The hold this turn placed against the ceiling
    must still come back — otherwise every failed turn permanently shrinks the
    day's budget until the reservation TTL (5 min) expires."""
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()

    async def _loop_that_dies_with_no_usage(**kwargs):
        yield LoopEvent(type="token", text="Looking at that...")
        raise RuntimeError("provider exploded")

    request = _make_request(_body())
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(
        chat_route, "verify_token_async", AsyncMock(return_value=_verified())
    ), patch.object(chat_route, "run_tool_loop", _loop_that_dies_with_no_usage), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=_mock_spring())
    ):
        response = await chat_route.chat(request, authorization="Bearer whatever")
        await _drain(response)

    assert await spend_tracker.get_global_total_today() == Decimal(0), "test premise: no spend settled"
    held = await spend_tracker.get_reserved_global()
    assert held == Decimal(0), (
        f"${held} of reservation is still held against the ceiling after a failed turn"
    )
    assert await spend_tracker.get_reserved_workspace(WORKSPACE_ID) == Decimal(0)


@pytest.mark.asyncio
async def test_f05f_a_settled_turn_leaves_nothing_held(monkeypatch):
    """Control: the normal path settles through `record_spend(reservation=...)`,
    so the release above is testing the unsettled branch and not merely
    'reservations always end at zero'."""
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()

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

    assert await spend_tracker.get_global_total_today() > Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


# ---------------------------------------------------------------------------
# F-06g — a later-chunk TTS failure still reports what was already billed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f06g_a_multi_chunk_tts_failure_reports_the_chunks_already_posted(monkeypatch):
    """Sarvam bills per POST. A three-chunk reply that dies on chunk 2 has
    already been billed for chunk 1 — returning billed=False/billed_chars=0
    there is the F-06 defect exactly: a real charge the daily counter never
    sees, reachable by any reply long enough to chunk."""
    from app.providers import sarvam as sarvam_module

    provider = sarvam_module.SarvamProvider.__new__(sarvam_module.SarvamProvider)
    provider._settings = _FakeSettings()
    provider._breaker_tts = _NoopBreaker()

    long_text = ("Budget Rs.15,000 per creator for UGC. " * 120).strip()
    chunks = sarvam_module._chunk_text(long_text)
    assert len(chunks) >= 2, "test premise: the reply must split into multiple chunks"

    calls = {"n": 0}

    class _FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        async def post(self, *args, **kwargs):
            calls["n"] += 1
            if calls["n"] >= 2:
                raise RuntimeError("sarvam 500 on the second chunk")
            resp = MagicMock()
            resp.raise_for_status = MagicMock()
            resp.json = MagicMock(return_value={"audios": [""]})
            return resp

    monkeypatch.setattr(sarvam_module.httpx, "AsyncClient", lambda **kw: _FakeClient())

    result = await provider.speak(long_text)

    assert result.ok is False
    assert result.billed is True, "chunk 1 was POSTed and billed; the result says otherwise"
    # `billed_chars` is incremented BEFORE each POST, so the chunk whose request
    # raised is counted too. That is deliberate and fail-safe in the same
    # direction as F-04's most-expensive fallback: a spend counter may
    # over-report a call that might have been billed, never under-report one
    # that was.
    posted = sum(len(sarvam_module.speakable(c)) for c in chunks[:2])
    assert result.billed_chars == posted, (
        f"billed_chars={result.billed_chars}, but {posted} chars were sent before the failure"
    )
    assert result.billed_chars >= len(sarvam_module.speakable(chunks[0])) > 0


class _FakeTimeouts:
    sarvam_connect = 1.0
    sarvam_tts_read = 1.0


class _FakeSettings:
    timeouts = _FakeTimeouts()
    sarvam_api_key = "test-key"


class _NoopBreaker:
    def before_call(self) -> None:
        return None

    def on_success(self) -> None:
        return None

    def on_failure(self) -> None:
        return None
