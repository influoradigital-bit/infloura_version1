"""Cost fix 1 (2026-09-22): the creator system blocks shared by EVERY creator are cached
for 1 hour; the per-creator block stays on 5 minutes, and 1-hour entries come first
(Anthropic rejects a 1-hour entry after a 5-minute one). AI_CREATOR_SHARED_CACHE_TTL=5m
turns it back off. Brand prompts are unchanged."""

from __future__ import annotations

import pytest

from app.config import get_settings
from app.prompt.assembler import build_block_a, build_block_a_creator, build_block_b_creator
from app.prompt.content_knowledge import build_creator_knowledge_block

ONE_HOUR = {"type": "ephemeral", "ttl": "1h"}
FIVE_MIN = {"type": "ephemeral"}


@pytest.fixture(autouse=True)
def _fresh_settings(monkeypatch):
    monkeypatch.delenv("AI_CREATOR_SHARED_CACHE_TTL", raising=False)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _creator_system_blocks():
    return [
        build_block_a_creator(["get_my_earnings"]),
        build_creator_knowledge_block(),
        build_block_b_creator({"first_name": "Asha"}),
    ]


def test_shared_creator_blocks_are_one_hour_and_the_creator_block_is_five_minutes():
    a, knowledge, b = _creator_system_blocks()
    assert a["cache_control"] == ONE_HOUR
    assert knowledge["cache_control"] == ONE_HOUR
    assert b["cache_control"] == FIVE_MIN


def test_no_one_hour_entry_ever_follows_a_five_minute_one():
    ttls = [blk["cache_control"].get("ttl", "5m") for blk in _creator_system_blocks()]
    seen_5m = False
    for ttl in ttls:
        if ttl == "5m":
            seen_5m = True
        assert not (seen_5m and ttl == "1h"), ttls


def test_setting_5m_turns_the_one_hour_cache_off(monkeypatch):
    monkeypatch.setenv("AI_CREATOR_SHARED_CACHE_TTL", "5m")
    get_settings.cache_clear()
    for blk in _creator_system_blocks():
        assert blk["cache_control"] == FIVE_MIN


def test_brand_block_a_is_untouched():
    assert build_block_a()["cache_control"] == FIVE_MIN
