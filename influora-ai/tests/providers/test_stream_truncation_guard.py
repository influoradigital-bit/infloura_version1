"""CI REGRESSION GUARD — a max_tokens-truncated tool call must never become a
silent blank turn.

WHY THIS EXISTS (2026-07-24): when the Anthropic stream was cut by max_tokens
mid `input_json_delta`, the `tool_use` content block never closed
(`content_block_stop` was never emitted), so `ClaudeProvider.stream_turn`
silently discarded the entire tool call — no event, no log, no error — and the
tool loop fell through to `done finish_reason="stop"`: a completely blank turn
(~28% of Meera turns on large tool payloads like create_campaign). Ported from
the diagnostic repro at wiki/ai-review/meera-blank-turn-repro.py with the
assertions set to the FIXED behaviour, so a regression re-breaks CI here.

Ref: wiki/ai-review/meera-blank-turn-ai-review.md (F1 = provider surfaces a
`truncated` event and never salvages the partial JSON; F2 = loop retries once
then streams an honest fallback + finish_reason="empty_response").
"""

from __future__ import annotations

import os
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

# Config loads these at import time; TESTING mode never makes a real call.
os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-key")
os.environ.setdefault("SARVAM_API_KEY", "test-key")
os.environ.setdefault("INTERNAL_HMAC_KEY", "test-key")
os.environ.setdefault("SERVICE_TOKEN_SIGNING_KEY", "test-key")
os.environ.setdefault("DEV_SHARED_JWT_SECRET", "test-secret")

from app.providers.claude import ClaudeProvider, ClaudeStreamEvent, CircuitBreaker  # noqa: E402
from app.tools.loop import ToolLoopContext, run_tool_loop  # noqa: E402


# A create_campaign tool call whose JSON is cut off partway (a max_tokens cut).
_TRUNCATED_JSON = (
    '{"product_name":"Glow Serum","campaign_type":"HYPE",'
    '"title":"72-Hour Glow Blitz","description":"A 72-hour remix blitz where '
    'creators remix the brand hero reel and add their own'  # <-- cut here
)


def _truncated_tool_use_events():
    """Simulates the Anthropic stream for a tool_use block abandoned mid-JSON:
    NO content_block_stop is ever emitted for the open block."""
    yield SimpleNamespace(type="message_start")
    yield SimpleNamespace(
        type="content_block_start",
        index=0,
        content_block=SimpleNamespace(type="tool_use", id="toolu_01ABC", name="create_campaign"),
    )
    for i in range(0, len(_TRUNCATED_JSON), 32):
        yield SimpleNamespace(
            type="content_block_delta",
            index=0,
            delta=SimpleNamespace(type="input_json_delta", partial_json=_TRUNCATED_JSON[i : i + 32]),
        )
    # max_tokens cut: NO content_block_stop for index 0.
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
                output_tokens=384,
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


class _ProviderYieldingOnlyUsage:
    """A provider turn that streams no text and no tool call — the empty turn the
    loop's guard must recover, not pass through as a silent 'stop'."""

    def stream_turn(self, **kwargs):
        async def gen():
            yield ClaudeStreamEvent(type="usage", usage={"input_tokens": 9123, "output_tokens": 384})

        return gen()


@pytest.mark.asyncio
async def test_provider_surfaces_truncation_and_never_salvages_partial_tool():
    """F1: an unclosed tool_use at message_stop is surfaced as a `truncated`
    event carrying stop_reason, and the partial JSON is NEVER carried through —
    a half-built create_campaign/request_payment must never be forwarded."""
    provider = ClaudeProvider.__new__(ClaudeProvider)
    from app.config import get_settings

    provider._settings = get_settings()
    provider._breaker = CircuitBreaker(failure_threshold=5, recovery_seconds=30.0)
    provider._client = MagicMock()
    provider._client.messages.stream = lambda **kw: _FakeStreamCtx(_truncated_tool_use_events())

    events = [
        e
        async for e in provider.stream_turn(system_blocks=[], messages=[], tools=[], max_tokens=384)
    ]
    kinds = [e.type for e in events]

    assert "tool_use" not in kinds, "partial tool JSON must NEVER be salvaged (money guardrail)"
    assert "truncated" in kinds, "the truncated tool_use must be surfaced, not silently dropped"
    trunc = next(e for e in events if e.type == "truncated")
    assert trunc.tool_name_partial is not None, "truncated tool name reported for logs"
    assert trunc.tool_input is None, "partial JSON is not carried through"
    assert trunc.stop_reason == "max_tokens", "the real stop_reason is carried, not discarded"


@pytest.mark.asyncio
async def test_loop_emits_honest_fallback_not_silent_blank():
    """F2: an empty model turn yields an honest streamed fallback message and a
    distinct, refundable finish_reason='empty_response' — never a silent
    `done finish_reason='stop'` indistinguishable from a real answer."""
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

    assert [e.type for e in events] == ["token", "done"], "honest fallback text, not silence"
    assert events[0].text and "train of thought" in events[0].text, "the in-persona fallback message"
    assert events[1].finish_reason == "empty_response", "distinct + refundable, never 'stop'"
