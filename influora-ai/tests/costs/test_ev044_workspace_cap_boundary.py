"""EV-044 — the per-workspace daily hard cap blocks AT the boundary.

`tests/costs/test_gate.py` covers the default's existence and the explicit-0
escape hatch. This file covers the thing the ticket actually asks for: a
workspace that spends up to its cap gets served, and the NEXT request is
refused with a code a client can branch on.

The gate is exercised for real (real `check_spend_gate`, real
`spend_tracker`); nothing here talks to a provider.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.config import get_settings
from app.costs import spend_tracker
from app.costs.gate import check_spend_gate, workspace_hard_cap_usd

WORKSPACE = "ws-ev044-boundary"


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    monkeypatch.delenv("REDIS_URL", raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    monkeypatch.delenv("WORKSPACE_DAILY_HARD_CAP_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


@pytest.mark.asyncio
async def test_allowed_up_to_the_cap_then_refused_on_the_next_request(monkeypatch):
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "2.00")
    get_settings.cache_clear()

    # Four calls of $0.50 fill exactly $2.00. Every one of them must be served.
    for i in range(4):
        result = await check_spend_gate(workspace_id=WORKSPACE)
        assert result.allowed is True, f"call {i} should have been served"
        await spend_tracker.record_spend(Decimal("0.50"), workspace_id=WORKSPACE)

    # The fifth is the first one past the boundary.
    refused = await check_spend_gate(workspace_id=WORKSPACE)
    assert refused.allowed is False
    assert refused.error_code == "AI_WORKSPACE_SPEND_CAP_REACHED"
    assert refused.error_message
    assert refused.reservation is None


@pytest.mark.asyncio
async def test_the_refusal_is_scoped_to_this_workspace(monkeypatch):
    """A capped workspace must not take anyone else down with it -- the whole
    point of the per-workspace cap is that one tenant's spending stops being
    everyone's problem."""
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "2.00")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("2.00"), workspace_id=WORKSPACE)

    assert (await check_spend_gate(workspace_id=WORKSPACE)).allowed is False
    assert (await check_spend_gate(workspace_id="ws-innocent-bystander")).allowed is True


@pytest.mark.asyncio
async def test_an_in_flight_reservation_counts_against_the_cap(monkeypatch):
    """FALSIFICATION: a cap that only counted SETTLED spend would let N
    concurrent turns all pass at cap-minus-one. The gate reserves before it
    returns, so the second caller sees the first one's in-flight cost."""
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "1.00")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("0.90"), workspace_id=WORKSPACE)

    first = await check_spend_gate(workspace_id=WORKSPACE, reserve_usd="0.50")
    assert first.allowed is True
    assert first.reservation is not None

    # Nothing has been RECORDED since; only the hold exists.
    second = await check_spend_gate(workspace_id=WORKSPACE, reserve_usd="0.50")
    assert second.allowed is False
    assert second.error_code == "AI_WORKSPACE_SPEND_CAP_REACHED"


def test_only_an_explicit_non_positive_value_turns_the_cap_off():
    """Unit-level falsification of the one predicate that decides "capped or
    not", so a future refactor cannot quietly reintroduce "unset == off"."""
    assert workspace_hard_cap_usd(3.0) == Decimal("3.0")
    assert workspace_hard_cap_usd(0.01) == Decimal("0.01")
    assert workspace_hard_cap_usd(0) is None
    assert workspace_hard_cap_usd(-1) is None
    assert workspace_hard_cap_usd(None) is None
