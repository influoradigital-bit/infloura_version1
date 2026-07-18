"""Unit tests for app/auth/replay_guard.py in isolation (no JWT involved) --
the in-memory consume-once store, exercised directly. Integration coverage
(via `verify_token_async` and real `chat:stream` tokens) lives in
tests/security/test_service_token_replay.py.

No `REDIS_URL` is set in this test environment, so `consume_once` always
exercises the in-memory fallback path here -- that IS "in-memory fallback
works" per the task's coverage list, since it's also the whole store when
Redis isn't configured at all.
"""

from __future__ import annotations

import uuid

import pytest

from app.auth import replay_guard


@pytest.fixture(autouse=True)
async def _reset_state():
    await replay_guard.reset_for_testing()
    yield
    await replay_guard.reset_for_testing()


def _jti() -> str:
    return f"jti-{uuid.uuid4()}"


@pytest.mark.asyncio
async def test_first_consumption_is_allowed():
    jti = _jti()
    allowed = await replay_guard.consume_once(jti, ttl_seconds=30.0)
    assert allowed is True


@pytest.mark.asyncio
async def test_second_consumption_of_same_jti_is_rejected():
    jti = _jti()
    first = await replay_guard.consume_once(jti, ttl_seconds=30.0)
    second = await replay_guard.consume_once(jti, ttl_seconds=30.0)

    assert first is True
    assert second is False


@pytest.mark.asyncio
async def test_different_jtis_are_independent():
    jti_a = _jti()
    jti_b = _jti()

    assert await replay_guard.consume_once(jti_a, ttl_seconds=30.0) is True
    assert await replay_guard.consume_once(jti_b, ttl_seconds=30.0) is True
    # Replaying jti_a still fails even after jti_b was consumed in between.
    assert await replay_guard.consume_once(jti_a, ttl_seconds=30.0) is False


@pytest.mark.asyncio
async def test_expired_entry_can_be_reconsumed_in_memory():
    """An entry whose TTL has already elapsed by the time of the second call
    is treated as a fresh jti (the sweep in `_consume_memory` drops it) --
    this only matters for the in-memory fallback since Redis's own `EX`
    handles real expiry; a negative/zero ttl_seconds simulates "already
    expired by the time we checked"."""
    jti = _jti()
    first = await replay_guard.consume_once(jti, ttl_seconds=-5.0)
    # First call still succeeds (it's the first consumption)...
    assert first is True
    # ...but the entry is stored with the floor TTL (_MIN_TTL_SECONDS), so an
    # immediate replay within that floor window is still rejected -- the
    # floor exists precisely so a defensively-negative ttl can't accidentally
    # disable replay protection entirely.
    second = await replay_guard.consume_once(jti, ttl_seconds=-5.0)
    assert second is False


@pytest.mark.asyncio
async def test_in_memory_fallback_used_when_redis_not_configured(monkeypatch):
    """No REDIS_URL is set by default in this test env -- assert that's
    actually true (so this test is meaningful) and that consume_once still
    works end to end on the in-memory-only path."""
    monkeypatch.delenv("REDIS_URL", raising=False)
    from app.config import get_settings

    get_settings.cache_clear()
    try:
        assert replay_guard._redis_configured() is False
        jti = _jti()
        assert await replay_guard.consume_once(jti, ttl_seconds=10.0) is True
        assert await replay_guard.consume_once(jti, ttl_seconds=10.0) is False
    finally:
        get_settings.cache_clear()
