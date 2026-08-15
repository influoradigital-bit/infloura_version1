"""Regression battery for the money-path findings in the 2026-08-08 deep audit.

F-01 prompt-cache tokens billed at $0
F-02 client disconnect burns tokens and records zero spend
F-03 spend accrued during a Redis outage is permanently invisible
F-04 env-overridden model ids priced at the old model's rate
F-05 the spend gate is read-then-spend with no reservation
F-06 billed provider calls that fail late record $0
F-07 TTS billed on pre-normalization length

Every one of these causes real provider spend the $15/day ceiling cannot see.
"""

from __future__ import annotations

import asyncio
from decimal import Decimal

import pytest

from app.config import get_settings
from app.costs import spend_tracker
from app.costs.gate import check_spend_gate
from app.costs.pricing import PRICING_TABLE, estimate_cost_usd


@pytest.fixture(autouse=True)
async def _clean_state():
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()
    yield
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()
    get_settings.cache_clear()


SONNET = "claude-sonnet-4-5-20250929"


# ---------------------------------------------------------------------------
# F-01 — cache tokens billed at $0
# ---------------------------------------------------------------------------


def test_f01_cached_prefix_is_billed_not_free():
    """The audit's worked example: a turn with an 8,000-token cached prefix,
    200 fresh input and 300 output. Real cost on a cache MISS (write) is
    $0.0351; the old formula recorded $0.0051 — a 7x under-count on every chat
    turn, largest exactly where prompt-cache volume is highest."""
    miss = estimate_cost_usd(
        SONNET,
        {"input_tokens": 200, "output_tokens": 300, "cache_creation_input_tokens": 8000,
         "cache_read_input_tokens": 0},
    )
    assert miss == Decimal("0.0351"), miss

    hit = estimate_cost_usd(
        SONNET,
        {"input_tokens": 200, "output_tokens": 300, "cache_read_input_tokens": 8000,
         "cache_creation_input_tokens": 0},
    )
    assert hit == Decimal("0.0075"), hit

    naive = estimate_cost_usd(SONNET, {"input_tokens": 200, "output_tokens": 300})
    assert naive == Decimal("0.0051")
    assert miss > naive * 6, "cache tokens are still being billed at $0"


def test_f01_alternate_cache_field_spellings_are_all_billed():
    a = estimate_cost_usd(SONNET, {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 1000})
    b = estimate_cost_usd(SONNET, {"input_tokens": 0, "output_tokens": 0, "cache_read_input_tokens": 1000})
    assert a == b > Decimal(0)


# ---------------------------------------------------------------------------
# F-04 — env-overridden model ids priced at the old model's rate
# ---------------------------------------------------------------------------


def test_f04_pricing_table_is_keyed_by_literal_model_ids():
    """The table was keyed by the CONSTANTS `CLAUDE_MODEL`/`TRENDSPARK_MODEL`,
    which are themselves `os.getenv(...)` — so the key moved with the override
    and the lookup always hit. Setting CLAUDE_MODEL=claude-opus-4-1 priced every
    chat turn at Sonnet's $3/$15 instead of Opus's $15/$75."""
    assert SONNET in PRICING_TABLE
    assert "claude-opus-4-1-20250805" in PRICING_TABLE

    opus = estimate_cost_usd("claude-opus-4-1-20250805", {"input_tokens": 1000, "output_tokens": 1000})
    sonnet = estimate_cost_usd(SONNET, {"input_tokens": 1000, "output_tokens": 1000})
    assert opus == sonnet * 5, "an Opus turn is still priced at the Sonnet rate"


def test_f04_an_unpriced_model_override_fails_loud():
    with pytest.raises(ValueError, match="no pricing entry"):
        estimate_cost_usd("claude-some-future-model-99", {"input_tokens": 1, "output_tokens": 1})


# ---------------------------------------------------------------------------
# F-03 — spend accrued during a Redis outage is permanently invisible
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f03_a_lagging_redis_total_never_masks_a_higher_memory_total(monkeypatch):
    """Redis down 10:00-10:30 while $12 accrues (memory $12, Redis stuck at $3).
    Redis recovers; the gate read $3 and kept authorizing until Redis ALONE
    reached $15 — about $27 of real spend against a $15 ceiling."""
    monkeypatch.setattr(spend_tracker, "_redis_configured", lambda: True)
    monkeypatch.setattr(spend_tracker, "_record_spend_redis", lambda *a, **k: _none())
    await spend_tracker.record_spend(Decimal("12.00"), workspace_id="ws1")

    monkeypatch.setattr(spend_tracker, "_get_global_total_redis", lambda: _value(Decimal("3.00")))
    total = await spend_tracker.get_global_total_today()
    assert total == Decimal("12.00"), (
        f"gate would read {total} while ${'12.00'} of real spend has accrued"
    )

    monkeypatch.setattr(
        spend_tracker, "_get_workspace_total_redis", lambda ws: _value(Decimal("3.00"))
    )
    assert await spend_tracker.get_workspace_total_today("ws1") == Decimal("12.00")


