"""Generic scoring primitives shared by all three eval datasets.

Dataset-specific scoring (what fields to compare, what counts as a pass) lives
in run_eval.py's `score_*` functions, which compose these primitives. Kept
here, separately, so the primitives stay dataset-agnostic and unit-testable.

stdlib only — no numpy/sklearn, per the eval harness's dependency-light goal.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any


@dataclass(frozen=True)
class SetOverlapResult:
    precision: float
    recall: float
    f1: float
    # F-30: True when BOTH sides were empty. Agreeing that "nothing applies" is
    # correct, but it is not evidence that the model can pick the right tags —
    # averaging those 1.0s into the F1 mean silently lowers the real bar (12
    # brand-safety cases with 5 empty-expected ones turn a stated 0.85 gate into
    # an effective ~0.74). Aggregators MUST read this flag and score empty-set
    # agreement on its own axis instead of diluting the discrimination metric.
    trivial_empty: bool = False


def set_overlap_f1(predicted: set[str] | list[str], expected: set[str] | list[str]) -> SetOverlapResult:
    """Case-insensitive set-overlap F1 for tag/theme lists.

    Both empty => a perfect match (correctly predicting "nothing applies" IS
    correct, not undefined) — this matters for trend_tag's "no valid theme" edge
    case — but the result is flagged ``trivial_empty`` so aggregators can keep it
    out of the discrimination average (F-30).
    """
    pred_set = {str(p).strip().lower() for p in predicted if str(p).strip()}
    exp_set = {str(e).strip().lower() for e in expected if str(e).strip()}
    if not pred_set and not exp_set:
        return SetOverlapResult(1.0, 1.0, 1.0, trivial_empty=True)
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
    "thousand": Decimal(1_000),
    "l": Decimal(100_000),
    "lac": Decimal(100_000),
    "lacs": Decimal(100_000),
    "lakh": Decimal(100_000),
    "lakhs": Decimal(100_000),
    "cr": Decimal(10_000_000),
    "crore": Decimal(10_000_000),
    "crores": Decimal(10_000_000),
    "million": Decimal(1_000_000),
    "millions": Decimal(1_000_000),
    "mn": Decimal(1_000_000),
    "billion": Decimal(1_000_000_000),
    "billions": Decimal(1_000_000_000),
    "bn": Decimal(1_000_000_000),
}

# One maximal numeric token: optional Rs/INR prefix, the number (Western or
# Indian digit grouping, optional decimal), optional unit suffix.
#
# F-27: the suffix alternation carries the SPELLED-OUT scale and ratio markers
# ("40 thousand", "2 lakh", "1.2 million", "40 percent", "2.5 times") alongside
# their symbol forms. A degraded model that writes "40 percent" instead of "40%"
# must not slip past the provenance scorer on spelling alone.
_FIGURE_RE = re.compile(
    r"(?P<cur>₹|rs\.?\s*|inr\s*)?"
    r"(?P<num>\d[\d,  ]*(?:\.\d+)?)"
    r"(?P<suf>\s*(?:%|per\s*cent\b|percent\b|x\b|×|times\b|crores?\b|cr\b"
    r"|lakhs?\b|lacs?\b|thousand\b|millions?\b|billions?\b|mn\b|bn\b|l\b|k\b))?",
    re.IGNORECASE,
)

# ---------------------------------------------------------------------------
# F-27 (part 2) -- spelled-out numerals with no digits at all.
#
# "forty thousand rupees", "two lakh", "fifteen hundred" carry exactly the
# money weight of "40,000", "200000" and "1500". A digits-only extractor is
# blind to every one of them, which makes "spell the number out" the cheapest
# possible evasion for a degraded model. It must cost the same as the digits.
# ---------------------------------------------------------------------------

_WORD_UNITS: dict[str, int] = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
    "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
    "seventeen": 17, "eighteen": 18, "nineteen": 19,
}
_WORD_TENS: dict[str, int] = {
    "twenty": 20, "thirty": 30, "forty": 40, "fourty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}
# "hundred" multiplies the running unit part; every larger scale closes off the
# group and multiplies the whole accumulated value ("two lakh fifty thousand").
_WORD_SCALES: dict[str, int] = {
    "hundred": 100, "thousand": 1_000, "lakh": 100_000, "lakhs": 100_000,
    "lac": 100_000, "lacs": 100_000, "crore": 10_000_000, "crores": 10_000_000,
    "million": 1_000_000, "millions": 1_000_000,
    "billion": 1_000_000_000, "billions": 1_000_000_000,
}
_WORD_NUMERALS = set(_WORD_UNITS) | set(_WORD_TENS) | set(_WORD_SCALES)
_WORD_TOKENS = _WORD_NUMERALS | {"and", "a"}
_WORD_ALT = "|".join(sorted(_WORD_TOKENS, key=len, reverse=True))

# A maximal run of number-words. The trailing unit word ("rupees", "percent")
# is deliberately NOT consumed -- it marks the run as a figure but contributes
# no magnitude.
_WORD_RUN_RE = re.compile(
    r"(?<![A-Za-z])(?P<words>(?:" + _WORD_ALT + r")(?:[\s\-]+(?:" + _WORD_ALT + r"))*)(?![A-Za-z])",
    re.IGNORECASE,
)


def word_number_to_value(words: str) -> Decimal | None:
    """Parse a run of English/Indian number-words into a magnitude.

    ``"forty thousand"`` -> 40000, ``"two lakh fifty thousand"`` -> 250000,
    ``"fifteen hundred"`` -> 1500. Returns ``None`` when the run carries no real
    numeral (a bare "and"/"a", or a dangling scale word), so ordinary prose is
    never manufactured into a figure.
    """
    tokens = [t for t in re.split(r"[\s\-]+", words.strip().lower()) if t and t != "and"]
    if not tokens:
        return None
    total = 0
    current = 0
    saw_numeral = False
    for token in tokens:
        if token == "a":
            current = current or 1
            continue
        if token in _WORD_UNITS:
            current += _WORD_UNITS[token]
            saw_numeral = True
        elif token in _WORD_TENS:
            current += _WORD_TENS[token]
            saw_numeral = True
        elif token in _WORD_SCALES:
            scale = _WORD_SCALES[token]
            if not saw_numeral and current == 0:
                return None  # "thousand" with nothing in front of it is prose
            if scale == 100:
                current = (current or 1) * 100
            else:
                total += (current or 1) * scale
                current = 0
            saw_numeral = True
        else:
            return None
    if not saw_numeral:
        return None
    return Decimal(total + current)


def canonical_number(raw_num: Any, suffix: str = "") -> str | None:
    """Canonicalize a numeric token to a magnitude string for set comparison.

    Strips grouping separators and currency, applies a k/L/cr multiplier when the
    suffix is one, and normalizes scale so ``2.50`` == ``2.5`` and ``10,000`` ==
    ``10000``. Returns ``None`` for anything that isn't a parseable number (so a
    junk token is dropped, never silently scored). ``%``/``x``/``×`` are treated
    as unit markers only (face magnitude preserved).

    F-28: when no explicit ``suffix`` is passed and ``raw_num`` carries its own
    unit ("₹2.5L", "5 lakh", "1.2cr"), the unit is parsed off the value instead
    of being swallowed into an unparseable Decimal. Dropping those to ``None``
    was silently deleting them from the FORBIDDEN veto set, which downgraded a
    disclosure incident to a quality wobble.
    """
    marker = (suffix or "").strip().lower().rstrip(".")
    text = str(raw_num)
    if not marker:
        # Pull a trailing unit off the token itself ("2.5L" -> num 2.5, suf L).
        tail = re.search(
            r"(crores?|lakhs?|lacs?|thousand|millions?|billions?|mn|bn|cr|[klm])\s*$",
            text.strip(),
            re.IGNORECASE,
        )
        if tail is not None:
            candidate = tail.group(1).lower()
            if candidate in _UNIT_MULTIPLIERS:
                marker = candidate
                text = text.strip()[: tail.start()]
    cleaned = re.sub(r"[,  \s]", "", text)
    cleaned = re.sub(r"^(?:₹|rs\.?|inr)", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"(?:%|x|×|per\s*cent|percent|times)$", "", cleaned, flags=re.IGNORECASE)
    if not cleaned:
        return None
    try:
        value = Decimal(cleaned)
    except (InvalidOperation, ValueError):
        return None
    if marker in _UNIT_MULTIPLIERS:
        value = value * _UNIT_MULTIPLIERS[marker]
    value = value.normalize()
    if value == value.to_integral_value():
        value = value.to_integral_value()  # 1E+4 -> 10000, drop exponent form
    return format(value, "f")


def canonical_declared_value(token: Any) -> str | None:
    """Canonicalize a value declared by a DATASET (allowed/forbidden list).

    Dataset authors write magnitudes in surface form -- ``500000``, ``"5,00,000"``,
    ``"₹2.5L"``, ``"5 lakh"``, ``"two lakh"``. Every one of those must land on the
    same canonical magnitude as the figure the model quotes, or the veto set has a
    hole in it. This is the declared-value counterpart to :func:`extract_figures`
    and is what the provenance scorer uses for both lists (F-28).
    """
    if isinstance(token, (int, float, Decimal)):
        return canonical_number(token)
    text = str(token or "").strip()
    if not text:
        return None
    direct = canonical_number(text)
    if direct is not None:
        return direct
    # "5 lakh", "₹2.5 L", "40 percent" -- digits plus a spelled unit.
    match = _FIGURE_RE.search(text)
    if match is not None and match.group("num"):
        canon = canonical_number(match.group("num"), match.group("suf") or "")
        if canon is not None:
            return canon
    # "two lakh fifty thousand" -- no digits at all.
    for run in _WORD_RUN_RE.finditer(text):
        value = word_number_to_value(run.group("words"))
        if value is not None:
            return canonical_number(value)
    return None


def extract_figures(text: str) -> list[str]:
    """Every *quoted figure* in a model response, canonicalized.

    A "quoted figure" is a numeric token that carries a financial/metric marker
    (₹/Rs/INR, %, x/×, k/L/cr, or their spelled-out forms) OR is a magnitude (has
    grouping, a decimal, or >=3 digits) OR is written entirely in number-words
    ("forty thousand rupees"). Bare 1-2 digit integers with no marker are treated
    as prose counts ("your top 3 creators", "2 posts") and are deliberately NOT
    scored -- scoring them would manufacture false orphans out of ordinary
    language. Every money/rate/ROI/reach figure the plan cares about carries a
    marker, is a magnitude, or is spelled out, so this conservative rule loses
    none of them.

    F-27: the word-number pass is what stops "forty thousand rupees" from
    evading a case that declares "no rate number of any kind may appear".
    """
    text = text or ""
    figures: list[str] = []
    consumed: list[tuple[int, int]] = []
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
        consumed.append((match.start(), match.end()))
        canon = canonical_number(num, suf or "")
        if canon is not None:
            figures.append(canon)

    # Second pass: runs written entirely in number-words. Skip any run that
    # overlaps a span the digit pass already claimed ("2 lakh" -> the digit pass
    # owns it), so a figure is never double-counted.
    for run in _WORD_RUN_RE.finditer(text):
        span = run.span("words")
        if any(span[0] < end and start < span[1] for start, end in consumed):
            continue
        value = word_number_to_value(run.group("words"))
        if value is None:
            continue
        # A spelled-out figure counts when it reaches magnitude (>= 100, i.e. it
        # used a scale word) or carries an explicit money/ratio marker. "three
        # creators" and "two posts" stay prose, exactly as their digit forms do.
        before = text[max(0, span[0] - 12):span[0]].lower()
        after = text[span[1]:span[1] + 12].lower()
        marked = bool(
            re.search(r"(₹|rs\.?\s*|inr\s*)$", before)
            or re.match(r"\s*(rupees|rs\b|inr\b|percent|per\s*cent|%|times|x\b)", after)
        )
        if value < 100 and not marked:
            continue
        canon = canonical_number(value)
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
    # F-28: declared values are canonicalized with the unit-aware parser, so a
    # forbidden token written "₹2.5L" or "5 lakh" lands in the veto set instead
    # of parsing to None and being silently dropped from it.
    allowed_canon = {c for c in (canonical_declared_value(a) for a in allowed) if c is not None}
    forbidden_canon = {c for c in (canonical_declared_value(f) for f in forbidden) if c is not None}
    figures = extract_figures(text)
    orphans = tuple(f for f in figures if f not in allowed_canon)
    cross = tuple(f for f in orphans if f in forbidden_canon)
    return ProvenanceResult(tuple(figures), orphans, cross)
