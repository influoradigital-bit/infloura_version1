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
    DATASETS_DIR,
    FEATURES,
    FIXTURES_DIR,
    EvalReport,
    aggregate_campaign_performance,
    aggregate_outcome_recommendation,
    load_dataset,
    make_offline_caller,
    run_dataset,
    score_brand_safety,
    score_campaign_performance,
    score_outcome_recommendation,
    score_trend_tag,
)
from evals.scorers import (  # noqa: E402
    bucketize,
    canonical_number,
    extract_figures,
    provenance_exact_match,
    set_overlap_f1,
    tone_bucket_score,
)

ALL_DATASETS = sorted(FEATURES)

# Phase-2 provenance datasets. Vikram's golden CASES are committed
# (outcome_recommendation=15, campaign_performance=10); the recorded FIXTURES
# (well-behaved model responses / executor outputs) are the remaining artifact,
# produced by `--live --record` (outcome) or the Spring integration test
# (performance). Until those fixtures land in the same PR, skip the end-to-end
# offline RUN for these two rather than erroring on a missing fixture — the
# scorer unit tests below still fully exercise the scoring logic. A missing
# fixture for any OTHER (already-shipped) feature must still fail loudly, so the
# skip is scoped to exactly these two names.
PENDING_DATASETS = {"outcome_recommendation", "campaign_performance"}


def _dataset_present(name: str) -> bool:
    return (DATASETS_DIR / f"{name}.jsonl").exists()


def _fixtures_present(name: str) -> bool:
    fixture_dir = FIXTURES_DIR / name
    return fixture_dir.is_dir() and any(fixture_dir.glob("*.json"))


def _skip_if_fixtures_pending(dataset: str) -> None:
    if dataset in PENDING_DATASETS and not _fixtures_present(dataset):
        pytest.skip(
            f"{dataset}: golden cases committed but fixtures not yet recorded "
            "(Vikram/Kavya QA-1/QA-2) — scorer wired, offline run pending fixtures"
        )


# ---------------------------------------------------------------------------
# End-to-end: every dataset must run green offline out of the box.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("dataset", ALL_DATASETS)
def test_offline_run_is_green(dataset: str):
    _skip_if_fixtures_pending(dataset)
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
        if dataset in PENDING_DATASETS and not _fixtures_present(dataset):
            continue
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


# ---------------------------------------------------------------------------
# provenance_exact_match (Phase-2 moat scorer, build plan §2.5). These run
# without the pending datasets/fixtures — they exercise the scorer directly and,
# per the "a gate that can't go red is decoration" principle, prove it fails on
# an orphaned/hallucinated number and on a cross-party/PII leak.
# ---------------------------------------------------------------------------


def test_canonical_number_units_and_grouping():
    assert canonical_number("10,000") == "10000"
    assert canonical_number("2,50,000") == "250000"  # Indian grouping
    assert canonical_number("2.50") == "2.5"
    assert canonical_number("₹45000") == "45000"
    assert canonical_number("2.5", "L") == "250000"  # ₹2.5L == 250000
    assert canonical_number("1.2", "cr") == "12000000"
    assert canonical_number("15", "k") == "15000"
    assert canonical_number("45", "%") == "45"  # unit marker, not a multiplier
    assert canonical_number("not-a-number") is None


def test_extract_figures_skips_bare_counts_keeps_markers_and_magnitudes():
    figs = extract_figures("Spent ₹10,000 across 3 creators for a 2.5x ROI, 45% reply rate.")
    assert "10000" in figs and "2.5" in figs and "45" in figs
    assert "3" not in figs  # a bare 1-2 digit count is prose, not a quoted figure


def test_provenance_pass_when_every_figure_is_sourced():
    result = provenance_exact_match(
        "Your campaign spent ₹10,000 and drove ₹45,000 — about 4.5x return.",
        allowed=["10000", "45000", "4.5"],  # 4.5 = declared deterministic calc (rev/spend)
    )
    assert result.ok and result.orphans == ()


def test_provenance_fails_on_orphaned_number():
    result = provenance_exact_match(
        "Expect around ₹90,000 in revenue next month.",  # 90000 sourced by nothing
        allowed=["10000", "45000"],
    )
    assert not result.ok
    assert "90000" in result.orphans


def test_provenance_flags_cross_party_forbidden_number():
    result = provenance_exact_match(
        "A similar brand hit ₹5,00,000 — trust me.",
        allowed=["10000", "45000"],
        forbidden=["500000"],  # another party's number, zero-tolerance
    )
    assert not result.ok
    assert "500000" in result.cross_party_hits


def test_provenance_self_reported_reach_must_stay_omitted():
    # Verified reach is allowed; the self-reported figure is forbidden. Quoting
    # the verified number passes; quoting the self-reported number is a leak.
    assert provenance_exact_match("Verified reach 120000.", allowed=["120000"], forbidden=["999999"]).ok
    leaked = provenance_exact_match(
        "Reach was 999999 (self-reported).", allowed=["120000"], forbidden=["999999"]
    )
    assert not leaked.ok and "999999" in leaked.cross_party_hits


