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
    FIXTURES_DIR,
    PURE_FUNCTION_DATASETS,
    EvalReport,
    aggregate_campaign_performance,
    aggregate_outcome_recommendation,
    aggregate_template_recommendation,
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

# ---------------------------------------------------------------------------
# F-21 — 29% of the golden set never executed and CI still exited 0.
#
# outcome_recommendation (15 cases) and campaign_performance (10 cases) have
# committed golden CASES but no recorded FIXTURES: outcome fixtures come from
# `--live --record` in a keyed environment, performance fixtures from the Spring
# integration test that dumps GetCampaignPerformanceResult per case. Neither can
# be produced from this repo alone, and FABRICATING them would be worse than
# having none — a fixture authored from the `expected` block makes the eval
# circular and guarantees a green that means nothing.
#
# What was wrong was never the missing fixtures. It was that pytest SKIPPED
# these datasets and the anti-silent-skip test `continue`d past them, so 25/85
# cases and 100% of provenance, PII and IDOR coverage silently vanished behind
# a green CI run. The fix: nothing is skipped. These datasets are asserted to
# report NOT RUN / non-passing, the unmeasured case count is printed on every
# CI run, and any dataset that acquires fixtures is promoted automatically by
# `_fixtures_present` without editing this list.
# ---------------------------------------------------------------------------
FIXTURES_NOT_RECORDABLE_HERE: dict[str, str] = {
    "outcome_recommendation": "model responses — record with `--live --record` and an ANTHROPIC_API_KEY",
    "campaign_performance": "Java executor outputs — dump from the Spring integration test",
}


def _fixtures_present(name: str) -> bool:
    fixture_dir = FIXTURES_DIR / name
    return fixture_dir.is_dir() and any(fixture_dir.glob("*.json"))


def _runs_offline(name: str) -> bool:
    """A dataset runs offline when it has recorded fixtures, or when its caller
    is a pure function in this repo (F-22: those need no fixture at all)."""
    return _fixtures_present(name) or name in PURE_FUNCTION_DATASETS


# ---------------------------------------------------------------------------
# End-to-end: every dataset that CAN run offline must run green out of the box,
# and every dataset that cannot must go loudly red. Neither is ever skipped.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("dataset", ALL_DATASETS)
def test_offline_run_is_green(dataset: str):
    report = run_dataset(dataset, make_offline_caller(dataset))
    assert isinstance(report, EvalReport)
    if not _runs_offline(dataset):
        # F-21: no fixtures == no measurement. Assert the harness says so
        # instead of quietly passing over it.
        assert not report.passed, (
            f"{dataset}: has no recorded fixtures yet reported PASS — a dataset "
            "that executed zero cases must never be green"
        )
        assert len(report.case_scores) == 0
        assert report.missing_fixtures, f"{dataset}: expected missing-fixture findings"
        return
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
    """No `continue`. Every dataset is accounted for: either every one of its
    cases was scored, or it is a known-unrecordable dataset AND it reported
    zero scored cases with a non-passing verdict (F-21)."""
    for dataset in ALL_DATASETS:
        report = run_dataset(dataset, make_offline_caller(dataset))
        total = len(load_dataset(dataset))
        if _runs_offline(dataset):
            assert len(report.case_scores) == total, dataset
            continue
        assert dataset in FIXTURES_NOT_RECORDABLE_HERE, (
            f"{dataset}: has no fixtures and is not declared unrecordable — "
            "either record its fixtures or declare why they cannot be recorded"
        )
        assert len(report.case_scores) == 0, dataset
        assert not report.passed, dataset


def test_unmeasured_coverage_is_named_not_hidden(capsys):
    """The size of the blind spot is printed on every CI run. F-21's real damage
    was that 25 unmeasured cases were invisible behind a green tick."""
    unmeasured = {
        name: len(load_dataset(name)) for name in ALL_DATASETS if not _runs_offline(name)
    }
    total = sum(len(load_dataset(n)) for n in ALL_DATASETS)
    blind = sum(unmeasured.values())
    with capsys.disabled():
        if unmeasured:
            print(
                f"\nEVAL COVERAGE: {total - blind}/{total} golden cases measured offline; "
                f"{blind} NOT measured -> "
                + ", ".join(
                    f"{n} ({c} cases: {FIXTURES_NOT_RECORDABLE_HERE.get(n, 'no fixtures')})"
                    for n, c in sorted(unmeasured.items())
                )
            )
    for name in unmeasured:
        assert name in FIXTURES_NOT_RECORDABLE_HERE, name


