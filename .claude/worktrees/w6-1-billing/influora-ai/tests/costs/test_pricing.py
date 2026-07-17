"""P2-17 §3.1 -- pricing table + estimate_cost_usd correctness."""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.config import CLAUDE_MODEL, GEMINI_MODEL
from app.costs.pricing import estimate_cost_usd


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
