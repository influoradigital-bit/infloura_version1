"""P2-17 §3.1 — provider pricing table + cost estimation.

Rates verified 2026-07-11/12, per
wiki/decisions/budget-proposals/2026-07-12-ai-spend-ceiling-and-killswitch.md §1.
Uses `Decimal`, not `float`, for money math -- same reasoning as the Java side's
`BigDecimal` (floating point cents/dollars drift is unacceptable for a budget
enforcement path, even at this "estimate" fidelity).

Re-price this table (do not treat it as permanent) once a full month of real
logged `ai_spend` lines exists -- same "recompute, don't set-and-forget"
discipline as the rest of Rohan's cost-review docs.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, NamedTuple

from app.config import CLAUDE_MODEL, GEMINI_MODEL, TRENDSPARK_MODEL

_MTOK = Decimal("1000000")


class ModelRate(NamedTuple):
    """Dollars per token, already divided down from the per-MTok list price."""

    input_per_token: Decimal
    output_per_token: Decimal


def _per_mtok(input_usd: str, output_usd: str) -> ModelRate:
    return ModelRate(Decimal(input_usd) / _MTOK, Decimal(output_usd) / _MTOK)


# model_id -> (input $/token, output $/token). Keyed by the exact model id
# strings the providers are pinned to in app/config.py -- never a family/alias
# name -- so a model bump that isn't also priced here fails loud (see
# estimate_cost_usd's ValueError below) instead of silently under-billing.
PRICING_TABLE: dict[str, ModelRate] = {
    CLAUDE_MODEL: _per_mtok("3.00", "15.00"),
    GEMINI_MODEL: _per_mtok("0.10", "0.40"),
    # Claude Haiku 4.5 — the cheap Trend-Spark nudge model (T8). $1/$5 per MTok.
    TRENDSPARK_MODEL: _per_mtok("1.00", "5.00"),
}

# Sarvam STT/TTS has no per-token usage payload (flat per-call estimate per
# §1) -- not keyed by model id, used directly by any Sarvam call site that
# wants to record a flat cost. Not currently wired into a spend-gated route
# (voice.py isn't one of the 3 in-scope P2-17 call sites), kept here so the
# number lives in one place when that route is wired up later.
SARVAM_FLAT_COST_PER_CALL = Decimal("0.006")


def estimate_cost_usd(model: str, usage: dict[str, Any] | None) -> Decimal:
    """Estimates the USD cost of one completed provider call from its token
    usage dict (`{"input_tokens": int, "output_tokens": int, ...}` -- the
    shape `ClaudeProvider`/tool-loop `LoopEvent.usage` already produce).

    Returns `Decimal("0")` for a falsy/unusable `usage` (nothing to bill) --
    callers (chat.py, brand_safety.py) already only call this when `usage`
    is truthy, but this stays defensive rather than raising on a partial
    provider response.

    Raises `ValueError` for a `model` not in `PRICING_TABLE` -- a model bump
    that isn't priced here must fail loud in tests/CI, not silently record
    $0 spend for a model that may cost real money.
    """
    if model not in PRICING_TABLE:
        raise ValueError(f"no pricing entry for model {model!r} -- add it to PRICING_TABLE first")

    if not usage:
        return Decimal("0")

    rate = PRICING_TABLE[model]
    input_tokens = usage.get("input_tokens") or 0
    output_tokens = usage.get("output_tokens") or 0

    cost = (Decimal(int(input_tokens)) * rate.input_per_token) + (
        Decimal(int(output_tokens)) * rate.output_per_token
    )
    return cost
