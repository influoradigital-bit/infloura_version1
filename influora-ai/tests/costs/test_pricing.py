"""P2-17 §3.1 -- pricing table + estimate_cost_usd correctness."""

from __future__ import annotations

import logging
from decimal import Decimal

import pytest

from app.config import BRAND_SAFETY_MODEL, CLAUDE_MODEL, GEMINI_MODEL, TRENDSPARK_MODEL
from app.costs import pricing
from app.costs.pricing import (
    estimate_cost_usd,
    estimate_sarvam_flat_cost_usd,
    estimate_sarvam_tts_cost_usd,
)


def test_claude_cost_matches_3_and_15_per_mtok():
    # 1,000,000 input tokens @ $3/MTok + 1,000,000 output tokens @ $15/MTok = $18.00
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}
    assert estimate_cost_usd(CLAUDE_MODEL, usage) == Decimal("18.00")


def test_gemini_cost_matches_point30_and_2point50_per_mtok():
    # gemini-2.5-flash official rate: $0.30/MTok input + $2.50/MTok output
    # (verified against ai.google.dev pricing, 2026-07). 1M in + 1M out = $2.80.
    # Was $0.10/$0.40 = $0.50 under the retired gemini-2.5-flash-lite; the
    # PRICING_TABLE moved to the flash rate but this assertion lagged behind.
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}
    assert estimate_cost_usd(GEMINI_MODEL, usage) == Decimal("2.80")


def test_small_token_counts_are_not_rounded_to_zero():
    # 1000 input tokens of Claude @ $3/MTok = $0.003 -- must not truncate to 0.
    usage = {"input_tokens": 1000, "output_tokens": 0}
    cost = estimate_cost_usd(CLAUDE_MODEL, usage)
    assert cost == Decimal(3) / Decimal(1000)


def test_missing_usage_returns_zero():
    assert estimate_cost_usd(CLAUDE_MODEL, None) == Decimal(0)
    assert estimate_cost_usd(CLAUDE_MODEL, {}) == Decimal(0)


def test_unpriced_model_raises_value_error():
    with pytest.raises(ValueError):
        estimate_cost_usd("some-unpriced-model-id", {"input_tokens": 10, "output_tokens": 10})


def test_sarvam_flat_cost_matches_the_published_constant():
    assert estimate_sarvam_flat_cost_usd() == pricing.SARVAM_FLAT_COST_PER_CALL


# ---------------------------------------------------------------------------
# TREND_TAG_MODEL fallback (H-25 follow-up): TREND_TAG_MODEL defaults to the
# exact TRENDSPARK_MODEL string in app/config.py, so ordinarily they share one
# PRICING_TABLE row and this fallback never triggers. But TREND_TAG_MODEL is
# independently env-overridable -- these tests patch the pricing module's own
# TREND_TAG_MODEL binding (rather than reloading app.config, which is a
# module-level constant fixed at import time) to simulate that override
# without disturbing any other test's config state.
# ---------------------------------------------------------------------------


def test_trend_tag_model_override_falls_back_to_trendspark_rate(monkeypatch):
    monkeypatch.setattr(pricing, "TREND_TAG_MODEL", "some-distinct-trend-tag-model-id")
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}

    cost = pricing.estimate_cost_usd("some-distinct-trend-tag-model-id", usage)

    # F-04 (round 2): the fallback used to be TRENDSPARK_MODEL's own Haiku-class
    # rate ($1/$5 per MTok = $6.00 here). That is a SAME-OR-CHEAPER row, which is
    # exactly how a 5x under-bill hides: point the override at Opus and it bills
    # at Haiku. Spend must still be RECORDED (trend_tag.py swallows ValueError,
    # so raising would silently lose it), so the fallback is now the most
    # EXPENSIVE known rate — over-estimating makes the ceiling stricter, never
    # looser.
    assert cost == estimate_cost_usd("claude-opus-4-1-20250805", usage)
    assert cost > estimate_cost_usd(TRENDSPARK_MODEL, usage)


def test_trend_tag_model_override_fallback_logs_a_warning(monkeypatch, caplog):
    monkeypatch.setattr(pricing, "TREND_TAG_MODEL", "some-distinct-trend-tag-model-id")
    with caplog.at_level(logging.WARNING, logger="app.costs.pricing"):
        pricing.estimate_cost_usd("some-distinct-trend-tag-model-id", {"input_tokens": 1, "output_tokens": 1})
    assert any("some-distinct-trend-tag-model-id" in record.message for record in caplog.records)


def test_unrelated_unpriced_model_still_raises_even_with_trend_tag_override(monkeypatch):
    """The fallback is scoped to TREND_TAG_MODEL specifically -- a genuinely
    unrelated unpriced model must still raise, even while TREND_TAG_MODEL is
    overridden to some other unpriced id."""
    monkeypatch.setattr(pricing, "TREND_TAG_MODEL", "some-distinct-trend-tag-model-id")
    with pytest.raises(ValueError):
        pricing.estimate_cost_usd("totally-different-unpriced-model", {"input_tokens": 1, "output_tokens": 1})


def test_trend_tag_model_default_already_shares_trendspark_row():
    """Sanity check on the DEFAULT (unoverridden) config: TREND_TAG_MODEL
    equals TRENDSPARK_MODEL out of the box, per app/config.py, so the normal
    path never needs the fallback at all."""
    from app.config import TREND_TAG_MODEL

    assert TREND_TAG_MODEL == TRENDSPARK_MODEL
    assert TREND_TAG_MODEL in pricing.PRICING_TABLE


