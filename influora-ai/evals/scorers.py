"""Generic scoring primitives shared by all three eval datasets.

Dataset-specific scoring (what fields to compare, what counts as a pass) lives
in run_eval.py's `score_*` functions, which compose these primitives. Kept
here, separately, so the primitives stay dataset-agnostic and unit-testable.

stdlib only — no numpy/sklearn, per the eval harness's dependency-light goal.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable


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


# ---------------------------------------------------------------------------
# provenance_exact_match — the Phase-2 moat scorer (build plan §2.5, Ash gate).
#
# Contract (the reason this primitive exists): every currency/numeric figure
# Meera QUOTES in a response must be traceable to a value the tools actually
# returned (a tool-returned field), a *declared* deterministic calc of such
# values, or a config constant. A number in the response with no such source is
# an ORPHAN — a hallucinated / self-manufactured figure — and is the exact
# failure mode this scorer exists to catch (SR-1 at the presentation layer: the
# model may not invent a money/outcome number and present it as fact).
#
# This scorer enforces provenance by VALUE TRACEABILITY, not by parsing English
# provenance words. That is deliberate and stronger: a model that mislabels a
# self-reported number as "verified" still fails, because the *value* it quoted
# is not in the allowed set. Units are intentionally magnitude-only — "₹2.5L",
# "2,50,000" and "250000" all canonicalize to the same magnitude, so the dataset
# author lists a plain magnitude and the model may render it in any surface form.
# ---------------------------------------------------------------------------

# Indian/Western unit multipliers Meera may render (₹2.5L == 250000, 1.2cr ==
# 12000000, 15k == 15000). "%", "x", "×" are UNIT markers, not multipliers — a
# ratio/percentage keeps its face magnitude (45% -> 45, 2.5x -> 2.5).
_UNIT_MULTIPLIERS: dict[str, Decimal] = {
    "k": Decimal(1_000),
    "l": Decimal(100_000),
    "lac": Decimal(100_000),
    "lakh": Decimal(100_000),
    "cr": Decimal(10_000_000),
    "crore": Decimal(10_000_000),
}

# One maximal numeric token: optional ₹/Rs/INR prefix, the number (Western or
# Indian digit grouping, optional decimal), optional %/x/×/k/L/lakh/cr/crore.
_FIGURE_RE = re.compile(
    r"(?P<cur>₹|rs\.?\s*|inr\s*)?"
    r"(?P<num>\d[\d,\u00A0\u202F]*(?:\.\d+)?)"
    r"(?P<suf>\s*(?:%|x\b|×|crore|cr\b|lakh|lac|l\b|k\b))?",
    re.IGNORECASE,
)


def canonical_number(raw_num: Any, suffix: str = "") -> str | None:
    """Canonicalize a numeric token to a magnitude string for set comparison.

    Strips grouping separators and currency, applies a k/L/cr multiplier when the
    suffix is one, and normalizes scale so ``2.50`` == ``2.5`` and ``10,000`` ==
    ``10000``. Returns ``None`` for anything that isn't a parseable number (so a
    junk token is dropped, never silently scored). ``%``/``x``/``×`` are treated
    as unit markers only (face magnitude preserved)."""
    cleaned = re.sub(r"[,\u00A0\u202F\s]", "", str(raw_num))
    cleaned = re.sub(r"^(?:₹|rs\.?|inr)", "", cleaned, flags=re.IGNORECASE)
    if not cleaned:
        return None
    try:
        value = Decimal(cleaned)
    except (InvalidOperation, ValueError):
        return None
    marker = (suffix or "").strip().lower().rstrip(".")
    if marker in _UNIT_MULTIPLIERS:
        value = value * _UNIT_MULTIPLIERS[marker]
    value = value.normalize()
    if value == value.to_integral_value():
        value = value.to_integral_value()  # 1E+4 -> 10000, drop exponent form
    return format(value, "f")


def extract_figures(text: str) -> list[str]:
    """Every *quoted figure* in a model response, canonicalized.

    A "quoted figure" is a numeric token that carries a financial/metric marker
    (₹/Rs/INR, %, x/×, k/L/cr) OR is a magnitude (has grouping, a decimal, or
    >=3 digits). Bare 1-2 digit integers with no marker are treated as prose
    counts ("your top 3 creators", "2 posts") and are deliberately NOT scored —
    scoring them would manufacture false orphans out of ordinary language. Every
    money/rate/ROI/reach figure the plan cares about carries a marker or is a
    magnitude, so this conservative rule loses none of them."""
    text = text or ""
    figures: list[str] = []
    for match in _FIGURE_RE.finditer(text):
        # Skip a number embedded in an identifier ("camp_101", "ms_13",
        # "V20260714150000") — a preceding letter/digit/underscore means this is
        # a token, not a quoted figure.
        start = match.start()
        if start > 0 and (text[start - 1].isalnum() or text[start - 1] == "_"):
            continue
        num = match.group("num")
        cur = match.group("cur")
        suf = match.group("suf")
        digits = re.sub(r"\D", "", num)
        is_figure = bool(cur) or bool(suf) or ("," in num) or ("." in num) or len(digits) >= 3
        if not is_figure:
            continue
        canon = canonical_number(num, suf or "")
        if canon is not None:
            figures.append(canon)
    return figures


@dataclass(frozen=True)
class ProvenanceResult:
    """Outcome of a provenance_exact_match check on one response."""

    figures: tuple[str, ...]           # every canonical figure quoted
    orphans: tuple[str, ...]           # figures with NO allowed source (hallucinated)
    cross_party_hits: tuple[str, ...]  # orphans that match a declared forbidden number

    @property
    def ok(self) -> bool:
        """True iff every quoted figure is traceable to an allowed source."""
        return not self.orphans


def provenance_exact_match(
    text: str,
    allowed: Iterable[Any],
    forbidden: Iterable[Any] = (),
) -> ProvenanceResult:
    """Assert every figure quoted in ``text`` is traceable.

    ``allowed`` = the union of {tool-returned field values} ∪ {declared
    deterministic calcs of them} ∪ {config values} — every magnitude Meera MAY
    legitimately quote for this case. ``forbidden`` = zero-tolerance magnitudes
    that must NEVER appear (another party's numbers, a self-reported figure that
    must stay omitted, a below-k-floor rate band's suppressed values). ``forbidden``
    is disjoint from ``allowed`` by construction — a magnitude cannot be both a
    legitimate source and a leak.

    A figure not in ``allowed`` is an orphan (provenance fails). An orphan that is
    also in ``forbidden`` is additionally a cross-party/leak hit (a separate,
    hard-veto signal — see the aggregators)."""
    allowed_canon = {c for c in (canonical_number(a) for a in allowed) if c is not None}
    forbidden_canon = {c for c in (canonical_number(f) for f in forbidden) if c is not None}
    figures = extract_figures(text)
    orphans = tuple(f for f in figures if f not in allowed_canon)
    cross = tuple(f for f in orphans if f in forbidden_canon)
    return ProvenanceResult(tuple(figures), orphans, cross)
