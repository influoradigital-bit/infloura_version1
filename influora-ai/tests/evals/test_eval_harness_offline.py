"""CI wrapper for the golden-set eval harness (evals/run_eval.py).

Runs every dataset in OFFLINE mode against the committed fixtures and asserts
the scorers + runner work end-to-end. This is what CI runs on every change to
a prompt, model pin, or the harness itself; the live Sonnet-vs-Haiku A/B is a
manual, keyed procedure (evals/README.md) and is deliberately NOT here.

No network, no API keys, no app.config import — pure stdlib + fixtures.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SERVICE_ROOT = Path(__file__).resolve().parents[2]  # influora-ai/
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from evals.run_eval import (  # noqa: E402
    FEATURES,
    EvalReport,
    load_dataset,
    make_offline_caller,
    run_dataset,
    score_brand_safety,
    score_trend_tag,
)
from evals.scorers import bucketize, set_overlap_f1, tone_bucket_score  # noqa: E402

ALL_DATASETS = sorted(FEATURES)


# ---------------------------------------------------------------------------
# End-to-end: every dataset must run green offline out of the box.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("dataset", ALL_DATASETS)
def test_offline_run_is_green(dataset: str):
    report = run_dataset(dataset, make_offline_caller(dataset))
    assert isinstance(report, EvalReport)
    assert not report.missing_fixtures, (
        f"{dataset}: missing fixtures {report.missing_fixtures} — a golden case's "
        "input changed without re-recording (--live --record), or a fixture was deleted"
    )
    assert not report.errors, f"{dataset}: case errors {report.errors}"
    assert report.passed, f"{dataset}: thresholds failed: {report.failures}"


@pytest.mark.parametrize("dataset", ALL_DATASETS)
def test_dataset_has_enough_cases_and_unique_ids(dataset: str):
    cases = load_dataset(dataset)
    assert len(cases) >= 10, f"{dataset}: golden set must hold at least 10 cases"
    ids = [c["id"] for c in cases]
    assert len(ids) == len(set(ids)), f"{dataset}: duplicate case ids"


def test_every_case_was_scored_not_silently_skipped():
    for dataset in ALL_DATASETS:
        report = run_dataset(dataset, make_offline_caller(dataset))
        assert len(report.case_scores) == len(load_dataset(dataset)), dataset


# ---------------------------------------------------------------------------
# The harness must be able to FAIL — a quality gate that can't go red is
# decoration. Feed it a deliberately bad model and assert red.
# ---------------------------------------------------------------------------


def test_brand_safety_all_floor_model_fails_the_gate():
    """A model that scores everything floor (e.g. one that obeyed the
    bs-012 injection caption) must trip the unsafe->safe hard veto."""
    from app.tools.schemas import GARM_CATEGORIES

    def lobotomized_caller(case_input):
        return {
            "content_id": "eval-item-1",
            "garm_flags": [
                {"category": c, "risk": "floor", "rationale": "no concern"}
                for c in GARM_CATEGORIES
            ],
            "content_sentiment": "neutral",
            "sentiment_score": 0.0,
            "brand_safety_score": 100.0,
            "overall_rationale": "all clear",
        }

    report = run_dataset("brand_safety_garm", lobotomized_caller)
    assert not report.passed
    assert report.aggregate["unsafe_to_safe_misses"] > 0
    assert any("hard veto" in f for f in report.failures)


def test_brand_safety_malformed_output_scores_zero_and_fails():
    """Missing categories == malformed by the route's own contract."""
    scores = score_brand_safety(
        {"unsafe": True, "flagged_categories": ["terrorism"]},
        {"garm_flags": [{"category": "terrorism", "risk": "high", "rationale": "x"}]},
    )
    assert scores["malformed"] == 1.0
    assert scores["category_f1"] == 0.0
    assert scores["unsafe_to_safe_miss"] == 1.0


def test_trend_tag_off_vocab_themes_are_dropped_by_real_validator():
    """The scorer routes raw output through app.prompt.trend_tag.parse_and_
    validate — invented themes never earn credit, and all-invented output
    becomes a drop."""
    scores = score_trend_tag(
        {"themes": ["pride", "victory"], "campaign_type": "PRIDE"},
        {"themes": ["blockbuster", "vibes"], "campaign_type": "PRIDE"},
    )
    assert scores["theme_f1"] == 0.0
    assert scores["campaign_acc"] == 0.0  # dropped => predicted None != PRIDE
    assert scores["drop_agreement"] == 0.0


def test_trend_tag_expected_drop_agreement():
    scores = score_trend_tag(
        {"themes": [], "campaign_type": None},
        {"themes": [], "campaign_type": "EDUCATIONAL"},  # validator -> drop
    )
    assert scores == {"theme_f1": 1.0, "campaign_acc": 1.0, "drop_agreement": 1.0}


def test_template_recommendation_always_wrong_model_fails_the_gate():
    """A model that always recommends the same (wrong) template must trip the
    0.80 name-accuracy threshold — proves the eval gate can actually go red,
    same as the brand_safety/trend_tag checks above."""

    def stubborn_caller(case_input):
        return {"template_name": "Brand Awareness", "campaign_type": "HYPE", "budget_band": "₹10,000–₹50,000"}

    report = run_dataset("template_recommendation", stubborn_caller)
    assert not report.passed
    assert report.aggregate["name_acc"] < 0.80
    assert any("template-name accuracy" in f for f in report.failures)


def test_template_recommendation_off_catalog_name_scores_malformed():
    from evals.run_eval import score_template_recommendation

    scores = score_template_recommendation(
        {"template_name": "Brand Awareness", "campaign_type": "HYPE", "budget_band": "₹10,000–₹50,000"},
        {"template_name": "Made Up Template", "campaign_type": "HYPE", "budget_band": "₹10,000–₹50,000"},
    )
    assert scores["malformed"] == 1.0
    assert scores["name_acc"] == 0.0


# ---------------------------------------------------------------------------
# Scorer unit checks.
# ---------------------------------------------------------------------------


def test_set_overlap_f1_edges():
    assert set_overlap_f1([], []).f1 == 1.0  # both empty == correct "nothing"
    assert set_overlap_f1(["a"], []).f1 == 0.0
    assert set_overlap_f1([], ["a"]).f1 == 0.0
    r = set_overlap_f1(["A", "b"], ["a"])  # case-insensitive, partial
    assert r.recall == 1.0 and r.precision == 0.5 and abs(r.f1 - 2 / 3) < 1e-9


def test_bucketize_terciles():
    assert bucketize(0.0) == "low"
    assert bucketize(0.5) == "med"
    assert bucketize(0.9) == "high"
    assert bucketize(None) == "unknown"


def test_tone_bucket_score_only_scores_asserted_fields():
    dial = {"formality": 0.5, "energy": 0.9, "emoji_ok": True}
    assert tone_bucket_score(dial, {"formality": "med"}) == 1.0
    assert tone_bucket_score(dial, {"formality": "low", "emoji_ok": True}) == 0.5
    assert tone_bucket_score(dial, {}) == 1.0
