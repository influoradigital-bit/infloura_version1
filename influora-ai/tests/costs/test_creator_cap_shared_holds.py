"""Gate fix round 2 (Q7, Priya caveat 1) -- the per-creator monthly hold lives
in Redis, so the cap survives more than one uvicorn worker / replica.

Round 1 kept holds in the process-local `_creator_reservations` dict while the
TOTAL lived in Redis: two workers at cap-minus-one both admitted a turn. These
tests drive `app.costs.spend_tracker` against a small in-process fake of the
Redis command surface it uses (INCRBY / DECRBY / EXPIRE / GET inside a
MULTI/EXEC pipeline) and simulate a second worker by wiping the process-local
table between calls -- exactly what a different process would see.
"""

from __future__ import annotations

import asyncio
from decimal import Decimal

import pytest

from app.config import get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import (
    SpendCapExceeded,
    check_creator_spend_gate,
    get_creator_month_total,
    get_reserved_creator,
    record_creator_spend,
    release_creator,
)

CREATOR_ID = "creator-shared-001"
CAP = Decimal("0.75")
RESERVE = Decimal("0.05")


# ---------------------------------------------------------------------------
# A minimal fake of the redis.asyncio surface spend_tracker touches. Atomic
# per command and per MULTI/EXEC block, like Redis. Every method is async and
# yields to the loop once so concurrent gate calls actually interleave.
# ---------------------------------------------------------------------------


class _FakePipeline:
    def __init__(self, store: "FakeRedis") -> None:
        self._store = store
        self._ops: list[tuple] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def incrby(self, key, amount):
        self._ops.append(("incrby", key, int(amount)))

    def decrby(self, key, amount):
        self._ops.append(("decrby", key, int(amount)))

    def expire(self, key, ttl):
        self._ops.append(("expire", key, int(ttl)))

    def get(self, key):
        self._ops.append(("get", key))

    async def execute(self):
        await asyncio.sleep(0)  # network hop: let other coroutines run first
        # EXEC: the queued commands run back-to-back with nothing in between.
        results = []
        for op in self._ops:
            results.append(self._store._apply(op))
        self._ops = []
        self._store.exec_count += 1
        return results


class FakeRedis:
    def __init__(self) -> None:
        self.data: dict[str, int] = {}
        self.ttls: dict[str, int] = {}
        self.exec_count = 0
        self.fail_next: Exception | None = None  # next single command
        self.fail_next_pipeline: Exception | None = None  # next MULTI/EXEC

    def _apply(self, op: tuple):
        kind = op[0]
        if kind == "incrby":
            self.data[op[1]] = self.data.get(op[1], 0) + op[2]
            return self.data[op[1]]
        if kind == "decrby":
            self.data[op[1]] = self.data.get(op[1], 0) - op[2]
            return self.data[op[1]]
        if kind == "expire":
            self.ttls[op[1]] = op[2]
            return True
        if kind == "get":
            value = self.data.get(op[1])
            return None if value is None else str(value)
        raise AssertionError(op)

    def _maybe_fail(self) -> None:
        if self.fail_next is not None:
            exc, self.fail_next = self.fail_next, None
            raise exc

    def pipeline(self, transaction: bool = True):
        assert transaction, "spend_tracker must use MULTI/EXEC pipelines"
        if self.fail_next_pipeline is not None:
            exc, self.fail_next_pipeline = self.fail_next_pipeline, None
            raise exc
        return _FakePipeline(self)

    async def incrby(self, key, amount):
        await asyncio.sleep(0)
        self._maybe_fail()
        return self._apply(("incrby", key, int(amount)))

    async def decrby(self, key, amount):
        await asyncio.sleep(0)
        self._maybe_fail()
        return self._apply(("decrby", key, int(amount)))

    async def get(self, key):
        await asyncio.sleep(0)
        self._maybe_fail()
        return self._apply(("get", key))

    async def expire(self, key, ttl):
        await asyncio.sleep(0)
        return self._apply(("expire", key, int(ttl)))

    async def delete(self, *keys):
        for key in keys:
            self.data.pop(key, None)
            self.ttls.pop(key, None)
        return len(keys)


