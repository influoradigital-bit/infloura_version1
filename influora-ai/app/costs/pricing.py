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

import logging
from decimal import Decimal
from typing import Any, NamedTuple

from app.config import (
    BRAND_SAFETY_MODEL,
    CLAUDE_MODEL,
    GEMINI_MODEL,
    TREND_TAG_MODEL,
    TRENDSPARK_MODEL,
)

logger = logging.getLogger(__name__)

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

# Sarvam STT has no per-token/length usage payload (flat per-call estimate
# per §1) -- not keyed by model id, used directly by routes/voice.py's STT
# (transcribe) call site via estimate_sarvam_flat_cost_usd() below. STT stays
# flat (no length signal to scale off -- audio duration isn't the same as
# transcript char count, and Sarvam doesn't return one anyway).
SARVAM_FLAT_COST_PER_CALL = Decimal("0.006")

# Sarvam TTS DOES have a length signal (the char count we send it), so it's
# char-scaled instead of flat -- P2 fix per Ash's AI review (wiki/ai-review/
# partial-fixes-batch-ai-review.md): the flat $0.006 under-bills a
# max-length (TTS_MAX_CHARS=200, see routes/voice.py) call by ~20% against
# the published Rs.30/10k-chars rate that same route's docstring already
# quotes (Rs.0.60 for 200 chars).
#
# Rs.30 per 10,000 chars, converted to USD at Rs.83/$1 (same FX reference
# Rohan's cost-review docs use elsewhere in this repo; no other FX constant
# exists yet in this module to reuse):
#   USD_PER_10K_CHARS = 30 / 83 = 0.36144... ~= 0.3614
SARVAM_TTS_INR_PER_10K_CHARS = Decimal("30")
SARVAM_USD_PER_INR = Decimal("1") / Decimal("83")
SARVAM_TTS_USD_PER_10K_CHARS = (SARVAM_TTS_INR_PER_10K_CHARS * SARVAM_USD_PER_INR).quantize(
    Decimal("0.0001")
)


def estimate_sarvam_flat_cost_usd() -> Decimal:
    """Flat per-call USD cost for a Sarvam STT call. Unlike
    `estimate_cost_usd`, this takes no `usage` dict -- Sarvam's API doesn't
    return per-token usage, so every completed STT call is billed the same
    flat `SARVAM_FLAT_COST_PER_CALL` regardless of audio length. Never raises
    (the rate is a module constant, not a lookup that can miss).

    TTS calls should use `estimate_sarvam_tts_cost_usd` instead (char-scaled,
    see that function's docstring for why STT and TTS diverge here).
    """
    return SARVAM_FLAT_COST_PER_CALL


def estimate_sarvam_tts_cost_usd(char_count: int) -> Decimal:
    """Char-scaled USD cost for a Sarvam TTS call, using the published
    Rs.30/10,000-chars rate (`SARVAM_TTS_USD_PER_10K_CHARS`). Replaces the
    flat `SARVAM_FLAT_COST_PER_CALL` for TTS specifically: TTS text length is
    known before the call (routes/voice.py truncates to `TTS_MAX_CHARS`
    first), so there's no reason to under/over-bill a fixed number when the
    real length signal is right there -- unlike STT, which has no such
    signal (see `estimate_sarvam_flat_cost_usd`).

    `char_count` should be the length of the text actually sent to Sarvam's
    TTS endpoint (i.e. AFTER `_truncate_for_tts`, not the original reply
    length) -- that's what Sarvam bills for. Never raises: a non-positive
    `char_count` returns `Decimal("0")` rather than a negative/nonsensical
    cost.
    """
    if char_count <= 0:
        return Decimal("0")
    return (Decimal(char_count) / Decimal("10000")) * SARVAM_TTS_USD_PER_10K_CHARS