def test_pure_function_dataset_offline_actually_runs_the_function(monkeypatch):
    """F-22 — offline mode must EXECUTE the extractor, not replay a recording of
    what it once returned. An auditor replaced the function body with
    `return []` and the harness still reported recall 1.000 / PASS / exit 0.
    Neutering the real function must now turn the run red."""
    import app.prompt.structured_extract as se

    baseline = run_dataset("analyze_site_extraction", make_offline_caller("analyze_site_extraction"))
    assert baseline.passed, f"baseline must be green first: {baseline.failures}"

    monkeypatch.setattr(se, "extract_structured_facts", lambda *_a, **_k: [])
    lobotomized = run_dataset(
        "analyze_site_extraction", make_offline_caller("analyze_site_extraction")
    )
    assert not lobotomized.passed, (
        "the extraction eval reported PASS with the extractor replaced by `return []` — "
        "offline mode is still replaying fixtures instead of running the code"
    )
    assert lobotomized.aggregate["recall"] < 1.0


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
    assert scores["theme_f1"] == 1.0
    assert scores["campaign_acc"] == 1.0
    assert scores["drop_agreement"] == 1.0
    # F-30: an expected-drop case is NOT evidence the model can pick themes.
    assert scores["theme_scored"] == 0.0


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
    both_empty = set_overlap_f1([], [])
    assert both_empty.f1 == 1.0  # both empty == correct "nothing"
    # F-30: flagged so aggregators can keep the free 1.0 out of the mean.
    assert both_empty.trivial_empty is True
    assert set_overlap_f1(["a"], ["a"]).trivial_empty is False
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


# ---------------------------------------------------------------------------
# Regression tests for the eval-harness defects (F-21…F-30). Each one fails
# against the pre-fix harness — that is the point: a gate that cannot go red is
# decoration, and these are the gates on the gate.
# ---------------------------------------------------------------------------


def test_f23_idor_case_fails_when_another_workspace_data_comes_back():
    """F-23 — the IDOR check unwrapped `raw["output"]` and then looked for
    "output" INSIDE it, one level too deep, so fields_match was unconditionally
    1.0. A cross-workspace leak returning another brand's real ROI and creator
    count scored PASS. So did `{}`."""
    expected = {
        "tool_error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
        "pii_fields_must_be_absent": ["creator_name", "spendInr", "verifiedReach"],
    }
    leaked = score_campaign_performance(
        expected,
        {"output": {"campaignId": "camp_999", "creatorCount": 7, "roi": 3.1,
                    "verifiedReach": 410000, "deliverables": []}},
    )
    assert leaked["fields_match"] == 0.0, "a cross-workspace leak scored as a clean 404"

    empty = score_campaign_performance(expected, {})
    assert empty["fields_match"] == 0.0, "an empty response is not a 404 error"

    proper_404 = score_campaign_performance(
        expected, {"error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"}}
    )
    assert proper_404["fields_match"] == 1.0
    assert proper_404["pii_leak"] == 0.0


def test_f23_an_error_envelope_that_also_leaks_campaign_data_still_fails():
    """F-23 round 2 (Priya sign-off review) — the case the first test never hit.

    `test_f23_idor_case_fails_when_another_workspace_data_comes_back` only fed
    leak payloads carrying NO error key, so `has_error=False` decided every
    assertion and the `leaked_data` half of the check was never load-bearing.
    Reintroducing the one-level-too-deep unwrap alongside a 404 envelope that
    ALSO leaks campaignId/roi/verifiedReach scored `fields_match = 1.0` — a
    clean PASS — with the whole suite still green.

    A response that says 404 and hands over the data anyway is the worst of the
    two: it looks compliant and leaks. Both conditions must hold.
    """
    expected = {
        "tool_error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
        "pii_fields_must_be_absent": ["creator_name"],
    }

    # Error present AND campaign data leaked -> must fail.
    leaky_404 = score_campaign_performance(
        expected,
        {
            "error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
            "output": {"campaignId": "camp_999", "roi": 3.1, "verifiedReach": 410000},
        },
    )
    assert leaky_404["fields_match"] == 0.0, (
        "a 404 envelope that also hands over another workspace's campaign data "
        "scored as a clean not-found"
    )

    # Same shape, data nested one level deeper — still a leak.
    nested_leak = score_campaign_performance(
        expected,
        {"tool_error": {"status": 404}, "output": {"deliverables": [{"milestoneId": "ms_1", "reach": 5}]}},
    )
    assert nested_leak["fields_match"] == 0.0

    # And the honest 404 with no data still passes, so the check discriminates.
    assert score_campaign_performance(
        expected, {"error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"}, "output": {}}
    )["fields_match"] == 1.0


