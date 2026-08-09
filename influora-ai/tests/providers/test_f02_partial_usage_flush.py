"""F-02 (provider half) — the cancelled stream must flush the usage the
provider already billed.

Priya's sign-off review mutation-tested this: the route-level F-02 tests use a
fake tool loop, so disabling `claude.py`'s flush left them green. This drives
`ClaudeProvider.stream_turn` directly with `is_cancelled` going True mid-stream.

Before the fix, `usage` was emitted ONLY at `message_stop`, so a cancelled
stream returned with no usage at all and every caller's `if usage:` guard
skipped recording — while Anthropic billed the full cached prefix and every
token generated before the disconnect.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.costs.pricing import estimate_cost_usd
from app.providers.claude import ClaudeProvider


class _Usage:
    def __init__(self, **kw):
        self.input_tokens = kw.get("input_tokens")
        self.output_tokens = kw.get("output_tokens")
        self.cache_read_input_tokens = kw.get("cache_read_input_tokens")
        self.cache_creation_input_tokens = kw.get("cache_creation_input_tokens")


class _Event:
    def __init__(self, type_, **kw):
        self.type = type_
        for k, v in kw.items():
            setattr(self, k, v)


class _FakeStream:
    """Emits message_start (input + cache tokens), text deltas, then
    message_delta (running output_tokens) — the real Anthropic stream shape."""

    def __init__(self, events):
        self._events = events
        self.closed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        async def gen():
            for event in self._events:
                yield event

        return gen()

    async def close(self):
        self.closed = True


def _provider_with(events) -> tuple[ClaudeProvider, _FakeStream]:
    stream = _FakeStream(events)

    class _Messages:
        def stream(self, **kwargs):
            return stream

    provider = ClaudeProvider.__new__(ClaudeProvider)
    provider._client = type("C", (), {"messages": _Messages()})()
    provider._breaker = type(
        "B", (), {"before_call": lambda self: None,
                  "on_success": lambda self: None,
                  "on_failure": lambda self: None},
    )()
    return provider, stream


@pytest.mark.asyncio
async def test_f02_cancelled_stream_emits_the_usage_already_billed():
    events = [
        _Event("message_start", message=type("M", (), {
            "usage": _Usage(input_tokens=200, cache_read_input_tokens=8000,
                            cache_creation_input_tokens=0)})()),
        _Event("content_block_delta", delta=type("D", (), {"type": "text_delta", "text": "Scanning"})()),
        _Event("message_delta", usage=_Usage(output_tokens=300)),
        _Event("content_block_delta", delta=type("D", (), {"type": "text_delta", "text": " creators"})()),
    ]
    provider, stream = _provider_with(events)

    seen = {"n": 0}

    def is_cancelled():
        # Connected for the first two events, gone from the third.
        seen["n"] += 1
        return seen["n"] > 3

    emitted = [
        e async for e in provider.stream_turn(
            system_blocks=[], messages=[], tools=[], is_cancelled=is_cancelled
        )
    ]

    usage_events = [e for e in emitted if e.type == "usage"]
    assert usage_events, (
        "a cancelled stream emitted NO usage — the provider billed the prefix and "
        "everything generated, and the caller records $0"
    )
    usage = usage_events[-1].usage
    assert usage["partial"] is True
    assert usage["input_tokens"] == 200
    assert usage["output_tokens"] == 300
    assert usage["cache_read_input_tokens"] == 8000
    assert usage_events[-1].stop_reason == "client_disconnected"
    assert stream.closed, "the stream must be closed on cancellation"

    # And it prices to something real — this is the money the ceiling must see.
    cost = estimate_cost_usd("claude-sonnet-4-5-20250929", usage)
    assert cost == Decimal("0.0075"), cost


@pytest.mark.asyncio
async def test_f02_cancelling_before_any_usage_arrived_emits_nothing():
    """Nothing was billed yet, so nothing is claimed. The flush must not invent
    a zero-token usage event that would look like a recorded turn."""
    events = [_Event("content_block_delta", delta=type("D", (), {"type": "text_delta", "text": "x"})())]
    provider, _stream = _provider_with(events)

    emitted = [
        e async for e in provider.stream_turn(
            system_blocks=[], messages=[], tools=[], is_cancelled=lambda: True
        )
    ]
    assert [e for e in emitted if e.type == "usage"] == []
