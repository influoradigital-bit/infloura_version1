"""P2-17 §3.2 — module-level, per-process daily spend counter.

**Known Phase-1 limitation, documented not hidden** (per
wiki/decisions/budget-proposals/2026-07-12-ai-spend-ceiling-and-killswitch.md
§3.2/§4): this tracker is per-PROCESS. Each running Python worker accumulates
its own `_state` independently -- there is no shared store. If this service
ever runs with more than one worker process, the *effective* real ceiling is
`AI_DAILY_SPEND_CEILING_USD x worker_count`, not the configured number, until
a Phase 2 shared ledger exists (spec's suggestion: reuse the existing
Spring-internal-API HMAC pattern so Postgres becomes the single cross-instance
source of truth -- explicitly out of scope for this pass; do not build a new
datastore just for this).

Concurrency: guarded by a single module-level `asyncio.Lock` -- this service
is a single-process asyncio app (FastAPI/Uvicorn), so a plain (non-multiprocess)
lock is sufficient to make the read-check-then-increment sequence atomic
across concurrent request coroutines within one process.
"""

from __future__ import annotations

import asyncio
import datetime as dt
from dataclasses import dataclass, field
from decimal import Decimal


def _today_utc() -> dt.date:
    return dt.datetime.now(dt.timezone.utc).date()


@dataclass
class _DailySpendState:
    day: dt.date = field(default_factory=_today_utc)
    global_total: Decimal = Decimal("0")
    per_workspace: dict[str, Decimal] = field(default_factory=dict)


_state = _DailySpendState()
_lock = asyncio.Lock()


def _roll_if_new_day_locked() -> None:
    """Must only be called while holding `_lock`. Resets the counters when
    the stored day no longer matches the current UTC day -- a fresh
    `_DailySpendState()` is simplest and correct (no need to keep yesterday's
    numbers around in-process; Rohan's monthly rollup reads structured log
    lines, not this in-memory state)."""
    global _state
    today = _today_utc()
    if _state.day != today:
        _state = _DailySpendState(day=today)


async def record_spend(cost_usd: Decimal, workspace_id: str | None) -> Decimal:
    """Adds `cost_usd` to today's global total (and, if `workspace_id` is
    given, that workspace's running total), rolling over to a fresh day's
    state first if the UTC date has changed. Returns the new global total
    for today so the caller can include `spend_today_usd` in its structured
    log line without a second lock acquisition.
    """
    async with _lock:
        _roll_if_new_day_locked()
        _state.global_total += cost_usd
        if workspace_id:
            _state.per_workspace[workspace_id] = (
                _state.per_workspace.get(workspace_id, Decimal("0")) + cost_usd
            )
        return _state.global_total


async def get_global_total_today() -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.global_total


async def get_workspace_total_today(workspace_id: str) -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.per_workspace.get(workspace_id, Decimal("0"))


async def reset_for_testing() -> None:
    """Test-only seam -- forces a fresh, empty state regardless of the
    current day. Production code never calls this."""
    global _state
    async with _lock:
        _state = _DailySpendState()
