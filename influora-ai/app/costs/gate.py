"""P2-17 §3.3 — `check_spend_gate()`, the single enforcement point called at
the top of every AI-provider-bound route (chat.py, analyze_site.py's
classify_site path, brand_safety.py) BEFORE any provider client is touched.

Mirrors `app.auth.service_token`'s "any failure -> 401/403, no token spend"
contract: on any trip below, the caller must return the structured error
immediately and make zero provider calls -- this module never calls a
provider itself, it only decides yes/no.

Order of checks: (a) kill-switch env var first, (b) today's global total vs
the daily ceiling, (c) [Kabir red-team FIX 3, opt-in] today's per-workspace
total vs `WORKSPACE_DAILY_HARD_CAP_USD`, only when both a `workspace_id` is
passed by the caller AND that env var is set.

Per-workspace $3/day (`AI_WORKSPACE_DAILY_SOFT_CAP_USD`) remains a
WARNING-only soft cap logged by chat.py itself after a successful call, not
gated here -- unchanged by FIX 3. The new hard cap below is a SEPARATE,
opt-in, BLOCKING check: unset (the default) reproduces today's
warning-only-only behavior exactly, byte-for-byte backward compatible with
every existing caller of `check_spend_gate()` that doesn't pass
`workspace_id` at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from app.config import get_settings
from app.costs import spend_tracker


@dataclass(frozen=True)
class SpendGateResult:
    allowed: bool
    error_code: str | None = None
    error_message: str | None = None
    # F-05: budget held for this caller between the gate check and the recorded
    # spend. Present only when the caller asked for a reservation. ALWAYS pass
    # it to `record_spend(..., reservation=...)` or `release()` in a `finally`.
    reservation: spend_tracker.Reservation | None = None


async def check_spend_gate(
    workspace_id: str | None = None,
    *,
    reserve_usd: Decimal | str | float | None = None,
    reserve_ttl_seconds: float | None = None,
) -> SpendGateResult:
    """`workspace_id` is optional and additive: omit it (as analyze_site.py /
    voice.py / brand_safety.py currently do) and behavior is identical to
    before FIX 3 -- only the global kill-switch/ceiling checks run. Pass it
    (as chat.py now does) to additionally enforce the opt-in per-workspace
    hard cap below.

    F-05 — `reserve_usd` closes the read-then-spend race. This function used to
    read the total, return, and hold NOTHING; `record_spend` ran only after the
    provider call completed. At $14.90 against a $15 ceiling, 200 concurrent
    requests all read $14.90, all passed, and all executed at ~$0.07 -- about
    $29 spent before one request was blocked, with the overshoot bounded only by
    concurrency. Pass a pessimistic per-call estimate and in-flight cost becomes
    visible to every concurrent caller: the comparison below is
    `total + reserved >= ceiling`, and this call reserves before returning
    `allowed=True`, inside the same lock-ordered sequence.

    The caller MUST settle the reservation -- `record_spend(..., reservation=r)`
    on success, `spend_tracker.release(r)` on any failure path. An unsettled
    reservation expires on its own so a crash cannot leak budget permanently.
    """
    settings = get_settings()

    if settings.ai_spend_kill_switch:
        return SpendGateResult(
            allowed=False,
            error_code="AI_KILL_SWITCH_ACTIVE",
            error_message="AI provider calls are temporarily disabled (kill switch active)",
        )

    reserve_amount = Decimal(str(reserve_usd)) if reserve_usd is not None else Decimal(0)
    ceiling = Decimal(str(settings.ai_daily_spend_ceiling_usd))
    hard_cap = settings.ai_workspace_daily_hard_cap_usd

    # Every blocking read happens HERE, before the atomic section. F-05 round 2:
    # the previous shape read the global total, compared it, then read the
    # workspace total, and reserved after that — so with Redis and a workspace
    # cap configured there was network I/O between the check and the reserve,
    # and 200 concurrent callers all passed seeing `reserved = 0`.
    global_total = await spend_tracker.get_global_total_today()
    workspace_total = None
    if workspace_id and hard_cap is not None:
        workspace_total = await spend_tracker.get_workspace_total_today(workspace_id)

    # Compare and reserve under one lock, no await in between.
    reservation, error_code = await spend_tracker.try_reserve(
        reserve_amount,
        workspace_id,
        global_total=global_total,
        global_ceiling=ceiling,
        workspace_total=workspace_total,
        workspace_cap=Decimal(str(hard_cap)) if hard_cap is not None else None,
        **({} if reserve_ttl_seconds is None else {"ttl_seconds": reserve_ttl_seconds}),
    )
    if error_code == "AI_SPEND_CEILING_REACHED":
        return SpendGateResult(
            allowed=False,
            error_code=error_code,
            error_message="Daily AI spend ceiling reached -- try again after UTC midnight",
        )
    if error_code == "AI_WORKSPACE_SPEND_CAP_REACHED":
        return SpendGateResult(
            allowed=False,
            error_code=error_code,
            error_message=(
                "Daily AI spend cap for this workspace reached -- try again after UTC midnight"
            ),
        )
    return SpendGateResult(allowed=True, reservation=reservation)