def _resolve_rate(model: str) -> ModelRate:
    """Looks up `model`'s `ModelRate`, with two deliberate fallbacks, each
    scoped to one specific env-overridable model constant -- never a general
    "unknown model -> guess" behavior:

    1. `model` equals the CONFIGURED `TREND_TAG_MODEL` but isn't itself a
       `PRICING_TABLE` key -> fall back to `TRENDSPARK_MODEL`'s (Haiku-class)
       row and log a warning.
    2. `model` equals the CONFIGURED `BRAND_SAFETY_MODEL` but isn't itself a
       `PRICING_TABLE` key -> fall back to `CLAUDE_MODEL`'s (Sonnet) row and
       log a warning.

    Why fallback 1 exists: `app/config.py` defaults `TREND_TAG_MODEL` to the
    exact `TRENDSPARK_MODEL` string, so ordinarily they share ONE row above
    and this branch never triggers. But `TREND_TAG_MODEL` is independently
    env-overridable (for "an independent bump" per its config.py comment) --
    override it to a distinct model id and, before this fallback existed,
    every trend_tag.py call raised `ValueError` here, which that route only
    logs (`ai_spend_pricing_error`) and swallows, so spend went silently
    UNRECORDED instead of merely mis-priced.

    Why fallback 2 exists: mirrors fallback 1 exactly, for `BRAND_SAFETY_MODEL`
    (app/config.py defaults it to `CLAUDE_MODEL`'s exact string, so this also
    never triggers in the default configuration -- it only matters if
    `BRAND_SAFETY_MODEL` is overridden, e.g. to the future Haiku-class GARM
    model, before that model id has its own `PRICING_TABLE` row).
    brand_safety.py's spend recording (routes/brand_safety.py) has the same
    bare `except ValueError`-swallows-and-only-logs shape as trend_tag.py, so
    the same "same-cost-class estimate beats silently unrecorded spend"
    reasoning applies.

    Any other unpriced model still raises.
    """
    rate = PRICING_TABLE.get(model)
    if rate is not None:
        return rate

    if model == TREND_TAG_MODEL and TRENDSPARK_MODEL in PRICING_TABLE:
        logger.warning(
            "pricing: TREND_TAG_MODEL=%r has no PRICING_TABLE row -- falling back to "
            "TRENDSPARK_MODEL=%r's rate so spend is still recorded; add %r to "
            "PRICING_TABLE to price it correctly",
            model, TRENDSPARK_MODEL, model,
        )
        return PRICING_TABLE[TRENDSPARK_MODEL]

    if model == BRAND_SAFETY_MODEL and CLAUDE_MODEL in PRICING_TABLE:
        logger.warning(
            "pricing: BRAND_SAFETY_MODEL=%r has no PRICING_TABLE row -- falling back to "
            "CLAUDE_MODEL=%r's rate so spend is still recorded; add %r to "
            "PRICING_TABLE to price it correctly",
            model, CLAUDE_MODEL, model,
        )
        return PRICING_TABLE[CLAUDE_MODEL]

    raise ValueError(f"no pricing entry for model {model!r} -- add it to PRICING_TABLE first")


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
    $0 spend for a model that may cost real money. The ONE exception is
    `TREND_TAG_MODEL` (see `_resolve_rate` below): it defaults to sharing
    `TRENDSPARK_MODEL`'s row, so an env override that points it at a distinct,
    unpriced id falls back to that same Haiku-class rate (logged loudly)
    instead of raising -- trend_tag.py's ai_spend recording is best-effort
    (a bare `except ValueError` that only logs, per app/routes/trend_tag.py),
    so a raise there means spend silently goes UNRECORDED, which is worse
    than a same-cost-class estimate.
    """
    rate = _resolve_rate(model)

    if not usage:
        return Decimal("0")

    input_tokens = usage.get("input_tokens") or 0
    output_tokens = usage.get("output_tokens") or 0

    cost = (Decimal(int(input_tokens)) * rate.input_per_token) + (
        Decimal(int(output_tokens)) * rate.output_per_token
    )
    return cost
