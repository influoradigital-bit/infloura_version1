"""P2-17 §3.2 -- spend_tracker accumulation + UTC-day rollover."""

from __future__ import annotations

import datetime as dt
from decimal import Decimal

import pytest

from app.costs import spend_tracker


@pytest.fixture(autouse=True)
async def _reset_tracker():
    await spend_tracker.reset_for_testing()
    yield
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_record_spend_accumulates_global_total():
    await spend_tracker.record_spend(Decimal("1.50"), workspace_id=None)
    total = await spend_tracker.record_spend(Decimal("2.50"), workspace_id=None)

    assert total == Decimal("4.00")
    assert await spend_tracker.get_global_total_today() == Decimal("4.00")


@pytest.mark.asyncio
async def test_record_spend_accumulates_per_workspace_independently_of_global():
    await spend_tracker.record_spend(Decimal("1.00"), workspace_id="ws-a")
    await spend_tracker.record_spend(Decimal("2.00"), workspace_id="ws-b")
    await spend_tracker.record_spend(Decimal("0.50"), workspace_id="ws-a")

    assert await spend_tracker.get_workspace_total_today("ws-a") == Decimal("1.50")
    assert await spend_tracker.get_workspace_total_today("ws-b") == Decimal("2.00")
    # Global total sums across all workspaces regardless of per-workspace split.
    assert await spend_tracker.get_global_total_today() == Decimal("3.50")


@pytest.mark.asyncio
async def test_no_workspace_id_only_updates_global_total():
    await spend_tracker.record_spend(Decimal("5.00"), workspace_id=None)

    assert await spend_tracker.get_global_total_today() == Decimal("5.00")
    assert await spend_tracker.get_workspace_total_today("anything") == Decimal(0)


@pytest.mark.asyncio
async def test_state_resets_on_utc_day_rollover(monkeypatch):
    await spend_tracker.record_spend(Decimal("9.99"), workspace_id="ws-a")
    assert await spend_tracker.get_global_total_today() == Decimal("9.99")

    # Simulate a UTC day rollover by monkeypatching _today_utc to "tomorrow".
    tomorrow = dt.datetime.now(dt.timezone.utc).date() + dt.timedelta(days=1)
    monkeypatch.setattr(spend_tracker, "_today_utc", lambda: tomorrow)

    # The next read/write must see a fresh, empty state for the new day.
    assert await spend_tracker.get_global_total_today() == Decimal(0)
    assert await spend_tracker.get_workspace_total_today("ws-a") == Decimal(0)

    total_after_rollover = await spend_tracker.record_spend(Decimal("1.00"), workspace_id="ws-a")
    assert total_after_rollover == Decimal("1.00")
