"""Tests for app.tools.loop's MONEY_TOOL_SCOPE_DECLINE branch (F-0146).

When Spring rejects a *money* tool on the on-behalf gate, the loop must NOT feed the raw 403
back into `messages` and let Claude improvise around it. It emits a fixed, persona-consistent
decline and ends the turn with `finish_reason="money_tool_scope_declined"`.

Nothing referenced `MONEY_TOOL_SCOPE_DECLINE` or `money_tool_scope_declined` anywhere in
influora-ai/tests before this file, so the branch was entirely unexercised. That matters more
than usual here because the condition is an **exact-string match against error codes raised in a
different language** (`OnBehalfAuthResolver.java`). Renaming a code on the Java side breaks this
silently: the branch simply stops firing, the loop falls through to the freestyle path, and Meera
starts narrating a 403 she cannot act on. Nothing fails; the behaviour just degrades.

So these cover both halves:
  - the branch's behaviour, for each of the three codes and for the cases it must NOT catch
  - the cross-language coupling itself — the codes are asserted to still exist in the Java source

Run: pytest tests/tools/test_loop_money_tool_scope_decline.py
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pytest

from app.clients.spring import SpringCallError, SpringResponse
from app.providers.claude import ClaudeStreamEvent
from app.tools.loop import MONEY_TOOL_SCOPE_DECLINE, ToolLoopContext, run_tool_loop
from app.tools.schemas import CONFIRM_LAUNCH, REQUEST_PAYMENT, SHOW_CREATORS, is_money_tool

# The three codes OnBehalfAuthResolver can raise before a money tool ever runs. Role is checked
# before scope, so which one comes back depends on the caller — all three mean the same thing to
# the brand, and all three must decline.
ON_BEHALF_REJECTIONS = [
    "ON_BEHALF_SCOPE_INSUFFICIENT",
    "ON_BEHALF_INSUFFICIENT_ROLE",
    "ON_BEHALF_NOT_A_MEMBER",
]


class _FakeClaude:
    """One scripted `stream_turn` per tool-loop iteration."""

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


class _RejectingSpring:
    """Raises the given on-behalf rejection for every forwarded tool call."""

    def __init__(self, code: str, status_code: int = 403):
        self.code = code
        self.status_code = status_code
        self.calls: list[dict[str, Any]] = []

    async def call_tool_endpoint(self, **kwargs: Any) -> SpringResponse:
        self.calls.append(kwargs)
        raise SpringCallError(
            status_code=self.status_code,
            code=self.code,
            message="On-behalf token may not perform this action",
        )


def _ctx() -> ToolLoopContext:
    return ToolLoopContext(workspace_id="ws-money-decline-001", onbehalf_jwt="fake-jwt", max_iterations=6)


def _turn_calling(tool_name: str) -> list[list[ClaudeStreamEvent]]:
    return [
        [
            ClaudeStreamEvent(
                type="tool_use", tool_name=tool_name, tool_input={}, tool_use_id="tool_1"
            ),
            ClaudeStreamEvent(
                type="usage",
                usage={
                    "input_tokens": 10,
                    "output_tokens": 5,
                    "cache_read_input_tokens": None,
                    "cache_creation_input_tokens": None,
                },
            ),
        ],
        # A second scripted turn so that if the branch DOESN'T fire, the loop can continue and
        # the test fails on behaviour rather than on an IndexError.
        [ClaudeStreamEvent(type="text", text="improvised apology about the 403")],
    ]


async def _run(claude: _FakeClaude, spring: Any):
    return [
        event
        async for event in run_tool_loop(
            claude=claude, spring=spring, system_blocks=[], initial_messages=[], ctx=_ctx()
        )
    ]


@pytest.mark.parametrize("code", ON_BEHALF_REJECTIONS)
@pytest.mark.parametrize("tool_name", [REQUEST_PAYMENT, CONFIRM_LAUNCH])
@pytest.mark.asyncio
async def test_money_tool_rejection_declines_deterministically(tool_name: str, code: str):
    """Every (money tool x on-behalf rejection) pair ends the turn with the fixed decline."""
    claude = _FakeClaude(_turn_calling(tool_name))
    spring = _RejectingSpring(code)

    events = await _run(claude, spring)

    # The decline text is emitted verbatim — not paraphrased by Claude.
    tokens = [e.text for e in events if e.type == "token"]
    assert MONEY_TOOL_SCOPE_DECLINE in tokens

    done = [e for e in events if e.type == "done"]
    assert len(done) == 1
    assert done[0].finish_reason == "money_tool_scope_declined"

    # The turn ends here: Claude is never called a second time to narrate the 403.
    assert claude.call_count == 1

    # The failure is still surfaced as a tool_result so the client can render it honestly.
    tool_results = [e for e in events if e.type == "tool_result"]
    assert tool_results and tool_results[-1].tool_status == "error"
    assert tool_results[-1].tool_result_data["error"] == code


@pytest.mark.asyncio
async def test_non_money_tool_with_same_rejection_does_not_decline():
    """The gate is (money tool AND on-behalf code) — a read tool must fall through to the
    ordinary error path so Claude can still answer around it."""
    assert not is_money_tool(SHOW_CREATORS)
    claude = _FakeClaude(_turn_calling(SHOW_CREATORS))
    spring = _RejectingSpring("ON_BEHALF_SCOPE_INSUFFICIENT")

    events = await _run(claude, spring)

    assert MONEY_TOOL_SCOPE_DECLINE not in [e.text for e in events if e.type == "token"]
    done = [e for e in events if e.type == "done"]
    assert not done or done[0].finish_reason != "money_tool_scope_declined"
    # It continued the loop rather than ending the turn.
    assert claude.call_count == 2


@pytest.mark.asyncio
async def test_money_tool_with_unrelated_error_does_not_decline():
    """An ordinary failure on a money tool is not a scope rejection — declining there would hide
    a real bug behind a permissions message."""
    claude = _FakeClaude(_turn_calling(REQUEST_PAYMENT))
    spring = _RejectingSpring("INSUFFICIENT_FUNDS", status_code=402)

    events = await _run(claude, spring)

    assert MONEY_TOOL_SCOPE_DECLINE not in [e.text for e in events if e.type == "token"]
    done = [e for e in events if e.type == "done"]
    assert not done or done[0].finish_reason != "money_tool_scope_declined"


def test_on_behalf_codes_still_exist_in_the_java_source():
    """The coupling guard.

    `loop.py` matches these codes as literal strings raised by Java. If someone renames one in
    OnBehalfAuthResolver, every test above still passes — they construct the code themselves —
    while production silently stops declining. This is the only assertion that can catch that,
    so it reads the Java source directly rather than trusting a constant on the Python side.
    """
    # parents: [0]=tests/tools [1]=tests [2]=influora-ai [3]=repo root
    resolver = (
        Path(__file__).resolve().parents[3]
        / "influora-api"
        / "src"
        / "main"
        / "java"
        / "com"
        / "influora"
        / "security"
        / "OnBehalfAuthResolver.java"
    )
    if not resolver.exists():  # pragma: no cover - influora-api absent in an AI-only checkout
        pytest.skip(f"influora-api source not present at {resolver}")

    source = resolver.read_text(encoding="utf-8", errors="replace")
    for code in ON_BEHALF_REJECTIONS:
        assert re.search(rf'"{code}"', source), (
            f"{code} is matched as a literal in app/tools/loop.py but no longer appears in "
            f"OnBehalfAuthResolver.java — the money-tool decline has silently stopped firing"
        )
