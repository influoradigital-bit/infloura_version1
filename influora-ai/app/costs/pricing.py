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
    CREATOR_COPILOT_MODEL,
    GEMINI_MODEL,
    TREND_TAG_MODEL,
    TRENDSPARK_MODEL,
)

logger = logging.getLogger(__name__)

_MTOK = Decimal(1000000)


class ModelRate(NamedTuple):
    """Dollars per token, already divided down from the per-MTok list price.

    F-01: `cache_read_per_token` and `cache_write_per_token` exist because the
    Anthropic API reports `cache_read_input_tokens` and
    `cache_creation_input_tokens` as fields DISJOINT from `input_tokens` — a
    cached prefix is not counted twice. `estimate_cost_usd` read only
    `input_tokens`/`output_tokens`, so every cached token was billed at $0 while
    `providers/claude.py` was already collecting both cache fields and handing
    them over. Prompt caching is the stated cost lever, so the error was largest
    exactly where volume is highest.

    Anthropic's published multipliers on the base input rate: a cache WRITE
    (5-minute TTL) costs 1.25x, a cache READ costs 0.1x.
    """

    input_per_token: Decimal
    output_per_token: Decimal
    cache_read_per_token: Decimal
    cache_write_per_token: Decimal


# Anthropic prompt-caching multipliers on the base input rate.
CACHE_WRITE_MULTIPLIER = Decimal("1.25")
CACHE_READ_MULTIPLIER = Decimal("0.1")


def _per_mtok(
    input_usd: str,
    output_usd: str,
    *,
    cache_read_usd: str | None = None,
    cache_write_usd: str | None = None,
) -> ModelRate:
    """Build a ModelRate from per-MTok list prices.

    Cache rates default to the published multipliers on the input rate; pass
    them explicitly for a provider that prices caching differently (Gemini's
    implicit caching is not billed on this path at all, so its cache rates are
    set to the same input rate — never zero, which is what F-01 was).
    """
    input_rate = Decimal(input_usd) / _MTOK
    output_rate = Decimal(output_usd) / _MTOK
    read_rate = (
        Decimal(cache_read_usd) / _MTOK if cache_read_usd is not None
        else input_rate * CACHE_READ_MULTIPLIER
    )
    write_rate = (
        Decimal(cache_write_usd) / _MTOK if cache_write_usd is not None
        else input_rate * CACHE_WRITE_MULTIPLIER
    )
    return ModelRate(input_rate, output_rate, read_rate, write_rate)


# model_id -> (input $/token, output $/token). Keyed by the exact model id
# strings the providers are pinned to in app/config.py -- never a family/alias
# name -- so a model bump that isn't also priced here fails loud (see
# estimate_cost_usd's ValueError below) instead of silently under-billing.
# F-04: this table used to be keyed by the CONSTANTS `CLAUDE_MODEL` /
# `TRENDSPARK_MODEL`, which are themselves `os.getenv(...)`. The comment above
# claimed "a model bump that isn't also priced here fails loud" — false for
# precisely the three values that can change without a code edit, because the
# key moved with the override and the lookup always hit. Setting
# `CLAUDE_MODEL=claude-opus-4-1` priced every chat turn and brand-safety call at
# Sonnet's $3/$15 instead of Opus's $15/$75: a silent 5x under-bill, so a $15
# ceiling authorized roughly $75/day of real spend.
#
# The table is now keyed by LITERAL model ids. An env override to an unpriced id
# misses the table and raises, which is what the comment always promised.
PRICING_TABLE: dict[str, ModelRate] = {
    # Anthropic (list prices per MTok, verified 2026-07-11/12).
    "claude-sonnet-4-5-20250929": _per_mtok("3.00", "15.00"),
    "claude-haiku-4-5-20251001": _per_mtok("1.00", "5.00"),
    "claude-opus-4-1-20250805": _per_mtok("15.00", "75.00"),
    "claude-3-5-haiku-20241022": _per_mtok("0.80", "4.00"),
    # Google (no billed prompt-cache path on this integration — cache rates are
    # set to the input rate rather than zero, so an unexpected cache field can
    # never be billed at $0 the way F-01 billed Anthropic's).
    "gemini-2.5-flash": _per_mtok(
        "0.30", "2.50", cache_read_usd="0.30", cache_write_usd="0.30"
    ),
}

# Sanity: the pinned defaults must all be priced. This runs at import time, so a
# config.py default that is not in the table above fails at boot, not on the
# first billed call.
for _pinned in (CLAUDE_MODEL, GEMINI_MODEL, TRENDSPARK_MODEL):
    if _pinned not in PRICING_TABLE:
        logger.warning(
            "pricing: pinned model %r has no PRICING_TABLE row -- every call to it will "
            "raise ValueError at billing time until a rate is added",
            _pinned,
        )

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
SARVAM_TTS_INR_PER_10K_CHARS = Decimal(30)
SARVAM_USD_PER_INR = Decimal(1) / Decimal(83)
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
        return Decimal(0)
    return (Decimal(char_count) / Decimal(10000)) * SARVAM_TTS_USD_PER_10K_CHARS