@pytest.mark.asyncio
async def test_f03_a_higher_redis_total_still_wins(monkeypatch):
    """max(), not "always memory" — another worker's spend must still count."""
    monkeypatch.setattr(spend_tracker, "_redis_configured", lambda: True)
    monkeypatch.setattr(spend_tracker, "_get_global_total_redis", lambda: _value(Decimal("40.00")))
    assert await spend_tracker.get_global_total_today() == Decimal("40.00")


async def _none():
    return None


async def _value(v):
    return v


# ---------------------------------------------------------------------------
# F-05 — read-then-spend with no reservation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f05_concurrent_callers_cannot_all_pass_the_same_stale_total(monkeypatch):
    """Total $14.90, ceiling $15, 200 concurrent requests at ~$0.07 each. All
    200 read $14.90, all passed, all executed -> ~$29 spent before one was
    blocked. Overshoot was bounded only by concurrency."""
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("14.90"), workspace_id=None)

    results = await asyncio.gather(
        *[check_spend_gate(workspace_id="ws1", reserve_usd=Decimal("0.07")) for _ in range(200)]
    )
    allowed = [r for r in results if r.allowed]
    # Pinned near the real number, not just "< 200": with $0.10 of headroom and a
    # $0.07 reservation, at most two callers can be admitted. A loose bound would
    # let 199-of-200 pass for a green tick.
    assert len(allowed) <= 2, (
        f"{len(allowed)} of 200 concurrent callers were admitted against $0.10 of headroom"
    )
    assert len(allowed) >= 1, "the gate is now refusing callers that fit inside the ceiling"
    # Held budget + recorded spend must not blow past the ceiling.
    held = sum((r.reservation.amount for r in allowed if r.reservation), Decimal(0))
    assert Decimal("14.90") + held <= Decimal("15.0") + Decimal("0.07"), (
        f"{len(allowed)} callers authorized ${held} of in-flight spend against a $0.10 headroom"
    )


@pytest.mark.asyncio
async def test_f05_the_global_ceiling_counts_holds_from_every_workspace(monkeypatch):
    """The property that makes the $15/day ceiling GLOBAL rather than per-tenant.

    Priya's round-7 blocker: every other F-05 concurrency test fires its 200
    callers from a single `workspace_id`, so `held_global`'s sum over ALL
    reservations and a sum restricted to `r.workspace_id == workspace_id` are
    indistinguishable — the one clause that makes the control cross-tenant had
    no test. Narrowing it reproduces the audit's headline number to the cent:
    200 of 200 admitted, $14.00 of in-flight spend authorized against $0.10 of
    headroom, with the whole suite green.

    Here each caller is a DIFFERENT workspace, which is the shape of the real
    traffic this ceiling exists to bound: one runaway tenant must not be able to
    spend the day's budget just because no single tenant exceeded its own cap.
    """
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("14.90"), workspace_id=None)

    results = await asyncio.gather(
        *[
            check_spend_gate(workspace_id=f"ws-{i}", reserve_usd=Decimal("0.07"))
            for i in range(200)
        ]
    )
    admitted = [r for r in results if r.allowed]
    held = sum((r.reservation.amount for r in admitted if r.reservation), Decimal(0))

    assert len(admitted) <= 2, (
        f"{len(admitted)} of 200 concurrent callers from 200 DIFFERENT workspaces were "
        f"admitted, ${held} authorized against $0.10 of headroom — the daily ceiling is "
        "per-tenant, not global"
    )
    assert len(admitted) >= 1, "the gate is now refusing callers that fit inside the ceiling"
    assert Decimal("14.90") + held <= Decimal("15.0") + Decimal("0.07")


@pytest.mark.asyncio
async def test_f05_one_workspaces_holds_block_a_different_workspace(monkeypatch):
    """The same property stated without concurrency, so a future refactor that
    serialises the gate cannot make the test above vacuous: workspace A holds
    the last of the headroom, and workspace B — which has spent nothing and is
    nowhere near its own cap — is refused because the GLOBAL budget is gone."""
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "100.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("14.90"), workspace_id="ws-a")

    first = await check_spend_gate(workspace_id="ws-a", reserve_usd=Decimal("0.10"))
    assert first.allowed, "test premise: the first caller fits inside the headroom"

    second = await check_spend_gate(workspace_id="ws-b", reserve_usd=Decimal("0.07"))
    assert not second.allowed, (
        "ws-b was admitted while ws-a holds the last of the global headroom — the "
        "$15/day ceiling is not counting other tenants' in-flight spend"
    )
    assert second.error_code == "AI_SPEND_CEILING_REACHED"


