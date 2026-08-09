"""P2-17 §3.2 — daily spend counter, Redis-backed with an in-memory fallback.

**History:** originally a module-level, per-PROCESS counter only (see
wiki/decisions/budget-proposals/2026-07-12-ai-spend-ceiling-and-killswitch.md
§3.2/§4) -- each running Python worker accumulated its own `_state`
independently, so the *effective* real ceiling was
`AI_DAILY_SPEND_CEILING_USD x worker_count`, not the configured number, on
any multi-worker/multi-instance deploy. H-25 (2026-07-14 audit) closes that
gap: when `REDIS_URL` is configured, this module persists both the global
and per-workspace daily totals in Redis so every worker/instance reads and
writes the same counters.

**Fallback contract (CTO-approved, Priya):** Redis is an optimization, not a
hard dependency of the chat path -- a Redis outage, a missing `REDIS_URL`,
or the `redis` package being unavailable must never block `/chat`. Every
Redis operation in this module is wrapped; on any failure (connection
refused, timeout, DNS, etc.) the call transparently falls back to the same
in-memory counter used when `REDIS_URL` is unset entirely, and the read/write
in question still completes. This means the ceiling/kill-switch enforcement
degrades from "cross-instance" to "per-process" under a Redis outage rather
than degrading to "unenforced" or "500".

**Storage shape:** two integer counters per UTC day, denominated in
micro-dollars (`Decimal` USD x 1,000,000, rounded half-up) so Redis's atomic
`INCRBY` can be used without floating-point drift --
`influora:ai:spend:global:{YYYY-MM-DD}` and
`influora:ai:spend:ws:{workspace_id}:{YYYY-MM-DD}`. Each key gets a
best-effort TTL (see `_KEY_TTL_SECONDS`) so old days self-expire instead of
accumulating forever; day-scoped keys also mean there is no explicit
"rollover" step on the Redis path (unlike the in-memory path below) -- a new
UTC day is simply a new key.

Concurrency (in-memory fallback only): guarded by a single module-level
`asyncio.Lock` -- sufficient for this single-process asyncio app to make the
read-check-then-increment sequence atomic across concurrent request
coroutines within one process. The Redis path relies on Redis's own atomicity
for `INCRBY` instead.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import logging
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from time import monotonic as _monotonic

from app.config import get_settings

logger = logging.getLogger(__name__)

try:  # pragma: no cover - import shape, not logic
    import redis.asyncio as redis_asyncio
except ImportError:  # pragma: no cover
    # `redis[hiredis]` is declared in requirements.txt, so in a correctly built
    # image this import succeeds and the ceiling is enforced cross-instance.
    # This guard only keeps the module importable if the package is somehow
    # absent (e.g. a stripped/partial env), degrading straight to the
    # per-process in-memory path. If REDIS_URL is set while this branch is
    # taken, /readyz reports NOT ready so the misconfiguration is surfaced
    # rather than silently downgrading the ceiling to per-worker.
    redis_asyncio = None  # type: ignore[assignment]


_GLOBAL_KEY_PREFIX = "influora:ai:spend:global"
_WORKSPACE_KEY_PREFIX = "influora:ai:spend:ws"
_KEY_TTL_SECONDS = 3 * 24 * 60 * 60  # 3 days -- comfortably outlives one UTC day + clock skew
_MICROS_PER_DOLLAR = Decimal(1000000)


def _today_utc() -> dt.date:
    return dt.datetime.now(dt.timezone.utc).date()


def _to_micros(amount: Decimal) -> int:
    return int((amount * _MICROS_PER_DOLLAR).to_integral_value(rounding=ROUND_HALF_UP))


def _from_micros(micros: int | str | None) -> Decimal:
    if not micros:
        return Decimal(0)
    return Decimal(int(micros)) / _MICROS_PER_DOLLAR


def _global_key(day: dt.date) -> str:
    return f"{_GLOBAL_KEY_PREFIX}:{day.isoformat()}"


def _workspace_key(workspace_id: str, day: dt.date) -> str:
    return f"{_WORKSPACE_KEY_PREFIX}:{workspace_id}:{day.isoformat()}"


# ---------------------------------------------------------------------------
# In-memory fallback (also the whole store when REDIS_URL is unset)
# ---------------------------------------------------------------------------


@dataclass
class _DailySpendState:
    day: dt.date = field(default_factory=_today_utc)
    global_total: Decimal = Decimal(0)
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


async def _record_spend_memory(cost_usd: Decimal, workspace_id: str | None) -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        _state.global_total += cost_usd
        if workspace_id:
            _state.per_workspace[workspace_id] = (
                _state.per_workspace.get(workspace_id, Decimal(0)) + cost_usd
            )
        return _state.global_total


async def _get_global_total_memory() -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.global_total


async def _get_workspace_total_memory(workspace_id: str) -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.per_workspace.get(workspace_id, Decimal(0))


# ---------------------------------------------------------------------------
# Redis-backed store
# ---------------------------------------------------------------------------

_redis_client: redis_asyncio.Redis | None = None  # type: ignore[name-defined]
_redis_client_lock = asyncio.Lock()


def _redis_configured() -> bool:
    settings = get_settings()
    return bool(settings.redis_url) and redis_asyncio is not None


async def _get_redis_client() -> redis_asyncio.Redis | None:  # type: ignore[name-defined]
    """Lazily creates (and caches) the shared async Redis client. Returns
    None when Redis isn't configured/importable so every call site has a
    single, uniform "no Redis available" branch."""
    global _redis_client
    if not _redis_configured():
        return None
    if _redis_client is None:
        async with _redis_client_lock:
            if _redis_client is None:
                settings = get_settings()
                _redis_client = redis_asyncio.from_url(
                    settings.redis_url,
                    decode_responses=True,
                    socket_connect_timeout=2,
                    socket_timeout=2,
                )
    return _redis_client


async def close_redis_client() -> None:
    """Optional cleanup hook for app shutdown. Safe to call even if no
    client was ever created (no-op) or Redis was never configured."""
    global _redis_client
    if _redis_client is not None:
        try:
            await _redis_client.aclose()
        except Exception:
            logger.warning("spend_tracker: error closing Redis client", exc_info=True)
        finally:
            _redis_client = None


async def _record_spend_redis(cost_usd: Decimal, workspace_id: str | None) -> Decimal | None:
    """Returns the new global total on success, or None to signal the
    caller should fall back to the in-memory path (Redis unavailable or a
    call failed)."""
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        today = _today_utc()
        micros = _to_micros(cost_usd)
        gkey = _global_key(today)
        async with client.pipeline(transaction=True) as pipe:
            pipe.incrby(gkey, micros)
            pipe.expire(gkey, _KEY_TTL_SECONDS)
            if workspace_id:
                wkey = _workspace_key(workspace_id, today)
                pipe.incrby(wkey, micros)
                pipe.expire(wkey, _KEY_TTL_SECONDS)
            results = await pipe.execute()
        new_global_micros = results[0]
        return _from_micros(new_global_micros)
    except Exception:
        logger.warning(
            "spend_tracker: Redis record_spend failed, falling back to in-memory counter",
            exc_info=True,
        )
        return None


async def _get_global_total_redis() -> Decimal | None:
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        value = await client.get(_global_key(_today_utc()))
        return _from_micros(value)
    except Exception:
        logger.warning(
            "spend_tracker: Redis get_global_total failed, falling back to in-memory counter",
            exc_info=True,
        )
        return None


async def _get_workspace_total_redis(workspace_id: str) -> Decimal | None:
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        value = await client.get(_workspace_key(workspace_id, _today_utc()))
        return _from_micros(value)
    except Exception:
        logger.warning(
            "spend_tracker: Redis get_workspace_total failed, falling back to in-memory counter",
            exc_info=True,
        )
        return None


# ---------------------------------------------------------------------------
# Public API (used by app.costs.gate / app.routes.chat) -- unchanged signatures
# ---------------------------------------------------------------------------


async def record_spend(
    cost_usd: Decimal,
    workspace_id: str | None,
    reservation: Reservation | None = None,
) -> Decimal:
    """Adds `cost_usd` to today's global total (and, if `workspace_id` is
    given, that workspace's running total). Returns the new global total for
    today so the caller can include `spend_today_usd` in its structured log
    line without a second round trip.

    Always also updates the in-memory counter, even when Redis is
    configured and the write succeeds there -- cheap, keeps the two stores
    from drifting apart, and means a mid-request Redis outage on the *next*
    call still has a locally-consistent number to fall back to.
    """
    # F-05: the reservation is replaced by the real number, not stacked on top
    # of it. Released FIRST so a failure in the Redis write below can never
    # leave the hold in place.
    await release(reservation)
    memory_total = await _record_spend_memory(cost_usd, workspace_id)
    if _redis_configured():
        redis_total = await _record_spend_redis(cost_usd, workspace_id)
        if redis_total is not None:
            # F-03: the in-memory counter can be AHEAD of Redis (it accumulated
            # every spend, including those whose Redis write failed and was
            # never replayed). Report the larger of the two.
            return max(redis_total, memory_total)
    return memory_total


async def get_global_total_today() -> Decimal:
    """Today's global spend — the LARGER of the Redis and in-memory totals.

    F-03: this used to return the Redis value whenever Redis was reachable,
    ignoring a higher in-memory total. `record_spend` always increments memory,
    writes Redis best-effort, swallows failures and never replays them — so
    spend accrued during a Redis outage was permanently invisible to the gate.

    Worked example from the audit: Redis down 10:00–10:30 while $12 accrues
    (memory $12, Redis stuck at $3). Redis recovers; the gate reads $3 and keeps
    authorizing until Redis ALONE reaches $15 — about $27 of real spend against
    a $15 ceiling. The module docstring promises degradation to "per-process",
    not to "unenforced", and `max()` is what makes that promise true: whichever
    store saw more, saw more.
    """
    memory_total = await _get_global_total_memory()
    if _redis_configured():
        redis_total = await _get_global_total_redis()
        if redis_total is not None:
            return max(redis_total, memory_total)
    return memory_total


async def get_workspace_total_today(workspace_id: str) -> Decimal:
    """Same F-03 reasoning as `get_global_total_today`, per workspace."""
    memory_total = await _get_workspace_total_memory(workspace_id)
    if _redis_configured():
        redis_total = await _get_workspace_total_redis(workspace_id)
        if redis_total is not None:
            return max(redis_total, memory_total)
    return memory_total


# ---------------------------------------------------------------------------
# F-05 — reservations. The gate was read-then-spend and held NOTHING.
#
# `check_spend_gate` read the total, returned, and reserved no budget;
# `record_spend` ran only AFTER the provider call completed. With the total at
# $14.90 against a $15 ceiling, 200 concurrent brand-safety requests all read
# $14.90, all passed, and all executed at ~$0.07 — about $29 spent before a
# single request was blocked. Overshoot was bounded only by concurrency. One
# gate check at chat.py also covered a tool loop of up to 6 separate Claude
# turns, so a single authorization could span six billable calls.
#
# A reservation is budget held between the gate check and the recorded spend.
# The gate counts it, so concurrent callers see each other's in-flight cost
# instead of all reading the same stale total. Reservations are per-process
# (matching the in-memory counter's contract) and are ALWAYS released — on
# success they are replaced by the real recorded spend, on failure they simply
# expire, so a crashed request cannot leak budget forever.
# ---------------------------------------------------------------------------

# A reservation whose owner never settles must not hold budget forever. Kept
# short: the cost of an over-long TTL is a false "ceiling reached" for other
# callers; the cost of a too-short one is the F-05 race reopening for the tail
# of a slow call. 60s comfortably covers a single provider call; chat.py's tool
# loop passes a longer one because one gate check there covers up to
# tool_loop_max_iterations turns.
_RESERVATION_TTL_SECONDS = 60.0


@dataclass
class Reservation:
    """Budget held for one in-flight provider call."""

    reservation_id: int
    amount: Decimal
    workspace_id: str | None
    created_at: float
    ttl_seconds: float = _RESERVATION_TTL_SECONDS


_reservations: dict[int, Reservation] = {}
_reservation_seq = 0


def _prune_expired_reservations_locked(now: float) -> None:
    """Must hold `_lock`. A reservation whose owner died (crash, cancellation
    before `release`) must not hold budget forever."""
    stale = [
        rid for rid, r in _reservations.items()
        if now - r.created_at > r.ttl_seconds
    ]
    for rid in stale:
        logger.warning(
            "spend_tracker: releasing expired reservation %s ($%s) — its owner never settled",
            rid, _reservations[rid].amount,
        )
        del _reservations[rid]


async def reserve(
    amount: Decimal,
    workspace_id: str | None = None,
    *,
    ttl_seconds: float = _RESERVATION_TTL_SECONDS,
) -> Reservation:
    """Hold `amount` of budget for one in-flight call.

    Settle it with `record_spend(..., reservation=...)` on success or
    `release()` on failure. `ttl_seconds` is the backstop for the paths that do
    neither (a crash, a cancelled task): the hold expires on its own so budget
    can never be leaked permanently.
    """
    global _reservation_seq
    async with _lock:
        now = _monotonic()
        _prune_expired_reservations_locked(now)
        _reservation_seq += 1
        reservation = Reservation(
            reservation_id=_reservation_seq,
            amount=max(amount, Decimal(0)),
            workspace_id=workspace_id,
            created_at=now,
            ttl_seconds=ttl_seconds,
        )
        _reservations[reservation.reservation_id] = reservation
        return reservation


async def try_reserve(
    amount: Decimal,
    workspace_id: str | None,
    *,
    global_total: Decimal,
    global_ceiling: Decimal,
    workspace_total: Decimal | None = None,
    workspace_cap: Decimal | None = None,
    ttl_seconds: float = _RESERVATION_TTL_SECONDS,
) -> tuple[Reservation | None, str | None]:
    """Check the ceilings and take the reservation ATOMICALLY.

    F-05 (round 2, Priya sign-off review). The first fix read the global total,
    checked the ceiling, then read the WORKSPACE total, and only then reserved.
    With Redis configured and `WORKSPACE_DAILY_HARD_CAP_USD` set — both
    supported, both controls this codebase added deliberately — that workspace
    read is real network I/O sitting between the ceiling check and the reserve.
    Every concurrent caller therefore passed the ceiling check seeing
    `reserved = 0`, and the measured result was the audit's original number
    verbatim: **200 of 200 admitted, $14.00 of in-flight spend authorized
    against $0.10 of headroom.**

    The fix was protected by the absence of an `await`, not by design — its test
    happened to run with no Redis and no workspace cap, so nothing yielded.

    Everything that can block is passed IN, already read. This function does the
    comparison and the insert under one lock with no await in between, so N
    concurrent callers serialise: each one sees the reservations the callers
    before it already took.

    Returns `(reservation, None)` when admitted, or `(None, error_code)` when
    the ceiling or the per-workspace cap would be breached.
    """
    global _reservation_seq
    async with _lock:
        now = _monotonic()
        _prune_expired_reservations_locked(now)

        held_global = sum((r.amount for r in _reservations.values()), Decimal(0))
        if global_total + held_global >= global_ceiling:
            return None, "AI_SPEND_CEILING_REACHED"

        if workspace_id and workspace_cap is not None and workspace_total is not None:
            held_workspace = sum(
                (r.amount for r in _reservations.values() if r.workspace_id == workspace_id),
                Decimal(0),
            )
            if workspace_total + held_workspace >= workspace_cap:
                return None, "AI_WORKSPACE_SPEND_CAP_REACHED"

        if amount <= 0:
            return None, None  # gate passed; caller asked for no reservation

        _reservation_seq += 1
        reservation = Reservation(
            reservation_id=_reservation_seq,
            amount=amount,
            workspace_id=workspace_id,
            created_at=now,
            ttl_seconds=ttl_seconds,
        )
        _reservations[reservation.reservation_id] = reservation
        return reservation, None


async def release(reservation: Reservation | None) -> None:
    """Release a reservation. Idempotent — releasing twice is a no-op."""
    if reservation is None:
        return
    async with _lock:
        _reservations.pop(reservation.reservation_id, None)


async def get_reserved_global() -> Decimal:
    async with _lock:
        _prune_expired_reservations_locked(_monotonic())
        return sum((r.amount for r in _reservations.values()), Decimal(0))


async def get_reserved_workspace(workspace_id: str) -> Decimal:
    async with _lock:
        _prune_expired_reservations_locked(_monotonic())
        return sum(
            (r.amount for r in _reservations.values() if r.workspace_id == workspace_id),
            Decimal(0),
        )


async def reset_reservations_for_testing() -> None:
    async with _lock:
        _reservations.clear()


async def reset_for_testing() -> None:
    """Test-only seam -- forces a fresh, empty in-memory state regardless of
    the current day, and best-effort clears today's Redis keys when Redis is
    configured. Production code never calls this."""
    global _state
    async with _lock:
        _state = _DailySpendState()
    if _redis_configured():
        try:
            client = await _get_redis_client()
            if client is not None:
                today = _today_utc()
                await client.delete(_global_key(today))
        except Exception:
            logger.warning("spend_tracker: Redis reset_for_testing failed", exc_info=True)