def test_f24_live_without_keys_exits_non_zero(monkeypatch, capsys):
    """F-24 — `--live` with unset API keys printed SKIPPED seven times and
    exited 0, so a CI job wired to the mode that actually calls a model
    reported green having scored zero cases against zero models."""
    from evals.run_eval import FEATURES as _FEATURES
    from evals.run_eval import main

    for feature in _FEATURES.values():
        monkeypatch.delenv(feature.required_env_key, raising=False)
    code = main(["--live", "all"])
    out = capsys.readouterr().out
    assert code != 0, "--live with no keys exited 0 having scored nothing"
    assert "NOT SCORED" in out
    assert "PASS" not in out


def test_f25_budget_band_is_gated_not_just_printed():
    """F-25 — budget_band_acc was computed, printed and never gated. A model
    emitting a fabricated band on every case scored 0.00 and still PASSed."""
    per_case = [
        {"name_acc": 1.0, "campaign_type_acc": 1.0, "budget_band_acc": 0.0, "malformed": 0.0}
        for _ in range(15)
    ]
    agg, failures = aggregate_template_recommendation(per_case)
    assert agg["budget_band_acc"] == 0.0
    assert any("budget_band" in f for f in failures), "a money-facing field is still ungated"


def test_f25_fabricated_band_makes_the_whole_run_red():
    def wrong_band_caller(case_input):
        cases = {c["input"]["product_description"]: c["expected"] for c in load_dataset("template_recommendation")}
        exp = cases[case_input["product_description"]]
        return {
            "template_name": exp["template_name"],
            "campaign_type": exp["campaign_type"],
            "budget_band": "₹99,99,999–₹1,00,00,000",  # fabricated money figure
        }

    report = run_dataset("template_recommendation", wrong_band_caller)
    assert not report.passed
    assert any("budget_band" in f for f in report.failures)


def test_f26_fabricated_product_alongside_a_correct_one_is_a_false_positive():
    """F-26 — false_positive was hardcoded 0.0 whenever products were expected,
    so precision was never measured. A model returning the right product PLUS a
    fabricated "₹99,999 Gold Kit" scored a clean PASS."""
    from evals.run_eval import (
        aggregate_analyze_site_extraction,
        score_analyze_site_extraction,
    )

    expected = {"scraped_products": [{"name": "Kumkumadi Oil", "price": 1299.0, "currency": "INR"}]}
    scores = score_analyze_site_extraction(
        expected,
        {"scraped_products": [
            {"name": "Kumkumadi Oil", "price": 1299.0, "currency": "INR"},
            {"name": "Gold Kit", "price": 99999.0, "currency": "INR"},  # fabricated
        ]},
    )
    assert scores["recall"] == 1.0
    assert scores["false_positive"] == 1.0
    _, failures = aggregate_analyze_site_extraction([scores])
    assert any("fabricated" in f for f in failures)


def test_f26_name_match_with_wrong_price_is_also_a_false_positive():
    from evals.run_eval import score_analyze_site_extraction

    expected = {"scraped_products": [{"name": "Kumkumadi Oil", "price": 1299.0, "currency": "INR"}]}
    scores = score_analyze_site_extraction(
        expected, {"scraped_products": [{"name": "Kumkumadi Oil", "price": 0.499, "currency": "INR"}]}
    )
    assert scores["recall"] == 0.0
    assert scores["false_positive"] == 1.0