# ---------------------------------------------------------------------------
# BRAND_SAFETY_MODEL fallback (Ash AI review P1 #2 / P2 pricing fix): mirrors
# the TREND_TAG_MODEL fallback above exactly. BRAND_SAFETY_MODEL defaults to
# the exact CLAUDE_MODEL string in app/config.py, so ordinarily they share
# CLAUDE_MODEL's PRICING_TABLE row and this fallback never triggers -- it
# only matters once BRAND_SAFETY_MODEL is overridden (e.g. to a future
# Haiku-class GARM model) to an id that doesn't have its own row yet.
# ---------------------------------------------------------------------------


def test_brand_safety_model_default_already_shares_claude_model_row():
    """Sanity check on the DEFAULT (unoverridden) config: BRAND_SAFETY_MODEL
    equals CLAUDE_MODEL out of the box (deliberately Sonnet, per
    app/config.py's BRAND_SAFETY_MODEL docstring), so the normal path never
    needs the fallback at all."""
    assert BRAND_SAFETY_MODEL == CLAUDE_MODEL
    assert BRAND_SAFETY_MODEL in pricing.PRICING_TABLE


def test_brand_safety_model_override_falls_back_to_claude_model_rate(monkeypatch):
    monkeypatch.setattr(pricing, "BRAND_SAFETY_MODEL", "some-distinct-brand-safety-model-id")
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}

    cost = pricing.estimate_cost_usd("some-distinct-brand-safety-model-id", usage)

    # F-04 (round 2): was CLAUDE_MODEL's Sonnet rate ($3/$15 = $18.00). Setting
    # BRAND_SAFETY_MODEL=claude-opus-4-1 then billed $18 instead of $90 — the
    # identical 5x under-bill F-04 describes, warning-logged instead of raised.
    # The fallback is now the most EXPENSIVE known rate: spend is still recorded
    # (brand_safety.py swallows ValueError), and the only possible error is an
    # over-estimate, which makes the ceiling stricter rather than looser.
    assert cost == estimate_cost_usd("claude-opus-4-1-20250805", usage)
    assert cost > Decimal("18.00")
    assert cost > estimate_cost_usd(CLAUDE_MODEL, usage)


def test_brand_safety_model_override_fallback_logs_a_warning(monkeypatch, caplog):
    monkeypatch.setattr(pricing, "BRAND_SAFETY_MODEL", "some-distinct-brand-safety-model-id")
    with caplog.at_level(logging.WARNING, logger="app.costs.pricing"):
        pricing.estimate_cost_usd(
            "some-distinct-brand-safety-model-id", {"input_tokens": 1, "output_tokens": 1}
        )
    assert any(
        "some-distinct-brand-safety-model-id" in record.message for record in caplog.records
    )


def test_unrelated_unpriced_model_still_raises_even_with_brand_safety_override(monkeypatch):
    """The fallback is scoped to BRAND_SAFETY_MODEL specifically -- a
    genuinely unrelated unpriced model must still raise, even while
    BRAND_SAFETY_MODEL is overridden to some other unpriced id."""
    monkeypatch.setattr(pricing, "BRAND_SAFETY_MODEL", "some-distinct-brand-safety-model-id")
    with pytest.raises(ValueError):
        pricing.estimate_cost_usd("totally-different-unpriced-model", {"input_tokens": 1, "output_tokens": 1})


# ---------------------------------------------------------------------------
# Sarvam TTS char-scaled cost (Ash AI review P2): TTS is billed per-char at
# the published Rs.30/10k-chars rate, unlike STT which stays flat (no length
# signal). See app/costs/pricing.py's SARVAM_TTS_USD_PER_10K_CHARS docstring
# for the Rs.-to-USD conversion this is built on.
# ---------------------------------------------------------------------------


def test_sarvam_tts_cost_scales_with_char_count():
    # 10,000 chars should cost exactly one SARVAM_TTS_USD_PER_10K_CHARS unit.
    assert estimate_sarvam_tts_cost_usd(10_000) == pricing.SARVAM_TTS_USD_PER_10K_CHARS
    # Half the chars, half the cost.
    assert estimate_sarvam_tts_cost_usd(5_000) == pricing.SARVAM_TTS_USD_PER_10K_CHARS / 2


def test_sarvam_tts_cost_at_200_chars_matches_voice_py_docstrings_estimate():
    # routes/voice.py's module docstring prices a max-length (TTS_MAX_CHARS=200)
    # TTS call at ~Rs.0.60 =~ $0.0072 -- pin that this estimator lands there,
    # not at the old flat $0.006 (a ~20% under-bill per Ash's AI review).
    cost = estimate_sarvam_tts_cost_usd(200)
    assert cost > pricing.SARVAM_FLAT_COST_PER_CALL
    assert abs(cost - Decimal("0.0072")) < Decimal("0.0001")


def test_sarvam_tts_cost_zero_chars_is_zero():
    assert estimate_sarvam_tts_cost_usd(0) == Decimal(0)


def test_sarvam_tts_cost_never_negative_for_bad_input():
    assert estimate_sarvam_tts_cost_usd(-5) == Decimal(0)
