"""P2-17 §3.1 -- pricing table + estimate_cost_usd correctness."""

from __future__ import annotations

import logging
from decimal import Decimal

import pytest

from app.config import CLAUDE_MODEL, GEMINI_MODEL, TRENDSPARK_MODEL
from app.costs import pricing
from app.costs.pricing import estimate_cost_usd, estimate_sarvam_flat_cost_usd


def test_claude_cost_matches_3_and_15_per_mtok():
    # 1,000,000 input tokens @ $3/MTok + 1,000,000 output tokens @ $15/MTok = $18.00
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}
    assert estimate_cost_usd(CLAUDE_MODEL, usage) == Decimal("18.00")


def test_gemini_cost_matches_point10_and_point40_per_mtok():
    usage = {"input_tokens": 1_000_000, "output_tokens": 1_000_000}
    assert estimate_cost_usd(GEMINI_MODEL, usage) == Decimal("0.50")


def test_small_token_counts_are_not_rounded_to_zero():
    # 1000 input tokens of Claude @ $3/MTok = $0.003 -- must not truncate to 0.
    usage = {"input_tokens": 1000, "output_tokens": 0}
    cost = estimate_cost_usd(CLAUDE_MODEL, usage)
    assert cost == Decimal("3") / Decimal("1000")


def test_missing_usage_returns_zero():
    assert estimate_cost_usd(CLAUDE_MODEL, None) == Decimal("0")
    assert estimate_cost_usd(CLAUDE_MODEL, {}) == Decimal("0")


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

    # TRENDSPARK_MODEL's rate ($1/$5 per MTok) applied to 1M/1M tokens = $6.00 --
    # spend is recorded (at the Haiku-class rate) instead of raising and being
    # silently swallowed by trend_tag.py's bare `except ValueError`.
    assert cost == Decimal("6.00")
    assert cost == estimate_cost_usd(TRENDSPARK_MODEL, usage)


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
