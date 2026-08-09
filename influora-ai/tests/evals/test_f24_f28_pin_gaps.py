"""Round-7 pin gaps: two fix-halves that survived mutation with the suite green.

The round-6 sweep reverted every fix half-by-half. Two halves came back GREEN
(NOT PINNED) — the code was correct, but nothing in the suite would have noticed
if it silently regressed:

- **F-24b** — `main()` sets `exit_code = 1` when a *scored* dataset misses its
  threshold. Every existing F-24 test covers the *unscored* path (`--live` with
  no key -> 3). Deleting the `if not report.passed: exit_code = 1` branch left
  627 tests green, i.e. a CI job wired to `--offline all` would have reported
  success while a dataset failed its bar.
- **F-28b** — `canonical_declared_value`'s `_FIGURE_RE` fallback. `canonical_number`
  already parses a *bare* declared token ("5 lakh", "₹2.5L"), so every existing
  F-28 case takes the `direct` path and the fallback is only reached when the
  dataset author wrote the magnitude inside a phrase ("about 5 lakh rupees").
  Deleting the fallback left the suite green while those tokens silently
  dropped out of the allowed/forbidden veto sets.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SERVICE_ROOT = Path(__file__).resolve().parents[2]  # influora-ai/
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from evals import run_eval  # noqa: E402
from evals.scorers import canonical_declared_value  # noqa: E402

# ---------------------------------------------------------------------------
# F-24b — a SCORED dataset that misses its threshold must exit non-zero
# ---------------------------------------------------------------------------


def test_f24b_a_scored_dataset_below_threshold_exits_one(monkeypatch, capsys):
    """The half F-24 never pinned: `--offline` runs, the dataset IS scored, and
    it FAILS its bar. `main()` must return 1, not 0."""

    class _FailingReport:
        dataset = "brand_safety_garm"
        passed = False
        n_cases = 3

    monkeypatch.setattr(run_eval, "make_offline_caller", lambda name: (lambda case: {}))
    monkeypatch.setattr(run_eval, "run_dataset", lambda *a, **k: _FailingReport())
    monkeypatch.setattr(run_eval, "print_report", lambda report: None)

    rc = run_eval.main(["--offline", "brand_safety_garm"])
    capsys.readouterr()
    assert rc == 1, "a scored-but-failing dataset must not be reported as green"


def test_f24b_a_scored_dataset_that_passes_exits_zero(monkeypatch, capsys):
    """Control: the same path with a passing report is still 0, so the pin above
    is testing the failure branch and not merely 'main() always returns 1'."""

    class _PassingReport:
        dataset = "brand_safety_garm"
        passed = True
        n_cases = 3

    monkeypatch.setattr(run_eval, "make_offline_caller", lambda name: (lambda case: {}))
    monkeypatch.setattr(run_eval, "run_dataset", lambda *a, **k: _PassingReport())
    monkeypatch.setattr(run_eval, "print_report", lambda report: None)

    rc = run_eval.main(["--offline", "brand_safety_garm"])
    capsys.readouterr()
    assert rc == 0


# ---------------------------------------------------------------------------
# F-28b — declared tokens written inside a phrase still canonicalize
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("token", "expected"),
    [
        ("about 5 lakh rupees", "500000"),
        ("Budget: ₹2.5L per month", "250000"),
        ("upto 3 crore reach", "30000000"),
        ("roughly 40 percent lift", "40"),
        ("spend 12k", "12000"),
    ],
)
def test_f28b_declared_value_inside_a_phrase_still_canonicalizes(token, expected):
    """`canonical_number` alone returns None for every one of these — they only
    canonicalize via the `_FIGURE_RE` fallback. A dataset author writing a
    forbidden value as a phrase must still get a veto, not a silent hole."""
    from evals.scorers import canonical_number

    assert canonical_number(token) is None, "test premise: the direct path must miss"
    assert canonical_declared_value(token) == expected


# ---------------------------------------------------------------------------
# F-21r — a dataset with zero recorded fixtures reports NOT RUN, not FAIL
# ---------------------------------------------------------------------------


def _report_with_no_fixtures() -> run_eval.EvalReport:
    report = run_eval.EvalReport(dataset="outcome_recommendation")
    report.missing_fixtures = ["case-1: aaa.json", "case-2: bbb.json"]
    report.failures = ["no cases were scored"]
    return report


def test_f21r_zero_executed_cases_print_not_run_and_the_case_count(capsys):
    """F-21's whole point: 0-of-N executed is *no measurement*, and the printed
    verdict must say so in words a CI reader cannot mistake for a low score.
    Collapsing this branch into the generic FAIL text loses the '0 of N
    executed / nothing was measured' sentence that made the gap visible."""
    run_eval.print_report(_report_with_no_fixtures())
    out = capsys.readouterr().out
    assert "NOT RUN" in out
    assert "0 of 2 case(s) executed" in out
    assert "Nothing about this dataset was measured" in out
    assert "RESULT: PASS" not in out


def test_f21r_a_dataset_that_scored_some_cases_still_reports_fail(capsys):
    """Control: NOT RUN is reserved for zero executed cases. A dataset that DID
    score cases and missed its bar must still read FAIL, so the branch above is
    not simply swallowing every failure."""
    report = run_eval.EvalReport(dataset="brand_safety_garm")
    report.case_scores = {"case-1": {"acc": 0.0}}
    report.aggregate = {"acc": 0.0}
    report.failures = ["acc 0.000 < 0.900"]
    run_eval.print_report(report)
    out = capsys.readouterr().out
    assert "RESULT: FAIL" in out
    assert "NOT RUN" not in out


