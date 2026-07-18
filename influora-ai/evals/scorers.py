"""Generic scoring primitives shared by all three eval datasets.

Dataset-specific scoring (what fields to compare, what counts as a pass) lives
in run_eval.py's `score_*` functions, which compose these primitives. Kept
here, separately, so the primitives stay dataset-agnostic and unit-testable.

stdlib only — no numpy/sklearn, per the eval harness's dependency-light goal.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class SetOverlapResult:
    precision: float
    recall: float
    f1: float


def set_overlap_f1(predicted: set[str] | list[str], expected: set[str] | list[str]) -> SetOverlapResult:
    """Case-insensitive set-overlap F1 for tag/theme lists. Both empty => a
    perfect match (correctly predicting "nothing applies" is correct, not
    undefined) — this matters for trend_tag's "no valid theme" edge case.
    """
    pred_set = {str(p).strip().lower() for p in predicted if str(p).strip()}
    exp_set = {str(e).strip().lower() for e in expected if str(e).strip()}
    if not pred_set and not exp_set:
        return SetOverlapResult(1.0, 1.0, 1.0)
    if not pred_set or not exp_set:
        return SetOverlapResult(0.0, 0.0, 0.0)
    tp = len(pred_set & exp_set)
    precision = tp / len(pred_set)
    recall = tp / len(exp_set)
    f1 = 0.0 if (precision + recall) == 0 else 2 * precision * recall / (precision + recall)
    return SetOverlapResult(precision, recall, f1)


def exact_match(predicted: Any, expected: Any) -> float:
    """1.0/0.0 exact-match score. Used for closed-enum fields (GARM unsafe
    bool, campaign_type, content_sentiment) where partial credit makes no
    sense — either the model picked the right bucket or it didn't."""
    return 1.0 if predicted == expected else 0.0


def bucketize(value: float | None, *, low_max: float = 0.34, med_max: float = 0.67) -> str:
    """Coarse 3-bucket mapping for a continuous 0-1 tone_dial field
    (formality/energy). `low_max`/`med_max` are the same terciles for both
    fields — analyze_site's contract treats them identically (app/providers/
    gemini.py _CLASSIFY_SYSTEM_INSTRUCTION: 'formality 0-1, energy 0-1')."""
    if value is None or not isinstance(value, (int, float)):
        return "unknown"
    if value < low_max:
        return "low"
    if value < med_max:
        return "med"
    return "high"


def tone_bucket_score(predicted_tone_dial: dict[str, Any], expected_bucket: dict[str, Any]) -> float:
    """Tolerant tone match: fraction of {formality, energy, emoji_ok} whose
    coarse bucket / boolean matches expected. A field missing from
    `expected_bucket` is simply not scored (not counted as a miss), so a
    golden case can assert only the fields it cares about."""
    components = 0
    hits = 0
    if "formality" in expected_bucket:
        components += 1
        if bucketize(predicted_tone_dial.get("formality")) == expected_bucket["formality"]:
            hits += 1
    if "energy" in expected_bucket:
        components += 1
        if bucketize(predicted_tone_dial.get("energy")) == expected_bucket["energy"]:
            hits += 1
    if "emoji_ok" in expected_bucket:
        components += 1
        if bool(predicted_tone_dial.get("emoji_ok")) == bool(expected_bucket["emoji_ok"]):
            hits += 1
    return hits / components if components else 1.0


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0
