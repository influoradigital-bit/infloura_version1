"""Deterministic repro for the Meera BLANK TURN defect (P1, 2026-07-24).

ARTIFACT of wiki/ai-review/meera-blank-turn-ai-review.md (Ash). Deliberately
parked here, NOT under influora-ai/tests/ -- these tests assert the CURRENT
(buggy) behaviour, so collecting them in CI would lock the bug in. When F1/F2
land, port them into influora-ai/tests/providers/test_stream_truncation.py and
influora-ai/tests/routes/test_chat_blank_turn_guard.py with the assertions
INVERTED (a `token` event must exist; finish_reason must be "empty_response").

Run:
    cd influora-ai
    INFLUORA_AI_ROOT="$PWD" python -m pytest ../wiki/ai-review/meera-blank-turn-repro.py -q -s
Result at time of writing: 3 passed; route body == 113 bytes (exact match to
neha's live capture).


Reproduces, at the code level, the exact wire signature neha observed:
    event: prompt_meta
    event: done {"finish_reason":"stop"}
...with ZERO token events and ZERO tool_start/tool_result events.

Mechanism under test: an assistant turn that opens a `tool_use` content block
and is then cut off by max_tokens BEFORE the block closes. The Anthropic
stream therefore never emits `content_block_stop` for that block, so
ClaudeProvider.stream_turn (app/providers/claude.py:163) never yields the
`tool_use` event, run_tool_loop sees an empty `pending_tool_calls` and an
empty `assistant_text`, and falls straight through to
`done finish_reason="stop"` (app/tools/loop.py:166-171).
"""

from __future__ import annotations

import json
import os
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-key")
os.environ.setdefault("SARVAM_API_KEY", "test-key")
os.environ.setdefault("INTERNAL_HMAC_KEY", "test-key")
os.environ.setdefault("SERVICE_TOKEN_SIGNING_KEY", "test-key")
os.environ.setdefault("DEV_SHARED_JWT_SECRET", "test-secret")

sys.path.insert(0, os.environ["INFLUORA_AI_ROOT"])

from fastapi import Request  # noqa: E402

from app.auth.service_token import VerifiedToken  # noqa: E402
from app.providers.claude import ClaudeProvider  # noqa: E402
from app.routes import chat as chat_route  # noqa: E402
from app.tools.loop import ToolLoopContext, run_tool_loop  # noqa: E402


# --------------------------------------------------------------------------
# Fake Anthropic stream: message_start -> content_block_start(tool_use) ->
# input_json_delta * N -> message_delta(stop_reason="max_tokens") ->
# message_stop.  NO content_block_stop -- the block is abandoned mid-JSON,
# which is what a max_tokens cut during tool-input generation looks like.
# --------------------------------------------------------------------------
_TRUNCATED_CREATE_CAMPAIGN_JSON = (
    '{"product_name":"Glow Serum","creator_count":12,"campaign_type":"HYPE",'
    '"title":"72-Hour Glow Blitz","description":"A 72-hour remix blitz where '
    'creators remix the brand hero reel. The goal is fast reach at a flat '
    'per-reel rate with first-come slots. Creators keep the hook and add their '
    'own'  # <-- cut here by max_tokens
)


def _truncated_tool_use_events():
    yield SimpleNamespace(type="message_start")
    yield SimpleNamespace(
        type="content_block_start",
        index=0,
        content_block=SimpleNamespace(type="tool_use", id="toolu_01ABC", name="create_campaign"),
    )
    for i in range(0, len(_TRUNCATED_CREATE_CAMPAIGN_JSON), 32):
        yield SimpleNamespace(
            type="content_block_delta",
            index=0,
            delta=SimpleNamespace(
                type="input_json_delta", partial_json=_TRUNCATED_CREATE_CAMPAIGN_JSON[i : i + 32]
            ),
        )
    # max_tokens cut: NO content_block_stop is emitted for index 0.
    yield SimpleNamespace(
        type="message_delta", delta=SimpleNamespace(stop_reason="max_tokens", stop_sequence=None)
    )
    yield SimpleNamespace(type="message_stop")


class _FakeStream:
    def __init__(self, events):
        self._events = list(events)

    def __aiter__(self):
        async def gen():
            for e in self._events:
                yield e

        return gen()

    async def get_final_message(self):
        return SimpleNamespace(
            usage=SimpleNamespace(
                input_tokens=9123,
                output_tokens=384,  # == MEERA_CHAT_MAX_TOKENS
                cache_read_input_tokens=8000,
                cache_creation_input_tokens=0,
            ),
            stop_reason="max_tokens",
        )

    async def close(self):
        return None


class _FakeStreamCtx:
    def __init__(self, events):
        self._events = events

    async def __aenter__(self):
        return _FakeStream(self._events)

    async def __aexit__(self, *exc):
        return False