def test_f21r2_a_dataset_that_scored_nothing_is_never_passed():
    """`run_dataset` records "no cases were scored" as a FAILURE. Emptying that
    list makes `EvalReport.passed` True for a dataset that executed zero cases —
    exit 0, "RESULT: PASS", zero measurement. That is the F-21/F-24 family's
    root shape and it must be impossible."""
    report = run_eval.EvalReport(dataset="outcome_recommendation")
    assert report.passed is True, "test premise: an empty report is vacuously passed"

    report.failures = ["no cases were scored"]
    assert report.passed is False

    scored = run_eval.EvalReport(dataset="outcome_recommendation")
    scored.case_scores = {"case-1": {"acc": 1.0}}
    scored.aggregate = {"acc": 1.0}
    assert scored.passed is True


def test_f21r2_run_dataset_marks_an_all_missing_fixture_run_as_failed(monkeypatch):
    """End-to-end on `run_dataset`: every case raises FixtureMissingError, so
    nothing is scored. The report must carry BOTH the missing fixtures and the
    "no cases were scored" failure — the failure is what stops `passed` from
    ever being read off an empty aggregate."""
    cases = [{"id": "c1", "input": {}, "expected": {}}, {"id": "c2", "input": {}, "expected": {}}]
    monkeypatch.setattr(run_eval, "load_dataset", lambda name: cases)

    def _always_missing(case_input):
        raise run_eval.FixtureMissingError(Path("/nonexistent/fixture.json"))

    report = run_eval.run_dataset("brand_safety_garm", _always_missing)

    assert report.case_scores == {}
    assert len(report.missing_fixtures) == 2
    assert report.failures == ["no cases were scored"]
    assert report.passed is False


# ---------------------------------------------------------------------------
# F-28 — canonical_number parses a unit off the token itself
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("token", "expected"),
    [
        ("2.5L", "250000"),
        ("₹2.5L", "250000"),
        ("1.2cr", "12000000"),
        ("5 lakh", "500000"),
        ("3 crore", "30000000"),
        ("12k", "12000"),
        ("1.5 million", "1500000"),
    ],
)
def test_f28_a_unit_suffixed_token_canonicalizes_without_an_explicit_suffix(token, expected):
    """The `tail` search inside `canonical_number` is what makes a bare
    "₹2.5L" parseable. `canonical_declared_value` masks its loss (it retries via
    `_FIGURE_RE` with an explicit suffix), so the sweep found this branch
    unpinned — but `extract_figures` and every direct caller depend on it, and
    dropping a forbidden magnitude to None is what downgraded a disclosure
    incident to a quality wobble in the first place."""
    from evals.scorers import canonical_number

    assert canonical_number(token) == expected


def test_f28_a_bare_number_is_unaffected_by_the_unit_tail():
    """No regression: the tail search must not invent a multiplier where the
    token has no unit, and must not eat a trailing digit."""
    from evals.scorers import canonical_number

    assert canonical_number("1499.00") == "1499"
    assert canonical_number("10,000") == "10000"
    assert canonical_number("40%") == "40"


def test_f28_an_unrecognised_trailing_word_is_not_a_multiplier():
    """"5 reels" is not 5 lakh. A tail that is not in _UNIT_MULTIPLIERS must
    leave the marker empty, and the token then fails to parse rather than
    silently scoring at some invented magnitude."""
    from evals.scorers import canonical_number

    assert canonical_number("5 reels") is None


# ---------------------------------------------------------------------------
# Round-7, Priya advisory 1 — F-23's recursive key walk is load-bearing
# ---------------------------------------------------------------------------


def test_f23_a_leak_nested_below_the_top_level_is_still_caught():
    """`_keys_anywhere` (evals/run_eval.py:856) recurses on purpose, but every
    existing F-23 case puts the leaked field at the TOP level of `output`, where
    the non-recursive `keys.add(key)` finds it anyway. Deleting the recursion
    left the whole suite green.

    Here the 404 envelope is fully valid and the ONLY thing wrong is campaign
    data buried two levels down — a shape the executor's own contract produces
    (`deliverables` is a list of milestone objects). Without the walk this
    scores a clean 1.0: a not-found response that hands over another
    workspace's spend, reach and commission and reads as compliant.
    """
    expected = {
        "tool_error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
        "pii_fields_must_be_absent": ["creator_name"],
    }

    deeply_nested_leak = run_eval.score_campaign_performance(
        expected,
        {
            "error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
            "output": {
                "meta": {
                    "debug": {
                        "milestones": [
                            {"milestoneId": "ms_1", "settledCommissionInr": 41000},
                        ]
                    }
                }
            },
        },
    )
    assert deeply_nested_leak["fields_match"] == 0.0, (
        "a 404 that leaks another workspace's settled commission two levels down "
        "scored as a clean not-found"
    )

    # Control: the same envelope with a nested payload carrying NO campaign-shaped
    # key still passes, so the walk discriminates rather than failing everything.
    honest_404 = run_eval.score_campaign_performance(
        expected,
        {
            "error": {"status": 404, "code": "CAMPAIGN_NOT_FOUND"},
            "output": {"meta": {"debug": {"traceId": "abc", "durationMs": 12}}},
        },
    )
    assert honest_404["fields_match"] == 1.0


def test_f23_the_pii_key_check_also_reaches_nested_keys():
    """The same walk backs `pii_leak`. A creator name nested inside a
    deliverable is the exact shape the PLATFORM_VERIFIED filter exists to
    strip, and it must not need to be top-level to be seen."""
    expected = {
        "tool_result": {"campaignId": "camp_1"},
        "pii_fields_must_be_absent": ["creator_name"],
    }
    scored = run_eval.score_campaign_performance(
        expected,
        {"output": {"campaignId": "camp_1",
                    "deliverables": [{"milestoneId": "ms_1", "creator_name": "Aarav"}]}},
    )
    assert scored["pii_leak"] == 1.0
