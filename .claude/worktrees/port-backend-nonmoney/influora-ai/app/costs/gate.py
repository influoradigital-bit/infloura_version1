"""P2-17 §3.3 — `check_spend_gate()`, the single enforcement point called at
the top of every AI-provider-bound route (chat.py, analyze_site.py's
classify_site path, brand_safety.py) BEFORE any provider client is touched.

Mirrors `app.auth.service_token`'s "any failure -> 401/403, no token spend"
contract: on either trip below, the caller must return the structured error
immediately and make zero provider calls -- this module never calls a
provider itself, it only decides yes/no.

Order of checks (per spec §3.3): (a) kill-switch env var first, (b) today's
global total vs the daily ceiling. Per-workspace $3/day is deliberately NOT
checked here -- it's a WARNING-only soft cap scoped to the chat route only
(the only route with a reliable workspace_id on every call today), logged by
the chat route itself after a successful call, not gated here.
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


async def check_spend_gate() -> SpendGateResult:
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

    return SpendGateResult(allowed=True)
