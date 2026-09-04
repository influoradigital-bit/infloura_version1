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
# Meera for Creators Phase A (A8): per-creator MONTHLY counter, keyed by
# UTC calendar month -- `influora:ai:spend:creator:{creator_id}:{YYYY-MM}`.
_CREATOR_MONTH_KEY_PREFIX = "influora:ai:spend:creator"
_KEY_TTL_SECONDS = 3 * 24 * 60 * 60  # 3 days -- comfortably outlives one UTC day + clock skew
# A month key must outlive its month plus skew; 40 days covers the longest
# month with room to spare and lets old months self-expire.
_MONTH_KEY_TTL_SECONDS = 40 * 24 * 60 * 60
_MICROS_PER_DOLLAR = Decimal(1000000)

# A8 default: USD 0.75/creator/month (~INR 60). The live value comes from
# `Settings.ai_creator_monthly_cap_usd` (env AI_CREATOR_MONTHLY_CAP_USD); this
# constant documents the spec default and backs the settings default.
CREATOR_MONTHLY_CAP_USD = Decimal("0.75")

# Friendly, persona-consistent over-cap message (A8). Spoken-safe: no symbols,
# no jargon. Deliberately does NOT mention dollars, tokens or "spend" -- the
# creator never bought anything, this is a usage allowance.
CREATOR_CAP_MESSAGE = (
    "You've reached your monthly Meera usage limit. It resets on the 1st of next month. "
    "If you need more before then, message support and we'll sort it out."
)


# Structured error code the routes return when the creator cap trips
# (chat: HTTP 429 body; voice: the 200 "fallback" envelope's `code` field).
CREATOR_CAP_CODE = "CREATOR_MONTHLY_CAP_REACHED"

# Gate fix round 1 (Q7): the CREATOR context payload key Spring uses to carry
# a per-creator cap override (USD, string-rendered like every other number in
# that payload, or a bare number). Set by support through the admin override
# endpoint; absent/null means "use the process-wide default".
CREATOR_CAP_OVERRIDE_CONTEXT_KEY = "ai_monthly_cap_usd"


def creator_cap_override_from_context(creator_context: dict | None) -> str | None:
    """Reads the per-creator cap override off a CREATOR context payload.
    Returns the raw value as a string (parsed by `creator_monthly_cap_usd`),
    or None when the payload carries no override. Strings are stripped of the
    thousands separators Java's NumberFormat inserts ("1,000.00")."""
    if not isinstance(creator_context, dict):
        return None
    value = creator_context.get(CREATOR_CAP_OVERRIDE_CONTEXT_KEY)
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value).strip().replace(",", "")
    return text or None


class SpendCapExceeded(Exception):
    """Raised by `check_creator_spend_gate` when a creator is at/over their
    monthly cap. `message` is safe to show to the creator verbatim."""

    def __init__(self, message: str = CREATOR_CAP_MESSAGE, *, creator_id: str | None = None):
        self.message = message
        self.creator_id = creator_id
        super().__init__(message)


def _today_utc() -> dt.date:
    return dt.datetime.now(dt.timezone.utc).date()


def _to_micros(amount: Decimal) -> int:
    return int((amount * _MICROS_PER_DOLLAR).to_integral_value(rounding=ROUND_HALF_UP))


def _from_micros(micros: int | str | None) -> Decimal:
    if not micros:
        return Decimal(0)
    return Decimal(int(micros)) / _MICROS_PER_DOLLAR


def _current_month_utc() -> str:
    """'YYYY-MM' for the current UTC month -- the per-creator cap's period."""
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m")


def _global_key(day: dt.date) -> str:
    return f"{_GLOBAL_KEY_PREFIX}:{day.isoformat()}"


def _creator_month_key(creator_id: str, month: str) -> str:
    return f"{_CREATOR_MONTH_KEY_PREFIX}:{creator_id}:{month}"


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

# In-memory per-creator monthly totals: {(creator_id, "YYYY-MM"): Decimal}.
# Same fallback contract as the daily counters (per-process when Redis is
# absent or failing). Old months are pruned lazily on write.
_creator_month_totals: dict[tuple[str, str], Decimal] = {}


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


