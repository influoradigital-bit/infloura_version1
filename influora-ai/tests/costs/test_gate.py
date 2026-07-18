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
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
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


# ---------------------------------------------------------------------------
# Kabir red-team FIX 3 — opt-in, BLOCKING per-workspace daily hard cap
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_workspace_hard_cap_unset_is_warning_only_never_blocks(monkeypatch):
    """Default (unset) behavior must be byte-for-byte unchanged: even a
    workspace with enormous spend is allowed through when
    WORKSPACE_DAILY_HARD_CAP_USD is not configured -- this is the
    backward-compatibility guarantee for every caller of check_spend_gate()
    that doesn't pass workspace_id at all, and for chat.py's existing
    warning-only soft cap."""
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("1000.00"), workspace_id="ws-big-spender")

    result = await check_spend_gate(workspace_id="ws-big-spender")

    assert result.allowed is True


@pytest.mark.asyncio
async def test_workspace_hard_cap_under_threshold_is_allowed(monkeypatch):
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "5.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("4.99"), workspace_id="ws-under")

    result = await check_spend_gate(workspace_id="ws-under")

    assert result.allowed is True


@pytest.mark.asyncio
async def test_workspace_hard_cap_at_or_over_threshold_blocks(monkeypatch):
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "5.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("5.00"), workspace_id="ws-over")

    result = await check_spend_gate(workspace_id="ws-over")

    assert result.allowed is False
    assert result.error_code == "AI_WORKSPACE_SPEND_CAP_REACHED"


@pytest.mark.asyncio
async def test_workspace_hard_cap_does_not_block_other_workspaces(monkeypatch):
    """The hard cap is per-workspace: workspace A blowing past its cap must
    never block workspace B's independent, still-under-cap total."""
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "5.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("9.00"), workspace_id="ws-a-over-cap")
    await spend_tracker.record_spend(Decimal("1.00"), workspace_id="ws-b-under-cap")

    result_a = await check_spend_gate(workspace_id="ws-a-over-cap")
    result_b = await check_spend_gate(workspace_id="ws-b-under-cap")

    assert result_a.allowed is False
    assert result_b.allowed is True


@pytest.mark.asyncio
async def test_workspace_hard_cap_ignored_when_no_workspace_id_passed(monkeypatch):
    """Callers that don't pass workspace_id (analyze_site.py, voice.py,
    brand_safety.py today) must be completely unaffected by the hard cap
    even when it IS configured -- the cap only ever applies to a call site
    that opts in by supplying workspace_id."""
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "5.0")
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("50.00"), workspace_id="ws-no-context-given")

    result = await check_spend_gate()

    assert result.allowed is True


@pytest.mark.asyncio
async def test_global_kill_switch_still_checked_before_workspace_hard_cap(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "5.0")
    get_settings.cache_clear()

    result = await check_spend_gate(workspace_id="ws-whatever")

    assert result.allowed is False
    assert result.error_code == "AI_KILL_SWITCH_ACTIVE"
