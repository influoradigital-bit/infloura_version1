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

from app.config import get_settings

logger = logging.getLogger(__name__)

try:  # pragma: no cover - import shape, not logic
    import redis.asyncio as redis_asyncio
except ImportError:  # pragma: no cover - redis is a pinned dep (requirements.txt);
    # this guard keeps the module importable even in an environment where it
    # hasn't been installed yet, degrading straight to the in-memory path.
    redis_asyncio = None  # type: ignore[assignment]


_GLOBAL_KEY_PREFIX = "influora:ai:spend:global"
_WORKSPACE_KEY_PREFIX = "influora:ai:spend:ws"
_KEY_TTL_SECONDS = 3 * 24 * 60 * 60  # 3 days -- comfortably outlives one UTC day + clock skew
_MICROS_PER_DOLLAR = Decimal("1000000")


def _today_utc() -> dt.date:
    return dt.datetime.now(dt.timezone.utc).date()


def _to_micros(amount: Decimal) -> int:
    return int((amount * _MICROS_PER_DOLLAR).to_integral_value(rounding=ROUND_HALF_UP))


def _from_micros(micros: int | str | None) -> Decimal:
    if not micros:
        return Decimal("0")
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


async def _record_spend_memory(cost_usd: Decimal, workspace_id: str | None) -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        _state.global_total += cost_usd
        if workspace_id:
            _state.per_workspace[workspace_id] = (
                _state.per_workspace.get(workspace_id, Decimal("0")) + cost_usd
            )
        return _state.global_total


async def _get_global_total_memory() -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.global_total


async def _get_workspace_total_memory(workspace_id: str) -> Decimal:
    async with _lock:
        _roll_if_new_day_locked()
        return _state.per_workspace.get(workspace_id, Decimal("0"))


# ---------------------------------------------------------------------------
# Redis-backed store
# ---------------------------------------------------------------------------

_redis_client: "redis_asyncio.Redis | None" = None  # type: ignore[name-defined]
_redis_client_lock = asyncio.Lock()


def _redis_configured() -> bool:
    settings = get_settings()
    return bool(settings.redis_url) and redis_asyncio is not None


async def _get_redis_client() -> "redis_asyncio.Redis | None":  # type: ignore[name-defined]
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
        except Exception:  # noqa: BLE001 - best-effort shutdown, never raise
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
    except Exception:  # noqa: BLE001 - any Redis failure -> caller falls back
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
    except Exception:  # noqa: BLE001
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
    except Exception:  # noqa: BLE001
        logger.warning(
            "spend_tracker: Redis get_workspace_total failed, falling back to in-memory counter",
            exc_info=True,
        )
        return None


# ---------------------------------------------------------------------------
# Public API (used by app.costs.gate / app.routes.chat) -- unchanged signatures
# ---------------------------------------------------------------------------


async def record_spend(cost_usd: Decimal, workspace_id: str | None) -> Decimal:
    """Adds `cost_usd` to today's global total (and, if `workspace_id` is
    given, that workspace's running total). Returns the new global total for
    today so the caller can include `spend_today_usd` in its structured log
    line without a second round trip.

    Always also updates the in-memory counter, even when Redis is
    configured and the write succeeds there -- cheap, keeps the two stores
    from drifting apart, and means a mid-request Redis outage on the *next*
    call still has a locally-consistent number to fall back to.
    """
    memory_total = await _record_spend_memory(cost_usd, workspace_id)
    if _redis_configured():
        redis_total = await _record_spend_redis(cost_usd, workspace_id)
        if redis_total is not None:
            return redis_total
    return memory_total


async def get_global_total_today() -> Decimal:
    if _redis_configured():
        redis_total = await _get_global_total_redis()
        if redis_total is not None:
            return redis_total
    return await _get_global_total_memory()


async def get_workspace_total_today(workspace_id: str) -> Decimal:
    if _redis_configured():
        redis_total = await _get_workspace_total_redis(workspace_id)
        if redis_total is not None:
            return redis_total
    return await _get_workspace_total_memory(workspace_id)


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
        except Exception:  # noqa: BLE001 - best-effort, tests still pass on in-memory state
            logger.warning("spend_tracker: Redis reset_for_testing failed", exc_info=True)
