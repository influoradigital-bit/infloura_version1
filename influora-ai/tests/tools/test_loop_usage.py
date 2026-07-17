"""Tests for app.tools.loop's tool-loop usage accumulation.

Before this fix, `run_tool_loop` OVERWROTE `final_usage` on every "usage"
event instead of summing it, so a turn that made two or more Claude calls
(one per tool-loop iteration) only ever billed chat.py's `estimate_cost_usd`
for the LAST iteration's tokens -- every earlier iteration's real spend was
silently dropped. These tests exercise the loop directly with a fake
ClaudeProvider that emits a distinct "usage" event per iteration and assert
the "done" event's usage is the SUM across iterations, not just the last one.
"""

from __future__ import annotations

from typing import Any

import pytest

from app.clients.spring import SpringResponse
from app.providers.claude import ClaudeStreamEvent
from app.tools.loop import ToolLoopContext, _accumulate_usage, run_tool_loop


class _FakeClaude:
    """Stands in for ClaudeProvider: `stream_turn(...)` returns a fresh async
    generator of pre-scripted events each call, one script per tool-loop
    iteration (mirrors real usage -- one `stream_turn` call per iteration)."""

    def __init__(self, turns: list[list[ClaudeStreamEvent]]):
        self._turns = list(turns)
        self.call_count = 0

    def stream_turn(self, **kwargs: Any):
        events = self._turns[self.call_count]
        self.call_count += 1

        async def _gen():
            for event in events:
                yield event

        return _gen()


class _FakeSpring:
    """Stands in for SpringInternalClient: every tool call succeeds with a
    plain (non-pending-confirm) result, so the loop always continues to the
    next iteration rather than stopping early."""

    def __init__(self):
        self.calls: list[dict[str, Any]] = []

    async def call_tool_endpoint(self, **kwargs: Any) -> SpringResponse:
        self.calls.append(kwargs)
        return SpringResponse(status_code=200, data={"creators": []}, raw={"data": {"creators": []}})


def _usage(input_tokens: int, output_tokens: int) -> dict[str, Any]:
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "cache_read_input_tokens": None,
        "cache_creation_input_tokens": None,
    }


def _ctx() -> ToolLoopContext:
    return ToolLoopContext(workspace_id="ws-loop-usage-001", onbehalf_jwt="fake-jwt", max_iterations=6)


@pytest.mark.asyncio
async def test_single_iteration_turn_usage_passes_through_unchanged():
    """No tool calls at all -- one `stream_turn` call, one usage event. The
    "done" event's usage must equal that single event's usage verbatim."""
    claude = _FakeClaude(
        turns=[
            [
                ClaudeStreamEvent(type="text", text="Hello!"),
                ClaudeStreamEvent(type="usage", usage=_usage(50, 20)),
            ],
        ]
    )
    spring = _FakeSpring()

    events = [
        event
        async for event in run_tool_loop(
            claude=claude, spring=spring, system_blocks=[], initial_messages=[], ctx=_ctx()
        )
    ]

    done_events = [e for e in events if e.type == "done"]
    assert len(done_events) == 1
    assert done_events[0].usage == _usage(50, 20)
    assert claude.call_count == 1


@pytest.mark.asyncio
async def test_multi_tool_call_turn_accumulates_usage_across_iterations():
    """Two tool-loop iterations (one tool call, then a final text-only
    answer) -- each iteration makes its OWN Claude call with its own usage.
    The "done" event's usage must be the SUM of both, not just iteration 2's."""
    claude = _FakeClaude(
        turns=[
            [
                ClaudeStreamEvent(
                    type="tool_use",
                    tool_name="show_creators",
                    tool_input={"niche": "fitness", "count": 3},
                    tool_use_id="tool_1",
                ),
                ClaudeStreamEvent(type="usage", usage=_usage(100, 50)),
            ],
            [
                ClaudeStreamEvent(type="text", text="Here are your creators!"),
                ClaudeStreamEvent(type="usage", usage=_usage(80, 30)),
            ],
        ]
    )
    spring = _FakeSpring()

    events = [
        event
        async for event in run_tool_loop(
            claude=claude, spring=spring, system_blocks=[], initial_messages=[], ctx=_ctx()
        )
    ]

    assert claude.call_count == 2
    assert len(spring.calls) == 1  # the one show_creators forward

    done_events = [e for e in events if e.type == "done"]
    assert len(done_events) == 1
    # Under the pre-fix behavior this would equal _usage(80, 30) -- iteration
    # 1's 100/50 tokens silently dropped.
    assert done_events[0].usage == {
        "input_tokens": 180,
        "output_tokens": 80,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
    }


@pytest.mark.asyncio
async def test_three_iteration_turn_sums_all_three():
    claude = _FakeClaude(
        turns=[
            [
                ClaudeStreamEvent(
                    type="tool_use", tool_name="show_creators", tool_input={"niche": "x", "count": 1}, tool_use_id="t1"
                ),
                ClaudeStreamEvent(type="usage", usage=_usage(10, 5)),
            ],
            [
                ClaudeStreamEvent(
                    type="tool_use", tool_name="calculate_budget", tool_input={"product_price": 100, "goal": "launch"}, tool_use_id="t2"
                ),
                ClaudeStreamEvent(type="usage", usage=_usage(20, 8)),
            ],
            [
                ClaudeStreamEvent(type="text", text="All set."),
                ClaudeStreamEvent(type="usage", usage=_usage(15, 6)),
            ],
        ]
    )
    spring = _FakeSpring()

    events = [
        event
        async for event in run_tool_loop(
            claude=claude, spring=spring, system_blocks=[], initial_messages=[], ctx=_ctx()
        )
    ]

    done_events = [e for e in events if e.type == "done"]
    assert done_events[0].usage["input_tokens"] == 10 + 20 + 15
    assert done_events[0].usage["output_tokens"] == 5 + 8 + 6


# ── _accumulate_usage unit coverage ─────────────────────────────────────────


def test_accumulate_usage_both_none_returns_none():
    assert _accumulate_usage(None, None) is None


def test_accumulate_usage_first_event_passes_through():
    usage = _usage(10, 5)
    assert _accumulate_usage(None, usage) == usage


def test_accumulate_usage_sums_fields():
    total = _accumulate_usage(_usage(10, 5), _usage(3, 2))
    assert total == {
        "input_tokens": 13,
        "output_tokens": 7,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
    }
