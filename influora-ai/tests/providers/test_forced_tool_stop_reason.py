"""`ClaudeProvider.complete_with_forced_tool` carries the Anthropic
`stop_reason` onto `ClaudeToolResult`.

T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: the brief
extraction route logged `getattr(result, "stop_reason", None)`, which was
always None because the dataclass had no such field, so a max_tokens cutoff
was invisible. These drive the REAL provider method against a fake SDK
client (the same seam tests/costs/test_f01_f07_money_path.py uses), so the
assertion is on what the provider reports, not on a mock of it.
Source: Kabir round-1 verdict, B0-AI defects[5].
"""

from __future__ import annotations

import pytest

from app.providers.claude import ClaudeProvider, ClaudeToolResult


class _Usage:
    input_tokens, output_tokens = 900, 1024
    cache_read_input_tokens, cache_creation_input_tokens = 0, 0


def _provider_returning(response) -> ClaudeProvider:
    class _Messages:
        async def create(self, **kwargs):
            return response

    provider = ClaudeProvider.__new__(ClaudeProvider)
    provider._client = type("C", (), {"messages": _Messages()})()
    provider._breaker = type(
        "B",
        (),
        {
            "before_call": lambda self: None,
            "on_success": lambda self: None,
            "on_failure": lambda self: None,
        },
    )()
    return provider


def _tool_use_block(partial_input):
    return type("T", (), {"type": "tool_use", "input": partial_input})()


@pytest.mark.asyncio
async def test_max_tokens_stop_on_a_tool_use_turn_is_carried_through():
    """A tool_use block cut off at max_tokens still arrives with ok=True —
    stop_reason is the only thing that says it is partial."""
    response = type(
        "R",
        (),
        {
            "content": (_tool_use_block({"brand_name": "Glow"}),),
            "usage": _Usage(),
            "stop_reason": "max_tokens",
        },
    )()
    result = await _provider_returning(response).complete_with_forced_tool(
        system_blocks=[], messages=[], tool_schema={"name": "extract_brief"}
    )
    assert result.ok is True
    assert result.stop_reason == "max_tokens"
    assert result.usage["output_tokens"] == 1024


@pytest.mark.asyncio
async def test_stop_reason_is_carried_on_the_no_tool_use_path_too():
    response = type(
        "R",
        (),
        {
            "content": (type("B", (), {"type": "text", "text": "..."})(),),
            "usage": _Usage(),
            "stop_reason": "max_tokens",
        },
    )()
    result = await _provider_returning(response).complete_with_forced_tool(
        system_blocks=[], messages=[], tool_schema={"name": "extract_brief"}
    )
    assert result.ok is False
    assert result.error == "no_tool_use_in_response"
    assert result.stop_reason == "max_tokens"


@pytest.mark.asyncio
async def test_a_response_without_stop_reason_leaves_it_none():
    response = type(
        "R", (), {"content": (_tool_use_block({"a": 1}),), "usage": _Usage()}
    )()
    result = await _provider_returning(response).complete_with_forced_tool(
        system_blocks=[], messages=[], tool_schema={"name": "t"}
    )
    assert result.ok is True
    assert result.stop_reason is None


def test_stop_reason_is_additive_and_defaulted():
    """Every existing positional/keyword constructor keeps working."""
    assert ClaudeToolResult(ok=True).stop_reason is None
    assert ClaudeToolResult(False, None, "provider_error", None).stop_reason is None