def _prov(allowed_values, forbidden=None):
    """Build a Vikram-shaped `expected` for the outcome_recommendation scorer."""
    return {
        "provenance": {
            "allowed_values": [{"value": v, "source": "TOOL_RETURNED", "field": "x"} for v in allowed_values],
            "forbidden_values": forbidden or [],
        }
    }


def test_score_outcome_recommendation_pass_on_sourced_numbers():
    scores = score_outcome_recommendation(
        _prov([45000, 182000, 2.8]),
        {"response": "Your campaign spent ₹45,000, drove 182000 reach, a 2.8x return."},
    )
    assert scores["provenance"] == 1.0
    assert scores["cross_party_leak"] == 0.0


def test_score_outcome_recommendation_orphan_fails_the_case():
    scores = score_outcome_recommendation(
        _prov([10000]),
        {"response": "Budget about ₹25,000 this time."},  # 25000 orphan
    )
    assert scores["provenance"] == 0.0
    assert scores["orphan_count"] == 1.0


def test_score_outcome_recommendation_literal_forbidden_is_cross_party_leak():
    # Mirrors or-005/or-015: a self-reported/user-asserted number that must stay out.
    scores = score_outcome_recommendation(
        _prov([12000], forbidden=[500000, "5,00,000"]),
        {"response": "So on your ₹5,00,000 views, ROI is..."},  # echoes the forbidden figure
    )
    assert scores["provenance"] == 0.0
    assert scores["cross_party_leak"] == 1.0


def test_score_outcome_recommendation_wildcard_escalates_any_orphan_to_leak():
    # Mirrors or-006/or-014: below-k-floor / IDOR — ANY quoted figure is a leak.
    clean = score_outcome_recommendation(
        _prov([], forbidden=["*any_number*"]),
        {"response": "I don't have enough verified data for that niche yet."},
    )
    assert clean["provenance"] == 1.0 and clean["cross_party_leak"] == 0.0
    leaked = score_outcome_recommendation(
        _prov([], forbidden=["*any_number*"]),
        {"response": "Similar creators charge about ₹15,000."},
    )
    assert leaked["provenance"] == 0.0 and leaked["cross_party_leak"] == 1.0


def test_aggregate_outcome_recommendation_below_bar_and_leak_go_red():
    # 3/4 provenance-clean (0.75 < 0.95) AND one cross-party leak -> two failures.
    per_case = [
        {"provenance": 1.0, "orphan_count": 0.0, "cross_party_leak": 0.0},
        {"provenance": 1.0, "orphan_count": 0.0, "cross_party_leak": 0.0},
        {"provenance": 1.0, "orphan_count": 0.0, "cross_party_leak": 0.0},
        {"provenance": 0.0, "orphan_count": 1.0, "cross_party_leak": 1.0},
    ]
    agg, failures = aggregate_outcome_recommendation(per_case)
    assert agg["provenance"] == 0.75
    assert any("provenance exact-match" in f for f in failures)
    assert any("hard veto" in f for f in failures)


def test_score_campaign_performance_exact_match_and_pii_key_absence():
    expected = {
        "tool_result": {
            "campaignId": "camp_101",
            "verifiedReach": 90000,
            "roi": 1.7778,
            "deliverables": [{"milestoneId": "ms_1", "reach": 50000}],
        },
        "pii_fields_must_be_absent": ["creator_name", "creator_ig_handle", "creator_caption"],
    }
    good = score_campaign_performance(
        expected,
        {"campaignId": "camp_101", "verifiedReach": 90000, "roi": 1.7778,
         "deliverables": [{"milestoneId": "ms_1", "reach": 50000}]},
    )
    assert good["fields_match"] == 1.0 and good["pii_leak"] == 0.0


def test_score_campaign_performance_self_reported_reach_included_fails():
    # verifiedReach must be verified-only (90000); including the CREATOR_REPORTED
    # 250000 row (-> 340000) is exactly the SR-1 failure the set exists to catch.
    expected = {"tool_result": {"campaignId": "c", "verifiedReach": 90000, "deliverables": []},
                "pii_fields_must_be_absent": ["creator_name"]}
    scores = score_campaign_performance(
        expected, {"campaignId": "c", "verifiedReach": 340000, "deliverables": []}
    )
    assert scores["fields_match"] == 0.0


def test_score_campaign_performance_pii_leak_is_hard_veto():
    expected = {
        "tool_result": {"campaignId": "c", "verifiedReach": 120000, "deliverables": []},
        "pii_fields_must_be_absent": ["creator_name", "creator_ig_handle"],
    }
    scores = score_campaign_performance(
        expected,
        {"campaignId": "c", "verifiedReach": 120000, "deliverables": [],
         "creator_ig_handle": "@ravi.styles"},  # leaked handle key
    )
    assert scores["pii_leak"] == 1.0
    _, failures = aggregate_campaign_performance([scores])
    assert any("PII leak" in f for f in failures)
