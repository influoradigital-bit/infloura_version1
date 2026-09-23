"""The provider must tell the cost tracker how many cache writes were 1-hour (2x),
including on the pinned SDK (0.42) that has no `cache_creation` breakdown."""

from __future__ import annotations

from decimal import Decimal
from types import SimpleNamespace

from app.providers.claude import _cache_write_1h_tokens, _uses_1h_cache


def test_reads_the_breakdown_when_the_sdk_has_it():
    usage = SimpleNamespace(cache_creation_input_tokens=19_000,
                            cache_creation=SimpleNamespace(ephemeral_1h_input_tokens=18_000))
    assert _cache_write_1h_tokens(usage, assume_all_1h=True) == 18_000


def test_reads_the_breakdown_from_extra_fields_as_a_dict():
    usage = SimpleNamespace(cache_creation_input_tokens=19_000,
                            model_extra={"cache_creation": {"ephemeral_1h_input_tokens": 18_000}})
    assert _cache_write_1h_tokens(usage) == 18_000


def test_old_sdk_with_1h_markers_counts_every_write_as_1h():
    usage = SimpleNamespace(cache_creation_input_tokens=19_000)
    assert _cache_write_1h_tokens(usage, assume_all_1h=True) == 19_000


def test_old_sdk_without_1h_markers_reports_none():
    usage = SimpleNamespace(cache_creation_input_tokens=19_000)
    assert _cache_write_1h_tokens(usage) is None


def test_detects_1h_markers_in_system_blocks():
    assert _uses_1h_cache([{"cache_control": {"type": "ephemeral", "ttl": "1h"}}])
    assert not _uses_1h_cache([{"cache_control": {"type": "ephemeral"}}, {"type": "text"}])
    assert not _uses_1h_cache(None)


# --- wiring: stream_turn itself (completed and cancelled), old-SDK usage shape ---

import pytest  # noqa: E402

from app.costs.pricing import estimate_cost_usd  # noqa: E402
from tests.providers.test_f02_partial_usage_flush import _Event, _provider_with, _Usage  # noqa: E402

ONE_HOUR_BLOCKS = [
    {"type": "text", "text": "shared", "cache_control": {"type": "ephemeral", "ttl": "1h"}},
    {"type": "text", "text": "creator", "cache_control": {"type": "ephemeral"}},
]


def _start(writes: int):
    return _Event("message_start", message=type("M", (), {
        "usage": _Usage(input_tokens=100, cache_read_input_tokens=0,
                        cache_creation_input_tokens=writes)})())


@pytest.mark.asyncio
async def test_completed_stream_reports_1h_writes_and_prices_them_at_2x():
    final = type("F", (), {"usage": _Usage(input_tokens=100, output_tokens=0,
                                           cache_read_input_tokens=0,
                                           cache_creation_input_tokens=19_000),
                           "stop_reason": "end_turn", "content": []})()
    provider, stream = _provider_with([_start(19_000), _Event("message_stop")])

    async def get_final_message():
        return final

    stream.get_final_message = get_final_message
    emitted = [e async for e in provider.stream_turn(system_blocks=ONE_HOUR_BLOCKS, messages=[], tools=[])]
    usage = [e for e in emitted if e.type == "usage"][-1].usage
    assert usage["cache_creation_1h_input_tokens"] == 19_000
    # 100 input at $3 + 19k writes at 2x $3, not 1.25x.
    assert estimate_cost_usd("claude-sonnet-4-5-20250929", usage) == Decimal("0.1143")


@pytest.mark.asyncio
async def test_cancelled_stream_flush_also_carries_the_1h_writes():
    provider, _ = _provider_with([_start(19_000), _Event("message_delta", usage=_Usage(output_tokens=5))])
    seen = {"n": 0}

    def is_cancelled():
        seen["n"] += 1
        return seen["n"] > 1

    emitted = [e async for e in provider.stream_turn(
        system_blocks=ONE_HOUR_BLOCKS, messages=[], tools=[], is_cancelled=is_cancelled)]
    usage = [e for e in emitted if e.type == "usage"][-1].usage
    assert usage["partial"] is True
    assert usage["cache_creation_1h_input_tokens"] == 19_000


@pytest.mark.asyncio
async def test_brand_stream_without_1h_markers_adds_no_1h_count():
    provider, _ = _provider_with([_start(8_000), _Event("message_delta", usage=_Usage(output_tokens=5))])
    seen = {"n": 0}

    def is_cancelled():
        seen["n"] += 1
        return seen["n"] > 1

    emitted = [e async for e in provider.stream_turn(
        system_blocks=[{"type": "text", "text": "b", "cache_control": {"type": "ephemeral"}}],
        messages=[], tools=[], is_cancelled=is_cancelled)]
    usage = [e for e in emitted if e.type == "usage"][-1].usage
    assert "cache_creation_1h_input_tokens" not in usage