async def _record_creator_spend_memory(cost_usd: Decimal, creator_id: str) -> Decimal:
    month = _current_month_utc()
    async with _lock:
        stale = [key for key in _creator_month_totals if key[1] != month]
        for key in stale:
            del _creator_month_totals[key]
        total = _creator_month_totals.get((creator_id, month), Decimal(0)) + cost_usd
        _creator_month_totals[(creator_id, month)] = total
        return total


async def _get_creator_month_total_memory(creator_id: str) -> Decimal:
    async with _lock:
        return _creator_month_totals.get((creator_id, _current_month_utc()), Decimal(0))


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


async def _record_creator_spend_redis(
    cost_usd: Decimal,
    creator_id: str,
    reservation: CreatorReservation | None = None,
) -> Decimal | None:
    """Records the spend and, when `reservation` is an unsettled Redis-store
    hold, gives it back in the same MULTI/EXEC (round 2, Q7). Marks the hold
    settled only after EXEC succeeded, so a failure here leaves it for the
    caller's fallback release."""
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        key = _creator_month_key(creator_id, _current_month_utc())
        settle_hold = (
            reservation is not None
            and reservation.store == _STORE_REDIS
            and not reservation.settled
        )
        async with client.pipeline(transaction=True) as pipe:
            pipe.incrby(key, _to_micros(cost_usd))
            pipe.expire(key, _MONTH_KEY_TTL_SECONDS)
            if settle_hold:
                pipe.decrby(
                    _creator_held_key(reservation.creator_id, reservation.month),
                    _to_micros(reservation.amount),
                )
            results = await pipe.execute()
        if settle_hold:
            async with _lock:
                reservation.settled = True
            remaining = int(results[2])
            if remaining < 0:
                # :held expired under the live hold -- clamp, never negative.
                await client.incrby(
                    _creator_held_key(reservation.creator_id, reservation.month), -remaining
                )
        return _from_micros(results[0])
    except Exception:
        logger.warning(
            "spend_tracker: Redis record_creator_spend failed, falling back to in-memory counter",
            exc_info=True,
        )
        return None