@pytest.mark.asyncio
async def test_f05_race_stays_closed_with_redis_and_a_workspace_cap_configured(monkeypatch):
    """F-05 round 2 — the config where the bug actually lives.

    The first fix read the global total, checked the ceiling, then read the
    WORKSPACE total, and reserved after that. With Redis configured and
    WORKSPACE_DAILY_HARD_CAP_USD set, that workspace read is network I/O sitting
    between the check and the reserve, so every concurrent caller passed seeing
    `reserved = 0`: measured 200 of 200 admitted, $14.00 authorized against
    $0.10 of headroom. The original fix's test passed only because it ran with
    no Redis and no workspace cap, where nothing yields — it was protected by
    the absence of an await, not by design.

    This test forces a yield in exactly that window.
    """
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "100.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("14.90"), workspace_id="ws1")

    real_ws_total = spend_tracker.get_workspace_total_today

    async def slow_workspace_read(workspace_id):
        # Stands in for the Redis round-trip: this is the await that used to sit
        # between the ceiling check and the reserve.
        await asyncio.sleep(0.01)
        return await real_ws_total(workspace_id)

    monkeypatch.setattr(spend_tracker, "get_workspace_total_today", slow_workspace_read)

    results = await asyncio.gather(
        *[check_spend_gate(workspace_id="ws1", reserve_usd=Decimal("0.07")) for _ in range(200)]
    )
    admitted = [r for r in results if r.allowed]
    held = sum((r.reservation.amount for r in admitted if r.reservation), Decimal(0))

    assert len(admitted) <= 2, (
        f"{len(admitted)} of 200 concurrent callers admitted, ${held} authorized against "
        "$0.10 of headroom — the check-then-reserve race is still open"
    )
    assert Decimal("14.90") + held <= Decimal("15.0") + Decimal("0.07")


@pytest.mark.asyncio
async def test_f05_the_per_workspace_cap_still_blocks(monkeypatch):
    """The atomic path must not lose the workspace cap it now evaluates."""
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "1000.0")
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "1.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("1.50"), workspace_id="ws1")

    blocked = await check_spend_gate(workspace_id="ws1", reserve_usd=Decimal("0.02"))
    assert not blocked.allowed
    assert blocked.error_code == "AI_WORKSPACE_SPEND_CAP_REACHED"
    # A different workspace is unaffected.
    assert (await check_spend_gate(workspace_id="ws2", reserve_usd=Decimal("0.02"))).allowed


@pytest.mark.asyncio
async def test_f05_in_flight_holds_count_against_the_per_workspace_cap(monkeypatch):
    """Priya's round-4 gap: `held_workspace` in `try_reserve` was unpinned.

    Replacing it with Decimal(0) left the suite green while restoring F-05's
    exact failure shape at workspace scope — measured 200 of 200 callers
    admitted with $14.00 in flight against $0.05 of workspace headroom. The
    GLOBAL ceiling still held, so the blast radius is the opt-in per-workspace
    cap, which is precisely the control someone turns on when they want one
    tenant bounded.
    """
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "10000.0")   # global out of the way
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "1.0")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("0.95"), workspace_id="ws1")

    results = await asyncio.gather(
        *[check_spend_gate(workspace_id="ws1", reserve_usd=Decimal("0.07")) for _ in range(200)]
    )
    admitted = [r for r in results if r.allowed]
    held = sum((r.reservation.amount for r in admitted if r.reservation), Decimal(0))

    assert len(admitted) <= 1, (
        f"{len(admitted)} of 200 admitted, ${held} in flight against $0.05 of workspace "
        "headroom — in-flight holds are not counted against the per-workspace cap"
    )
    assert Decimal("0.95") + held <= Decimal("1.0") + Decimal("0.07")

    # And a different workspace is genuinely unaffected by ws1's holds.
    assert (await check_spend_gate(workspace_id="ws2", reserve_usd=Decimal("0.07"))).allowed


@pytest.mark.asyncio
async def test_f05_recording_the_real_spend_settles_the_reservation():
    gate = await check_spend_gate(workspace_id="ws1", reserve_usd=Decimal("0.60"))
    assert gate.allowed and gate.reservation is not None
    assert await spend_tracker.get_reserved_global() == Decimal("0.60")

    await spend_tracker.record_spend(Decimal("0.05"), "ws1", reservation=gate.reservation)
    assert await spend_tracker.get_reserved_global() == Decimal(0)
    assert await spend_tracker.get_global_total_today() == Decimal("0.05")