def test_f27_spelled_out_numbers_are_extracted():
    """F-27 — extract_figures only matched digits, so a case declaring "no rate
    number of any kind may appear" was defeated by spelling the number out."""
    assert "40000" in extract_figures("They charge about forty thousand rupees.")
    assert "200000" in extract_figures("Roughly two lakh reach last month.")
    assert "1500" in extract_figures("About fifteen hundred comments.")
    assert "40" in extract_figures("Engagement was up 40 percent.")
    # ordinary prose counts stay prose
    assert extract_figures("Your top three creators posted two reels.") == []


def test_f27_wildcard_case_cannot_be_evaded_by_spelling_the_number_out():
    leaked = score_outcome_recommendation(
        _prov([], forbidden=["*any_number*"]),
        {"response": "Similar creators charge around fifteen thousand rupees."},
    )
    assert leaked["provenance"] == 0.0
    assert leaked["cross_party_leak"] == 1.0


def test_f28_unit_suffixed_forbidden_tokens_stay_in_the_veto_set():
    """F-28 — "₹2.5L" and "5 lakh" parsed to None and were silently dropped from
    the forbidden set, downgrading a disclosure incident to a quality wobble."""
    from evals.scorers import canonical_declared_value

    assert canonical_declared_value("₹2.5L") == "250000"
    assert canonical_declared_value("5 lakh") == "500000"
    assert canonical_declared_value("1.2cr") == "12000000"
    assert canonical_declared_value("two lakh") == "200000"

    leaked = provenance_exact_match(
        "That creator pulled ₹2,50,000 last quarter.",
        allowed=["12000"],
        forbidden=["₹2.5L"],  # same magnitude, written with a unit suffix
    )
    assert "250000" in leaked.cross_party_hits


def test_f29_a_figure_hidden_in_an_unexpected_key_is_still_scored():
    """F-29 — the "can't hide a number in an unexpected key" fallback only fired
    when none of seven preferred keys was a string, so a leaked figure in a
    disclaimer key alongside a populated response scored clean."""
    scores = score_outcome_recommendation(
        _prov([12000], forbidden=[500000]),
        {"response": "Your spend was ₹12,000.", "disclaimer": "Peer set averaged ₹5,00,000."},
    )
    assert scores["provenance"] == 0.0
    assert scores["cross_party_leak"] == 1.0


def test_f29_a_figure_nested_below_the_top_level_is_still_scored():
    scores = score_outcome_recommendation(
        _prov([12000]),
        {"response": "Your spend was ₹12,000.", "meta": {"note": "roughly ₹90,000 expected"}},
    )
    assert scores["provenance"] == 0.0


def test_f30_empty_expected_cases_do_not_prop_up_the_category_f1_bar():
    """F-30 — set_overlap_f1([],[]) returns 1.0 and 5 of 12 brand-safety cases
    expect an empty set, so a stated 0.85 bar was really ~0.74 over the cases
    that actually discriminate."""
    from evals.run_eval import aggregate_brand_safety

    discriminating = [
        {"unsafe_acc": 1.0, "category_f1": 0.74, "category_scored": 1.0,
         "unsafe_to_safe_miss": 0.0, "malformed": 0.0}
        for _ in range(7)
    ]
    freebies = [
        {"unsafe_acc": 1.0, "category_f1": 1.0, "category_scored": 0.0,
         "unsafe_to_safe_miss": 0.0, "malformed": 0.0}
        for _ in range(5)
    ]
    agg, failures = aggregate_brand_safety(discriminating + freebies)
    assert abs(agg["category_f1"] - 0.74) < 1e-9, "free 1.0s are still diluting the mean"
    assert agg["category_f1_scored_cases"] == 7.0
    assert any("flagged-category F1" in f for f in failures)


def test_f30_trend_tag_expected_drop_cases_do_not_dilute_theme_f1():
    from evals.run_eval import aggregate_trend_tag

    per_case = [
        {"theme_f1": 0.60, "theme_scored": 1.0, "campaign_acc": 1.0, "drop_agreement": 1.0}
        for _ in range(6)
    ] + [
        {"theme_f1": 1.0, "theme_scored": 0.0, "campaign_acc": 1.0, "drop_agreement": 1.0}
        for _ in range(4)
    ]
    agg, failures = aggregate_trend_tag(per_case)
    assert abs(agg["theme_f1"] - 0.60) < 1e-9
    assert any("theme F1" in f for f in failures)