def _most_expensive_rate() -> ModelRate:
    """The costliest row in the table, per token of output (the dominant term)."""
    return max(PRICING_TABLE.values(), key=lambda r: (r.output_per_token, r.input_per_token))


def _resolve_rate(model: str) -> ModelRate:
    """Look up `model`'s `ModelRate`.

    F-04 (round 2, Priya sign-off review). The env-overridable constants
    `TREND_TAG_MODEL`, `BRAND_SAFETY_MODEL` and `CREATOR_COPILOT_MODEL` each fell
    back to a FIXED row when the configured id was not itself a PRICING_TABLE
    key. Keying the table by literal ids closed the `CLAUDE_MODEL=claude-opus-4-1`
    path, but those three fallbacks left the same 5x under-bill reachable:
    `BRAND_SAFETY_MODEL=claude-opus-4-1` priced at Sonnet's $3/$15 instead of
    Opus's $15/$75, warning-logged rather than raised.

    Two failure directions, and they are not symmetric:

    - Under-billing inflates the effective ceiling. A $15/day cap that prices
      Opus at Sonnet's rate authorizes ~$75/day of real spend. This is the
      failure F-04 names and it must be impossible.
    - Not recording at all is also bad: `routes/trend_tag.py` records spend
      best-effort inside `except ValueError`, so a raise there means the spend
      silently never lands in the counter.

    So an unpriced-but-CONFIGURED model falls back to the MOST EXPENSIVE row in
    the table, never to a same-class or cheaper one. Spend is always recorded,
    and the only possible error is an over-estimate, which makes the ceiling
    stricter rather than looser. An unpriced model that is not one of the three
    configured overrides still raises — that is a code change nobody priced, and
    it should fail loud in CI.
    """
    rate = PRICING_TABLE.get(model)
    if rate is not None:
        return rate

    for label, configured in (
        ("TREND_TAG_MODEL", TREND_TAG_MODEL),
        ("BRAND_SAFETY_MODEL", BRAND_SAFETY_MODEL),
        ("CREATOR_COPILOT_MODEL", CREATOR_COPILOT_MODEL),
    ):
        if model == configured:
            conservative = _most_expensive_rate()
            logger.warning(
                "pricing: %s=%r has no PRICING_TABLE row -- billing it at the most "
                "EXPENSIVE known rate ($%s/$%s per MTok) so spend is recorded and can "
                "only be over-estimated, never under-billed (F-04). Add %r to "
                "PRICING_TABLE with its real rate.",
                label, model,
                conservative.input_per_token * _MTOK,
                conservative.output_per_token * _MTOK,
                model,
            )
            return conservative

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
    $0 spend for a model that may cost real money.

    The exception is the three env-overridable constants `TREND_TAG_MODEL`,
    `BRAND_SAFETY_MODEL` and `CREATOR_COPILOT_MODEL`: their `ai_spend` recording
    is best-effort (a bare `except ValueError` that only logs), so a raise there
    means the spend silently goes UNRECORDED. Those fall back to the MOST
    EXPENSIVE row in the table -- never a same-class or cheaper one, which is
    how F-04's 5x under-bill hid. See `_resolve_rate`.
    """
    rate = _resolve_rate(model)

    if not usage:
        return Decimal(0)

    input_tokens = usage.get("input_tokens") or 0
    output_tokens = usage.get("output_tokens") or 0

    # F-01: cache tokens are DISJOINT from input_tokens in the Anthropic usage
    # payload — reading only input/output billed an 8k-token cached prefix at
    # $0. Worked example from the audit: 8,000 cached + 200 fresh input + 300
    # output on Sonnet is $0.0351 on a cache miss (write) and $0.0071 on a hit;
    # the old formula recorded $0.0051 for both. Both spellings of each field
    # are accepted because providers/claude.py normalizes some and passes
    # others through verbatim.
    cache_read_tokens = (
        usage.get("cache_read_input_tokens")
        or usage.get("cache_read_tokens")
        or 0
    )
    cache_write_tokens = (
        usage.get("cache_creation_input_tokens")
        or usage.get("cache_write_tokens")
        or usage.get("cache_creation_tokens")
        or 0
    )

    cost = (
        (Decimal(int(input_tokens)) * rate.input_per_token)
        + (Decimal(int(output_tokens)) * rate.output_per_token)
        + (Decimal(int(cache_read_tokens)) * rate.cache_read_per_token)
        + (Decimal(int(cache_write_tokens)) * rate.cache_write_per_token)
    )
    return cost