@pytest.fixture
def fake_redis(monkeypatch):
    store = FakeRedis()
    monkeypatch.delenv("AI_CREATOR_MONTHLY_CAP_USD", raising=False)
    monkeypatch.delenv("REDIS_URL", raising=False)
    get_settings.cache_clear()
    monkeypatch.setattr(spend_tracker, "_redis_configured", lambda: True)

    async def _client():
        return store

    monkeypatch.setattr(spend_tracker, "_get_redis_client", _client)
    return store


@pytest.fixture(autouse=True)
async def _reset():
    await spend_tracker.reset_for_testing()
    yield
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


def _held_key() -> str:
    return spend_tracker._creator_held_key(CREATOR_ID, spend_tracker._current_month_utc())


def _total_key() -> str:
    return spend_tracker._creator_month_key(CREATOR_ID, spend_tracker._current_month_utc())


def _simulate_other_worker() -> None:
    """A different uvicorn worker / replica shares Redis but has an EMPTY
    process-local hold table. Round 1's guarantee lived in that table."""
    spend_tracker._creator_reservations.clear()


async def _seed_total(amount: Decimal) -> None:
    await record_creator_spend(amount, CREATOR_ID)


# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_hold_is_visible_to_a_second_worker_through_redis(fake_redis):
    """The Priya caveat, made a test: worker A takes the last slot; worker B
    (empty local table, same Redis) must be refused."""
    await _seed_total(CAP - RESERVE)  # cap-minus-one

    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert reservation is not None
    assert reservation.store == "redis"
    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE)

    _simulate_other_worker()
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)

    # The rejected worker gave back what it briefly added.
    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE)
    assert await get_reserved_creator(CREATOR_ID) == RESERVE


@pytest.mark.asyncio
async def test_round1_regression_would_admit_both_without_the_shared_hold(fake_redis):
    """Falsification: prove the fake actually reproduces the caveat when the
    hold is only process-local. If this stops failing the way described, the
    fake is no longer exercising the shared path."""
    await _seed_total(CAP - RESERVE)
    admitted = 0
    for _ in range(2):
        # A memory-only hold (what round 1 did), then a "new process".
        reservation, exceeded = await spend_tracker._try_reserve_creator_memory(
            RESERVE, CREATOR_ID,
            month_total=await get_creator_month_total(CREATOR_ID), cap=CAP,
            ttl_seconds=60,
        )
        if not exceeded:
            admitted += 1
        _simulate_other_worker()
    assert admitted == 2  # the bug, reproduced -- the shared path must not do this


@pytest.mark.asyncio
async def test_concurrent_turns_across_workers_admit_exactly_one(fake_redis):
    """N concurrent gate calls that all read the same stale total; Redis's
    MULTI/EXEC serialises the INCRBYs so exactly one sees held_before == 0."""
    await _seed_total(CAP - RESERVE)

    async def one_turn():
        # Each "worker" starts from an empty local table.
        _simulate_other_worker()
        try:
            return await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
        except SpendCapExceeded:
            return None

    outcomes = await asyncio.gather(*(one_turn() for _ in range(25)))
    admitted = [r for r in outcomes if r is not None]
    assert len(admitted) == 1
    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE)


@pytest.mark.asyncio
async def test_settle_gives_the_hold_back_in_the_same_transaction(fake_redis):
    await _seed_total(Decimal("0.10"))
    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert reservation is not None
    execs_before = fake_redis.exec_count

    total = await record_creator_spend(Decimal("0.02"), CREATOR_ID, reservation=reservation)

    assert total == Decimal("0.12")
    assert fake_redis.data[_held_key()] == 0
    assert fake_redis.data[_total_key()] == spend_tracker._to_micros(Decimal("0.12"))
    # One EXEC did both the spend and the hold release.
    assert fake_redis.exec_count == execs_before + 1
    assert reservation.settled is True
    # Releasing after a settle is a no-op: the counter must not go negative
    # (a second DECRBY would eat some other turn's hold).
    await release_creator(reservation)
    await release_creator(reservation)
    assert fake_redis.data[_held_key()] == 0


