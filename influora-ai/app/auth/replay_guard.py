"""Consume-once store for scoped stream-token `jti` claims.

Kabir red-team FIX 1: Spring's `StreamTokenService` (StreamTokenService.java:82)
mints a random UUID `jti` per `chat:stream`-scoped token and its javadoc claims
this Python service enforces single-use. Before this module existed that claim
was false -- `service_token.py:verify_token` checked signature/aud/iss/exp/
scope/workspace_id but never consulted or recorded `jti`, so a captured stream
token could be replayed against `/chat` any number of times until it expired.

Same Redis-first, in-memory-fallback shape as `app.costs.spend_tracker` (H-25):
Redis is an optimization, never a hard dependency -- a Redis outage, a missing
`REDIS_URL`, or the `redis` package being unavailable must never block a
legitimate first use of a stream token. Every Redis operation here is wrapped;
on any failure the call falls back to the in-process dict used when Redis
isn't configured at all.

Concurrency (in-memory fallback only): guarded by a single module-level
`asyncio.Lock`, sufficient for this single-process asyncio app to make the
"is it already consumed / mark it consumed" sequence atomic across concurrent
request coroutines within one process. The Redis path relies on `SET ... NX`
for its own atomicity instead.

**Blast radius:** only `chat:stream`-scoped tokens ever reach `consume_once`
(see `service_token.verify_token_async`) -- service tokens (used by
analyze_site/voice/brand_safety/trendspark and Spring-proxied /chat) are never
passed through this module and are completely unaffected.
"""

from __future__ import annotations

import asyncio
import logging
import time

from app.config import get_settings

logger = logging.getLogger(__name__)

try:  # pragma: no cover - import shape, not logic
    import redis.asyncio as redis_asyncio
except ImportError:  # pragma: no cover
    # `redis[hiredis]` is declared in requirements.txt, so a correctly built
    # image enforces the replay guard cross-instance. This guard only keeps the
    # module importable if the package is absent, degrading to the per-process
    # in-memory store. If REDIS_URL is set while this branch is taken, /readyz
    # reports NOT ready rather than silently narrowing the replay window to one
    # process.
    redis_asyncio = None  # type: ignore[assignment]


_KEY_PREFIX = "influora:auth:stream-jti"
_MIN_TTL_SECONDS = 1.0

# ---------------------------------------------------------------------------
# In-memory fallback (also the whole store when REDIS_URL is unset)
# ---------------------------------------------------------------------------

_memory_store: dict[str, float] = {}  # jti -> expiry epoch seconds
_memory_lock = asyncio.Lock()


def _sweep_memory_locked() -> None:
    """Must only be called while holding `_memory_lock`. Drops expired
    entries so this dict doesn't grow unbounded over a long process
    lifetime -- stream tokens have a <=60s TTL, so entries are short-lived,
    but nothing ever swept them out otherwise."""
    now = time.time()
    expired = [jti for jti, expiry in _memory_store.items() if expiry <= now]
    for jti in expired:
        del _memory_store[jti]


async def _consume_memory(jti: str, ttl_seconds: float) -> bool:
    """Returns True if this call is the first consumption of `jti` (allowed),
    False if `jti` is already present and not yet expired (replay)."""
    now = time.time()
    async with _memory_lock:
        _sweep_memory_locked()
        existing_expiry = _memory_store.get(jti)
        if existing_expiry is not None and existing_expiry > now:
            return False
        _memory_store[jti] = now + max(ttl_seconds, _MIN_TTL_SECONDS)
        return True


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
    single, uniform "no Redis available" branch. Deliberately a distinct
    client instance from spend_tracker's -- kept independent so a bug in one
    module's Redis usage can't affect the other's connection lifecycle."""
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
            logger.warning("replay_guard: error closing Redis client", exc_info=True)
        finally:
            _redis_client = None


async def _consume_redis(jti: str, ttl_seconds: float) -> bool | None:
    """Returns True (first use), False (replay), or None to signal the
    caller should fall back to the in-memory store (Redis unavailable or the
    call itself failed)."""
    try:
        client = await _get_redis_client()
        if client is None:
            return None
        key = f"{_KEY_PREFIX}:{jti}"
        ttl = max(int(ttl_seconds), int(_MIN_TTL_SECONDS))
        was_set = await client.set(key, "1", nx=True, ex=ttl)
        return bool(was_set)
    except Exception:  # noqa: BLE001 - any Redis failure -> caller falls back
        logger.warning(
            "replay_guard: Redis consume_once failed, falling back to in-memory store",
            exc_info=True,
        )
        return None


# ---------------------------------------------------------------------------
# Public API (used by app.auth.service_token.verify_token_async)
# ---------------------------------------------------------------------------


async def consume_once(jti: str, ttl_seconds: float) -> bool:
    """Attempts to consume `jti` for the first time.

    Returns True if this call is the first (legitimate) use -- the caller may
    proceed. Returns False if `jti` was already consumed -- the caller must
    reject the request as a replay.

    Tries Redis first (SETNX-with-TTL, atomic, shared across every
    worker/instance) when `REDIS_URL` is configured; falls back to the
    single-process in-memory dict on any Redis failure or when Redis isn't
    configured at all. `ttl_seconds` should be the token's remaining
    lifetime (`exp - now`) so a consumed-key entry never outlives the window
    in which the token itself could possibly be replayed.
    """
    if _redis_configured():
        redis_result = await _consume_redis(jti, ttl_seconds)
        if redis_result is not None:
            return redis_result
    return await _consume_memory(jti, ttl_seconds)


async def reset_for_testing() -> None:
    """Test-only seam -- forces a fresh, empty in-memory store. Production
    code never calls this."""
    global _memory_store
    async with _memory_lock:
        _memory_store = {}
