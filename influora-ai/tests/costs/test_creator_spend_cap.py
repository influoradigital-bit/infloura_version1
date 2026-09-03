"""A8 — per-creator MONTHLY spend cap (Meera for Creators Phase A).

Exercises `app.costs.spend_tracker`'s creator counters + gate in isolation
(in-memory path; Redis unset). The route wiring is covered in
tests/routes/test_chat_creator_audience.py.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.config import get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import (
    CREATOR_CAP_MESSAGE,
    CREATOR_MONTHLY_CAP_USD,
    SpendCapExceeded,
    check_creator_spend_gate,
    creator_monthly_cap_usd,
    get_creator_month_total,
    record_creator_spend,
)

CREATOR_ID = "creator-cap-001"


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    monkeypatch.delenv("AI_CREATOR_MONTHLY_CAP_USD", raising=False)
    monkeypatch.delenv("REDIS_URL", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("AI_CREATOR_MONTHLY_CAP_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


def test_default_cap_is_the_spec_value():
    assert CREATOR_MONTHLY_CAP_USD == Decimal("0.75")
    assert creator_monthly_cap_usd() == Decimal("0.75")


def test_cap_is_configurable_via_env(monkeypatch):
    monkeypatch.setenv("AI_CREATOR_MONTHLY_CAP_USD", "1.50")
    get_settings.cache_clear()
    assert creator_monthly_cap_usd() == Decimal("1.5")


@pytest.mark.asyncio
async def test_record_and_read_are_per_creator():
    await record_creator_spend(Decimal("0.10"), CREATOR_ID)
    await record_creator_spend(Decimal("0.25"), CREATOR_ID)
    await record_creator_spend(Decimal("5.00"), "someone-else")
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.35")
    assert await get_creator_month_total("someone-else") == Decimal("5.00")
    assert await get_creator_month_total("never-seen") == Decimal(0)


@pytest.mark.asyncio
async def test_gate_is_a_no_op_for_brand_audience_even_when_over_cap():
    await record_creator_spend(Decimal("100"), CREATOR_ID)
    await check_creator_spend_gate(CREATOR_ID, "BRAND")  # must not raise
    await check_creator_spend_gate(CREATOR_ID, None)
    await check_creator_spend_gate(None, "CREATOR")


@pytest.mark.asyncio
async def test_gate_passes_under_the_cap_and_blocks_at_the_cap():
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    await check_creator_spend_gate(CREATOR_ID, "CREATOR")  # 0.74 < 0.75

    await record_creator_spend(Decimal("0.01"), CREATOR_ID)  # now exactly 0.75
    with pytest.raises(SpendCapExceeded) as excinfo:
        await check_creator_spend_gate(CREATOR_ID, "creator")  # case-insensitive
    assert excinfo.value.creator_id == CREATOR_ID
    assert excinfo.value.message == CREATOR_CAP_MESSAGE


def test_over_cap_message_is_friendly_and_spoken_safe():
    """The creator reads this verbatim: no dollars, no 'spend', no jargon,
    tells them when it resets and what to do."""
    lower = CREATOR_CAP_MESSAGE.lower()
    assert "monthly" in lower
    assert "1st of next month" in lower
    assert "support" in lower
    for banned in ("$", "usd", "token", "spend", "escr" + "ow"):
        assert banned not in lower


@pytest.mark.asyncio
async def test_zero_cap_disables_the_gate(monkeypatch):
    monkeypatch.setenv("AI_CREATOR_MONTHLY_CAP_USD", "0")
    get_settings.cache_clear()
    await record_creator_spend(Decimal("999"), CREATOR_ID)
    await check_creator_spend_gate(CREATOR_ID, "CREATOR")  # must not raise


@pytest.mark.asyncio
async def test_counter_resets_on_a_new_utc_month(monkeypatch):
    await record_creator_spend(Decimal("0.75"), CREATOR_ID)
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR")

    monkeypatch.setattr(spend_tracker, "_current_month_utc", lambda: "2999-01")
    assert await get_creator_month_total(CREATOR_ID) == Decimal(0)
    await check_creator_spend_gate(CREATOR_ID, "CREATOR")  # fresh month, passes


@pytest.mark.asyncio
async def test_daily_ledger_and_monthly_ledger_are_independent():
    """`record_creator_spend` must not touch the daily counters and vice
    versa -- the route records BOTH deliberately."""
    await record_creator_spend(Decimal("0.50"), CREATOR_ID)
    assert await spend_tracker.get_global_total_today() == Decimal(0)
    assert await spend_tracker.get_workspace_total_today(CREATOR_ID) == Decimal(0)

    await spend_tracker.record_spend(Decimal("0.30"), CREATOR_ID)
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.50")


# ---------------------------------------------------------------------------
# Gate fix round 1 (Q7) -- reservation semantics on the creator counter.
#
# Priya: "two concurrent messages at cap-minus-one BOTH pass.
# check_creator_spend_gate is a plain read-then-compare with no reservation,
# unlike the brand daily gate which reserves a pessimistic estimate."
# ---------------------------------------------------------------------------

import asyncio  # noqa: E402
from unittest.mock import patch  # noqa: E402

from app.costs.spend_tracker import (  # noqa: E402
    creator_cap_override_from_context,
    get_reserved_creator,
    release_creator,
)

RESERVE = Decimal("0.02")


@pytest.mark.asyncio
async def test_gate_reserves_and_counts_in_flight_holds():
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    first = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert first is not None and first.amount == RESERVE
    assert await get_reserved_creator(CREATOR_ID) == RESERVE

    # The recorded total is STILL 0.74, but the hold makes 0.76 >= 0.75.
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.74")
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)

    # Settling replaces the estimate with the real number (released first).
    await record_creator_spend(Decimal("0.005"), CREATOR_ID, reservation=first)
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.745")
    second = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=Decimal("0.001"))
    assert second is not None
    await release_creator(second)


@pytest.mark.asyncio
async def test_release_creator_gives_the_hold_back_and_is_idempotent():
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    hold = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    await release_creator(hold)
    await release_creator(hold)  # no-op
    await release_creator(None)  # no-op
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)
    again = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert again is not None


@pytest.mark.asyncio
async def test_holds_are_per_creator():
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    # Another creator's allowance is untouched by this creator's hold.
    other = await check_creator_spend_gate("someone-else", "CREATOR", reserve_usd=RESERVE)
    assert other is not None
    assert await get_reserved_creator("someone-else") == RESERVE


@pytest.mark.asyncio
async def test_no_reserve_amount_still_gates_but_holds_nothing():
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    assert await check_creator_spend_gate(CREATOR_ID, "CREATOR") is None
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)
    await record_creator_spend(Decimal("0.01"), CREATOR_ID)
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR")


@pytest.mark.asyncio
async def test_expired_hold_is_pruned_so_a_crashed_turn_cannot_leak_budget(monkeypatch):
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    hold = await check_creator_spend_gate(
        CREATOR_ID, "CREATOR", reserve_usd=RESERVE, reserve_ttl_seconds=5.0
    )
    assert hold is not None
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    # Its owner never settles; the clock moves past the TTL.
    monkeypatch.setattr(spend_tracker, "_monotonic", lambda: hold.created_at + 6.0)
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)
    fresh = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert fresh is not None


@pytest.mark.asyncio
async def test_concurrent_turns_at_cap_minus_one_admit_exactly_one():
    """The Q7 race, reproduced. Every caller reads the same 0.74 total (the
    read is made to yield, as a Redis round-trip would) BEFORE any of them
    reserves. The old read-then-compare gate admitted all 25; the atomic
    compare-and-reserve admits exactly one."""
    await record_creator_spend(Decimal("0.74"), CREATOR_ID)
    real_read = spend_tracker.get_creator_month_total

    async def slow_read(creator_id: str) -> Decimal:
        total = await real_read(creator_id)
        await asyncio.sleep(0.01)  # everyone parks here holding the stale 0.74
        return total

    admitted: list = []
    refused = 0

    async def one_turn():
        nonlocal refused
        try:
            admitted.append(
                await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
            )
        except SpendCapExceeded:
            refused += 1

    with patch.object(spend_tracker, "get_creator_month_total", slow_read):
        await asyncio.gather(*(one_turn() for _ in range(25)))

    assert len(admitted) == 1, f"{len(admitted)} concurrent turns passed a cap with 0.01 headroom"
    assert refused == 24
    assert await get_reserved_creator(CREATOR_ID) == RESERVE
    await release_creator(admitted[0])


@pytest.mark.asyncio
async def test_sequential_turns_overshoot_by_at_most_one_turn():
    """Same admission rule as the brand gate (`try_reserve`): a turn is
    admitted while recorded + IN-FLIGHT holds are under the cap, so the
    sequential path can overshoot by at most one turn's real cost -- and
    with the hold in place, never by more than one, however many arrive
    concurrently (see the gather test above)."""
    await record_creator_spend(Decimal("0.72"), CREATOR_ID)
    hold = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)  # 0.72 < 0.75
    await record_creator_spend(Decimal("0.02"), CREATOR_ID, reservation=hold)  # now 0.74
    hold = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)  # 0.74 < 0.75: admitted
    assert hold is not None
    await record_creator_spend(Decimal("0.02"), CREATOR_ID, reservation=hold)  # now 0.76: crossed once
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)  # refused from here on
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)


# ---------------------------------------------------------------- per-creator override (Q7)


@pytest.mark.asyncio
async def test_cap_override_wins_over_the_env_default():
    await record_creator_spend(Decimal("2.00"), CREATOR_ID)
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR")
    hold = await check_creator_spend_gate(CREATOR_ID, "CREATOR", cap_usd="5.00")
    assert hold is None  # admitted, no reserve requested
    assert creator_monthly_cap_usd("5.00") == Decimal("5.00")
    assert creator_monthly_cap_usd(3) == Decimal("3")
    assert creator_monthly_cap_usd(None) == Decimal("0.75")


@pytest.mark.asyncio
async def test_cap_override_can_lower_and_zero_disables():
    await record_creator_spend(Decimal("0.30"), CREATOR_ID)
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", cap_usd="0.25")
    await check_creator_spend_gate(CREATOR_ID, "CREATOR", cap_usd="0")  # disabled for this creator


def test_unparseable_override_falls_back_to_the_default_not_to_unlimited():
    assert creator_monthly_cap_usd("not-a-number") == Decimal("0.75")
    assert creator_monthly_cap_usd("") == Decimal("0.75")


def test_override_is_read_off_the_creator_context_payload():
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": "1,000.50"}) == "1000.50"
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": 2}) == "2"
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": 1.5}) == "1.5"
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": None}) is None
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": ""}) is None
    assert creator_cap_override_from_context({"ai_monthly_cap_usd": True}) is None
    assert creator_cap_override_from_context({}) is None
    assert creator_cap_override_from_context(None) is None
