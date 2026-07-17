"""P2-17 §3.3/§5 acceptance criteria -- check_spend_gate() itself.

Route-level "zero provider calls made" coverage lives in
tests/routes/test_ai_spend_gate.py; this module unit-tests the gate function
in isolation (kill-switch precedence, ceiling math, day-scoped state).
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.config import get_settings
from app.costs import spend_tracker
from app.costs.gate import check_spend_gate


@pytest.fixture(autouse=True)
async def _reset_state(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_defaults_allow_a_normal_request():
    result = await check_spend_gate()
    assert result.allowed is True
    assert result.error_code is None


@pytest.mark.asyncio
async def test_kill_switch_blocks_regardless_of_spend(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()

    result = await check_spend_gate()

    assert result.allowed is False
    assert result.error_code == "AI_KILL_SWITCH_ACTIVE"


@pytest.mark.asyncio
async def test_kill_switch_checked_before_ceiling(monkeypatch):
    """Both conditions true simultaneously -- kill-switch error code wins
    (checked first per spec §3.3 ordering)."""
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "1.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("5.00"), workspace_id=None)

    result = await check_spend_gate()

    assert result.error_code == "AI_KILL_SWITCH_ACTIVE"


@pytest.mark.asyncio
async def test_ceiling_breach_blocks_with_no_kill_switch(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "10.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("10.00"), workspace_id=None)

    result = await check_spend_gate()

    assert result.allowed is False
    assert result.error_code == "AI_SPEND_CEILING_REACHED"


@pytest.mark.asyncio
async def test_spend_strictly_under_ceiling_is_allowed(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "10.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("9.99"), workspace_id=None)

    result = await check_spend_gate()

    assert result.allowed is True