# --------------------------------------------------------------------------
# 1. Provider level
# --------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_provider_emits_truncated_signal_and_never_salvages_partial_tool():
    """POST-FIX regression guard (was test_provider_swallows_...): a truncated
    tool_use block is now surfaced as a `truncated` event, NOT silently dropped,
    and the partial JSON is deliberately never carried/salvaged (money guardrail
    — a half-built create_campaign/request_payment must never be forwarded)."""
    provider = ClaudeProvider.__new__(ClaudeProvider)
    from app.config import get_settings
    from app.providers.claude import CircuitBreaker

    settings = get_settings()
    provider._settings = settings
    provider._breaker = CircuitBreaker(failure_threshold=5, recovery_seconds=30.0)
    provider._client = MagicMock()
    provider._client.messages.stream = lambda **kw: _FakeStreamCtx(_truncated_tool_use_events())

    events = [
        e
        async for e in provider.stream_turn(system_blocks=[], messages=[], tools=[], max_tokens=384)
    ]

    kinds = [e.type for e in events]
    assert "text" not in kinds, "no text was ever streamed"
    assert "tool_use" not in kinds, "partial tool JSON must NEVER be salvaged (money guardrail)"
    assert "truncated" in kinds, "FIX: the truncated tool_use is now surfaced, not silently dropped"
    trunc = next(e for e in events if e.type == "truncated")
    assert trunc.tool_name_partial is not None, "the truncated tool's name is reported (for logs)"
    assert trunc.tool_input is None, "partial JSON is NOT carried through — never salvaged"
    assert trunc.stop_reason == "max_tokens", "the real stop_reason is carried, not discarded"
    assert "usage" in kinds
    usage_ev = next(e for e in events if e.type == "usage")
    assert usage_ev.usage["output_tokens"] == 384  # the fingerprint in the ai_spend log


# --------------------------------------------------------------------------
# 2. Tool-loop level
# --------------------------------------------------------------------------
class _ProviderYieldingOnlyUsage:
    def stream_turn(self, **kwargs):
        async def gen():
            from app.providers.claude import ClaudeStreamEvent

            yield ClaudeStreamEvent(type="usage", usage={"input_tokens": 9123, "output_tokens": 384})

        return gen()


@pytest.mark.asyncio
async def test_loop_emits_honest_fallback_instead_of_silent_blank():
    """POST-FIX regression guard (was test_loop_turns_a_dropped_tool_use_into_a_clean_done_stop):
    an empty model turn now yields an honest streamed fallback message + a
    distinct, refundable finish_reason="empty_response" — never a silent
    `done finish_reason="stop"` that is indistinguishable from a real answer."""
    events = [
        e
        async for e in run_tool_loop(
            claude=_ProviderYieldingOnlyUsage(),
            spring=MagicMock(),
            system_blocks=[],
            initial_messages=[],
            ctx=ToolLoopContext(workspace_id="ws-1", onbehalf_jwt="jwt"),
        )
    ]
    assert [e.type for e in events] == ["token", "done"], "FIX: honest fallback text, not silence"
    assert events[0].text and "train of thought" in events[0].text, "the in-persona fallback message"
    assert events[1].finish_reason == "empty_response", "distinct + refundable, never 'stop'"


# --------------------------------------------------------------------------
# 3. Route level -- the exact 113-byte wire body + the refund
# --------------------------------------------------------------------------
def _make_request(body: dict) -> Request:
    raw = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": raw, "more_body": False}

    return Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/chat",
            "headers": [],
            "query_string": b"",
            "client": ("test", 0),
        },
        receive,
    )


async def _blank_loop(**kwargs):
    from app.tools.loop import LoopEvent

    yield LoopEvent(type="done", finish_reason="stop", usage={"input_tokens": 9123, "output_tokens": 384})


@pytest.mark.asyncio
async def test_route_emits_prompt_meta_plus_done_only_and_refunds():
    spring = MagicMock()
    spring.persist_assistant_message = AsyncMock()
    spring.release_turn_credit = AsyncMock()

    verified = VerifiedToken(
        workspace_id="ws-1",
        scope="chat:stream",
        subject="u1",
        conversation_id="conv-1",
        claims={"messageId": "01HSERVERMINTED"},
    )

    with (
        patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)),
        patch.object(chat_route, "_get_spring", return_value=spring),
        patch.object(chat_route, "_get_claude", return_value=MagicMock()),
        patch.object(chat_route, "_fetch_brand_context", AsyncMock(return_value={
            "workspace_id": "ws-1", "audience": "BRAND", "brand": {}, "credit_state": {},
            "conversation": [],
        })),
        patch.object(chat_route, "run_tool_loop", _blank_loop),
    ):
        response = await chat_route.chat(
            _make_request(
                {
                    "workspace_id": "ws-1",
                    "conversation_id": "conv-1",
                    "stream_token": "x",
                    "onbehalf_jwt": "y",
                    "conversation": [{"role": "user", "content": "make the hype campaign"}],
                }
            ),
            authorization=None,
        )
        chunks = [c async for c in response.body_iterator]

    body = "".join(chunks)
    print("\n--- RAW SSE BODY ---")
    print(repr(body))
    print("bytes:", len(body.encode()))

    assert "event: token" not in body
    assert "event: tool_start" not in body
    assert "event: prompt_meta" in body
    assert '"finish_reason": "stop"' in body
    spring.persist_assistant_message.assert_not_called()
    spring.release_turn_credit.assert_awaited_once()  # the refund neha saw in the logs