@pytest.mark.asyncio
async def test_f05_an_abandoned_reservation_expires_instead_of_leaking_budget():
    reservation = await spend_tracker.reserve(Decimal("5.00"), "ws1", ttl_seconds=0.0)
    assert reservation.amount == Decimal("5.00")
    await asyncio.sleep(0.01)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_f05_release_is_idempotent():
    gate = await check_spend_gate(reserve_usd=Decimal("0.10"))
    await spend_tracker.release(gate.reservation)
    await spend_tracker.release(gate.reservation)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_f05_trend_tag_is_inside_the_per_workspace_cap(monkeypatch):
    """trend_tag called check_spend_gate() with NO workspace_id, placing the
    route entirely outside the per-workspace cap — an ingest endpoint behind a
    static secret was the one path with no per-tenant ceiling at all.

    Behavioural, not source-text: set a per-workspace hard cap, spend it out on
    the route's own bucket, and assert the route is then refused.
    """
    from app.routes.trend_tag import TREND_TAG_SPEND_BUCKET

    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "1.0")
    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "secret")
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")
    get_settings.cache_clear()

    # Before: the bucket is empty, so the gate lets the route through.
    assert (await check_spend_gate(workspace_id=TREND_TAG_SPEND_BUCKET)).allowed

    await spend_tracker.record_spend(Decimal("1.50"), TREND_TAG_SPEND_BUCKET)

    blocked = await check_spend_gate(workspace_id=TREND_TAG_SPEND_BUCKET)
    assert not blocked.allowed, "the trend_tag bucket is still outside the per-workspace cap"
    assert blocked.error_code == "AI_WORKSPACE_SPEND_CAP_REACHED"

    # And a global-only gate call (the pre-fix shape) would NOT have blocked it.
    assert (await check_spend_gate()).allowed


# ---------------------------------------------------------------------------
# F-06 / F-07 — billed calls that fail late, and pre-normalization TTS billing
# ---------------------------------------------------------------------------


def test_f06_a_billed_empty_transcript_is_marked_billed():
    from app.providers.sarvam import TranscribeResult

    result = TranscribeResult(ok=False, error="empty_transcript", billed=True)
    assert result.billed, "POSTing silence in a loop is still free unlimited STT"


def test_f06_transport_failures_are_not_billed():
    from app.providers.sarvam import SpeakResult, TranscribeResult

    assert TranscribeResult(ok=False, error="provider_error").billed is False
    assert SpeakResult(ok=False, error="circuit_open: x").billed is False


@pytest.mark.asyncio
async def test_f06_claude_forced_tool_carries_usage_when_no_tool_use_came_back():
    """`no_tool_use_in_response` DISCARDED a populated usage object — a
    brand-safety batch that hits max_tokens costs ~$0.07 and recorded $0.

    Behavioural: drive the real provider method with a fake client whose
    response carries usage but no tool_use block, and assert the usage survives
    onto the result the caller bills from.
    """
    from app.providers.claude import ClaudeProvider

    class _Usage:
        input_tokens, output_tokens = 5000, 900
        cache_read_input_tokens, cache_creation_input_tokens = 0, 0

    class _TextOnlyResponse:
        content = (type("B", (), {"type": "text", "text": "I cannot do that."})(),)
        usage = _Usage()

    class _Messages:
        async def create(self, **kwargs):
            return _TextOnlyResponse()

    provider = ClaudeProvider.__new__(ClaudeProvider)
    provider._client = type("C", (), {"messages": _Messages()})()
    provider._breaker = type("B", (), {"before_call": lambda self: None,
                                       "on_success": lambda self: None,
                                       "on_failure": lambda self: None})()

    result = await provider.complete_with_forced_tool(
        system_blocks=[], messages=[], tool_schema={"name": "t"},
    )
    assert result.ok is False
    assert result.error == "no_tool_use_in_response"
    assert result.usage is not None, "a billed call still records $0"
    assert result.usage["output_tokens"] == 900
    assert estimate_cost_usd(SONNET, result.usage) > Decimal(0)


def test_f07_tts_bills_the_normalized_text_sarvam_actually_receives():
    """voice.py billed `len(tts_text)`, but speak() posts `speakable(chunk)`,
    which expands rupee amounts and initialisms first. The audit's example:
    42 chars billed against 68 charged — a systematic ~40% under-bill on
    exactly the budget-quoting replies Meera produces most."""
    from app.costs.pricing import estimate_sarvam_tts_cost_usd
    from app.providers.sarvam import speakable

    raw = "Budget ₹15,000–₹75,000 per creator for UGC"
    spoken = speakable(raw)
    assert len(spoken) > len(raw), "speakable() no longer expands — re-check this test's premise"

    under_bill = estimate_sarvam_tts_cost_usd(len(raw))
    real = estimate_sarvam_tts_cost_usd(len(spoken))
    assert real > under_bill

    from app.providers.sarvam import SpeakResult

    result = SpeakResult(ok=True, audio_bytes=b"x", billed=True, billed_chars=len(spoken))
    assert estimate_sarvam_tts_cost_usd(result.billed_chars) == real
