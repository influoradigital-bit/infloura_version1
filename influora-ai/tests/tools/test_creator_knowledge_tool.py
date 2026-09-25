"""The creator knowledge lookup (PROMPT_VERSION .13, 2026-09-24): tool-loop side.

`get_creator_knowledge` is a LOCAL tool. The loop runs it in-process from Influora's own static
knowledge (`LOOKUP_TEXT`) and must never forward it to Spring: no `/internal/meera/creator/*`
route, no on-behalf JWT on the wire. The Spring client here is a mock whose
`call_tool_endpoint` must never be awaited.

What this pins:
- a creator turn (warn-only or fully granted, the tool list taken from `assemble_prompt`) runs
  it and hands the model `{"topic", "knowledge": LOOKUP_TEXT[topic]}`; the browser's SSE copy
  carries only the topic;
- an unknown or missing topic is an is_error `{"error": "unknown_topic", "topics": [...]}`
  result, never a crash and never a Spring call;
- a BRAND turn that emits it is refused by the per-turn offered-tools gate.
"""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.prompt.assembler import assemble_prompt
from app.prompt.content_knowledge import LOOKUP_TEXT, LOOKUP_TOPICS
from app.providers.claude import ClaudeStreamEvent
from app.tools.creator_schemas import CREATOR_TOOL_NAMES, GET_CREATOR_KNOWLEDGE
from app.tools.loop import ToolLoopContext, run_tool_loop
from app.tools.schemas import get_tool_schemas


class _FakeClaude:
    """One scripted `stream_turn` per loop iteration; records every call's kwargs."""

    def __init__(self, turns: list[list[ClaudeStreamEvent]]):
        self._turns = list(turns)
        self.calls: list[dict[str, Any]] = []

    def stream_turn(self, **kwargs: Any):
        self.calls.append(kwargs)
        events = self._turns[len(self.calls) - 1]

        async def _gen():
            for event in events:
                yield event

        return _gen()


def _spring() -> MagicMock:
    spring = MagicMock()
    spring.call_tool_endpoint = AsyncMock(side_effect=AssertionError("forwarded to Spring"))
    return spring


def _claude_calling(tool_input: dict[str, Any]) -> _FakeClaude:
    return _FakeClaude(
        [
            [
                ClaudeStreamEvent(
                    type="tool_use",
                    tool_name=GET_CREATOR_KNOWLEDGE,
                    tool_input=tool_input,
                    tool_use_id="tool_k1",
                ),
            ],
            [ClaudeStreamEvent(type="text", text="Clip the mic about a hand below your chin.")],
        ]
    )


def _creator_tools(tools_enabled: list[str]) -> list[dict[str, Any]]:
    prompt = assemble_prompt(
        {
            "workspace_id": "creator-lookup-loop",
            "audience": "CREATOR",
            "creator": {"workspace_id": "creator-lookup-loop", "tools_enabled": tools_enabled},
            "conversation": [{"role": "user", "content": "hi"}],
        },
        session_id="s-loop",
    )
    assert prompt.audience == "CREATOR"
    return prompt.tools


async def _run(claude: _FakeClaude, spring: MagicMock, tools: list[dict[str, Any]]):
    return [
        event
        async for event in run_tool_loop(
            claude=claude,
            spring=spring,
            system_blocks=[],
            initial_messages=[],
            ctx=ToolLoopContext(workspace_id="creator-lookup-loop", onbehalf_jwt="fake-jwt", max_iterations=4),
            tools=tools,
        )
    ]


def _tool_result_block(claude: _FakeClaude) -> dict[str, Any]:
    """The tool_result block the loop handed the model on its second call."""
    assert len(claude.calls) == 2
    # The loop keeps appending to the same `messages` list after the call, so search it.
    blocks = [
        b
        for m in claude.calls[1]["messages"]
        if m["role"] == "user" and isinstance(m["content"], list)
        for b in m["content"]
        if isinstance(b, dict) and b.get("type") == "tool_result"
    ]
    assert len(blocks) == 1
    assert blocks[0]["tool_use_id"] == "tool_k1"
    return blocks[0]


@pytest.mark.asyncio
@pytest.mark.parametrize("tools_enabled", [[], list(CREATOR_TOOL_NAMES)], ids=["warn_only", "full_grant"])
@pytest.mark.parametrize("topic", list(LOOKUP_TOPICS))
async def test_a_creator_turn_runs_the_lookup_in_process(tools_enabled: list[str], topic: str):
    claude = _claude_calling({"topic": topic})
    spring = _spring()

    events = await _run(claude, spring, _creator_tools(tools_enabled))

    spring.call_tool_endpoint.assert_not_awaited()
    block = _tool_result_block(claude)
    assert not block.get("is_error")
    assert json.loads(block["content"]) == {"topic": topic, "knowledge": LOOKUP_TEXT[topic]}

    results = [e for e in events if e.type == "tool_result"]
    assert len(results) == 1
    assert results[0].tool_name == GET_CREATOR_KNOWLEDGE
    assert results[0].tool_status == "ok"
    # The browser gets the topic for its work-trail step, never the notes themselves.
    assert results[0].tool_result_data == {"topic": topic}
    assert [e for e in events if e.type == "done"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "tool_input",
    [
        {"topic": "lighting"},
        {"topic": ""},
        {"topic": 3},
        {},
        # Extra fields are refused, not ignored (schema: additionalProperties false).
        {"topic": "audio", "workspace_id": "other-creator", "path": "/etc/passwd"},
    ],
    ids=["unknown", "empty", "not_a_string", "missing", "extra_fields"],
)
async def test_an_unknown_or_missing_topic_is_an_error_listing_the_topics(tool_input: dict[str, Any]):
    claude = _claude_calling(tool_input)
    spring = _spring()

    events = await _run(claude, spring, _creator_tools([]))

    spring.call_tool_endpoint.assert_not_awaited()
    expected = {"error": "unknown_topic", "topics": list(LOOKUP_TOPICS)}
    block = _tool_result_block(claude)
    assert block["is_error"] is True
    assert json.loads(block["content"]) == expected
    results = [e for e in events if e.type == "tool_result"]
    assert [(e.tool_status, e.tool_result_data) for e in results] == [("error", expected)]
    # The turn still finished: the model got the error back and answered.
    assert [e for e in events if e.type == "done"]


@pytest.mark.asyncio
async def test_a_brand_turn_that_emits_it_is_refused_by_the_offered_tools_gate():
    claude = _claude_calling({"topic": "audio"})
    spring = _spring()

    events = await _run(claude, spring, get_tool_schemas())

    spring.call_tool_endpoint.assert_not_awaited()
    block = _tool_result_block(claude)
    assert block["is_error"] is True
    payload = json.loads(block["content"])
    assert payload["error"] == "tool_not_offered"
    assert LOOKUP_TEXT["audio"] not in block["content"]
    results = [e for e in events if e.type == "tool_result"]
    assert [(e.tool_name, e.tool_status) for e in results] == [(GET_CREATOR_KNOWLEDGE, "error")]