async def _get_creator_month_total_redis(creator_id: str) -> Decimal | None:
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        value = await client.get(_creator_month_key(creator_id, _current_month_utc()))
        return _from_micros(value)
    except Exception:
        logger.warning(
            "spend_tracker: Redis get_creator_month_total failed, falling back to in-memory counter",
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
# Meera for Creators Phase A (A8) -- per-creator MONTHLY cap.
#
# CREATOR-audience chat turns are metered per creator per UTC calendar month,
# on top of (not instead of) the daily global/workspace counters above: the
# route still calls `record_spend` for the daily ledger AND
# `record_creator_spend` for this monthly one. Same Redis-with-in-memory-
# fallback contract and the same F-03 "whichever store saw more" read.
# ---------------------------------------------------------------------------


async def record_creator_spend(
    cost_usd: Decimal,
    creator_id: str,
    reservation: CreatorReservation | None = None,
) -> Decimal:
    """Adds `cost_usd` to this creator's running total for the current UTC
    month. Returns the new monthly total.

    Gate fix round 1 (Q7): `reservation` is the hold taken by
    `check_creator_spend_gate`; it is settled here (same F-05 shape as
    `record_spend`) so the real number replaces the estimate instead of
    stacking on top of it. Round 2: a Redis-store hold is given back in the
    SAME MULTI/EXEC that records the spend, so no concurrent gate check can
    observe a moment where neither the hold nor the spend counts; a
    memory-store hold is released first, as before. Whatever path fails, the
    hold is never left in place."""
    if reservation is not None and reservation.store == _STORE_MEMORY:
        await release_creator(reservation)
    memory_total = await _record_creator_spend_memory(cost_usd, creator_id)
    if _redis_configured():
        redis_total = await _record_creator_spend_redis(cost_usd, creator_id, reservation)
        if redis_total is not None:
            return max(redis_total, memory_total)
    # No Redis, or the Redis write failed: give a Redis-store hold back on its
    # own so a failed settle can never leave it counting against the creator.
    await release_creator(reservation)
    return memory_total


async def get_creator_month_total(creator_id: str) -> Decimal:
    memory_total = await _get_creator_month_total_memory(creator_id)
    if _redis_configured():
        redis_total = await _get_creator_month_total_redis(creator_id)
        if redis_total is not None:
            return max(redis_total, memory_total)
    return memory_total


def creator_monthly_cap_usd(override: Decimal | str | float | int | None = None) -> Decimal:
    """The cap that applies to one creator.

    `override` is the per-creator allowance Spring carries in the CREATOR
    context payload (`ai_monthly_cap_usd`, set by support through the admin
    override endpoint -- gate fix round 1, Q7: "who can raise it" used to be
    "nobody without a redeploy"). When present and parseable it wins over the
    process-wide default (env AI_CREATOR_MONTHLY_CAP_USD, default 0.75); an
    unparseable value is logged and ignored so a bad row can never disable the
    cap by accident. `<= 0` disables the cap (either source).
    """
    if override is not None:
        try:
            return Decimal(str(override))
        except (ArithmeticError, ValueError, TypeError):
            logger.warning(
                "spend_tracker: ignoring unparseable per-creator cap override %r", override
            )
    return Decimal(str(get_settings().ai_creator_monthly_cap_usd))


async def check_creator_spend_gate(
    creator_id: str | None,
    audience: str | None,
    *,
    reserve_usd: Decimal | str | float | None = None,
    reserve_ttl_seconds: float | None = None,
    cap_usd: Decimal | str | float | int | None = None,
) -> CreatorReservation | None:
    """Enforce the per-creator monthly cap for CREATOR-audience turns (A8).

    No-op for any other audience (brand turns are governed by the daily
    counters + `app.costs.gate.check_spend_gate`). Raises `SpendCapExceeded`
    -- carrying a creator-safe friendly message -- when this creator's
    month-to-date total PLUS in-flight reservations is at or over the cap.
    Call it in the route AFTER audience derivation and BEFORE any provider
    call; the route turns the exception into a structured non-5xx response.

    Gate fix round 1 (Q7): this used to be a plain read-then-compare that
    held nothing between the check and `record_creator_spend`, so two
    concurrent turns at cap-minus-one both passed. Pass `reserve_usd` (a
    pessimistic per-turn estimate) and the gate takes a hold under the same
    lock as the comparison -- no await in between -- so concurrent callers
    see each other's in-flight cost. The caller MUST settle the returned
    reservation with `record_creator_spend(..., reservation=r)` on success or
    `release_creator(r)` on any other exit; an unsettled hold expires on its
    own after `reserve_ttl_seconds`.

    Gate fix round 2 (Q7): with Redis configured the hold is a shared,
    atomic `:held` counter, so the guarantee spans every uvicorn worker and
    container replica; without Redis it is per-process (see the reservation
    section comment and `app.costs.worker_guard`).

    `cap_usd` is the per-creator override from the creator's context payload
    (see `creator_monthly_cap_usd`).
    """
    if (audience or "").upper() != "CREATOR" or not creator_id:
        return None
    cap = creator_monthly_cap_usd(cap_usd)
    if cap <= 0:
        return None
    reserve_amount = Decimal(str(reserve_usd)) if reserve_usd is not None else Decimal(0)
    # Every blocking read happens HERE, before the atomic section (same shape
    # as app.costs.gate.check_spend_gate / try_reserve).
    total = await get_creator_month_total(creator_id)
    reservation, exceeded = await try_reserve_creator(
        reserve_amount,
        creator_id,
        month_total=total,
        cap=cap,
        **({} if reserve_ttl_seconds is None else {"ttl_seconds": reserve_ttl_seconds}),
    )
    if exceeded:
        logger.warning(
            "spend_tracker: creator %s at monthly cap (%s + in-flight >= %s USD)",
            creator_id, total, cap,
        )
        raise SpendCapExceeded(creator_id=creator_id)
    return reservation


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
        _creator_reservations.clear()


# ---------------------------------------------------------------------------
# Gate fix round 1 (Q7) -- per-creator MONTHLY reservations.
#
# Same F-05 reasoning as the daily reservations above, applied to the creator
# cap: `check_creator_spend_gate` was read-then-compare and held nothing until
# `record_creator_spend` ran after the provider call, so two concurrent turns
# at cap-minus-one both passed. A `CreatorReservation` is budget held against
# ONE creator's monthly allowance between the gate and the recorded spend; the
# gate counts it, so concurrent callers see each other's in-flight cost.
# Kept in a separate table from the daily reservations because the two
# ledgers are independent (a creator turn holds one of EACH, settled
# separately) and because a creator hold must never count against the global
# daily ceiling twice.
#
# Gate fix round 2 (Q7, Priya caveat 1): round 1 kept the holds in the
# process-local `_creator_reservations` dict while the TOTAL lived in shared
# Redis, so the "exactly one of N concurrent turns is admitted" guarantee only
# held within ONE Python process -- true today only because the Dockerfile
# runs `--workers 1` and the compose files declare a single influora-ai
# replica, neither of which the code asserted. Now, whenever Redis is
# configured, the hold is an atomic `INCRBY` on
# `influora:ai:spend:creator:{creator_id}:{YYYY-MM}:held` (micro-dollars,
# same unit as the total) taken in the same MULTI/EXEC that reads the month
# total, and given back with `DECRBY` on release -- or in the same MULTI/EXEC
# that records the real spend on settle, so there is no window where neither
# the hold nor the spend is visible. Redis serialises the MULTI blocks, so N
# concurrent callers across ANY number of uvicorn workers or container
# replicas each see the holds taken before theirs.
#
# SCOPE OF THE GUARANTEE -- read before changing `--workers` or `replicas`:
#   * REDIS_URL set and reachable: cross-process, cross-replica.
#   * REDIS_URL unset, or Redis failing on that call: the hold falls back to
#     the in-memory table below and the guarantee is PER-PROCESS ONLY (the
#     same fallback contract as the totals). `app.main` refuses to boot with
#     more than one uvicorn worker and no REDIS_URL, and `/readyz` reports
#     `creator_cap_scope` so the downgrade is never silent (see
#     `app.costs.worker_guard`).
#
# Leak backstop on the Redis path: a single counter cannot expire ONE owner's
# phantom hold, so the `:held` key carries a TTL of the reservation TTL plus a
# grace (`_HELD_KEY_GRACE_SECONDS`), refreshed on every reserve. A crashed
# owner's hold therefore lingers only until the creator has been idle for
# that long; a phantom hold can only over-restrict (a false "limit reached"
# for that one creator), never over-spend. If the key expires under a live
# hold, the release clamps the counter back to zero instead of going
# negative. The in-memory fallback keeps the per-reservation TTL prune.
# ---------------------------------------------------------------------------

_CREATOR_HELD_KEY_SUFFIX = "held"
# Grace added to the reservation TTL for the shared `:held` key's expiry so a
# hold that is still legitimately in flight at the edge of its TTL is not
# dropped by the key expiring a moment early.
_HELD_KEY_GRACE_SECONDS = 120

_STORE_MEMORY = "memory"
_STORE_REDIS = "redis"


def _creator_held_key(creator_id: str, month: str) -> str:
    return f"{_creator_month_key(creator_id, month)}:{_CREATOR_HELD_KEY_SUFFIX}"


@dataclass
class CreatorReservation:
    """Budget held against one creator's monthly allowance for one in-flight
    turn (chat) or provider call (voice).

    `store` says where the hold lives: `"redis"` (shared `:held` counter,
    the round-2 default whenever Redis is configured and answered) or
    `"memory"` (the process-local table -- Redis unset or that call failed).
    `settled` flips once the hold has been given back or folded into a
    recorded spend, which is what makes `release_creator` idempotent on the
    Redis path (a second DECRBY would otherwise eat someone else's hold)."""

    reservation_id: int
    creator_id: str
    amount: Decimal
    created_at: float
    ttl_seconds: float = _RESERVATION_TTL_SECONDS
    store: str = _STORE_MEMORY
    month: str = ""
    settled: bool = False


_creator_reservations: dict[int, CreatorReservation] = {}


def _prune_expired_creator_reservations_locked(now: float) -> None:
    """Must hold `_lock`."""
    stale = [
        rid for rid, r in _creator_reservations.items()
        if now - r.created_at > r.ttl_seconds
    ]
    for rid in stale:
        logger.warning(
            "spend_tracker: releasing expired creator reservation %s ($%s, creator %s) "
            "— its owner never settled",
            rid, _creator_reservations[rid].amount, _creator_reservations[rid].creator_id,
        )
        del _creator_reservations[rid]


def _next_reservation_id_locked() -> int:
    """Must hold `_lock`. Ids are only ever compared within this process (the
    Redis path keys nothing by them), so a process-local sequence is enough."""
    global _reservation_seq
    _reservation_seq += 1
    return _reservation_seq


async def _try_reserve_creator_redis(
    amount: Decimal,
    creator_id: str,
    *,
    month_total: Decimal,
    cap: Decimal,
    ttl_seconds: float,
) -> tuple[CreatorReservation | None, bool] | None:
    """Shared-store check-and-reserve. Returns `(reservation, exceeded)` like
    `try_reserve_creator`, or None when Redis is unavailable / the call
    failed so the caller falls back to the in-memory table.

    One MULTI/EXEC does `INCRBY :held amount` + `EXPIRE` + `GET total`. The
    INCRBY's reply is the held amount AFTER this caller's hold, so
    `held_before = reply - amount` is exactly what earlier concurrent callers
    hold, and the comparison `total + held_before >= cap` is the same one the
    in-memory path makes under its lock. On rejection the hold is given back
    with DECRBY (best-effort; the key TTL is the backstop)."""
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        month = _current_month_utc()
        held_key = _creator_held_key(creator_id, month)
        micros = _to_micros(amount) if amount > 0 else 0
        held_ttl = int(ttl_seconds) + _HELD_KEY_GRACE_SECONDS
        async with client.pipeline(transaction=True) as pipe:
            pipe.incrby(held_key, micros)
            pipe.expire(held_key, held_ttl)
            pipe.get(_creator_month_key(creator_id, month))
            results = await pipe.execute()
        held_after = int(results[0])
        held_before = _from_micros(max(held_after - micros, 0))
        total = max(month_total, _from_micros(results[2]))
        if total + held_before >= cap:
            if micros:
                try:
                    await client.decrby(held_key, micros)
                except Exception:
                    logger.warning(
                        "spend_tracker: could not give back a rejected creator hold "
                        "(creator %s); the :held key TTL will reclaim it",
                        creator_id, exc_info=True,
                    )
            return None, True
        if micros == 0:
            return None, False
        async with _lock:
            reservation_id = _next_reservation_id_locked()
        return (
            CreatorReservation(
                reservation_id=reservation_id,
                creator_id=creator_id,
                amount=amount,
                created_at=_monotonic(),
                ttl_seconds=ttl_seconds,
                store=_STORE_REDIS,
                month=month,
            ),
            False,
        )
    except Exception:
        logger.warning(
            "spend_tracker: Redis try_reserve_creator failed, falling back to the "
            "per-process hold table (creator cap is process-local until Redis recovers)",
            exc_info=True,
        )
        return None


async def _try_reserve_creator_memory(
    amount: Decimal,
    creator_id: str,
    *,
    month_total: Decimal,
    cap: Decimal,
    ttl_seconds: float,
) -> tuple[CreatorReservation | None, bool]:
    """Process-local check-and-reserve (Redis unset or failing). The
    comparison and the insert happen under one lock with no await in between,
    so N concurrent callers IN THIS PROCESS serialise."""
    async with _lock:
        now = _monotonic()
        _prune_expired_creator_reservations_locked(now)
        held = sum(
            (r.amount for r in _creator_reservations.values() if r.creator_id == creator_id),
            Decimal(0),
        )
        if month_total + held >= cap:
            return None, True
        if amount <= 0:
            return None, False
        reservation = CreatorReservation(
            reservation_id=_next_reservation_id_locked(),
            creator_id=creator_id,
            amount=amount,
            created_at=now,
            ttl_seconds=ttl_seconds,
            store=_STORE_MEMORY,
            month=_current_month_utc(),
        )
        _creator_reservations[reservation.reservation_id] = reservation
        return reservation, False


async def try_reserve_creator(
    amount: Decimal,
    creator_id: str,
    *,
    month_total: Decimal,
    cap: Decimal,
    ttl_seconds: float = _RESERVATION_TTL_SECONDS,
) -> tuple[CreatorReservation | None, bool]:
    """Check the creator's cap and take the reservation ATOMICALLY.

    `month_total` is passed IN, already read (that read may be Redis I/O).
    With Redis configured the hold is an atomic INCRBY on the shared `:held`
    counter (see the section comment above) and the guarantee spans every
    worker and replica; otherwise -- or if that Redis call fails -- the
    comparison and the insert happen under one process lock with no await in
    between, and the guarantee is per-process.

    Returns `(reservation, False)` when admitted (`reservation` is None when
    `amount <= 0`, i.e. the caller asked for no hold), or `(None, True)` when
    the cap would be breached.
    """
    if _redis_configured():
        outcome = await _try_reserve_creator_redis(
            amount, creator_id, month_total=month_total, cap=cap, ttl_seconds=ttl_seconds
        )
        if outcome is not None:
            return outcome
    return await _try_reserve_creator_memory(
        amount, creator_id, month_total=month_total, cap=cap, ttl_seconds=ttl_seconds
    )


async def _release_creator_redis(reservation: CreatorReservation) -> bool:
    """Give a Redis-store hold back. Returns False when the DECRBY could not
    be issued (the key TTL then reclaims the hold)."""
    try:
        client = await _get_redis_client()
        if client is None:
            return False
        key = _creator_held_key(reservation.creator_id, reservation.month)
        remaining = int(await client.decrby(key, _to_micros(reservation.amount)))
        if remaining < 0:
            # The :held key expired under this live hold (owner outlived the
            # TTL + grace). Clamp back to zero rather than leaving a negative
            # counter that would admit that much extra spend.
            await client.incrby(key, -remaining)
        return True
    except Exception:
        logger.warning(
            "spend_tracker: Redis release_creator failed (creator %s, $%s); "
            "the :held key TTL will reclaim the hold",
            reservation.creator_id, reservation.amount, exc_info=True,
        )
        return False


async def release_creator(reservation: CreatorReservation | None) -> None:
    """Release a creator reservation. Idempotent — releasing twice is a no-op
    on both stores (`settled` guards the Redis DECRBY)."""
    if reservation is None:
        return
    async with _lock:
        if reservation.settled:
            return
        reservation.settled = True
        if reservation.store == _STORE_MEMORY:
            _creator_reservations.pop(reservation.reservation_id, None)
            return
    await _release_creator_redis(reservation)


async def get_reserved_creator(creator_id: str) -> Decimal:
    """What this creator currently has on hold: the shared `:held` counter
    when Redis is configured and answers, plus this process's in-memory
    holds (which exist only when a reserve fell back)."""
    async with _lock:
        _prune_expired_creator_reservations_locked(_monotonic())
        memory_held = sum(
            (r.amount for r in _creator_reservations.values() if r.creator_id == creator_id),
            Decimal(0),
        )
    if not _redis_configured():
        return memory_held
    try:
        client = await _get_redis_client()
        if client is None:
            return memory_held
        value = await client.get(_creator_held_key(creator_id, _current_month_utc()))
        return memory_held + max(_from_micros(value), Decimal(0))
    except Exception:
        logger.warning("spend_tracker: Redis get_reserved_creator failed", exc_info=True)
        return memory_held


async def reset_for_testing() -> None:
    """Test-only seam -- forces a fresh, empty in-memory state regardless of
    the current day, and best-effort clears today's Redis keys when Redis is
    configured. Production code never calls this."""
    global _state
    async with _lock:
        _state = _DailySpendState()
        _creator_month_totals.clear()
        # Holds are per-process state too: a StreamingResponse a test never
        # drained would otherwise leave its daily hold on the ceiling for the
        # next test file (Q7 round: surfaced as an order-dependent failure in
        # tests/costs/test_gate.py).
        _reservations.clear()
        _creator_reservations.clear()
    if _redis_configured():
        try:
            client = await _get_redis_client()
            if client is not None:
                today = _today_utc()
                await client.delete(_global_key(today))
        except Exception:
            logger.warning("spend_tracker: Redis reset_for_testing failed", exc_info=True)
