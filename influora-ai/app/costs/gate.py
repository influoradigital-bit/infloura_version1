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


async def check_spend_gate(workspace_id: str | None = None) -> SpendGateResult:
    """`workspace_id` is optional and additive: omit it (as analyze_site.py /
    voice.py / brand_safety.py currently do) and behavior is identical to
    before FIX 3 -- only the global kill-switch/ceiling checks run. Pass it
    (as chat.py now does) to additionally enforce the opt-in per-workspace
    hard cap below.
    """
    settings = get_settings()

    if settings.ai_spend_kill_switch:
        return SpendGateResult(
            allowed=False,
            error_code="AI_KILL_SWITCH_ACTIVE",
            error_message="AI provider calls are temporarily disabled (kill switch active)",
        )

    global_total = await spend_tracker.get_global_total_today()
    ceiling = Decimal(str(settings.ai_daily_spend_ceiling_usd))
    if global_total >= ceiling:
        return SpendGateResult(
            allowed=False,
            error_code="AI_SPEND_CEILING_REACHED",
            error_message="Daily AI spend ceiling reached -- try again after UTC midnight",
        )

    hard_cap = settings.ai_workspace_daily_hard_cap_usd
    if workspace_id and hard_cap is not None:
        workspace_total = await spend_tracker.get_workspace_total_today(workspace_id)
        if workspace_total >= Decimal(str(hard_cap)):
            return SpendGateResult(
                allowed=False,
                error_code="AI_WORKSPACE_SPEND_CAP_REACHED",
                error_message=(
                    "Daily AI spend cap for this workspace reached -- try again after UTC midnight"
                ),
            )

    return SpendGateResult(allowed=True)