@pytest.mark.asyncio
async def test_release_is_idempotent_and_never_eats_another_workers_hold(fake_redis):
    mine = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    _simulate_other_worker()
    theirs = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert mine is not None and theirs is not None
    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE * 2)

    await release_creator(mine)
    await release_creator(mine)  # duplicate release from a finally: block

    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE)
    assert await get_reserved_creator(CREATOR_ID) == RESERVE


@pytest.mark.asyncio
async def test_held_key_expiring_under_a_live_hold_clamps_to_zero(fake_redis):
    """A crashed owner's phantom hold is reclaimed by the key TTL. If the key
    expires while a legitimate hold is still in flight, its release must not
    leave a NEGATIVE counter (which would admit that much extra spend)."""
    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert reservation is not None
    assert fake_redis.ttls[_held_key()] == 60 + spend_tracker._HELD_KEY_GRACE_SECONDS

    await fake_redis.delete(_held_key())  # TTL fired
    await release_creator(reservation)
    assert fake_redis.data[_held_key()] == 0

    # Same clamp on the settle path.
    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    await fake_redis.delete(_held_key())
    await record_creator_spend(Decimal("0.01"), CREATOR_ID, reservation=reservation)
    assert fake_redis.data[_held_key()] == 0


@pytest.mark.asyncio
async def test_redis_failure_on_reserve_falls_back_to_a_process_local_hold(fake_redis):
    """Fallback contract (Priya): Redis is an optimisation, never a blocker.
    A failed INCRBY degrades the hold to the per-process table -- and it is
    still counted, released, and settled correctly from there."""
    fake_redis.fail_next_pipeline = ConnectionError("redis down")  # the reserve MULTI
    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert reservation is not None
    assert reservation.store == "memory"
    assert _held_key() not in fake_redis.data
    assert await get_reserved_creator(CREATOR_ID) == RESERVE

    await record_creator_spend(Decimal("0.01"), CREATOR_ID, reservation=reservation)
    assert await get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.01")


@pytest.mark.asyncio
async def test_redis_failure_on_settle_still_releases_the_shared_hold(fake_redis):
    reservation = await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    assert reservation is not None and reservation.store == "redis"

    fake_redis.fail_next_pipeline = ConnectionError("redis blipped")  # the settle MULTI
    await record_creator_spend(Decimal("0.01"), CREATOR_ID, reservation=reservation)

    assert reservation.settled is True
    assert fake_redis.data[_held_key()] == 0  # released on its own afterwards
    # The in-memory ledger still carries the spend (F-03 "whichever saw more").
    assert await get_creator_month_total(CREATOR_ID) == Decimal("0.01")


@pytest.mark.asyncio
async def test_no_reserve_amount_gates_on_shared_holds_but_adds_none(fake_redis):
    """A caller that asks for no hold still sees other workers' holds."""
    await _seed_total(CAP - RESERVE)
    await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    _simulate_other_worker()
    with pytest.raises(SpendCapExceeded):
        await check_creator_spend_gate(CREATOR_ID, "CREATOR")
    assert fake_redis.data[_held_key()] == spend_tracker._to_micros(RESERVE)


@pytest.mark.asyncio
async def test_holds_are_per_creator_in_redis(fake_redis):
    await check_creator_spend_gate(CREATOR_ID, "CREATOR", reserve_usd=RESERVE)
    other = await check_creator_spend_gate("someone-else", "CREATOR", reserve_usd=RESERVE)
    assert other is not None
    assert await get_reserved_creator(CREATOR_ID) == RESERVE
    assert await get_reserved_creator("someone-else") == RESERVE
    assert set(k for k in fake_redis.data if k.endswith(":held")) == {
        _held_key(),
        spend_tracker._creator_held_key("someone-else", spend_tracker._current_month_utc()),
    }
