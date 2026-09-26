"""POST /internal/brief-extract — the ONE forced-tool call that turns a brief a
creator pasted into the structured `BriefExtraction` of SPEC.md §2.11.

T-MEERA-CREATOR-PHASE-B (SPEC.md §7.5), B0-39.

Internal-only: called by Java's `MeeraBriefAiClient`, never by a browser.

WHAT THIS ROUTE IS FOR. A creator is sent a deal in a DM or an email, which is
somewhere Influora cannot see. She pastes it. Java persists the raw text FIRST
(`CreatorBrief.paste`, so nothing she typed is lost), then asks this route to
read it. Java then prices and risk-checks the result against her own floors and
rate card. This route does exactly one job — read the brief — and has never been
shown a floor, a rate or a quote.

AUTH IS CREATOR-SCOPED, AND THAT SEGREGATION IS BIDIRECTIONAL. The token must
carry `scope=creator` and a `creator_profile_id` equal to the one in the body
(`ENDPOINT_SCOPES["brief_extract"] = (SCOPE_CREATOR,)`). A brand-side
`SCOPE_SERVICE` token cannot open this route and a creator token cannot open the
service routes — see `app/auth/service_token.py`'s `ENDPOINT_SCOPES` comment
(Kabir). That is why §3.8's Java client mirrors `CreatorSuggestionAiClient` and
not `MeeraVoiceAiClient`.

THE DETERMINISTIC-BODY INVARIANT, and why Java depends on it. Every failure path
here returns HTTP 200 with a deterministic body; only a missing
`creator_profile_id` (400) and auth (401/403) are non-200. Java cannot treat a
transport failure and a refusal the same way: a refusal because the creator is
over her brief cap must surface to her as "Meera's monthly limit is reached, so
this summary is rule-based" (`degraded_reason = "cap"`), while a provider outage
is `"ai_unavailable"`. Both produce a labelled fallback, and the only thing that
tells them apart is the error code in this 200 body. A 5xx here would collapse
the two and mislabel the cap as an outage.

THE SPEND GATE IS THE PER-CREATOR MONTHLY ONE, ON ITS OWN KEY.
`check_creator_spend_gate` (`app/costs/spend_tracker.py`) is the only gate that
accepts a `cap_usd` override, which is what makes `BRIEF_EXTRACT_MONTHLY_CAP_USD`
mean anything. `app.costs.gate.check_spend_gate` — the DAILY workspace ceiling
`creator_suggestion.py` calls — takes no cap argument, so wiring this route to it
would leave the separate brief cap bound to nothing and enforcing nothing, with
no error anywhere (SPEC.md §14.4.b). The key is suffixed `:brief` so a chatty
creator's chat spend never starves paste-and-read, which is precisely the surface
that must keep working when her chat cap is gone.

The pasted brief is the most attacker-controlled string in the creator product —
a stranger's text, arriving through a text box, aimed at a model. It is wrapped
by `app.prompt.brief_extract.build_user_message` before it reaches the model, and
the model's answer is re-validated here against the same closed vocabularies the
schema stated, because a schema constraint is guidance to a model rather than a
guarantee from one.
"""

from __future__ import annotations

import logging
import os
import re
import unicodedata
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, NamedTuple

import anyio
from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_creator_token
from app.config import BRIEF_EXTRACT_MODEL, get_settings
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import (
    SpendCapExceeded,
    check_creator_spend_gate,
    record_creator_spend,
    release_creator,
)
from app.prompt.brief_extract import build_system_block, build_user_message
from app.prompt.creator_persona import CREATOR_BANNED_WORDS
from app.prompt.validators import _has_forbidden_petname
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of
from app.tools.schemas import (
    BRIEF_CATEGORIES,
    BRIEF_DELIVERABLE_TYPES,
    BRIEF_EXCLUSIVITY_SCOPES,
    BRIEF_REGULATED_CATEGORIES,
    BRIEF_SUMMARY_LINE_MAX_CHARS,
    BRIEF_SUMMARY_LINES_MAX,
    BRIEF_SUMMARY_LINES_MIN,
    BRIEF_USAGE_CHANNELS,
    get_brief_extraction_schema,
)

logger = logging.getLogger(__name__)
router = APIRouter()

# The error codes Java branches on. `CREATOR_MONTHLY_CAP_REACHED` is the same
# literal `chat.py` uses for the chat cap, deliberately: it is the one code the
# frontend already knows means "the allowance, not the platform", and
# MeeraBriefAiClient maps it to degraded_reason="cap". Anything else maps to
# "ai_unavailable".
CAP_ERROR_CODE = "CREATOR_MONTHLY_CAP_REACHED"
EXTRACTION_FAILED_CODE = "extraction_failed"

# §7.5 — the raw text is already capped at 8000 characters by Java
# (`CreatorBrief.MAX_RAW_TEXT_LENGTH`, applied AFTER sanitising). Re-capped here
# rather than trusted: this route's caller is a service, and a service that grows
# a bug must not be able to turn one paste into an unbounded prompt. Truncation
# rather than a 400, because a brief that is slightly too long still deserves an
# extraction of its first 8000 characters.
MAX_RAW_TEXT_CHARS = 8000

# T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 1 (ash-answers.md F1):
# this call used to borrow `creator_copilot_max_tokens` (300, sized for a
# one-line suggestion in creator_suggestion.py). A full extract_brief answer
# (22-field schema + 3-5 summary lines) runs close to or over that, so the call
# was regularly cut off mid-JSON and silently degraded to the rule-based
# fallback while still being billed in full (see the F-06 billing path below).
# Extraction gets its own budget, as a module constant here rather than in
# app/config.py (out of this lane's file scope), overridable so it can be
# tuned from real `ai_spend`/stop_reason data without a code change.
# Source: .proof-os/tasks/T-PHASEB-LIVE-0918/ash-answers.md
#
# T-PHASEB-LIVE-0918 REPAIR ROUND 1 [vikram · 2026-09-18] — finding 9 (LOW):
# a non-numeric BRIEF_EXTRACT_MAX_TOKENS env value used to crash module import
# via a bare int(), and 0/negative values were accepted silently (disabling or
# inverting the budget). Parsed defensively and floored to the documented
# default instead.
def _read_max_tokens_env() -> int:
    default = 1024
    raw = os.getenv("BRIEF_EXTRACT_MAX_TOKENS")
    if raw is None:
        return default
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default


BRIEF_EXTRACT_MAX_TOKENS = _read_max_tokens_env()

# T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 3: Indian briefs write
# money as shorthand ("15k", "1.5L", "1.5 lakh", "1 crore") that never appears
# in the brief as the plain rupee figure the model must output. Grounding a
# structured amount against the brief's LITERAL digits alone would reject a
# correct 15000 for a "15k" brief, so grounding is checked against this
# shorthand-expanded set instead (see `_amounts_in_inr`).
# Source: ash-answers.md §1 ("No Indian money shorthand") and §3.
#
# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 9 (LOW): "hazaar"/"hazar" is a
# common Hinglish spelling of "thousand" ("15 hazaar" == "15k") and was missing
# entirely, so a correctly-converted 15000 was dropped as ungrounded.
#
# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 8 (LOW): Devanagari money
# words ("हज़ार"/"हजार", "लाख", "करोड़"/"करोड") were not recognised at all, so a
# brief that stated its budget only in Devanagari script (e.g. "बजट १५ हज़ार")
# always came back with budget_stated=true and budget_inr=null — a correct
# Hindi-script figure was strictly worse off than an ungrounded one. Spelled-
# out English "thousand"/"hundred" are added for the same reason: they were
# previously invisible to grounding even though the prompt's rule 9 only
# teaches the model "k"/"L"/"lakh"/"crore" shorthand, not full English words —
# `_line_has_unanchored_money_word` (below) relies on these being recognised
# units so a digit-anchored "15 thousand" is treated the same as "15k" rather
# than being flagged as an unparseable word amount.
# Source: REPAIR ROUND 2 finding 8; T-PHASEB-LIVE-0918 REPAIR ROUND 2 findings
# 1-3 (this same table is reused by the deliverable-qty and money-marker
# anchoring below).
#
# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI HIGH #2: the unit
# list missed common spellings — "lacs", "grand", "million"/"mn" (and "crs",
# "laakh", "bn", "mil") — so an invented summary line "Budget 5 lacs" / "They
# can pay 50 grand" / "Brand worth 5 million" was checked only as its bare "5"
# or "50" and survived whenever the brief held that digit anywhere. Every
# spelling below now expands, so the line's EXPANDED value is what gets
# grounded. `_WORD_END` replaces the old trailing `\b`, which cannot anchor
# after a Devanagari combining mark (the nukta ending "करोड़").
# Source: Kabir round-1 verdict, B0-AI defects[1].
_WORD_END = r"(?![A-Za-zऀ-ॿ])"
# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — MEDIUM/LOW ("budget
# 8000 k andar hai"): in Hinglish a lone "k" after a SPACE is often the
# postposition "ke" ("8000 k andar" = "within 8000"), not the thousands
# suffix, so the brief was read as 8,000,000 — grounding an invented 8000000
# and dropping the real 8000. A single-letter unit written after a space is
# shorthand only when it is not followed by one of these postpositions; glued
# to the digits ("8000k") it is always shorthand. "se"/"tak" are deliberately
# not listed: "15 k tak" is "up to 15k". Source: B0-AI repair-round-1
# verdict, defects 5 and 6.
_SPACED_UNIT_POSTPOSITION = (
    r"\s+(?:andar|ander|upar|oopar|neeche|niche|baad|pehle|saath|sath|liye|lie|"
    r"bina|jaisa|jaise|hisaab|hisab)(?![A-Za-z])"
)
_SINGLE_LETTER_UNIT = (
    rf"(?:(?<=\d)(?:k|l)|(?<=\s)(?:k|l)(?!{_SPACED_UNIT_POSTPOSITION}))"
)
_SHORTHAND_UNIT_RE_TEXT = (
    rf"{_SINGLE_LETTER_UNIT}|hazaars?|hazars?|lacs?|lakhs?|laakhs?|lkh|cr|crs|crores?|"
    r"thousands?|hundreds?|grand|millions?|mn|mil|billions?|bn|"
    r"हज़ार|हजार|लाख|करोड़|करोड"
)
_SHORTHAND_RE = re.compile(
    rf"(?P<num>\d+(?:\.\d+)?)\s*(?P<unit>{_SHORTHAND_UNIT_RE_TEXT}){_WORD_END}",
    re.IGNORECASE,
)
_SHORTHAND_MULTIPLIERS: dict[str, float] = {
    "k": 1_000,
    "hazaar": 1_000,
    "hazaars": 1_000,
    "hazar": 1_000,
    "hazars": 1_000,
    "l": 100_000,
    "lac": 100_000,
    "lacs": 100_000,
    "lakh": 100_000,
    "lakhs": 100_000,
    "laakh": 100_000,
    "laakhs": 100_000,
    "lkh": 100_000,
    "cr": 10_000_000,
    "crs": 10_000_000,
    "crore": 10_000_000,
    "crores": 10_000_000,
    "grand": 1_000,
    "million": 1_000_000,
    "millions": 1_000_000,
    "mn": 1_000_000,
    "mil": 1_000_000,
    "billion": 1_000_000_000,
    "billions": 1_000_000_000,
    "bn": 1_000_000_000,
    "thousand": 1_000,
    "thousands": 1_000,
    "hundred": 100,
    "hundreds": 100,
    "हज़ार": 1_000,
    "हजार": 1_000,
    "लाख": 100_000,
    "करोड़": 10_000_000,
    "करोड": 10_000_000,
}

# T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 3 (ash-answers.md §4):
# `_numbers_in` matched only ASCII \d, so a Hindi summary line written with
# Devanagari digits (e.g. "१५,०००") compared unequal to the brief's own ASCII
# "15000" and the whole extraction fell back needlessly. Normalise Devanagari
# 0-9 (U+0966-U+096F) to ASCII before any digit comparison.
_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")


def _normalize_digits(text: str) -> str:
    # T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — HIGH (Devanagari
    # audience nouns): the same word arrives with a precomposed nukta letter
    # (U+095E "फ़") or as base letter + combining nukta (U+092B U+093C). NFC
    # maps both to the decomposed pair (the precomposed nukta letters are
    # composition exclusions), which is the form every Devanagari literal in
    # this file is written in, so one spelling in a pattern now covers both.
    # Every grounding helper reads text through this function first.
    # Source: B0-AI repair-round-1 verdict, defect 2.
    return unicodedata.normalize("NFC", text or "").translate(_DEVANAGARI_DIGITS)

# A number in a summary line that is not in the brief is an invented number, and
# the creator will price against it. Matches a digit group with an optional
# comma thousands-separator and decimals; the comparison itself is done on the
# digits alone (see `_numbers_in`), so "8,000" in a line matches "8000" in the
# brief.
#
# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 9 (LOW): the previous pattern
# (`\d[\d,  ]*`) also accepted a bare SPACE inside a number, which glues two
# unrelated numbers written next to each other into one: "Budget 15000 3
# reels" was read as the single number "150003", so the correct "15000" never
# matched anything. Only a comma is accepted as a separator now; a space
# always ends the current number.
_NUMBER_RE = re.compile(r"\d[\d,]*(?:\.\d+)?")

_BANNED_WORD_RE = re.compile(
    r"|".join(re.escape(word) for word in CREATOR_BANNED_WORDS), re.IGNORECASE
)

_claude_provider: ClaudeProvider | None = None


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


def _error_body(code: str) -> dict[str, Any]:
    """The deterministic non-success body. HTTP 200 — see the module docstring:
    the code in here is the only thing that lets Java tell a cap from an
    outage."""
    return {"success": False, "error": {"code": code}}


def _numbers_in(text: str) -> set[str]:
    """Every number in `text`, reduced to bare digits (separators and a trailing
    ".0" dropped) so "8,000", "8 000" and "8000.00" all compare equal.
    Devanagari digits are normalised to ASCII first (see `_normalize_digits`)
    so a Hindi-script number compares equal to the same value written in
    figures elsewhere.

    REPAIR ROUND 2 [vikram · 2026-09-18] — finding 3 (MEDIUM): a genuine
    decimal literal found IN THE TEXT (e.g. the "1.5" inside "1.5L") used to
    be collapsed by CONCATENATING its digits — "1.5" became "15" — which is
    not the same number and created a false equivalence: a model-invented
    budget_inr=15 then read as "grounded" against a brief that only ever said
    "1.5L" (150000), because both boiled down to the same "15" string. Only a
    genuinely trailing-zero fraction (the ".00" a Python float repr adds, e.g.
    formatting 8000.0) means "no fraction at all" and collapses to the whole
    part; any other fraction keeps the number as a distinct WHOLE.FRAC string
    instead of being merged into the whole part or dropped — "1.5" and "15"
    must never compare equal. The Indian-shorthand VALUE a decimal like "1.5"
    is part of ("1.5L" -> 150000) is still captured correctly by
    `_amounts_in_inr`'s separate shorthand expansion, which parses the digits
    as a float rather than concatenating them. Source: REPAIR ROUND 2 finding
    3 (B3: "budget 1.5L" wrongly grounded an invented budget_inr=15)."""
    found: set[str] = set()
    for match in _NUMBER_RE.finditer(_normalize_digits(text)):
        digits = re.sub(r"[^\d.]", "", match.group(0))
        if not digits:
            continue
        if "." in digits:
            whole, _, frac = digits.partition(".")
            digits = whole if frac.strip("0") == "" else f"{whole}.{frac}"
        found.add(digits.lstrip("0") or "0")
    return found


def _amounts_in_inr(text: str) -> set[str]:
    """`_numbers_in`, plus every Indian money-shorthand amount in `text`
    expanded to its plain rupee figure: "15k" -> 15000, "1.5L"/"1.5 lakh" ->
    150000, "1 crore" -> 10000000. This is the grounding set for the two
    MONEY fields, budget_inr and barter_mrp_inr (Ash's fix 2 and 3,
    ash-answers.md G1/§1) — a structured amount is kept only when it, or its
    plain-figure equivalent, actually appears in the brief; a brief that says
    "15k" never contains the literal digits "15000", so checking only
    `_numbers_in` would wrongly reject the correctly-converted figure.

    REPAIR ROUND 1 [vikram · 2026-09-18] — finding 2 (MEDIUM): this used to
    also be the grounding set for usage_months and exclusivity_days, which is
    wrong on two counts: (1) it means ANY number anywhere in the brief grounds
    ANY field — a "15k" budget wrongly grounded an invented usage_months=15 —
    and (2) `_numbers_in` on the raw text picks up the bare digits INSIDE a
    shorthand token too ("15k" contributes the literal number "15", not just
    the expanded "15000"), which is exactly the "15" that then vouched for the
    invented usage_months. usage_months and exclusivity_days now have their
    own unit-anchored grounding (`_numbers_with_unit`, below) instead of
    sharing this set. Source: REPAIR ROUND 1 finding 2."""
    return _plain_numbers_in(text) | _shorthand_expansions_in(text)


def _plain_numbers_in(text: str) -> set[str]:
    """`_numbers_in`, minus the bare digits of every shorthand token ("15" of
    "15k", "1.5" of "1.5L"), which are checked through their expansion instead.

    T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM (bare
    shorthand digits): a brief saying "budget 15k" does not state the number
    15, so it must not let a summary line say "Budget 15" either. A line's
    own shorthand is still checked, by its expanded value, in
    `_acceptable_summary_line`. Source: Kabir round-1 verdict, B0-AI defects[2]."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in _NUMBER_RE.finditer(normalized):
        if _is_shorthand_digits(normalized, match.end()):
            continue
        found |= _numbers_in(match.group(0))
    return found


def _shorthand_expansions_in(text: str) -> set[str]:
    """Only the Indian-shorthand-EXPANDED amounts in `text` ("15k" -> "15000",
    "1.5L" -> "150000") — deliberately excluding the plain literal numbers
    `_numbers_in` would also find, so a caller can tell "this is a number
    written in shorthand, and here is what it expands to" apart from "this
    number is written out in full". Split out of `_amounts_in_inr` in REPAIR
    ROUND 2 (finding 2, HIGH) so a summary line's OWN shorthand — the model
    writing back "50k" instead of expanding it — can be checked against this
    set specifically, rather than the combined literal+expanded set that a
    line's own unrelated literal digits would also satisfy by coincidence
    (e.g. a stray "50" from "50% advance" trivially covering a "50" that the
    model then suffixed with "k"). Source: REPAIR ROUND 2 finding 2."""
    values: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in _SHORTHAND_RE.finditer(normalized):
        multiplier = _SHORTHAND_MULTIPLIERS.get(match.group("unit").lower())
        if multiplier is None:
            continue
        try:
            amount = float(match.group("num")) * multiplier
        except ValueError:
            continue
        values |= _numbers_in(f"{amount:.10f}".rstrip("0").rstrip("."))
    return values


# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 2 (MEDIUM): usage_months and
# exclusivity_days are counts, not money, and must be grounded against a
# number that is actually attached to the right UNIT in the brief ("60 days",
# "3 months") rather than against any digit anywhere. This is what stops "3
# reels" from grounding an invented exclusivity_days=3, and stops the bare
# "15" inside a "15k" BUDGET shorthand token from grounding an invented
# usage_months=15 — neither "3" nor "15" sits next to "day(s)"/"month(s)" in
# either brief. Source: REPAIR ROUND 1 finding 2.
#
# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 4 (MEDIUM): a unit alone is
# not enough — it is FIELD anchoring, not just unit anchoring, that was
# missing. "payment within 3 months" and "payment in 45 days" used to ground
# usage_months=3 and exclusivity_days=45 respectively, purely because SOME
# duration unit sat next to the number; the number describes when PAYMENT is
# due, not usage or exclusivity. Conversely a genuine "usage rights 1 year" or
# "2 months category exclusivity" used to ground NOTHING, because the model's
# converted answer (12 months / 60 days) never appears as that literal figure
# next to "year"/"month" in the brief. `_numbers_with_context_unit` now
# requires a USAGE- or EXCLUSIVITY-context word within a small window of the
# unit match (not just the unit itself) before a number grounds either field,
# and converts year/month durations found in the RIGHT context into the
# field's own unit (year->12 months, month/year->30/365 days) the same way
# `_amounts_in_inr` converts Indian money shorthand. Devanagari duration words
# (दिन "day", महीने/महीना/माह "month") are matched directly since
# `_normalize_digits` only translates digits, not unit words.
#
# `\b` after a Devanagari alternative is unsafe when that alternative ends in
# a dependent vowel sign (a Unicode combining mark, e.g. the "े" ending
# "महीने") — Python's re does not count those marks as `\w`, so `\b` anchors
# to the CONSONANT before the mark instead of the true end of the word, and
# the whole alternative then never matches at all (caught by this lane's own
# red/green: "usage rights ke liye 3 महीने" matched zero times with `\b`).
# `_WORD_END` is a portable replacement: "not immediately followed by another
# ASCII or Devanagari letter", which works for every unit word above
# regardless of what character it ends on.
# Source: REPAIR ROUND 2 finding 4. (`_WORD_END` is defined with the shorthand
# table above, which now uses it too.)
_DAY_UNIT_RE = re.compile(
    # "din" (Latin-script Hinglish) added T-GOLIVE-0918-R2 REPAIR ROUND 1
    # [ash · 2026-09-18] — LOW: "15 din exclusivity" dropped a correct
    # exclusivity_days=15. Source: B0-AI repair-round-1 verdict, defect 6.
    rf"(\d[\d,]*(?:\.\d+)?)\s*-?\s*(?:days?|din|दिन){_WORD_END}", re.IGNORECASE
)
_MONTH_UNIT_RE = re.compile(
    # "mahine"/"mahina" (Latin-script Hinglish) added T-GOLIVE-0918-R2
    # [ash · 2026-09-18] — Kabir round-1 B0-AI LOW: "usage rights 3 mahine"
    # dropped a correct usage_months=3.
    rf"(\d[\d,]*(?:\.\d+)?)\s*-?\s*(?:months?|mo|mahine|mahina|maheene|महीन[ेा]|माह){_WORD_END}",
    re.IGNORECASE,
)
_YEAR_UNIT_RE = re.compile(
    rf"(\d[\d,]*(?:\.\d+)?)\s*-?\s*(?:years?|yrs?|साल|वर्ष){_WORD_END}",
    re.IGNORECASE,
)
_USAGE_CONTEXT_RE = re.compile(r"usage|use\b|rights?|licen[sc]e", re.IGNORECASE)
# A heuristic window, not full sentence parsing (same caveat as the brand
# exclusion check below): wide enough to span a short clause ("2 months
# category exclusivity"), narrow enough that an unrelated context word two
# sentences away should not cross-ground a different duration mention.
_DURATION_CONTEXT_WINDOW = 30

# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — LOW: the context window
# ran across sentence ends, so "Campaign runs 3 months. Usage: organic only."
# grounded usage_months=3 from the NEXT sentence's "Usage". A context window
# (here and for the weak usage terms below) now stops at a sentence boundary:
# . ! ? ; a newline or the Devanagari danda, but not a decimal point.
# Source: B0-AI repair-round-1 verdict, defect 6.
_SENTENCE_BREAK_RE = re.compile(r"(?<!\d)[.!?;\n।](?!\d)")


def _clause_bounds(text: str, start: int, end: int, width: int) -> tuple[int, int]:
    """(lo, hi) of text[start-width : end+width], clipped to the sentence that
    holds text[start:end]."""
    lo = max(0, start - width)
    hi = min(len(text), end + width)
    before = text[lo:start]
    breaks = list(_SENTENCE_BREAK_RE.finditer(before))
    if breaks:
        lo += breaks[-1].end()
    after_break = _SENTENCE_BREAK_RE.search(text, end, hi)
    if after_break is not None:
        hi = after_break.start()
    return lo, hi


def _clause_window(text: str, start: int, end: int, width: int) -> str:
    """text[start-width : end+width], clipped to the sentence that holds
    text[start:end]."""
    lo, hi = _clause_bounds(text, start, end, width)
    return text[lo:hi]


def _numbers_with_unit(text: str, unit_re: re.Pattern[str]) -> set[str]:
    """Every number in `text` that is immediately followed by the unit
    `unit_re` matches (e.g. "60 days", "3-month"), reduced to bare digits the
    same way `_numbers_in` does. Devanagari digits are normalised first."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in unit_re.finditer(normalized):
        found |= _numbers_in(match.group(1))
    return found


def _numbers_with_context_unit(
    text: str,
    unit_re: re.Pattern[str],
    context_re: re.Pattern[str] | None,
    *,
    multiplier: float = 1,
    context_spans: list[tuple[int, int]] | None = None,
) -> set[str]:
    """Like `_numbers_with_unit`, but a match only counts when `context_re`
    (a USAGE or EXCLUSIVITY context word) also appears within
    `_DURATION_CONTEXT_WINDOW` characters of it — see REPAIR ROUND 2 finding
    4 above. `multiplier` converts the unit found into the target field's own
    unit (e.g. a YEAR match feeding usage_months passes multiplier=12).

    `context_spans` (T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18]) is
    the alternative to `context_re`: positions in the normalised text where
    the brief STATES the term (see `_exclusivity_spans`), so a word that
    merely looks like the context ("Exclusive 20% off for 30 days") does not
    count. Source: B0-AI repair-round-2 verdict, defect 4."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in unit_re.finditer(normalized):
        lo, hi = _clause_bounds(
            normalized, match.start(), match.end(), _DURATION_CONTEXT_WINDOW
        )
        if context_spans is not None:
            if not any(s < hi and e > lo for s, e in context_spans):
                continue
        elif context_re is None or context_re.search(normalized[lo:hi]) is None:
            continue
        raw_numbers = _numbers_in(match.group(1))
        if multiplier == 1:
            found |= raw_numbers
            continue
        for number in raw_numbers:
            try:
                converted = float(number) * multiplier
            except ValueError:
                continue
            found |= _numbers_in(f"{converted:.10f}".rstrip("0").rstrip("."))
    return found


def _grounded_usage_months(text: str) -> set[str]:
    """usage_months grounds against a month figure OR a year figure (x12)
    stated in a USAGE/rights/licence context — never a bare "N months"
    wherever it happens to sit (REPAIR ROUND 2 finding 4, U1/U3)."""
    return _numbers_with_context_unit(
        text, _MONTH_UNIT_RE, _USAGE_CONTEXT_RE
    ) | _numbers_with_context_unit(
        text, _YEAR_UNIT_RE, _USAGE_CONTEXT_RE, multiplier=12
    )


def _grounded_exclusivity_days(text: str) -> set[str]:
    """exclusivity_days grounds against a day figure, a month figure (x30) or
    a year figure (x365) stated in an EXCLUSIVITY context (REPAIR ROUND 2
    finding 4, U2/U4)."""
    spans = _exclusivity_spans(text)
    if not spans:
        return set()
    return (
        _numbers_with_context_unit(text, _DAY_UNIT_RE, None, context_spans=spans)
        | _numbers_with_context_unit(
            text, _MONTH_UNIT_RE, None, multiplier=30, context_spans=spans
        )
        | _numbers_with_context_unit(
            text, _YEAR_UNIT_RE, None, multiplier=365, context_spans=spans
        )
    )


# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 3 (MEDIUM): budget_inr and
# barter_mrp_inr shared ONE grounding set (`_amounts_in_inr` over the whole
# brief), so ANY literal number anywhere in the brief — a follower count, an
# unrelated product's MRP, a percentage — could ground either money field.
# Each field now also requires its OWN literal number to sit near a marker
# word for THAT field (budget/fee/pay/price/cost for budget_inr; worth/
# barter/mrp/value for barter_mrp_inr), or a bare currency marker that could
# reasonably belong to either. Indian-shorthand amounts ("15k", "1.5L") are
# still accepted regardless of a nearby marker — a "k"/"lakh"/"crore" suffix
# is unambiguously a money figure by itself, which is exactly why
# `_shorthand_expansions_in` is unioned in separately, unrestricted by field.
# Source: REPAIR ROUND 2 finding 3 (B1: an unrelated "50000 followers" count;
# B2: a barter product's own "worth 50000" grounding the CASH budget_inr).
#
# Two bugs found and fixed WHILE building this anchor (caught by this lane's
# own red/green, not a named finding): (1) `\d[\d,]*` — the number pattern
# used everywhere else in this file — greedily swallows a comma that is
# actually a SENTENCE separator, not a thousands separator, so "50000, 15k
# fee" let the after-marker pattern capture "50000," as its number while
# treating "15k" as an ordinary two-word filler and reaching "fee" — grounding
# the WRONG number for the marker it was never next to. `_MONEY_NUMBER_RE`
# requires a digit immediately after every comma it consumes, so a comma with
# nothing but whitespace after it ends the number instead of joining it to
# whatever comes next. (2) that same "filler word" pattern being `\w+` meant
# a token that IS itself a number (like "15k") could be silently skipped over
# as filler; filler words are now letters-only, so a competing number can
# never be hopped across to reach a marker meant for a different one.
_MONEY_NUMBER_RE = r"\d(?:,?\d)*(?:\.\d+)?"
_MONEY_MARKER_WINDOW_WORDS = r"(?:\s+[A-Za-z']+){0,2}?"
# T-GOLIVE-0918-R2 [ash · 2026-09-18] — markers are now bounded by letter
# lookarounds instead of `\b`: `\b₹` can never match (₹ and the space before
# it are both non-word characters), so "₹8000" on its own grounded nothing,
# and Hinglish/Devanagari money words ("budget ... dunge", "बजट", "फीस",
# "रुपये") were not markers at all. "per reel"/"per post" is a price marker
# ("15k per reel"). Source: Kabir round-1 verdict, B0-AI defects[0].
_MARK_START = r"(?<![A-Za-zऀ-ॿ])"
_CURRENCY_MARKER_RE_TEXT = r"₹|rs\.?|inr|rupees?|rupaye|rupay|रुपये|रुपए|रु\.?"
_OPTIONAL_CURRENCY = rf"(?:\s*(?:{_CURRENCY_MARKER_RE_TEXT}))?"
# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — HIGH: the budget markers
# are split into the currency signs and the FEE words; since T-GOLIVE-0918-R2
# CLASSIFY only a fee word (or a deliverable tie) grounds budget_inr (see
# `_grounded_budget_amounts`). "total"/"overall"/"all-in"/"lump sum" are fee
# words too (LOW: "25k total" dropped a genuine budget). Source: B0-AI
# repair-round-2 verdict on 3d88d58, defects 1 and 6.
_BUDGET_FEE_MARKER_RE_TEXT = (
    r"budgets?|fees?|pay|pays|payment|paying|paid|payout|total|overall|all-?in|each|apiece|"
    r"lump\s*sum|lumpsum|"
    # T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: a bare
    # "price"/"pricing"/"cost" is how a brief states its PRODUCT's price
    # ("Promote our new serum (price ₹999). Budget TBD." grounded 999), so
    # only a price qualified as the creator's or the collab's counts here;
    # "price per reel" is the per-deliverable marker below. The bare words
    # are barter markers instead (the product's value). Source: B0-AI
    # repair-round-2 verdict, defect 3.
    r"(?:your|creator|collab(?:oration)?|campaign|deal|content)\s+(?:price|pricing|cost|rate)|"
    r"compensation|remuneration|honorarium|offers?|offered|offering|"
    r"amount|commercials?|dunge|denge|de\s+sakte|milenge|paisa|paise|"
    r"per\s+(?:reels?|posts?|stor(?:y|ies)|videos?|shorts?|deliverables?|pieces?|integrations?)|"
    # T-GOLIVE-0918-R2 CLASSIFY [ash · 2026-09-19] — "एक रील के ₹5000 देंगे".
    # ("fixed"/"flat" count only AFTER an amount, see `_BUDGET_TIE_AFTER_RE`:
    # "Get flat ₹150 off" is a discount.) Source: independent reviewer on 694d643.
    r"बजट|फीस|फ़ीस|भुगतान|पेमेंट|देंगे|देंगी|दे\s+सकते"
)
_BARTER_MARKER_RE_TEXT = (
    rf"{_CURRENCY_MARKER_RE_TEXT}|worth|barter|bartered|mrp|value|valued|"
    r"retail\s+price|price|priced|pricing|costs?|कीमत|मूल्य"
)
# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI HIGH #1: a number
# (plain or shorthand) that COUNTS an audience is never money, however close a
# money word sits: "creators with 50k+ followers, budget to be discussed" and
# "1.5L followers wali creator chahiye, budget baad mein" both grounded an
# invented budget_inr (50000 / 150000). Checked on both sides of the number
# ("50k+ followers", "followers above 50k"). Source: Kabir round-1 verdict,
# B0-AI defects[0].
_AUDIENCE_NOUN_RE_TEXT = (
    r"followers?|follower\s+count|following|subs|subscribers?|views?|likes?|reach|"
    r"impressions?|audience|fans?|members?|downloads?|installs?|users?|customers?|"
    r"comments?|shares?|saves?|plays?|streams?|engagements?|viewers?|fan\s*base|"
    # T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — HIGH: the fixed
    # Devanagari spellings missed फ़ॉलोवर्स, the precomposed-nukta फ़ॉलोअर्स,
    # singular फॉलोअर, सब्सक्राइबर्स, लाइक्स and व्यूअर्स, so each of them
    # still let a follower count ground an invented budget. Written as
    # spelling-tolerant patterns (optional nukta, ो/ॉ, अ/व/ए glide, optional
    # plural ्स) over NFC text — `_normalize_digits` normalises the input.
    # Source: B0-AI repair-round-1 verdict, defect 2.
    "फ़?[ॉो]?ल[ोौ]?(?:अ|व|ए)?र(?:्?स)?|"
    "सब्सक्राइबर(?:्?स)?|"
    "लाइक(?:्?स)?|"
    "व्यू(?:ज़?|अर(?:्?स)?|वर(?:्?स)?|स)?"
)
_AUDIENCE_AFTER_RE = re.compile(
    rf"\s*\+?\s*(?:{_AUDIENCE_NOUN_RE_TEXT}){_WORD_END}", re.IGNORECASE
)
_AUDIENCE_BEFORE_RE = re.compile(
    rf"{_MARK_START}(?:{_AUDIENCE_NOUN_RE_TEXT}){_WORD_END}"
    rf"{_MONEY_MARKER_WINDOW_WORDS}\s*[:\-]?\s*$",
    re.IGNORECASE,
)
# The bare digits of a shorthand token ("15" in "15k", "1.5" in "1.5L") are
# not a number the brief states — see `_is_shorthand_digits`.
_SHORTHAND_UNIT_AFTER_RE = re.compile(
    rf"\s*(?:{_SHORTHAND_UNIT_RE_TEXT}){_WORD_END}", re.IGNORECASE
)
_CONTEXT_LOOKAROUND_CHARS = 80


def _is_shorthand_digits(text: str, end: int) -> bool:
    """True when the number ending at `end` is the digit part of a shorthand
    token ("15" of "15k", "1.5" of "1.5 lakh").

    T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: the
    money-marker regex captured those bare digits as if they were the stated
    amount, so "budget 15k" kept an UNEXPANDED budget_inr=15 and "budget
    1.5L" kept 1.5 — a Rs 15 budget reaching Java pricing exactly when the
    model fails prompt rule 9. The token's value is its EXPANSION, which
    `_shorthand_expansions_in` supplies; its digits alone ground nothing.
    Source: Kabir round-1 verdict, B0-AI defects[2]."""
    return _SHORTHAND_UNIT_AFTER_RE.match(text, end) is not None


def _is_audience_count(text: str, start: int, end: int) -> bool:
    if _AUDIENCE_AFTER_RE.match(text, end) is not None:
        return True
    return _AUDIENCE_BEFORE_RE.search(text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]) is not None


def _has_money_marker_near(text: str, start: int, end: int, marker_re_text: str) -> bool:
    """A marker word for this field within two (letters-only) filler words on
    either side of text[start:end] — "budget is 15k", "15k ka budget",
    "₹15k", "15,000 per reel", "बजट १५ हज़ार". Letters-only fillers mean a
    competing number can never be hopped across to reach a marker that
    belongs to a different number (REPAIR ROUND 2's comma/filler bugs)."""
    # T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — a currency sign may
    # sit between a fee word and its amount ("Budget ₹5,000", "₹5000 fee"), so
    # a fee-words-only marker set (see `_grounded_budget_amounts`) still
    # reaches it. Source: B0-AI repair-round-2 verdict on 3d88d58, defect 1.
    before_re = re.compile(
        rf"{_MARK_START}(?:{marker_re_text}){_WORD_END}{_MONEY_MARKER_WINDOW_WORDS}"
        rf"\s*[:\-]?{_OPTIONAL_CURRENCY}\s*$",
        re.IGNORECASE,
    )
    after_re = re.compile(
        rf"{_OPTIONAL_CURRENCY}\s*[:\-/]?{_MONEY_MARKER_WINDOW_WORDS}\s*{_MARK_START}"
        rf"(?:{marker_re_text}){_WORD_END}",
        re.IGNORECASE,
    )
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    if before_re.search(before) is not None:
        return True
    return after_re.match(text, end) is not None


# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — MEDIUM: a rupee amount
# that is NOT this brand's fee for this creator still grounded an invented
# budget_inr, because a currency sign or "paid" is a budget marker: "(₹1,499)
# in exchange for 1 reel. Barter collab." kept 1499, "Last month we paid 50k
# to another creator" kept 50000, "Use code GLOW20 for ₹200 off" kept 200,
# "Min order ₹499 for free shipping" kept 499. An amount sitting right next to
# a product-value, discount, order/shipping or past-payment phrase is not a
# fee, whatever marker it also has. Applied to budget_inr only — "(₹1,499) in
# exchange" is exactly the barter product's value, so barter_mrp_inr still
# grounds on it. Deliberately tight windows (the phrase must touch the amount,
# or sit across one or two short connecting words) so "budget 20k, use code
# X" does not veto the real 20k. Source: B0-AI repair-round-1 verdict, defect 5.
_NON_FEE_AFTER_RE = re.compile(
    r"[\s)\]]*(?:[:\-/]\s*)?(?:(?:for|free|flat|of|on|the|a|an|as)\s+){0,2}?"
    r"(?:off|discount\w*|cashback|shipping|delivery\s+charges?|in\s+exchange|"
    r"in\s+return|to\s+(?:another|other|a\s+different|some\s+other)|"
    r"last\s+(?:month|year|time|campaign)|earlier|previously|m\.?\s?r\.?\s?p\.?|worth|retail)"
    r"(?![A-Za-z])",
    re.IGNORECASE,
)
_NON_FEE_BEFORE_RE = re.compile(
    r"(?<![A-Za-z])(?:min(?:imum)?\.?\s+order(?:\s+(?:value|of|above))?|"
    r"orders?\s+(?:above|over|of|worth)|discount\s+of|save|cashback\s+of|"
    r"last\s+(?:month|year|time|campaign)(?:\s+[A-Za-z']+){0,3}?|"
    r"previously(?:\s+[A-Za-z']+){0,2}?|m\.?\s?r\.?\s?p\.?|worth|priced\s+at|"
    # T-GOLIVE-0918-R2 RATE-REQUEST [ash · 2026-09-19] — HIGH: a price attached
    # to a product word is never the fee: "retails at ₹1299", "retail ₹1299",
    # "sells for ₹1299", "selling price ₹1299", "M.R.P. ₹1299" all grounded
    # budget_inr. Source: independent reviewer, rate-request finding.
    r"retail(?:s|ing)?(?:\s+(?:price|at|for))?|selling\s+(?:price|at|for)|"
    r"(?:sells?|sold|listed)\s+(?:at|for)|offer\s+price|"
    # T-GOLIVE-0918-R2 CLASSIFY [ash · 2026-09-19] — the Hindi product-price
    # words ("सीरम की कीमत ₹1299"). Source: independent reviewer on 694d643.
    r"कीमत|दाम|मूल्य)"
    r"\s*[:\-]?\s*(?:₹|rs\.?|inr)?\s*$",
    re.IGNORECASE,
)


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: more amounts
# that are not this creator's fee. A product's own price ("serum (price
# ₹999)", "costs ₹999") — unless the price is the creator's or the collab's
# ("your price", "collab cost") — and a refund/reimbursement ("we refund
# ₹1,299 after posting", "₹1,299 will be refunded"). Source: B0-AI
# repair-round-2 verdict, defect 3.
_PRODUCT_PRICE_BEFORE_RE = re.compile(
    r"(?<![A-Za-z])(?P<qual>[A-Za-z]+\s+)?(?:price[ds]?|pricing|costs?|refund\w*|"
    r"reimburs\w*)(?:\s+(?:is|of|at|will\s+be|back))?\s*[:\-(]?\s*(?:₹|rs\.?|inr)?\s*$",
    re.IGNORECASE,
)
_FEE_PRICE_QUALIFIERS = frozenset(
    {"your", "creator", "collab", "collaboration", "campaign", "deal", "content", "reel", "post"}
)
_REFUND_AFTER_RE = re.compile(
    r"\s*(?:(?:will\s+be|to\s+be|is|gets?|get|as\s+a?)\s+)?(?:refund\w*|reimburs\w*)(?![A-Za-z])",
    re.IGNORECASE,
)


def _is_product_price_or_refund(text: str, start: int, end: int) -> bool:
    if _REFUND_AFTER_RE.match(text, end) is not None:
        return True
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    match = _PRODUCT_PRICE_BEFORE_RE.search(before)
    if match is None:
        return False
    qualifier = (match.group("qual") or "").strip().lower()
    return qualifier not in _FEE_PRICE_QUALIFIERS


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: a phone number
# ("Call 98200 12345 to discuss budget" grounded 12345) and a calendar year
# ("Budget for 2026 campaign tbd" grounded 2026) are not amounts. A number is a
# phone number when it is part of a digit run (spaces/dashes allowed) of 8+
# digits, or follows call/phone/whatsapp/+91; a 19xx/20xx figure is a year when
# a period noun follows it or in/since/till/year/FY precedes it, and no
# currency sign touches it ("₹2026" stays an amount). Applied to every money
# field and to summary-line money. Source: B0-AI repair-round-2 verdict,
# defect 3.
_PHONE_CUE_BEFORE_RE = re.compile(
    r"(?:(?<![A-Za-z])(?:call|phone|mobile|mob|ph|tel|whats\s*app|wa|contact|number|no)"
    r"\.?\s*(?:(?:on|at|us|me|number|no)\.?\s*)?[:\-]?\s*|\+\s*91[\s\-]*)$",
    re.IGNORECASE,
)
_DIGIT_RUN_RE = re.compile(r"\+?\d[\d \-]*\d|\d")
_YEAR_RE = re.compile(r"(?:19|20)\d\d")
_YEAR_NOUN_AFTER_RE = re.compile(
    r"\s*(?:-\s*\d{2,4}\s*)?(?:campaigns?|seasons?|launch\w*|collections?|editions?|series|"
    r"drops?|range|sales?|festive|calendar|year|fy|q[1-4]|batch|model|version|onwards?|"
    r"diwali|holi|christmas|summer|winter|monsoon|spring|autumn|wedding)(?![A-Za-z])",
    re.IGNORECASE,
)
_YEAR_CUE_BEFORE_RE = re.compile(
    r"(?<![A-Za-z])(?:in|since|till|until|year|fy|calendar|of\s+year|circa|est\.?|estd\.?|"
    r"established|founded)\s*$",
    re.IGNORECASE,
)
_CURRENCY_TOUCHING_BEFORE_RE = re.compile(r"(?:₹|rs\.?|inr|rupees?)\s*$", re.IGNORECASE)
_CURRENCY_TOUCHING_AFTER_RE = re.compile(r"\s*(?:₹|rs\b|inr\b|rupees?|/-)", re.IGNORECASE)


def _is_phone_number(text: str, start: int, end: int) -> bool:
    before = text[max(0, start - 30) : start]
    if _PHONE_CUE_BEFORE_RE.search(before) is not None:
        return True
    for run in _DIGIT_RUN_RE.finditer(text, max(0, start - 20), min(len(text), end + 20)):
        if run.start() <= start and run.end() >= end:
            return sum(ch.isdigit() for ch in run.group(0)) >= 8
    return False


def _is_year(text: str, start: int, end: int) -> bool:
    if _YEAR_RE.fullmatch(text[start:end]) is None:
        return False
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    if _CURRENCY_TOUCHING_BEFORE_RE.search(before) or _CURRENCY_TOUCHING_AFTER_RE.match(text, end):
        return False
    return (
        _YEAR_NOUN_AFTER_RE.match(text, end) is not None
        or _YEAR_CUE_BEFORE_RE.search(before) is not None
    )


def _is_non_fee_amount(text: str, start: int, end: int) -> bool:
    if _is_not_an_amount(text, start, end):
        return True
    if _NON_FEE_AFTER_RE.match(text, end) is not None:
        return True
    if _is_product_price_or_refund(text, start, end):
        return True
    if _is_fundraise_or_voucher(text, start, end):
        return True
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    return _NON_FEE_BEFORE_RE.search(before) is not None


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM: a day count, a
# percentage or a deliverable count that sits next to a payment word is not an
# amount, yet each grounded budget_inr: "payment within 7 days" gave 7.0, "Pay
# 50% advance" 50.0, "payment net 30" 30.0, "payment 2 hafte me hogi" 2.0,
# "भुगतान 45 दिन में" 45.0, "Pay attention: 3 reels" 3.0. A number is not an
# amount when a time unit or "%" follows it, "net" precedes it, or a
# deliverable noun follows it — unless a currency sign touches it ("₹30 per
# day" stays money). Applied to every money field and to summary-line money
# (through `_is_non_fee_amount`, which `_money_role_in_line` reads), so the
# line "Payment within 7 days" restating the brief is still kept.
# Source: B0-AI repair-round-2 verdict on 3d88d58, defect 5.
_NOT_AN_AMOUNT_AFTER_RE = re.compile(
    r"\s*-?\s*(?:%|percent|per\s*cent|pc|days?|din|दिन|weeks?|wks?|hafte|hafta|haftey|"
    r"हफ्ते|हफ़्ते|हफ्ता|months?|mahine|mahina|महीने|महीना|years?|yrs?|saal|साल|hrs?|hours?|"
    r"ghante|घंटे|mins?|minutes?|secs?|seconds?)(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)
_NET_BEFORE_RE = re.compile(r"(?<![A-Za-z])net\s*-?\s*$", re.IGNORECASE)


def _is_not_an_amount(text: str, start: int, end: int) -> bool:
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    if _CURRENCY_TOUCHING_BEFORE_RE.search(before) or _CURRENCY_TOUCHING_AFTER_RE.match(text, end):
        return False
    if _NOT_AN_AMOUNT_AFTER_RE.match(text, end) is not None:
        return True
    if _NET_BEFORE_RE.search(before) is not None:
        return True
    return _COUNT_NOUN_AFTER_RE.match(text, end) is not None


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM: "raised Rs 20
# lakh seed" grounded budget_inr 2000000.0 and "Rs 1000 Amazon voucher"
# grounded 1000.0. Money the brand raised, earned or is worth, and a voucher,
# gift card or prize, is not a cash fee for this creator. Budget only; the
# clause must hold the word within a few words of the amount.
# Source: B0-AI repair-round-2 verdict on 3d88d58, defect 5.
_FUNDRAISE_RE = re.compile(
    r"(?<![A-Za-z])(?:raised?|raising|funding|funded|seed|series\s+[a-e]|valuation|"
    r"revenue|turnover|gmv|arr|mrr|investment|invested|investors?)(?![A-Za-z])",
    re.IGNORECASE,
)
_VOUCHER_AFTER_RE = re.compile(
    r"(?:\s+[A-Za-z']+){0,2}?\s*(?:vouchers?|gift\s*cards?|gift\s+vouchers?|coupons?|"
    r"store\s+credits?|wallet\s+credits?|prizes?|giveaways?)(?![A-Za-z])",
    re.IGNORECASE,
)


def _is_fundraise_or_voucher(text: str, start: int, end: int) -> bool:
    if _VOUCHER_AFTER_RE.match(text, end) is not None:
        return True
    lo, hi = _clause_bounds(text, start, end, 25)
    return _FUNDRAISE_RE.search(text, lo, hi) is not None


# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — MEDIUM (same probe):
# the "20" of a coupon code "GLOW20" was read as an amount, one filler word
# from "for ₹200". Digits glued to a preceding letter are part of a code or
# name, except "Rs500"/"INR500". Source: B0-AI repair-round-1 verdict, defect 5.
_LETTER_GLUED_BEFORE_RE = re.compile(r"[A-Za-z]$")
_CURRENCY_GLUED_BEFORE_RE = re.compile(r"(?<![A-Za-z])(?:rs|inr)$", re.IGNORECASE)


def _is_glued_to_a_word(text: str, start: int) -> bool:
    before = text[max(0, start - 4) : start]
    return (
        _LETTER_GLUED_BEFORE_RE.search(before) is not None
        and _CURRENCY_GLUED_BEFORE_RE.search(before) is None
    )


# A money-shaped number: not the tail of a longer number or of a decimal.
_MONEY_NUMBER_SCAN_RE = re.compile(rf"(?<!\d)(?<!\d[.,]){_MONEY_NUMBER_RE}")


def _numbers_with_money_marker(
    text: str, marker_re_text: str, *, veto_non_fee: bool = False
) -> set[str]:
    """Every literal number in `text` that sits within two filler words of one
    of the marker words in `marker_re_text`, on either side ("budget is
    8000", "8000 INR", "worth 50000") — except the digits of a shorthand token
    (grounded by their expansion instead), a number that counts an audience
    ("50000 followers"), digits glued to a word ("GLOW20") and, when
    `veto_non_fee`, an amount that is a discount/order/product value/past
    payment rather than a fee (`_is_non_fee_amount`)."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in _MONEY_NUMBER_SCAN_RE.finditer(normalized):
        start, end = match.span()
        if _is_shorthand_digits(normalized, end):
            continue
        if _is_glued_to_a_word(normalized, start):
            continue
        if _is_audience_count(normalized, start, end):
            continue
        if _is_phone_number(normalized, start, end) or _is_year(normalized, start, end):
            continue
        if _is_not_an_amount(normalized, start, end):
            continue
        if veto_non_fee and _is_non_fee_amount(normalized, start, end):
            continue
        if _has_money_marker_near(normalized, start, end, marker_re_text):
            found |= _numbers_in(match.group(0))
    return found


def _shorthand_expansions_with_money_marker(
    text: str, marker_re_text: str, *, veto_non_fee: bool = False
) -> set[str]:
    """`_shorthand_expansions_in`, restricted to shorthand tokens that sit in
    THIS field's money context and do not count an audience.

    T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI HIGH #1: REPAIR
    ROUND 2 unioned every shorthand expansion into both money fields on the
    assumption that a "k"/"L"/"lakh" suffix is always money. It is not: in
    Indian briefs the same suffix is how follower counts, views and reach are
    written ("50k+ followers", "1.5L followers wali creator"). A shorthand
    amount now grounds budget_inr / barter_mrp_inr only under the same
    marker rule a plain figure must meet. Source: Kabir round-1 verdict,
    B0-AI defects[0]."""
    values: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in _SHORTHAND_RE.finditer(normalized):
        start, end = match.span()
        if _is_glued_to_a_word(normalized, start):
            continue
        if _is_audience_count(normalized, start, end):
            continue
        if veto_non_fee and _is_non_fee_amount(normalized, start, end):
            continue
        if not _has_money_marker_near(normalized, start, end, marker_re_text):
            continue
        values |= _shorthand_expansions_in(match.group(0))
    return values


def _grounded_budget_amounts(text: str) -> set[str]:
    """The amounts in `text` classified as the BUDGET — the only figures
    budget_inr (and a fee-worded summary line) may carry.

    T-GOLIVE-0918-R2 CLASSIFY [ash · 2026-09-19] — design ruling (Priya, CTO)
    after the independent reviewer failed 694d643. Each money amount is
    classified by its OWN neighbourhood, never by what the rest of the brief
    says:

      1. PRODUCT: next to a product-price marker (MRP, price, costs, worth,
         retail, sells/sold/listed at/for, offer price, कीमत, दाम —
         `_is_non_fee_amount`) — never the budget. A figure written directly
         after a product noun ("Serum ₹999", "सीरम ₹1299") is a product
         price too, unless a fee word claims it.
      2. BUDGET: tied in the same clause to a fee word (budget, fee, pay,
         fixed, flat, total, per reel, बजट, देंगे — `_BUDGET_FEE_MARKER_RE_TEXT`)
         or to a deliverable ("for 1 reel", "1 reel = ₹6000", "1 reel
         ₹6000", "एक रील के लिए ₹5000").
      3. Neither — a figure with only a currency sign — is not the budget.

    The whole-brief "is this a rate request" detector (694d643) is gone: it
    missed "Kitna loge?" / "Quote karo" / "आप कितना लेंगे?", so the product
    price became the budget, and it fired on "Send rate card if higher" /
    "shipping charges are on us", rejecting a stated "Rs 8000 for 1 reel".
    A currency sign alone no longer grounds budget_inr in ANY brief, so no
    phrasing of a rate request can make a product price the budget, and no
    phrasing elsewhere in the brief can veto a fee tied to its deliverable.
    When unsure, the amount is not the budget: a missing budget is safe, a
    wrong one misleads the creator. Source: independent reviewer on 694d643,
    HIGH-1, HIGH-2, MEDIUM-3."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in _MONEY_NUMBER_SCAN_RE.finditer(normalized):
        start, end = match.span()
        if _is_shorthand_digits(normalized, end) or _is_glued_to_a_word(normalized, start):
            continue
        if _is_audience_count(normalized, start, end):
            continue
        if _is_phone_number(normalized, start, end) or _is_year(normalized, start, end):
            continue
        if _is_not_an_amount(normalized, start, end):
            continue
        if _is_budget_amount(normalized, start, end):
            found |= _numbers_in(match.group(0))
    for match in _SHORTHAND_RE.finditer(normalized):
        start, end = match.span()
        if _is_glued_to_a_word(normalized, start) or _is_audience_count(normalized, start, end):
            continue
        if _is_budget_amount(normalized, start, end):
            found |= _shorthand_expansions_in(match.group(0))
    return found


# The deliverable nouns a fee can be tied to ("₹6000 for 1 reel").
_TIE_DELIVERABLE_NOUN_RE_TEXT = (
    r"reels?|posts?|stor(?:y|ies)|videos?|shorts?|deliverables?|pieces?|integrations?|"
    r"carousels?|statics?|ugc|रील्?स?|पोस्ट|स्टोरीज?|स्टोरी|वीडियो"
)
_TIE_COUNT_RE_TEXT = r"(?:\d+|a|an|one|ek|एक|single|the|each|every|1st|first)"
# Amount THEN deliverable: "₹6000 for 1 reel", "10,000/- for 1 reel", "₹5000/reel",
# "10k for 2 reels", "₹5000 for 1 Instagram reel".
# "Rs 7500 fixed" / "₹8000 flat" also tie the amount as the fee; "प्रति रील" is
# "per reel".
_BUDGET_TIE_AFTER_RE = re.compile(
    r"\s*(?:/-)?\s*(?:(?:for|per|each|/|ke\s+liye|के\s+लिए|प्रति|x)\s*"
    rf"(?:{_TIE_COUNT_RE_TEXT}\s+)?(?:[A-Za-z]+\s+)?"
    rf"{_MARK_START}(?:{_TIE_DELIVERABLE_NOUN_RE_TEXT})|fixed|flat|"
    # "₹5000 एक रील के लिए" / "5k 1 reel ke liye": the Hindi postposition
    # follows the deliverable.
    rf"(?:{_TIE_COUNT_RE_TEXT}\s+)?{_MARK_START}(?:{_TIE_DELIVERABLE_NOUN_RE_TEXT})"
    rf"\s+(?:ke\s+liye|के\s+लिए)){_WORD_END}",
    re.IGNORECASE,
)
# Deliverable THEN amount: "1 reel = ₹6000", "1 reel ₹6000", "1 reel for ₹6000",
# "1 reel: ₹6000", "1 reel ke 5000", "एक रील के लिए ₹5000".
_BUDGET_TIE_BEFORE_RE = re.compile(
    rf"{_MARK_START}(?:{_TIE_DELIVERABLE_NOUN_RE_TEXT}){_WORD_END}\s*"
    r"(?:(?:=|:|-|–|@|for|ke(?:\s+liye)?|के(?:\s+लिए)?|ka|का|की)\s*)?"
    rf"{_OPTIONAL_CURRENCY}\s*$",
    re.IGNORECASE,
)
def _is_budget_amount(text: str, start: int, end: int) -> bool:
    """Rule 1-3 of `_grounded_budget_amounts` for the amount text[start:end]."""
    if _is_non_fee_amount(text, start, end):
        return False
    if _has_money_marker_near(text, start, end, _BUDGET_FEE_MARKER_RE_TEXT):
        return True
    # T-GOLIVE-0918-R2 CLASSIFY-R2 [ash · 2026-09-19] — HIGH: the "<Product>
    # ₹1299" check that ran here beat the deliverable/fee tie, so a brand or
    # product name before the fee ("Glow ₹5000 for 1 reel", "Serum ₹5000/reel",
    # "Boat ₹8000 fixed") dropped a genuine budget. Priya's ruling: a
    # deliverable or fee tie in the same clause beats a bare preceding product
    # noun. The check is removed; product prices stay out through their own
    # markers (MRP, price, costs, worth, दाम, कीमत — `_is_non_fee_amount`) and
    # because an untied amount never grounds a budget. Source: independent
    # reviewer on 373acba (18 product-price probes, none protected by it).
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    return (
        _BUDGET_TIE_AFTER_RE.match(text, end) is not None
        or _BUDGET_TIE_BEFORE_RE.search(before) is not None
    )


def _grounded_barter_amounts(text: str) -> set[str]:
    return _numbers_with_money_marker(
        text, _BARTER_MARKER_RE_TEXT
    ) | _shorthand_expansions_with_money_marker(text, _BARTER_MARKER_RE_TEXT)


class _SplitNumbers(NamedTuple):
    """The numbers of a text, split by whether each one counts an audience.
    `plain` are figures written out ("50,000"), `expanded` are shorthand
    tokens' values ("50k" -> 50000); a shorthand token's own digits are in
    neither (see `_is_shorthand_digits`)."""

    plain: set[str]
    expanded: set[str]
    audience_plain: set[str]
    audience_expanded: set[str]


# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — HIGH: the audience veto
# covered budget_inr/barter_mrp_inr but not SUMMARY LINES, which were checked
# against every amount in the brief. "Glow: 1 reel for creators with 50k+
# followers, budget to be discussed." kept the invented lines "Brand offers
# 50k" and "Budget is 50,000"; "1.5L followers ... budget baad mein" kept
# "Budget 1.5L"; "2 lakh followers ... Budget to be discussed" kept "Pay is 2
# lakh" — an invented brand offer on the paste card while budget_inr was
# correctly null. Both the brief's numbers and the line's numbers are now split
# by `_is_audience_count`: a line may restate an audience count only AS an
# audience count ("Creators need 50k+ followers"); any other number in the line
# must be a number the brief states that is not an audience count.
# Source: B0-AI repair-round-1 verdict, defect 1.
def _split_numbers_by_audience(text: str) -> _SplitNumbers:
    normalized = _normalize_digits(text or "")
    split = _SplitNumbers(set(), set(), set(), set())
    for match in _NUMBER_RE.finditer(normalized):
        if _is_shorthand_digits(normalized, match.end()):
            continue
        numbers = _numbers_in(match.group(0))
        if _is_audience_count(normalized, match.start(), match.end()):
            split.audience_plain.update(numbers)
        else:
            split.plain.update(numbers)
    for match in _SHORTHAND_RE.finditer(normalized):
        values = _shorthand_expansions_in(match.group(0))
        if _is_audience_count(normalized, match.start(), match.end()):
            split.audience_expanded.update(values)
        else:
            split.expanded.update(values)
    return split


def _own_numbers(extraction: dict[str, Any]) -> set[str]:
    """The numbers the extraction itself established, so a summary line may
    restate them even when the brief wrote them in words ("eight thousand")
    rather than figures.

    Deliberately EXCLUDES budget_inr, barter_mrp_inr, usage_months and
    exclusivity_days: those four are now grounded against the brief before
    they ever reach this function (see the grounding checks in
    `parse_and_validate_extraction`), so they must not ALSO vouch for
    summary-line numbers on their own — that was Ash's G1 finding
    (ash-answers.md): an invented field used to let a matching invented
    summary line survive uncaught.

    Deliverable qty IS included here, but only because `_clean_deliverables`
    (REPAIR ROUND 1 finding 1, HIGH) now grounds it against the brief itself
    before it reaches this dict — an ungrounded qty is reset to the default of
    1 there, so by the time it arrives here it is already trustworthy, the
    same reasoning that keeps the four money/count fields above out of this
    function. max_revisions is included on the same terms: since
    T-GOLIVE-0918-R2 it is grounded against a revision count in the brief
    (`_grounded_revision_counts`) before it gets here, so an invented one is
    already None and can no longer vouch for its own summary line."""
    candidates: list[Any] = [extraction.get("max_revisions")]
    deliverables = extraction.get("deliverables")
    if isinstance(deliverables, list):
        candidates.extend(
            line.get("qty") for line in deliverables if isinstance(line, dict)
        )
    numbers: set[str] = set()
    for value in candidates:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        numbers |= _numbers_in(f"{value:.10f}".rstrip("0").rstrip("."))
    return numbers


def _grounded_amount(value: float | None, grounded: set[str]) -> float | None:
    """Keeps a numeric field only when its value (as a bare-digit string,
    matching `_numbers_in`'s normalisation) is in `grounded` — the brief's own
    numbers plus their shorthand expansions. Ash's probe P1: a "15k" brief
    with an invented budget_inr=50000 must not survive; P3: usage_months=12
    for a "3 months" brief must not survive."""
    if value is None:
        return None
    as_number = f"{value:.10f}".rstrip("0").rstrip(".")
    digits = (_numbers_in(as_number) or {"0"}).pop()
    return value if digits in grounded else None


def _grounded_int(value: int | None, grounded: set[str]) -> int | None:
    if value is None:
        return None
    digits = str(abs(int(value))).lstrip("0") or "0"
    return value if digits in grounded else None


# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 3 (MEDIUM): the previous
# `_grounded_deadline` checked only that the day-of-month and the year each
# appeared as SOME digit run somewhere in the brief, independently of each
# other and of the month. That let an invented deadline sharing just those two
# digits with an UNRELATED date (or with unrelated numbers, e.g. a budget
# shorthand token) pass: "live by 2026-10-05" grounded an invented
# "2026-12-05" (day and year matched, month never checked); "Glow 2026
# campaign ... 25k budget" grounded an invented "2026-11-25" (year from "2026",
# day from the "25" inside "25k", no real date in the brief at all). The fix
# below requires the model's exact (year, month, day) triple to appear as one
# date literally written in the brief, in a handful of common formats, rather
# than checking each component in isolation. Source: REPAIR ROUND 1 finding 3.
_MONTH_NAMES: dict[str, int] = {
    "jan": 1, "january": 1, "feb": 2, "february": 2, "mar": 3, "march": 3,
    "apr": 4, "april": 4, "may": 5, "jun": 6, "june": 6, "jul": 7, "july": 7,
    "aug": 8, "august": 8, "sep": 9, "sept": 9, "september": 9, "oct": 10,
    "october": 10, "nov": 11, "november": 11, "dec": 12, "december": 12,
}
# YYYY-MM-DD or YYYY/MM/DD.
_ISO_DATE_RE = re.compile(r"\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b")
# DD-MM-YYYY or DD/MM/YYYY (the common Indian day-first written form).
_DMY_DATE_RE = re.compile(r"\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b")
# "5 October 2026" / "5th Oct 2026".
_DAY_MONTH_YEAR_RE = re.compile(
    r"\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})\b"
)
# "October 5, 2026" / "Oct 5 2026".
_MONTH_DAY_YEAR_RE = re.compile(
    r"\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b"
)


# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — LOW: "Sent on
# 2026-10-30" grounded deadline=2026-10-30. A date the brief labels as when the
# message was sent/dated/received/written is a record of the past, not a due
# date, so it does not ground `deadline`. Source: B0-AI repair-round-1
# verdict, defect 6.
_RECORD_DATE_BEFORE_RE = re.compile(
    r"(?<![A-Za-z])(?:sent|dated|received|written|emailed|mailed|shared|drafted|"
    r"issued|date\s+of\s+(?:email|message|brief))\s*(?:on|:)?\s*$",
    re.IGNORECASE,
)


def _dates_in(text: str) -> set[tuple[int, int, int]]:
    """Every full (year, month, day) date literally written in `text`, across
    a handful of common formats. Deliberately requires a YEAR alongside the
    day and month for every form: a bare day+month with no year, a month name
    with no digits, or digits with no unambiguous date shape cannot pin the
    exact date the model's `deadline` is required to match, so none of those
    are collected here."""
    found: set[tuple[int, int, int]] = set()
    normalized = _normalize_digits(text or "")

    def _is_record_date(match: re.Match[str]) -> bool:
        before = normalized[max(0, match.start() - 30) : match.start()]
        return _RECORD_DATE_BEFORE_RE.search(before) is not None

    for match in _ISO_DATE_RE.finditer(normalized):
        if not _is_record_date(match):
            found.add((int(match.group(1)), int(match.group(2)), int(match.group(3))))
    for match in _DMY_DATE_RE.finditer(normalized):
        if not _is_record_date(match):
            found.add((int(match.group(3)), int(match.group(2)), int(match.group(1))))
    for match in _DAY_MONTH_YEAR_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(2).lower())
        if month is not None and not _is_record_date(match):
            found.add((int(match.group(3)), month, int(match.group(1))))
    for match in _MONTH_DAY_YEAR_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(1).lower())
        if month is not None and not _is_record_date(match):
            found.add((int(match.group(3)), month, int(match.group(2))))
    return found


def _grounded_deadline(value: str | None, raw_text: str) -> str | None:
    """Keeps `deadline` only when it is a real, non-past ISO date AND that
    exact (year, month, day) is one of the dates literally written in the
    brief (`_dates_in`). Ash's probe P2: "Post by Diwali" (a relative date, no
    digits at all) produced an invented deadline that also happened to be in
    the past — either defect alone is disqualifying here, and a relative date
    with no digits can never appear in `_dates_in` even in a year where the
    model's guess lands in the future."""
    if value is None:
        return None
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        return None
    if parsed < datetime.now(timezone.utc).date():
        return None
    if (parsed.year, parsed.month, parsed.day) not in _deadline_dates_in(raw_text):
        return None
    return value


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: any date in the
# brief grounded the deadline apart from "Sent on": "Offer valid till
# 31/12/2026 for customers. Post anytime." kept 2026-12-31 and "Batch no
# 2026/11/15" kept 2026-11-15. A date now grounds `deadline` only when the
# same clause frames it as one — a cue before it ("by", "before", "deadline",
# "due", "live", "post on", "submit", "tak", ...) or right after it ("...
# deadline", "... tak") — and nothing in the clause before it marks it as an
# offer validity, expiry, batch/lot/invoice/order number or founding date.
# Fail-closed: an unframed date leaves deadline null, which is "no deadline
# stated". Source: B0-AI repair-round-2 verdict, defect 5.
_DEADLINE_CUE_RE = re.compile(
    r"(?<![A-Za-z])(?:by|before|till|until|upto|up\s+to|deadline|due|live|go-?live|"
    r"timeline|post(?:ed|ing)?|publish\w*|submi\w*|deliver\w*|drafts?|latest|tak|pehle|"
    # T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — LOW: "launch\w*"
    # removed — "launches on 1 Nov 2026" is the product's launch, not when
    # the creator must post, and grounded an invented deadline. The Devanagari
    # "तक" / "से पहले" after a date is now a cue ("20/12/2026 तक पोस्ट करें"
    # left deadline null). Source: B0-AI repair-round-2 verdict on 3d88d58,
    # defect 6.
    r"schedul\w*|last\s+date|no\s+later\s+than|on\s+or\s+before)(?![A-Za-z])",
    re.IGNORECASE,
)
_DEADLINE_CUE_AFTER_RE = re.compile(
    r"\s*(?:,\s*)?(?:deadline|tak|se\s+pehle|ke\s+pehle|last\s+date|तक|से\s+पहले|के\s+पहले)"
    r"(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)
_NON_DEADLINE_CUE_RE = re.compile(
    r"(?<![A-Za-z])(?:valid\w*|validity|expir\w*|exp|offer\s+ends?|sale\s+ends?|"
    r"batch|lot|mfg|manufactur\w*|best\s+before|use\s+by|invoice|order|ref\w*|"
    r"dob|born|since|established|estd|founded|registered)(?![A-Za-z])|(?:no\.?|number|#)\s*[:\-]?\s*$",
    re.IGNORECASE,
)
_ANY_DATE_RES = (_ISO_DATE_RE, _DMY_DATE_RE, _DAY_MONTH_YEAR_RE, _MONTH_DAY_YEAR_RE)


def _deadline_dates_in(text: str) -> set[tuple[int, int, int]]:
    """The subset of `_dates_in` that the brief frames as a deadline."""
    normalized = _normalize_digits(text or "")
    framed: set[tuple[int, int, int]] = set()
    for pattern in _ANY_DATE_RES:
        for match in pattern.finditer(normalized):
            date_parts = _dates_in(match.group(0))
            if not date_parts:
                continue
            lo, _ = _clause_bounds(normalized, match.start(), match.end(), 40)
            before = normalized[lo : match.start()]
            if _NON_DEADLINE_CUE_RE.search(before) is not None:
                continue
            if _RECORD_DATE_BEFORE_RE.search(before) is not None:
                continue
            if (
                _DEADLINE_CUE_RE.search(before) is not None
                or _DEADLINE_CUE_AFTER_RE.match(normalized, match.end()) is not None
            ):
                framed |= date_parts
    return framed


# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 8 (MEDIUM): a plain substring
# match let a short candidate match INSIDE an unrelated word ("Co" inside
# "Cosmetics") and let a brand named only to be EXCLUDED read as the client's
# own brand ("No Nykaa posts for 60 days" grounded brand_name="Nykaa"). The
# word-boundary regex fixes the first; the exclusion-context check below
# catches the common "no/not/excluding/competitor <name>" phrasing for the
# second. This is a heuristic, not full disambiguation of every mention of a
# name in a brief — reported as a remaining gap in the round-trip summary.
# Source: REPAIR ROUND 1 finding 8.
#
# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 5 (MEDIUM): round 1's check
# only looked BEFORE the name, for a fixed trigger word directly followed by
# 0-2 filler words then the name — three real phrasings still passed:
#   - "Don't post for Nykaa": "don't" is a contraction, not the word "not",
#     so the trigger list never matched it at all.
#   - "Competitors: Nykaa, Mamaearth not allowed": "Mamaearth" is the SECOND
#     name after a comma (the filler-word pattern `(?:\w+\s+){0,2}` requires
#     each filler to be followed by whitespace, which "Nykaa," is not), and
#     its own exclusion word ("not allowed") comes AFTER it, which a
#     before-only check can never see.
#   - "Nykaa ke saath ... kaam mat karna" (Hinglish "don't work with Nykaa"):
#     the negation ("mat") is a Hinglish word after the name, entirely outside
#     the English-only trigger list.
# Rather than one rigid "trigger, then name" template, this now checks a
# small window of text on BOTH sides of the name for any trigger word,
# English or Hinglish — which catches a leading "don't"/"competitors" and a
# trailing "not allowed"/"mat"/"nahi" alike. Source: REPAIR ROUND 2 finding 5
# (N1/N2/N3).
_BRAND_EXCLUSION_TRIGGER_RE = re.compile(
    r"\b(?:no|not|never|except|excluding|compet\w*|don'?t|doesn'?t|avoid\w*|"
    r"block(?:ed|ing)?\w*|banned?|disallow\w*|mat|nahin?\w*)\b",
    re.IGNORECASE,
)
# A short window (roughly 3-5 words either side): wide enough to span "Don't
# post for Nykaa" or "Nykaa ... mat karna", narrow enough that an unrelated
# trigger word attached to a DIFFERENT brand mention earlier in the brief
# (e.g. "No Nykaa posts ... Glow Cosmetics wants...") does not also exclude
# this one. A heuristic, not full disambiguation — same caveat as round 1.
_BRAND_EXCLUSION_WINDOW = 25
# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — LOW: a brand named only
# as a REFERENCE grounded brand_name: "Loved your Nykaa reel" and "hum
# Mamaearth jaise hain" ("we are like Mamaearth"). A name directly after
# "your"/"like"/"than"/"vs"/"similar to"/"inspired by"/"such as", or directly
# followed by "jaise/jaisa/jaisi"/"ki tarah"/"type"/"style", is a reference,
# not the sender. Directly adjacent only, so "Glow would like 1 reel" does not
# veto Glow. Source: B0-AI repair-round-1 verdict, defect 6.
_BRAND_REFERENCE_BEFORE_RE = re.compile(
    # "unlike"/"via"/"through" added T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash ·
    # 2026-09-18] — LOW: "Unlike Minimalist, we are vegan" grounded
    # "Minimalist". Source: B0-AI repair-round-2 verdict, defect 7.
    r"(?<![A-Za-z])(?:your|like|unlike|than|vs\.?|versus|similar\s+to|inspired\s+by|"
    r"compared\s+to|such\s+as|via|through)\s+$",
    re.IGNORECASE,
)
_BRAND_REFERENCE_AFTER_RE = re.compile(
    r"\s+(?:jaise|jaisa|jaisi|ki\s+tarah|type|style)(?![A-Za-z])", re.IGNORECASE
)
# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — LOW: "Found you on
# Instagram" grounded brand_name "Instagram" and "I'm from BuzzMedia agency,
# reaching out for a client" grounded "BuzzMedia". The platform a brief was
# sent through is never the client, and a name the brief itself calls an
# agency is the intermediary, not the brand whose deal it is. Source: B0-AI
# repair-round-2 verdict, defect 7.
_PLATFORM_NAMES = frozenset(
    {
        "instagram", "insta", "ig", "youtube", "yt", "facebook", "fb", "whatsapp",
        "tiktok", "linkedin", "twitter", "x", "snapchat", "moj", "josh", "telegram",
        "gmail", "email", "dm", "threads", "pinterest", "google",
    }
)
_AGENCY_AFTER_RE = re.compile(
    r"\s*(?:\(|,)?\s*(?:(?:media|pr|marketing|digital|talent|influencer|creative|ad)\s+)?"
    r"(?:agency|agencies|talent\s+management)(?![A-Za-z])",
    re.IGNORECASE,
)


def _grounded_brand_name(value: str | None, raw_text: str) -> str | None:
    """Keeps `brand_name` only when it appears as a whole word/phrase
    (case-insensitively) in the brief, AND has no exclusion trigger word
    (English or Hinglish) within a short window on either side of it — that
    phrasing names a brand the creator must AVOID, not the brand who sent this
    brief. Ash's probe P6: the model named "Nykaa" for a Glow Cosmetics brief;
    a name the brief never wrote (or wrote only to rule out) feeds
    `DealRiskService`'s blocked-brand/competitor checks, so an invented or
    misattributed one can cause a wrong result there."""
    if value is None:
        return None
    name = value.strip()
    if not name:
        return None
    text = raw_text or ""
    escaped = re.escape(name)
    # T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — LOW: a hashtag is
    # not a name the brief gives itself: "1 reel #glowup budget 10k" kept
    # brand_name "Glowup". A match directly after "#" does not count (an
    # "@handle" still does). Source: B0-AI repair-round-2 verdict on 3d88d58,
    # defect 7.
    match = re.search(rf"(?<![#\w]){escaped}\b", text, re.IGNORECASE)
    if match is None:
        return None
    lo = max(0, match.start() - _BRAND_EXCLUSION_WINDOW)
    hi = min(len(text), match.end() + _BRAND_EXCLUSION_WINDOW)
    before, after = text[lo : match.start()], text[match.end() : hi]
    if _BRAND_EXCLUSION_TRIGGER_RE.search(before) or _BRAND_EXCLUSION_TRIGGER_RE.search(after):
        return None
    if _BRAND_REFERENCE_BEFORE_RE.search(before) or _BRAND_REFERENCE_AFTER_RE.match(after):
        return None
    if name.lower() in _PLATFORM_NAMES:
        return None
    if _AGENCY_AFTER_RE.match(text, match.end()) is not None:
        return None
    return value


def _clean_enum(value: Any, allowed: tuple[str, ...]) -> str | None:
    """Closed-vocab guard. Off-vocab and non-string values fail CLOSED to None
    (the field is simply absent) rather than being passed through: an invented
    category silently prices at a neutral multiplier, and an invented
    exclusivity scope silently prices at no exclusivity at all."""
    if not isinstance(value, str):
        return None
    candidate = value.strip().upper()
    return candidate if candidate in allowed else None


def _clean_enum_list(value: Any, allowed: tuple[str, ...]) -> list[str]:
    """Closed-vocab guard over a list, preserving order and dropping duplicates
    and off-vocab members. Never returns None: an absent list and a list of
    entirely unrecognised values are both "the brief named no channel", and
    §2.11 carries that as an empty array rather than a null."""
    if not isinstance(value, list):
        return []
    cleaned: list[str] = []
    for item in value[:20]:
        member = _clean_enum(item, allowed)
        if member is not None and member not in cleaned:
            cleaned.append(member)
    return cleaned


def _clean_int(value: Any, *, minimum: int, maximum: int) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    as_int = int(value)
    return as_int if minimum <= as_int <= maximum else None


def _clean_number(value: Any, *, minimum: float, maximum: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    as_float = float(value)
    return as_float if minimum <= as_float <= maximum else None


def _clean_text(value: Any, *, max_chars: int) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    return text[:max_chars]


# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 9 (LOW): a BCP-47-shaped tag
# ("hi", "hi-IN", "en-IN"), nothing else. `creator_language` is spliced
# straight into the system prompt text (`build_system_block`), so it must be
# an allowlisted shape rather than arbitrary request-supplied text.
_LANGUAGE_TAG_RE = re.compile(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$")


def _clean_language(value: Any) -> str | None:
    text = _clean_text(value, max_chars=16)
    if text is None:
        return None
    return text if _LANGUAGE_TAG_RE.fullmatch(text) else None


def _clean_string_list(value: Any, *, max_items: int, max_chars: int) -> list[str]:
    if not isinstance(value, list):
        return []
    cleaned: list[str] = []
    for item in value[:max_items]:
        text = _clean_text(item, max_chars=max_chars)
        if text is not None:
            cleaned.append(text)
    return cleaned


# REPAIR ROUND 1 [vikram · 2026-09-18] — finding 1 (HIGH): a deliverable
# count was never checked against the brief at all, and an ungrounded qty then
# vouched for itself in a summary line via `_own_numbers` — the same G1
# pattern the T-PHASEB-LIVE-0918 commit closed for the four amount fields, but
# missed here. Probe: brief "Glow: some reels, budget 8000." with model qty=5
# -> deliverables kept `qty=5` and the summary line "Glow wants 5 reels"
# survived, feeding an invented count straight into Java pricing. `qty` is now
# grounded against the brief's own plain numbers; an ungrounded qty falls back
# to the conservative default of 1 (the deliverable itself may still be real
# even when the model's count of it was not). Source: REPAIR ROUND 1 finding 1.
#
# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 1 (HIGH): round 1's fix was
# only half the job — grounding qty against EVERY bare number in the brief
# means a date, a percentage or a budget shorthand's own digits can vouch for
# an unrelated deliverable count. "budget 8000, live by 2026-12-05" grounded
# an invented qty=5 (the "5" inside "2026-12-05"); "budget 15k" grounded
# qty=15 (the same bare-"15"-inside-shorthand pattern already fixed for
# usage_months in REPAIR ROUND 1 finding 2, missed here); "50% advance"
# grounded qty=50. `qty` is now anchored to a number that sits next to a
# DELIVERABLE NOUN ("3 reels", "1 story set"), the same way
# `_numbers_with_unit` anchors usage_months/exclusivity_days to their units,
# rather than to any digit anywhere in the brief. Source: REPAIR ROUND 2
# finding 1 (Q1/Q2/Q3).
#
# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: the count
# was anchored to ANY deliverable noun, not to the deliverable's own type, so
# "Glow: 5 posts on our page already; need reels" kept an invented
# [{"type": "REEL", "qty": 5}] — the "5" belongs to posts. Each type now has
# its own noun list, and a count grounds only the type whose noun it sits
# next to. Filler words between the number and the noun ("3 Instagram reels")
# may not themselves be deliverable nouns, so "5 posts and reels" cannot
# lend the posts' count to the reels. OTHER keeps the old any-noun rule: it
# is the type for a deliverable the enum has no name for.
# Source: Kabir round-1 verdict, B0-AI defects[6].
_DELIVERABLE_TYPE_NOUNS: dict[str, str] = {
    "REEL": r"reels?|रील्?स?",
    "STATIC_POST": r"(?:static|feed|carousel|image|photo)\s+posts?|posts?|statics?|carousels?|पोस्ट",
    "STORY_SET": r"stor(?:y|ies)(?:\s+(?:sets?|frames?))?|स्टोरीज?|स्टोरी",
    "SHORT": r"(?:yt\s+|youtube\s+)?shorts?",
    "YT_INTEGRATION": r"(?:yt\s+|youtube\s+)?integrations?",
    "YT_DEDICATED": r"(?:dedicated\s+)?(?:yt\s+|youtube\s+)?videos?",
    "UGC_ONLY": r"ugc(?:\s+videos?)?",
}
_ANY_DELIVERABLE_NOUN = "|".join([*_DELIVERABLE_TYPE_NOUNS.values(), r"pieces?"])
_DELIVERABLE_TYPE_NOUNS["OTHER"] = _ANY_DELIVERABLE_NOUN
# A number directly followed by a deliverable noun counts deliverables; it is
# not an amount (see `_is_not_an_amount`).
_COUNT_NOUN_AFTER_RE = re.compile(
    rf"\s*(?:x\s*)?(?:{_ANY_DELIVERABLE_NOUN}){_WORD_END}", re.IGNORECASE
)
# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM: a money figure
# written with a word unit next to the deliverable noun grounded an invented
# qty: "bhai 2 reels chahiye, 5 hazaar per reel" and "2 reels needed, 5
# thousand per reel" with model qty 5 kept [{REEL, qty 5}] — the unit and
# "per" were read as filler words. A count is never followed by a money unit
# or a currency word, and "per"/"each"/"prati"/"har" are never filler (a
# figure per deliverable is a rate, not a count). Source: B0-AI repair-round-2
# verdict on 3d88d58, defect 2.
_DELIVERABLE_FILLER = (
    rf"(?:(?!(?:{_ANY_DELIVERABLE_NOUN}|per|each|prati|har|a){_WORD_END})[A-Za-z]+\s+){{0,2}}?"
)
_MONEY_UNIT_AFTER_COUNT_RE_TEXT = (
    rf"\s*(?:{_SHORTHAND_UNIT_RE_TEXT}|{_CURRENCY_MARKER_RE_TEXT}|/-){_WORD_END}"
)
# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — LOW: "teen reels" (a
# Hinglish number word) grounded nothing, so a correct qty=3 fell back to 1;
# and "Reels: 3 min max duration, just one reel" grounded qty=3 from a
# DURATION. A count may now be a small number word (English or Hinglish; "do"
# is left out, it is also the English verb "do reels"), and a number followed
# by a time unit is a duration, never a count. Source: B0-AI repair-round-1
# verdict, defect 6.
_COUNT_WORDS: dict[str, int] = {
    "one": 1, "single": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "ek": 1, "teen": 3, "tin": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5,
    "chhe": 6, "chhah": 6, "saat": 7, "aath": 8, "nau": 9, "das": 10,
    "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाँच": 5,
}
_COUNT_WORDS_RE_TEXT = "|".join(sorted(_COUNT_WORDS, key=len, reverse=True))
_TIME_UNIT_AFTER_RE_TEXT = (
    r"\s*(?:min|mins|minutes?|sec|secs|seconds?|s|hrs?|hours?|days?|din|weeks?|"
    r"months?|%)(?![A-Za-z])"
)
_DELIVERABLE_COUNT_RES: dict[str, tuple[re.Pattern[str], re.Pattern[str]]] = {
    kind: (
        # "3 reels", "3x reels", "2 Instagram reels", "1 story set", "teen reels"
        re.compile(
            rf"(?<![\d.,])(?<![A-Za-zऀ-ॿ])(\d+|{_COUNT_WORDS_RE_TEXT})(?![A-Za-zऀ-ॿ])"
            rf"(?!{_TIME_UNIT_AFTER_RE_TEXT})(?!{_MONEY_UNIT_AFTER_COUNT_RE_TEXT})"
            rf"\s*(?:x\s*)?{_DELIVERABLE_FILLER}(?:{nouns}){_WORD_END}",
            re.IGNORECASE,
        ),
        # "reels x 3", "Reels: 3", "reel - 2" — but not "Reels: 3 min"
        re.compile(
            rf"{_MARK_START}(?:{nouns})\s*(?:x|×|:|-)\s*(\d+)(?![\d.,]?\d)"
            rf"(?!{_TIME_UNIT_AFTER_RE_TEXT})(?!{_MONEY_UNIT_AFTER_COUNT_RE_TEXT})",
            re.IGNORECASE,
        ),
    )
    for kind, nouns in _DELIVERABLE_TYPE_NOUNS.items()
}


def _count_token_value(token: str) -> set[str]:
    word = _COUNT_WORDS.get(token.lower())
    if word is not None:
        return {str(word)}
    return _numbers_in(token)


def _grounded_deliverable_counts(raw_text: str, kind: str) -> set[str]:
    """The counts the brief states for deliverables of type `kind`."""
    patterns = _DELIVERABLE_COUNT_RES.get(kind)
    if patterns is None:
        return set()
    normalized = _normalize_digits(raw_text or "")
    found: set[str] = set()
    for pattern in patterns:
        for match in pattern.finditer(normalized):
            if _is_past_deliverable(normalized, match.start(), match.end()):
                continue
            found |= _count_token_value(match.group(1))
    return found


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — LOW: "5 reels already
# done by other creators" grounded qty=5. A count the same clause describes as
# work already done (by anyone) is not what this brief asks for. Source: B0-AI
# repair-round-2 verdict, defect 8.
_PAST_DELIVERABLE_RE = re.compile(
    r"(?<![A-Za-z])(?:already|previously|earlier|done\s+by|made\s+by|posted\s+by|"
    r"by\s+(?:other|another|previous|past)|last\s+(?:month|year|week|time|campaign)|"
    r"in\s+the\s+past|so\s+far|ho\s+chuk\w*|kar\s+chuk\w*|ban\s+chuk\w*)(?![A-Za-z])",
    re.IGNORECASE,
)


def _is_past_deliverable(text: str, start: int, end: int) -> bool:
    lo, hi = _clause_bounds(text, start, end, 40)
    return _PAST_DELIVERABLE_RE.search(text, lo, hi) is not None


def _clean_deliverables(value: Any, raw_text: str) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    lines: list[dict[str, Any]] = []
    for item in value[:20]:
        if not isinstance(item, dict):
            continue
        kind = _clean_enum(item.get("type"), BRIEF_DELIVERABLE_TYPES)
        if kind is None:
            # An unmappable deliverable type is dropped rather than coerced to
            # OTHER here. Java's QuoteDeliverableType.fromOrOther already
            # decides what an unknown name is worth; inventing OTHER on this
            # side would hide from Java that the model never recognised the
            # line at all.
            continue
        qty = _clean_int(item.get("qty"), minimum=1, maximum=100)
        if qty is not None and str(qty) not in _grounded_deliverable_counts(raw_text, kind):
            qty = None
        lines.append({"type": kind, "qty": qty if qty is not None else 1})
    return lines


# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: an invented
# max_revisions was never checked, and because `_own_numbers` trusted it, it
# also vouched for its own summary line ("Up to 7 revisions" survived for a
# brief that never mentions revisions) — the G1 pattern closed for qty in
# round 1. It is now grounded against a count the brief attaches to a
# revision noun, in figures or in small number words, before it reaches
# `_own_numbers`. Source: Kabir round-1 verdict, B0-AI defects[4].
# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — LOW: "We made 3
# changes to the formula" grounded max_revisions=3. "changes"/"edits"/
# "corrections" are everyday words, so on their own they are a revision count
# only as "free changes", "rounds of changes", or "changes allowed/included/
# max" (checked in `_grounded_revision_counts`); "revisions"/"iterations"/
# "re-shoots" stay revision nouns by themselves. Source: B0-AI
# repair-round-1 verdict, defect 6.
_REVISION_NOUN_RE_TEXT = (
    r"(?:free\s+(?:revisions?|re-?edits?|edits?|changes?|iterations?|corrections?)|"
    r"(?:revisions?|re-?edits?|iterations?|re-?shoots?)|"
    r"rounds?\s+of\s+(?:feedback|revisions?|edits?|changes?|corrections?))"
)
_WEAK_REVISION_NOUN_RE_TEXT = r"(?:edits?|changes?|corrections?)"
_WEAK_REVISION_QUALIFIER_AFTER_RE = re.compile(
    r"\s+(?:allowed|included|max|maximum|permitted|only|free)(?![A-Za-z])",
    re.IGNORECASE,
)
_SMALL_NUMBER_WORDS: dict[str, int] = {
    "no": 0, "zero": 0, "one": 1, "a": 1, "single": 1, "two": 2, "three": 3,
    "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
}
_SMALL_NUMBER_WORDS_RE_TEXT = "|".join(_SMALL_NUMBER_WORDS)
_REVISION_COUNT_BEFORE_RE = re.compile(
    rf"(?<![\d.,])\b(\d+|{_SMALL_NUMBER_WORDS_RE_TEXT})\s*(?:x\s*)?"
    rf"(?:[A-Za-z]+\s+)?{_REVISION_NOUN_RE_TEXT}{_WORD_END}",
    re.IGNORECASE,
)
_REVISION_COUNT_AFTER_RE = re.compile(
    rf"{_MARK_START}(?:revisions?|edits?|changes?|iterations?)"
    rf"(?:\s+(?:allowed|included|max|maximum))?\s*[:\-]\s*(\d+)(?![\d.,]?\d)",
    re.IGNORECASE,
)


_WEAK_REVISION_COUNT_RE = re.compile(
    rf"(?<![\d.,])\b(\d+|{_SMALL_NUMBER_WORDS_RE_TEXT})\s*(?:x\s*)?"
    rf"{_WEAK_REVISION_NOUN_RE_TEXT}{_WORD_END}",
    re.IGNORECASE,
)
_WEAK_REVISION_QUALIFIER_BEFORE_RE = re.compile(
    r"(?<![A-Za-z])(?:up\s*to|max(?:imum)?|at\s+most)\s*$", re.IGNORECASE
)


def _revision_token_value(token: str) -> set[str]:
    token = token.lower()
    if token.isdigit():
        return _numbers_in(token)
    if token in _SMALL_NUMBER_WORDS:
        return {str(_SMALL_NUMBER_WORDS[token])}
    return set()


def _grounded_revision_counts(raw_text: str) -> set[str]:
    normalized = _normalize_digits(raw_text or "")
    found: set[str] = set()
    for match in _REVISION_COUNT_BEFORE_RE.finditer(normalized):
        found |= _revision_token_value(match.group(1))
    for match in _WEAK_REVISION_COUNT_RE.finditer(normalized):
        before = normalized[max(0, match.start() - 20) : match.start()]
        if (
            _WEAK_REVISION_QUALIFIER_AFTER_RE.match(normalized, match.end()) is not None
            or _WEAK_REVISION_QUALIFIER_BEFORE_RE.search(before) is not None
        ):
            found |= _revision_token_value(match.group(1))
    for match in _REVISION_COUNT_AFTER_RE.finditer(normalized):
        found |= _numbers_in(match.group(1))
    for match in _REVISION_TIMES_RE.finditer(normalized):
        token = match.group(1).lower()
        found |= {str(_HINGLISH_TIMES_WORDS[token])} if token in _HINGLISH_TIMES_WORDS else (
            _revision_token_value(token)
        )
    return found


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — LOW: "do baar changes
# kar sakte hain" ("changes can be made twice") dropped a genuine
# max_revisions=2. "<n> baar/times/rounds" directly before a change/edit/
# revision noun is a revision count; "do" means 2 only here, where it cannot
# be the English verb. Source: B0-AI repair-round-2 verdict on 3d88d58, defect 6.
_HINGLISH_TIMES_WORDS: dict[str, int] = {
    "ek": 1, "do": 2, "teen": 3, "tin": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5,
    "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाँच": 5,
}
_REVISION_TIMES_RE = re.compile(
    rf"{_MARK_START}(\d+|{'|'.join(_HINGLISH_TIMES_WORDS)}|{_SMALL_NUMBER_WORDS_RE_TEXT})"
    r"\s+(?:baar|bar|times?|rounds?|बार)\s+(?:(?:ke|ka|of|tak)\s+)?"
    r"(?:changes?|edits?|revisions?|corrections?|badlav|बदलाव)(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)


# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: the
# NON-numeric usage and exclusivity terms were never grounded. For a brief
# saying "organic only", an invented usage_perpetual=true with
# usage_channels=["PAID_ADS"] survived, and an invented
# exclusivity_scope="CATEGORY" with exclusivity_brands=["Nykaa"] survived for
# a brief with no exclusivity at all. These fields drive Java's pricing
# uplifts and risk checks, so each one now needs its own vocabulary in the
# brief, and a mention that is NEGATED right before it ("no paid ads", "no
# exclusivity") does not count. Fail-closed direction: a term the brief does
# not state is simply absent (false / [] / null), which prices at no uplift —
# the same as a brief that never mentioned it. Source: Kabir round-1 verdict,
# B0-AI defects[3].
_TERM_NEGATION_BEFORE_RE = re.compile(
    r"(?:\b(?:no|not|never|without|except|excluding|don'?t|won'?t|nahi\w*|mat)"
    r"(?:\s+[A-Za-z']+){0,2}?\s*|\bnon-?)$",
    re.IGNORECASE,
)
# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — MEDIUM: a negation
# AFTER the term grounded the very term it denies: "Ads nahi chalenge" kept
# PAID_ADS, "Exclusivity ki zarurat nahi hai" / "Exclusivity: not required." /
# "एक्सक्लूसिव नहीं चाहिए" kept CATEGORY, "Usage lifetime nahi" kept
# usage_perpetual, "Paid ads: none." kept PAID_ADS. Hinglish and Hindi put the
# negation after the thing negated, so a term followed within three words of
# the same clause (no comma/full stop in between) by nahi/नहीं/mat, or by an
# English "not required/needed/allowed/..." or "none"/"nil"/"n/a", is negated.
# A bare English "not" after the term is NOT a negation of it: "Paid ads, not
# organic" and "Paid ads are not optional" state paid ads. Source: B0-AI
# repair-round-1 verdict, defect 3.
# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — LOW: a question answered
# in the next words ("Paid promotion? Bilkul nahi.") is negated too, so "?"
# is accepted as the separator. Source: B0-AI repair-round-2 verdict, defect 8.
_TERM_NEGATION_AFTER_RE = re.compile(
    r"(?:\s*[:\-?]\s*|\s+)(?:[A-Za-zऀ-ॿ']+\s+){0,3}?"
    r"(?:nahi\w*|nahin|nai|mat|नहीं|नही|मत|none|nil|n/?a|"
    r"not\s+(?:required|needed|necessary|allowed|included|applicable|wanted|"
    r"expected|permitted|planned|part\s+of|in\s+scope))"
    r"(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)


class _Term(NamedTuple):
    """A term's vocabulary. `strong` states the term on its own; `weak` states
    it only when `context` also appears in the same clause (see
    `_brief_states_term`)."""

    strong: str
    weak: str | None = None
    context: str | None = None


# T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — MEDIUM: the term
# vocabulary matched unrelated phrases: "Our ad agency will share the script"
# kept PAID_ADS, "We'd be forever grateful" / "Glow permanent hair colour
# launch" / "Hamesha brand ko tag karna" kept usage_perpetual, "Hum TV show ke
# sponsor hain" kept OFFLINE, "Add our Amazon link in bio" kept WEBSITE and
# "Promote our Diwali gift hampers" kept barter_only. Everyday words (forever,
# permanent, hamesha, TV, print, Amazon, ...) are now WEAK terms that count
# only next to a usage/rights word in the same clause ("Content will be used
# forever", "TV ads usage allowed", "Amazon listing"); the singular "ad" never
# counts on its own ("ad agency"), and a bare "gift" is not barter (only
# "gifted"/"gifting" collab language is). Source: B0-AI repair-round-1
# verdict, defect 4.
_USAGE_CONTEXT_TERMS = (
    r"usage|use|used|using|re-?use\w*|rights?|licen[sc]\w*|repost\w*|repurpos\w*|"
    r"listings?|ads|advert\w*|commercials?|campaigns?|istemaal|इस्तेमाल"
)
_USAGE_CHANNEL_TERMS: dict[str, _Term] = {
    "ORGANIC": _Term(
        r"organic\w*|(?:own|your|creator'?s?)\s+(?:page|handle|feed|account|profile|channel)"
    ),
    "PAID_ADS": _Term(
        r"paid\s+(?:ads?|media|usage|promotions?|amplification|distribution|social|campaigns?)|"
        r"(?<![#\w])ads(?!\s+agenc)|(?:run|running|as\s+an?|in|for|boost(?:ed)?\s+as\s+an?)\s+ad|"
        r"advertis(?:e|es|ed|ing|ement|ements)(?!\s+agenc)|dark\s+posts?|performance\s+marketing|"
        # "विज्ञापन" (advertisement) added T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash ·
        # 2026-09-18] — LOW: "विज्ञापन में इस्तेमाल करेंगे" dropped PAID_ADS.
        # Source: B0-AI repair-round-2 verdict on 3d88d58, defect 6.
        r"विज्ञापन(?!\s+एजेंसी)",
        r"boost\w*",
        _USAGE_CONTEXT_TERMS,
    ),
    "WHITELISTING": _Term(
        r"white-?list\w*|allow-?list\w*|partnership\s+ads?|branded\s+content\s+ads?|"
        r"spark\s+ads?|creator\s+licens\w*"
    ),
    # T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: "Visit our
    # website for product details", "Link to our website in bio" and "Website
    # pe order karna" grounded WEBSITE usage: a brand's website is where a
    # customer shops, not by itself a place the CONTENT is used. Every website
    # word is now weak — it states website usage only next to a usage/rights
    # word in the same clause ("Content will be used on our website"). Source:
    # B0-AI repair-round-2 verdict, defect 4.
    "WEBSITE": _Term(
        r"(?:web\s*site|e-?commerce|landing\s+page|product\s+page)\s+(?:usage|use|rights?|banners?)",
        r"web\s*site\w*|e-?commerce|landing\s+pages?|product\s+pages?|online\s+store|"
        r"amazon|flipkart|myntra|marketplace\w*",
        _USAGE_CONTEXT_TERMS,
    ),
    "OFFLINE": _Term(
        r"offline|hoardings?|billboards?|ooh|in-?store|retail\s+(?:display|stores?)|"
        r"print\s+(?:ads?|media|usage|campaigns?)|tv\s+(?:ads?|commercials?|spots?|campaigns?)",
        r"print|tv|television|standees?|posters?|packaging",
        _USAGE_CONTEXT_TERMS,
    ),
}
_USAGE_PERPETUAL_TERMS = _Term(
    r"perpetu\w*|unlimited\s+(?:usage|use|period|duration|time|rights)|"
    r"(?:usage|rights?|licen[sc]e)\s+(?:with\s+)?(?:no|without)\s+expir\w*",
    r"forever|life-?\s*time|indefinite\w*|all[- ]time|permanent\w*|hamesha|हमेशा|"
    r"no\s+expir\w*|without\s+expir\w*",
    r"usage|use|used|using|re-?use\w*|rights?|licen[sc]\w*|repost\w*|repurpos\w*|"
    r"istemaal|इस्तेमाल",
)
# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: "exclusive"
# and "competitors" are everyday marketing words: "Exclusive launch for our
# fans!", "Exclusive 20% off for 30 days", "Glow is cheaper than competitors!"
# and "ये हमारा एक्सक्लूसिव लॉन्च है" each grounded exclusivity_scope=CATEGORY
# (and the second exclusivity_days=30). Only the noun "exclusivity",
# "non-compete", "competing/rival brands", "same category" and "exclusive"
# qualified as a deal term ("exclusive rights/partnership/contract") state it
# alone. A bare "exclusive" states it only next to a duration/category/brand
# word and no promo word (off/discount/offer/sale/launch/%...) in the same
# clause; "competitor(s)" only next to a restriction ("don't post for
# competitors", "competitor brands not allowed"). See `_exclusivity_spans`.
# Source: B0-AI repair-round-2 verdict, defect 4.
_EXCLUSIVITY_TERMS = _Term(
    r"exclusivit\w*|non-?compete|competing\s+brands?|rival\s+brands?|same\s+category|"
    r"exclusive(?:ly)?\s+(?:rights?|partner\w*|contracts?|collab\w*|arrangements?|"
    r"associations?|period|terms?|tie-?ups?|ambassador\w*|basis|agreements?|with\s+us)|"
    r"एक्सक्लूसिविटी"
)
_EXCLUSIVE_WEAK_RE = re.compile(
    rf"{_MARK_START}(?:exclusive(?:ly)?|एक्सक्लूसिव){_WORD_END}", re.IGNORECASE
)
_EXCLUSIVE_CONTEXT_RE = re.compile(
    r"(?<![A-Za-z])(?:days?|din|weeks?|months?|mahine|years?|period|category|categories|"
    r"brands?|competit\w*|rival\w*|rights?|contracts?|agreements?|partner\w*)(?![A-Za-z])|"
    r"दिन|महीन[ेा]|साल|ब्रांड",
    re.IGNORECASE,
)
_EXCLUSIVE_PROMO_RE = re.compile(
    r"(?<![A-Za-z])(?:off|discounts?|offers?|sale|launch\w*|coupons?|codes?|access|drops?|"
    r"previews?|deals?|prices?|fans?|customers?|members?|events?|giveaways?|"
    r"collections?|editions?|products?)(?![A-Za-z])|%|लॉन्च|ऑफर|सेल",
    re.IGNORECASE,
)
_COMPETITOR_WEAK_RE = re.compile(
    rf"{_MARK_START}(?:competitors?|competition|rivals?|प्रतिस्पर्धी){_WORD_END}",
    re.IGNORECASE,
)
_RESTRICTION_RE = re.compile(
    r"(?<![A-Za-z])(?:no|not|never|don'?t|do\s+not|avoid\w*|can'?t|cannot|mustn'?t|"
    r"must\s+not|shouldn'?t|should\s+not|refrain|restrict\w*|prohibit\w*|banned|"
    r"disallow\w*|mat|nahi\w*|nahin)(?![A-Za-z])|नहीं|मत",
    re.IGNORECASE,
)
_RESTRICTED_ACTIVITY_RE = re.compile(
    r"(?<![A-Za-z])(?:post\w*|promot\w*|work\w*|collab\w*|partner\w*|feature\w*|"
    r"endors\w*|advertis\w*|associat\w*|tag\w*|content|brands?|allowed|permitted|"
    r"days?|months?|period|karna|kaam)(?![A-Za-z])",
    re.IGNORECASE,
)


def _exclusivity_spans(raw_text: str) -> list[tuple[int, int]]:
    """Where the brief states an exclusivity term, as spans of the NFC/digit-
    normalised text. Empty when it states none."""
    normalized = _normalize_digits(raw_text or "")
    spans: list[tuple[int, int]] = []
    strong = re.compile(
        rf"{_MARK_START}(?:{_EXCLUSIVITY_TERMS.strong}){_WORD_END}", re.IGNORECASE
    )
    for match in strong.finditer(normalized):
        if not _term_is_negated(normalized, match.start(), match.end()):
            spans.append(match.span())
    for match in _EXCLUSIVE_WEAK_RE.finditer(normalized):
        if _term_is_negated(normalized, match.start(), match.end()):
            continue
        clause = _clause_window(normalized, match.start(), match.end(), _DURATION_CONTEXT_WINDOW)
        if _EXCLUSIVE_CONTEXT_RE.search(clause) and not _EXCLUSIVE_PROMO_RE.search(clause):
            spans.append(match.span())
    for pattern in (_COMPETITOR_WEAK_RE, _OTHER_BRANDS_RE):
        for match in pattern.finditer(normalized):
            before = normalized[max(0, match.start() - 12) : match.start()]
            if re.search(r"(?<![A-Za-z])than\s*$", before, re.IGNORECASE):
                continue
            clause = _clause_window(
                normalized, match.start(), match.end(), _DURATION_CONTEXT_WINDOW
            )
            if _RESTRICTION_RE.search(clause) and _RESTRICTED_ACTIVITY_RE.search(clause):
                spans.append(match.span())
    return spans


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM + LOW: a
# restriction on "other/competing/any other ... brands" is how an exclusivity
# is usually written, in English and Hinglish, without the word
# "exclusivity": "No other skincare brands for 30 days", "45 din tak koi aur
# hair oil brand ke saath kaam mat karna" (which dropped a genuine 45 days).
# Next to a restriction and an activity word in the same clause it now states
# exclusivity — in the brief, and in a summary line, so the invented line
# "You can't work with other skincare brands" for a brief with no exclusivity
# is stripped by `_line_states_ungrounded_term`. Source: B0-AI repair-round-2
# verdict on 3d88d58, defects 3 and 6.
_OTHER_BRANDS_RE = re.compile(
    r"(?<![A-Za-z])(?:other|competing|rival|similar|any\s+other|another|koi\s+aur|koi\s+dusr\w*|"
    r"dusr\w*|doosr\w*|aur\s+koi)\s+(?:[A-Za-zऀ-ॿ]+\s+){0,3}?"
    r"(?:brands?|companies|company|labels?|ब्रांड)(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)


def _brief_states_exclusivity(raw_text: str) -> bool:
    return bool(_exclusivity_spans(raw_text))


_BARTER_TERMS = _Term(
    # "sirf product"/"paisa nahi" added T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash ·
    # 2026-09-18] — LOW: "Sirf product milega, paisa nahi" lost barter_only.
    # Source: B0-AI repair-round-2 verdict, defect 8.
    r"sirf\s+products?|(?:paisa|paise|cash|payment)\s+(?:nahi\w*|nahin|nai)|"
    r"barter\w*|in\s+exchange|free\s+products?|gifted|gifting|complimentary|"
    r"pr\s+(?:package|kit|box)|product\s+(?:only|in\s+return|as\s+payment|seeding)|"
    r"no\s+(?:cash|fee|monetary|payment|budget)|unpaid|non-?paid|बार्टर"
)


def _term_is_negated(text: str, start: int, end: int) -> bool:
    before = text[max(0, start - 30) : start]
    if _TERM_NEGATION_BEFORE_RE.search(before) is not None:
        return True
    return _TERM_NEGATION_AFTER_RE.match(text, end) is not None


def _brief_states_term(raw_text: str, term: _Term) -> bool:
    """True when the brief contains one of `term`'s strong words, or a weak
    word with a context word in the same clause, that is not negated right
    before or right after it."""
    normalized = _normalize_digits(raw_text or "")
    strong = re.compile(rf"{_MARK_START}(?:{term.strong}){_WORD_END}", re.IGNORECASE)
    for match in strong.finditer(normalized):
        if not _term_is_negated(normalized, match.start(), match.end()):
            return True
    if term.weak is None or term.context is None:
        return False
    weak = re.compile(rf"{_MARK_START}(?:{term.weak}){_WORD_END}", re.IGNORECASE)
    context = re.compile(rf"{_MARK_START}(?:{term.context}){_WORD_END}", re.IGNORECASE)
    for match in weak.finditer(normalized):
        if _term_is_negated(normalized, match.start(), match.end()):
            continue
        window = _clause_window(
            normalized, match.start(), match.end(), _DURATION_CONTEXT_WINDOW
        )
        if context.search(window) is not None:
            return True
    return False


def _grounded_usage_channels(channels: list[str], raw_text: str) -> list[str]:
    return [
        channel
        for channel in channels
        if channel in _USAGE_CHANNEL_TERMS
        and _brief_states_term(raw_text, _USAGE_CHANNEL_TERMS[channel])
    ]


def _grounded_exclusivity_brands(brands: list[str], raw_text: str) -> list[str]:
    """A named-exclusivity brand is kept only when the brief names it as a
    whole word AND either the brief states exclusivity at all or the name
    sits next to an exclusion trigger ("no Nykaa posts", "Nykaa mat karna")."""
    text = raw_text or ""
    has_exclusivity = _brief_states_exclusivity(text)
    kept: list[str] = []
    for brand in brands:
        match = re.search(rf"\b{re.escape(brand.strip())}\b", text, re.IGNORECASE)
        if match is None:
            continue
        lo = max(0, match.start() - _BRAND_EXCLUSION_WINDOW)
        hi = min(len(text), match.end() + _BRAND_EXCLUSION_WINDOW)
        near_trigger = _BRAND_EXCLUSION_TRIGGER_RE.search(
            text[lo : match.start()]
        ) or _BRAND_EXCLUSION_TRIGGER_RE.search(text[match.end() : hi])
        if has_exclusivity or near_trigger:
            kept.append(brand)
    return kept


def _grounded_exclusivity_scope(
    scope: str | None, grounded_brands: list[str], raw_text: str
) -> str | None:
    """NONE (no uplift) is always acceptable. CATEGORY needs the brief to state
    exclusivity; NAMED_BRANDS needs that or at least one grounded brand."""
    if scope is None or scope == "NONE":
        return scope
    if _brief_states_exclusivity(raw_text):
        return scope
    if scope == "NAMED_BRANDS" and grounded_brands:
        return scope
    return None


# REPAIR ROUND 2 [vikram · 2026-09-18] — finding 2 (HIGH): the summary-line
# number check compared only LITERAL digits, so the MODEL writing its own
# invented amount in shorthand or in words sailed through untouched: "Brand
# may go up to 50k" was checked as the bare number "50" (the "k" is not a
# digit `_NUMBER_RE` sees), and a stray "50" already in the brief (from "50%
# advance", an unrelated percentage) was enough to ground it — the line never
# says 50000, but the creator would read it as one. Likewise "Budget is 5
# lakh" was checked as bare "5", and "Budget: fifty thousand rupees" contained
# no digit at all to check. Every summary line's OWN shorthand is now expanded
# the same way the brief's shorthand is (`_shorthand_expansions_in`) and the
# EXPANDED value must be grounded against the brief's own money set — not the
# combined allowed_numbers, which also contains non-money counts (deliverable
# qty, max_revisions) that a coincidentally-matching money shorthand must not
# be able to borrow. A number spelled out in WORDS ("fifty thousand") cannot
# be expanded at all, so — fail closed — any occurrence of a money-unit word
# ("thousand"/"hundred"/"lakh"/"crore"/"hazaar" and the Devanagari
# equivalents) that is not itself anchored to a preceding digit is treated as
# unverifiable and the whole line is rejected. Source: REPAIR ROUND 2 finding
# 2 (S1-S4).
_MONEY_UNIT_WORD_RE = re.compile(
    # "lacs"/"laakh"/"million"/"billion" added T-GOLIVE-0918-R2 [ash ·
    # 2026-09-18] (Kabir round-1 B0-AI HIGH #2). "grand" is deliberately NOT
    # here: as a bare word it is ordinary English ("grand launch"); "50 grand"
    # is caught by `_SHORTHAND_RE` and checked by its expansion instead.
    r"\b(?:thousands?|hundreds?|lakhs?|lacs?|laakhs?|crores?|hazaars?|hazars?|"
    r"millions?|billions?|हज़ार|हजार|लाख|करोड़|करोड)(?![A-Za-zऀ-ॿ])",
    re.IGNORECASE,
)
_DIGIT_IMMEDIATELY_BEFORE_RE = re.compile(r"\d[\d,]*(?:\.\d+)?\s*$")


def _line_has_unanchored_money_word(line: str) -> bool:
    """True when `line` contains a money-unit WORD that is not itself
    immediately preceded by the digit it multiplies — i.e. the amount is
    spelled out ("fifty thousand") rather than written as a shorthand token
    ("50 thousand"/"50k") a computer can expand and check."""
    normalized = _normalize_digits(line)
    for match in _MONEY_UNIT_WORD_RE.finditer(normalized):
        prefix = normalized[: match.start()]
        if _DIGIT_IMMEDIATELY_BEFORE_RE.search(prefix) is None:
            return True
    return False


# T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM: a summary
# line could restate an ungrounded usage/exclusivity TERM ("Usage forever on
# paid ads" for an organic-only brief) because only its numbers were checked.
# A line that states one of these terms (un-negated) is kept only when the
# brief states that term too. ORGANIC is not checked: it is the no-uplift
# default and its vocabulary ("your page") is ordinary restating language.
# Source: Kabir round-1 verdict, B0-AI defects[3].
_SUMMARY_LINE_TERM_CHECKS: tuple[Any, ...] = (
    _USAGE_PERPETUAL_TERMS,
    _USAGE_CHANNEL_TERMS["PAID_ADS"],
    _USAGE_CHANNEL_TERMS["WHITELISTING"],
    _USAGE_CHANNEL_TERMS["WEBSITE"],
    _USAGE_CHANNEL_TERMS["OFFLINE"],
)


def _line_states_ungrounded_term(line: str, raw_text: str) -> bool:
    if _brief_states_exclusivity(line) and not _brief_states_exclusivity(raw_text):
        return True
    if any(
        _brief_states_term(line, terms) and not _brief_states_term(raw_text, terms)
        for terms in _SUMMARY_LINE_TERM_CHECKS
    ):
        return True
    if _line_states_ungrounded_rights(line, raw_text):
        return True
    return _PAYMENT_WORD_RE.search(_normalize_digits(line)) is not None and bool(
        _ungrounded_payment_families(line, raw_text)
    )


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM: a summary line
# could paraphrase an invented rights term without the brief's trigger words:
# "Brand owns the content forever" survived on "1 reel, 10k" because "forever"
# is a WEAK perpetual word (it needs a usage word beside it in a BRIEF, so an
# everyday "forever grateful" does not count). A line is model-written and
# about the deal, so there the perpetual words count alone, and so do the
# ownership words; the brief must state a perpetual term for the first
# (or contain that very word, e.g. a "permanent hair colour" product) and
# some rights/usage/licence/ownership term for the second.
# Source: B0-AI repair-round-2 verdict on 3d88d58, defect 3.
_LINE_PERPETUAL_RE = re.compile(
    rf"{_MARK_START}(?:forever|life-?\s*time|perpetu\w*|permanent\w*|indefinite\w*|all[- ]time|"
    rf"hamesha|हमेशा|no\s+expir\w*|without\s+expir\w*|never\s+expir\w*){_WORD_END}",
    re.IGNORECASE,
)
_LINE_OWNERSHIP_RE = re.compile(
    rf"{_MARK_START}(?:owns?|owned|ownership|buy-?outs?|all\s+rights|full\s+rights|"
    rf"rights\s+transfer\w*|transfer\s+of\s+rights){_WORD_END}",
    re.IGNORECASE,
)
_BRIEF_RIGHTS_RE = re.compile(
    rf"{_MARK_START}(?:owns?|owned|ownership|buy-?outs?|rights?|licen[sc]\w*|usage|"
    rf"perpetu\w*|istemaal|इस्तेमाल){_WORD_END}",
    re.IGNORECASE,
)


def _line_states_ungrounded_rights(line: str, raw_text: str) -> bool:
    normalized_line = _normalize_digits(line)
    normalized_raw = _normalize_digits(raw_text or "")
    for match in _LINE_PERPETUAL_RE.finditer(normalized_line):
        if _term_is_negated(normalized_line, match.start(), match.end()):
            continue
        if _brief_states_term(raw_text, _USAGE_PERPETUAL_TERMS):
            continue
        word = re.escape(match.group(0))
        if re.search(rf"{_MARK_START}{word}{_WORD_END}", normalized_raw, re.IGNORECASE):
            continue
        return True
    if _LINE_OWNERSHIP_RE.search(normalized_line) and not _BRIEF_RIGHTS_RE.search(normalized_raw):
        return True
    return False


# T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM (two defects):
# payment timing, method and split were never grounded unless they carried a
# number. The line "Paid in full upfront" survived on "1 reel, 10k", and the
# field payment_terms "Full advance before shoot" survived on "1 reel, budget
# TBD" ("100% advance" / "Net 60" were only dropped for their numbers). Each
# payment term belongs to a family — advance, after-delivery, in-full, method,
# split — and every family a line or payment_terms states must be one the
# brief states too. A line is checked only when it talks about payment (a pay
# / fee / money word), so "Share the draft in advance" is not a payment term;
# payment_terms is checked always, and must state at least one family.
# Source: B0-AI repair-round-2 verdict on 3d88d58, defects 3 and 4.
_PAYMENT_FAMILY_RES: dict[str, re.Pattern[str]] = {
    name: re.compile(rf"{_MARK_START}(?:{pattern}){_WORD_END}", re.IGNORECASE)
    for name, pattern in {
        "ADVANCE": (
            r"up-?front|advance|in\s+advance|pre-?pa(?:id|y|yment)|on\s+signing|"
            r"before\s+(?:the\s+)?(?:shoot\w*|post\w*|going\s+live|delivery|work|content)|"
            r"pehle|agrim|अग्रिम|एडवांस"
        ),
        "AFTER": (
            r"after\s+(?:the\s+)?(?:[A-Za-z]+\s+){0,2}?(?:post\w*|delivery|delivered|live|"
            r"publish\w*|approv\w*|completion|complete\w*|invoice\w*|campaign|shoot\w*|content)|"
            r"on\s+(?:delivery|completion|approval|posting|publishing|going\s+live)|"
            r"post-?(?:delivery|posting|campaign)|net\s*-?\s*\d+|within\s+\d+\s*[A-Za-z]+|"
            r"in\s+\d+\s*(?:days?|weeks?|din|hafte)|once\s+(?:the\s+)?(?:[A-Za-z]+\s+){0,2}?"
            r"(?:live|posted|published|approved|delivered)|baad|बाद"
        ),
        "FULL": (
            r"in\s+full|full\s+(?:payment|amount|fee|advance|pay)|100\s*%|poora|pura|पूरा|"
            r"complete\s+payment|entire\s+(?:amount|fee|payment)"
        ),
        "METHOD": (
            r"upi|bank\s+transfer|neft|imps|rtgs|g-?pay|google\s+pay|phone-?pe|paytm|"
            r"cheque|cash|wire\s+transfer|paypal|crypto"
        ),
        "SPLIT": (
            r"instal+ments?|milestones?|tranches?|in\s+parts|split\s+payment|payment\s+split|"
            r"half|\d+\s*%|आधा"
        ),
    }.items()
}
_PAYMENT_WORD_RE = re.compile(
    rf"{_MARK_START}(?:pay\w*|paid|fees?|money|paisa|paise|amount|remuneration|compensation|"
    rf"settle\w*|invoice\w*|भुगतान|पेमेंट){_WORD_END}",
    re.IGNORECASE,
)


def _payment_families_in(text: str) -> set[str]:
    normalized = _normalize_digits(text or "")
    return {name for name, pattern in _PAYMENT_FAMILY_RES.items() if pattern.search(normalized)}


def _ungrounded_payment_families(text: str, raw_text: str) -> set[str]:
    return _payment_families_in(text) - _payment_families_in(raw_text)


def _payment_terms_are_grounded(value: str, raw_text: str) -> bool:
    return bool(_payment_families_in(value)) and not _ungrounded_payment_families(value, raw_text)


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — HIGH: a summary line
# could still state an invented brand OFFER. The line check dropped audience
# counts, but not the amounts `_is_non_fee_amount` keeps out of budget_inr, so
# with budget_inr correctly None these lines were KEPT: "Brand pays 50k" /
# "Budget is 50,000" (brief: "Last month we paid 50k to another creator"),
# "Fee ₹200" ("Use code GLOW for ₹200 off"), "Payment ₹499" ("Min order ₹499
# for free shipping"), "You get paid ₹1,499" ("serum (₹1,499) in exchange"),
# "Budget 10L" ("Pichle Diwali 10L ki sale hui"), "Fee 999" ("Serum MRP 999"),
# "Budget 30k" ("prev creator ko 30k diye the"). A money figure in a line is
# now checked by what the LINE says it is: next to a fee word (budget, fee,
# pay, paid, payment, offer, per reel, ...) it must be a figure that grounds
# budget_inr; next to only a currency sign it must ground budget_inr or
# barter_mrp_inr; a line that itself frames it as a product value, discount
# or past payment ("Serum MRP ₹999") keeps the old any-amount check.
# Source: B0-AI repair-round-2 verdict, defect 1.
_FEE_WORD_RE_TEXT = (
    r"budgets?|fees?|pay|pays|payment|paying|paid|payout|compensation|remuneration|"
    r"honorarium|offers?|offered|offering|amount|commercials?|dunge|denge|de\s+sakte|"
    r"milenge|milega|paisa|paise|earn\w*|get\s+paid|gets?|"
    r"(?:your|creator|collab(?:oration)?|campaign|deal|content)\s+(?:price|pricing|cost|rate)|"
    r"per\s+(?:reels?|posts?|stor(?:y|ies)|videos?|shorts?|deliverables?|pieces?|integrations?)|"
    r"बजट|फीस|फ़ीस|भुगतान|पेमेंट"
)
_FEE_WORD_BEFORE_RE = re.compile(
    rf"{_MARK_START}(?:{_FEE_WORD_RE_TEXT}){_WORD_END}{_MONEY_MARKER_WINDOW_WORDS}"
    rf"\s*[:\-]?{_OPTIONAL_CURRENCY}\s*[(]?\s*$",
    re.IGNORECASE,
)
_FEE_WORD_AFTER_RE = re.compile(
    rf"{_OPTIONAL_CURRENCY}\s*[:\-/)]?{_MONEY_MARKER_WINDOW_WORDS}\s*{_MARK_START}"
    rf"(?:{_FEE_WORD_RE_TEXT}){_WORD_END}",
    re.IGNORECASE,
)


def _money_role_in_line(line: str, start: int, end: int) -> str | None:
    """What the LINE says the amount at line[start:end] is: "fee" (next to a
    fee word), "money" (next to a currency sign only) or None (neither, or
    the line frames it as a non-fee amount)."""
    if _is_non_fee_amount(line, start, end):
        return None
    before = line[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    if _FEE_WORD_BEFORE_RE.search(before) or _FEE_WORD_AFTER_RE.match(line, end):
        return "fee"
    if _has_money_marker_near(line, start, end, _CURRENCY_MARKER_RE_TEXT):
        return "money"
    return None


def _line_states_ungrounded_offer(
    line: str, fee_money: set[str], any_money: set[str]
) -> bool:
    normalized = _normalize_digits(line)
    checks: list[tuple[int, int, set[str]]] = []
    for match in _MONEY_NUMBER_SCAN_RE.finditer(normalized):
        start, end = match.span()
        if _is_shorthand_digits(normalized, end) or _is_glued_to_a_word(normalized, start):
            continue
        checks.append((start, end, _numbers_in(match.group(0))))
    for match in _SHORTHAND_RE.finditer(normalized):
        checks.append((match.start(), match.end(), _shorthand_expansions_in(match.group(0))))
    for start, end, values in checks:
        if not values or _is_audience_count(normalized, start, end):
            continue
        role = _money_role_in_line(normalized, start, end)
        if role == "fee" and not values <= fee_money:
            return True
        if role == "money" and not values <= any_money:
            return True
    return False


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: "Budget fifty
# k" was kept: a spelled number plus a bare "k"/"L"/"grand" is an amount no
# computer here can expand, so — like "fifty thousand" — it fails closed.
# Source: B0-AI repair-round-2 verdict, defect 6.
_SPELLED_NUMBER_WORDS = (
    r"one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|"
    r"fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|"
    r"fourty|fifty|sixty|seventy|eighty|ninety|half|ek|do|teen|char|chaar|paanch|"
    r"panch|chhe|saat|aath|nau|das|bees|pachas|pachaas|sau"
)
_SPELLED_SHORTHAND_RE = re.compile(
    rf"(?<![A-Za-z])(?:{_SPELLED_NUMBER_WORDS})(?:[\s\-]+(?:{_SPELLED_NUMBER_WORDS}))*"
    r"\s*-?\s*(?:k|l|lacs?|lakhs?|cr|crores?|grand|mn|million|bn)(?![A-Za-z])",
    re.IGNORECASE,
)


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: "Nykaa wants a
# reel" was kept for a brief that never names Nykaa. A line whose SUBJECT is a
# capitalised name ("X wants/needs/offers/is/will ...") must use a name the
# brief contains, and no line may repeat a brand name the model gave that
# grounding rejected (brand_name, exclusivity_brands). This is a heuristic for
# the subject position only; a name elsewhere in a line is not checked
# against the brief. Source: B0-AI repair-round-2 verdict, defect 6.
_LINE_SUBJECT_RE = re.compile(
    r"^\s*(?P<name>[A-Z][\w&'.\-]*(?:\s+[A-Z][\w&'.\-]*){0,2})(?:'s)?\s+"
    r"(?:wants?|needs?|offers?|pays?|is|are|would|will|has|have|asks?|seeks?|requires?|"
    r"looking|expects?|plans?|proposes?|invites?|sent|chahta|chahti|chahte)(?![A-Za-z])"
)
_GENERIC_SUBJECT_WORDS = frozenset(
    {
        "the", "brand", "brands", "they", "we", "you", "it", "this", "that", "client",
        "company", "creator", "sender", "team", "deal", "budget", "fee", "payment",
        "usage", "content", "campaign", "deadline", "product", "exclusivity", "terms",
        "offer", "a", "an", "their", "our", "your", "he", "she", "reel", "reels",
        "story", "post", "brief", "agency",
    }
)


def _line_names_an_ungrounded_brand(
    line: str, raw_text: str, dropped_names: set[str]
) -> bool:
    for name in dropped_names:
        if re.search(rf"(?<!\w){re.escape(name)}(?!\w)", line, re.IGNORECASE):
            return True
    match = _LINE_SUBJECT_RE.match(line)
    if match is None:
        return False
    name = match.group("name")
    words = [w.lower().strip(".'") for w in name.split()]
    if all(w in _GENERIC_SUBJECT_WORDS for w in words):
        return False
    # T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — LOW: "Instagram
    # wants a reel" and "Glowup wants 1 reel" were kept because the name is in
    # the brief — as the platform it came through, or as a hashtag. Neither is
    # a sender. Source: B0-AI repair-round-2 verdict on 3d88d58, defect 7.
    if name.lower() in _PLATFORM_NAMES:
        return True
    return re.search(rf"(?<![#\w]){re.escape(name)}(?!\w)", raw_text or "", re.IGNORECASE) is None


# T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: a line could
# state a deadline the brief never gave: "Post by 31 Dec 2026" survived for
# "Offer valid till 31/12/2026 for customers". A date the LINE frames as a
# deadline must be one `_deadline_dates_in` grounds in the brief; a day and
# month without a year compare on (month, day). Source: B0-AI repair-round-2
# verdict, defect 5.
_LINE_DAY_MONTH_RE = re.compile(r"(?<!\d)(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?(?![A-Za-z])")
_LINE_MONTH_DAY_RE = re.compile(r"(?<![A-Za-z])([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?!\d)")


def _line_states_ungrounded_deadline(
    line: str, deadline_dates: set[tuple[int, int, int]]
) -> bool:
    normalized = _normalize_digits(line)
    if _DEADLINE_CUE_RE.search(normalized) is None:
        return False
    full = _dates_in(normalized)
    if any(d not in deadline_dates for d in full):
        return True
    month_days = {(m, d) for _, m, d in deadline_dates}
    for match in _LINE_DAY_MONTH_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(2).lower())
        if month is not None and (month, int(match.group(1))) not in month_days:
            return True
    for match in _LINE_MONTH_DAY_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(1).lower())
        if month is not None and (month, int(match.group(2))) not in month_days:
            return True
    return False


class _Grounding(NamedTuple):
    """What the brief grounds, for checking model-written free text (summary
    lines, product, payment_terms, claims)."""

    raw_text: str
    allowed_numbers: set[str]
    grounded_money: set[str]
    audience_numbers: set[str]
    fee_money: set[str]
    any_money: set[str]
    deadline_dates: set[tuple[int, int, int]]
    dropped_names: set[str]


def _free_text_is_grounded(text: str, grounding: _Grounding) -> bool:
    """The number, money, deadline and brand checks of a summary line, for
    any model-written text the creator or Java may read.

    T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: product,
    payment_terms and claims had no grounding at all: for "budget TBD",
    payment_terms "Rs 50,000 on delivery", product "Serum (you get 25k)" and
    claims ["Brand pays 1 lakh"] all passed through into extracted_json.
    Source: B0-AI repair-round-2 verdict, defect 6."""
    if _line_has_unanchored_money_word(text) or _SPELLED_SHORTHAND_RE.search(text):
        return False
    line_numbers = _split_numbers_by_audience(text)
    audience = grounding.audience_numbers
    if not (line_numbers.plain <= grounding.allowed_numbers):
        return False
    if not (line_numbers.audience_plain <= grounding.allowed_numbers | audience):
        return False
    if not (line_numbers.expanded <= grounding.grounded_money):
        return False
    if not (line_numbers.audience_expanded <= grounding.grounded_money | audience):
        return False
    if _line_states_ungrounded_offer(text, grounding.fee_money, grounding.any_money):
        return False
    if _line_states_ungrounded_deadline(text, grounding.deadline_dates):
        return False
    return not _line_names_an_ungrounded_brand(
        text, grounding.raw_text, grounding.dropped_names
    )


def _acceptable_summary_line(line: str, grounding: _Grounding) -> bool:
    """One summary line the creator may actually be shown.

    Rejects a line that carries a banned word, addresses the creator with a
    pet-name, states a number the brief never contained, or states a money
    amount — in shorthand, or spelled out in words — whose expanded value the
    brief never contained. The number checks are the load-bearing ones: the
    model has been shown no rate, no floor and no quote, so a figure in its
    output that is not in the brief was invented, and the creator would read
    it as the brand's offer.
    """
    if _BANNED_WORD_RE.search(line):
        return False
    if _has_forbidden_petname(line):
        return False
    if _line_states_ungrounded_term(line, grounding.raw_text):
        return False
    # An audience count in the line ("50k+ followers") may restate an audience
    # count in the brief; every other number must be grounded without one.
    # Money the line calls a fee must ground budget_inr (REPAIR ROUND 2 HIGH),
    # and a deadline or subject brand it states must be in the brief.
    return _free_text_is_grounded(line, grounding)


def parse_and_validate_extraction(
    tool_input: Any, raw_text: str
) -> dict[str, Any] | None:
    """Validate and normalise the model's tool input into the §2.11
    `BriefExtraction` shape. Returns the payload on success, or None on any
    failure so the route returns `extraction_failed` and Java falls back to
    `BriefFallbackExtractor`. NEVER raises.

    Field-level failures are absences, not rejections: an off-vocab category, an
    out-of-range integer or an unmappable deliverable line is dropped, matching
    §2.11's "absent means null" contract. Two things are hard failures, because
    an extraction without them is not usable:

      - the input is not an object at all;
      - fewer than {BRIEF_SUMMARY_LINES_MIN} summary lines survive validation.

    SUMMARY LINES ARE FILTERED, NOT ALL-OR-NOTHING. A line with a banned word, a
    pet-name or an invented number is STRIPPED — the creator must never read it,
    but one bad line out of five is not a reason to throw away a correct
    extraction of the terms. If the strip leaves fewer than the minimum, the
    whole extraction fails: three lines is what the paste card renders, and
    silently showing one is worse than showing the deterministic fallback.
    """
    if not isinstance(tool_input, dict):
        return None

    # T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 2 (ash-answers.md
    # G1/G2/G3), refined by REPAIR ROUND 1 finding 2 (MEDIUM) and REPAIR ROUND
    # 2 findings 3-4 (MEDIUM): each structured amount is grounded against the
    # set that actually matches what kind of number it is, not one shared set
    # for everything —
    #   - budget_inr / barter_mrp_inr (money): each has its OWN marker-
    #     anchored literal numbers ("budget"/"fee" for the cash figure,
    #     "worth"/"barter"/"mrp" for the barter product's value) PLUS
    #     Indian-shorthand expansions ("15k" -> 15000), which need no marker
    #     since the unit itself is unambiguously money. Sharing one set let an
    #     unrelated follower count or the OTHER money field's own figure
    #     ground either one (REPAIR ROUND 2 finding 3).
    #   - usage_months / exclusivity_days (counts): only a number in a
    #     USAGE/rights or EXCLUSIVITY context respectively, converting a year
    #     or month duration into the field's own unit — a bare "N months"
    #     next to unrelated context (e.g. a payment date) no longer grounds
    #     either field, and a correct "1 year" usage or "2 months"
    #     exclusivity now does (REPAIR ROUND 2 finding 4).
    # `grounded_amounts` (unrestricted by marker) remains the set summary
    # LINES may restate from — a line may correctly repeat either money field.
    # Source: ash-answers.md §1 and §3; REPAIR ROUND 1 finding 2; REPAIR ROUND
    # 2 findings 3-4.
    # T-GOLIVE-0918-R2 REPAIR ROUND 1 [ash · 2026-09-18] — HIGH: summary lines
    # are grounded against the brief's NON-audience amounts; an audience count
    # may be restated only as one (`_split_numbers_by_audience`). Source: B0-AI
    # repair-round-1 verdict, defect 1.
    brief_numbers = _split_numbers_by_audience(raw_text)
    grounded_amounts = brief_numbers.plain | brief_numbers.expanded
    audience_amounts = brief_numbers.audience_plain | brief_numbers.audience_expanded
    grounded_budget = _grounded_budget_amounts(raw_text)
    grounded_barter = _grounded_barter_amounts(raw_text)
    grounded_months = _grounded_usage_months(raw_text)
    grounded_days = _grounded_exclusivity_days(raw_text)

    deliverables = _clean_deliverables(tool_input.get("deliverables"), raw_text)
    model_exclusivity_brands = _clean_string_list(
        tool_input.get("exclusivity_brands"), max_items=20, max_chars=120
    )
    exclusivity_brands = _grounded_exclusivity_brands(model_exclusivity_brands, raw_text)
    model_brand_name = _clean_text(tool_input.get("brand_name"), max_chars=200)
    brand_name = _grounded_brand_name(model_brand_name, raw_text)
    # Names the model gave that the brief never contains; no free text may
    # repeat them (REPAIR ROUND 2, defect 6). A name the brief does contain
    # but grounding declined as the CLIENT (an excluded competitor, a
    # reference) may still be restated: "No Nykaa posts for 60 days".
    dropped_names = {
        name.strip()
        for name in [model_brand_name, *model_exclusivity_brands]
        if name
        and name.strip()
        and re.search(rf"(?<!\w){re.escape(name.strip())}(?!\w)", raw_text, re.IGNORECASE) is None
    }
    budget_stated = bool(tool_input.get("budget_stated"))
    budget_inr = _grounded_amount(
        _clean_number(tool_input.get("budget_inr"), minimum=0, maximum=1_000_000_000),
        grounded_budget,
    )
    if not budget_stated:
        # §4.3 / RateQuoteService.statedBudgetOf reads budget_inr only when
        # budget_stated is true, but carrying an unstated figure anyway would
        # still land it in creator_briefs.extracted_json and from there onto the
        # creator's screen as if the brand had written it.
        budget_inr = None

    extraction: dict[str, Any] = {
        "brand_name": brand_name,
        "product": _clean_text(tool_input.get("product"), max_chars=200),
        "category": _clean_enum(tool_input.get("category"), BRIEF_CATEGORIES),
        "deliverables": deliverables,
        "budget_inr": budget_inr,
        "budget_stated": budget_stated,
        "barter_only": bool(tool_input.get("barter_only"))
        and _brief_states_term(raw_text, _BARTER_TERMS),
        "barter_mrp_inr": _grounded_amount(
            _clean_number(
                tool_input.get("barter_mrp_inr"), minimum=0, maximum=1_000_000_000
            ),
            grounded_barter,
        ),
        "deadline": _grounded_deadline(
            _clean_text(tool_input.get("deadline"), max_chars=40), raw_text
        ),
        "usage_months": _grounded_int(
            _clean_int(tool_input.get("usage_months"), minimum=0, maximum=600),
            grounded_months,
        ),
        # T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM:
        # the non-numeric usage/exclusivity terms and max_revisions are
        # grounded against the brief like every numeric field (see
        # `_brief_states_term`, `_grounded_revision_counts`).
        "usage_perpetual": bool(tool_input.get("usage_perpetual"))
        and _brief_states_term(raw_text, _USAGE_PERPETUAL_TERMS),
        "usage_channels": _grounded_usage_channels(
            _clean_enum_list(tool_input.get("usage_channels"), BRIEF_USAGE_CHANNELS),
            raw_text,
        ),
        "exclusivity_days": _grounded_int(
            _clean_int(tool_input.get("exclusivity_days"), minimum=0, maximum=3650),
            grounded_days,
        ),
        "exclusivity_scope": _grounded_exclusivity_scope(
            _clean_enum(tool_input.get("exclusivity_scope"), BRIEF_EXCLUSIVITY_SCOPES),
            exclusivity_brands,
            raw_text,
        ),
        "exclusivity_brands": exclusivity_brands,
        "max_revisions": _grounded_int(
            _clean_int(tool_input.get("max_revisions"), minimum=0, maximum=50),
            _grounded_revision_counts(raw_text),
        ),
        "payment_terms": _clean_text(tool_input.get("payment_terms"), max_chars=200),
        "off_platform_payment_hint": bool(tool_input.get("off_platform_payment_hint")),
        "disclosure_hidden_hint": bool(tool_input.get("disclosure_hidden_hint")),
        "claims": _clean_string_list(
            tool_input.get("claims"), max_items=20, max_chars=200
        ),
        "regulated_category": _clean_enum(
            tool_input.get("regulated_category"), BRIEF_REGULATED_CATEGORIES
        ),
        "vague_deliverables": bool(tool_input.get("vague_deliverables")),
    }

    grounding = _Grounding(
        raw_text=raw_text,
        allowed_numbers=grounded_amounts | _own_numbers(extraction),
        grounded_money=grounded_amounts,
        audience_numbers=audience_amounts,
        fee_money=grounded_budget,
        any_money=grounded_budget | grounded_barter,
        deadline_dates=_deadline_dates_in(raw_text),
        dropped_names=dropped_names,
    )
    # T-GOLIVE-0918-R2 REPAIR ROUND 2 [ash · 2026-09-18] — MEDIUM: product,
    # payment_terms and claims get the summary line's number/money/deadline/
    # brand checks; a value that fails is absent (§2.11 "absent means null"),
    # a failing claim is dropped. Source: B0-AI repair-round-2 verdict, defect 6.
    for field in ("product", "payment_terms"):
        value = extraction[field]
        if value is not None and not _free_text_is_grounded(value, grounding):
            extraction[field] = None
    # T-GOLIVE-0918-R2 REPAIR ROUND 3 [ash · 2026-09-18] — MEDIUM: payment_terms
    # without a number ("Full advance before shoot" for "budget TBD") passed;
    # its payment families must now be ones the brief states. Source: B0-AI
    # repair-round-2 verdict on 3d88d58, defect 4.
    if extraction["payment_terms"] is not None and not _payment_terms_are_grounded(
        extraction["payment_terms"], raw_text
    ):
        extraction["payment_terms"] = None
    extraction["claims"] = [
        claim for claim in extraction["claims"] if _free_text_is_grounded(claim, grounding)
    ]
    raw_lines = tool_input.get("summary_lines")
    candidate_lines = _clean_string_list(
        raw_lines, max_items=BRIEF_SUMMARY_LINES_MAX, max_chars=1_000
    )
    summary_lines = [
        line
        for line in candidate_lines
        if len(line) <= BRIEF_SUMMARY_LINE_MAX_CHARS
        and _acceptable_summary_line(line, grounding)
    ]
    if len(summary_lines) < BRIEF_SUMMARY_LINES_MIN:
        return None
    extraction["summary_lines"] = summary_lines[:BRIEF_SUMMARY_LINES_MAX]
    return extraction


@router.post("/internal/brief-extract")
async def brief_extract(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    creator_profile_id = body.get("creator_profile_id")

    if not creator_profile_id:
        # The ONLY non-auth, non-200 path (§7.5). Without a profile id there is
        # nothing to authenticate the token against and no spend bucket to bill,
        # so this cannot degrade to a deterministic body the way everything
        # below does.
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "creator_profile_id is required"},
        )

    try:
        # F-09: verify_creator_token is SYNCHRONOUS and shares _decode_and_verify,
        # so an unknown-`kid` JWKS miss blocks the event loop for the whole JWKS
        # timeout. Offloaded exactly as creator_suggestion.py does.
        # `body_creator_profile_id` is keyword-only and REQUIRED — it is what
        # makes a stolen creator token useless against another creator's brief.
        await anyio.to_thread.run_sync(
            lambda: verify_creator_token(
                _bearer(authorization),
                endpoint="brief_extract",
                body_creator_profile_id=creator_profile_id,
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    settings = get_settings()
    raw_text = str(body.get("raw_text") or "")[:MAX_RAW_TEXT_CHARS]
    if not raw_text.strip():
        # Nothing to read. Deterministic body rather than a 400: Java has already
        # persisted the row, and its fallback extractor over an empty string is a
        # perfectly good answer ("we could not read anything out of this").
        log_event(
            logger, logging.INFO, "brief_extract_empty_text",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={"error_code": EXTRACTION_FAILED_CODE},
        )
        return _error_body(EXTRACTION_FAILED_CODE)

    # The per-creator MONTHLY gate on its OWN key. `audience` is the literal
    # "CREATOR" because the gate returns None for anything else, and `cap_usd`
    # must be > 0 because the gate treats <= 0 as "cap disabled" — two ways this
    # silently enforces nothing if either argument drifts (§14.4.b traps 1-2).
    spend_key = f"{creator_profile_id}:brief"
    # T-CREATOR-CREDITS-V2 (SPEC.md B20, C22, A38/A45): with CREATOR_CREDITS_ENABLED on, Spring
    # passes `brief_monthly_cap_usd` (the $12.00 backstop, sized so 10 briefs/day x 31 days never
    # trips the OLD $0.25/mo default within the first paid day). `max(...)` so a lower/absent value
    # can never LOWER the process-wide default -- this is a floor-raise, never a floor-lower.
    provided_cap = body.get("brief_monthly_cap_usd")
    effective_cap_usd = settings.brief_extract_monthly_cap_usd
    if provided_cap is not None:
        try:
            effective_cap_usd = max(effective_cap_usd, float(provided_cap))
        except (TypeError, ValueError):
            logger.warning(
                "brief_extract: ignoring unparseable brief_monthly_cap_usd override %r", provided_cap
            )
    try:
        reservation = await check_creator_spend_gate(
            spend_key,
            "CREATOR",
            reserve_usd=settings.ai_reservation_per_call_usd or None,
            cap_usd=effective_cap_usd,
        )
    except SpendCapExceeded:
        log_event(
            logger, logging.WARNING, "brief_extract_blocked_creator_monthly_cap",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={"error_code": CAP_ERROR_CODE},
        )
        # 200, so Java can tell this from an outage and label the creator's
        # fallback "cap" rather than "ai_unavailable" (§14.4.a).
        return _error_body(CAP_ERROR_CODE)

    # T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 3: read once and pass
    # to the model (previously only logged — see build_system_block's rule 12).
    # REPAIR ROUND 1 [vikram · 2026-09-18] — finding 9 (LOW): this value used
    # to reach the system prompt through `_clean_text` alone, i.e. any string
    # up to 16 characters, with no allowlist. Restricted to a BCP-47-shaped tag
    # (e.g. "hi-IN", "en", "en-IN") so request-supplied text cannot be spliced
    # into the prompt as anything other than a language tag; anything else is
    # treated the same as no language supplied at all.
    creator_language = _clean_language(body.get("creator_language"))

    log_event(
        logger, logging.INFO, "brief_extract_started",
        workspace_id=creator_profile_id, request_id=request_id,
        fields={
            "model": BRIEF_EXTRACT_MODEL,
            "raw_text": shape_of(raw_text),
            "creator_language": creator_language,
            "max_tokens": BRIEF_EXTRACT_MAX_TOKENS,
        },
    )

    try:
        claude = _get_claude()
        result = await claude.complete_with_forced_tool(
            system_blocks=[build_system_block(creator_language)],
            messages=[build_user_message(raw_text)],
            tool_schema=get_brief_extraction_schema(),
            max_tokens=BRIEF_EXTRACT_MAX_TOKENS,
            model=BRIEF_EXTRACT_MODEL,
        )
        # T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 1: log the stop
        # reason so a max_tokens cutoff is visible instead of looking exactly
        # like any other extraction_failed. NOTE (scope limit, reported per
        # hard rule 8): `ClaudeToolResult` does not carry `stop_reason` today —
        # that dataclass lives in app/providers/claude.py, outside this lane's
        # file scope (B0 files: brief_extract.py, prompt/brief_extract.py,
        # tests only). This getattr is forward-compatible and logs it the
        # moment that field is added there; until then it logs None. Reported
        # to Arjun rather than editing claude.py out of scope.
        #
        # T-GOLIVE-0918-R2 [ash · 2026-09-18] — Kabir round-1 B0-AI MEDIUM:
        # the NOTE above is now resolved — `ClaudeToolResult.stop_reason` is
        # populated by app/providers/claude.py. A forced tool call cut off at
        # max_tokens can still come back as ok=True with a PARTIAL tool_input,
        # which then failed validation and was logged only as
        # "malformed_model_output", indistinguishable from any other bad
        # answer while still being billed. A "max_tokens" stop is now a known
        # failure: logged as `brief_extract_truncated` (stop_reason,
        # output_tokens, max_tokens) and never parsed. If a provider ever
        # omits stop_reason, output_tokens reaching the budget is treated the
        # same way. Billing is unchanged — the provider billed the call, so
        # it is recorded (F-06) — but it is no longer silent.
        # Source: Kabir round-1 verdict, B0-AI defects[5].
        stop_reason = getattr(result, "stop_reason", None)
        output_tokens = (result.usage or {}).get("output_tokens")
        truncated = stop_reason == "max_tokens" or (
            stop_reason is None
            and isinstance(output_tokens, int)
            and not isinstance(output_tokens, bool)
            and output_tokens >= BRIEF_EXTRACT_MAX_TOKENS
        )

        billed = False
        if result.usage:
            # F-06: a `no_tool_use_in_response` turn carries a populated usage
            # object — the call succeeded at HTTP 200 and was billed in full —
            # so spend is recorded on `usage` regardless of `ok`, and it settles
            # against the SAME `:brief` key the gate reserved on.
            try:
                cost_usd = estimate_cost_usd(BRIEF_EXTRACT_MODEL, result.usage)
                month_total = await record_creator_spend(
                    Decimal(str(cost_usd)), spend_key, reservation=reservation
                )
                billed = True
                log_event(
                    logger, logging.INFO, "ai_spend",
                    workspace_id=creator_profile_id, request_id=request_id,
                    fields={
                        "route": "brief_extract",
                        "model": BRIEF_EXTRACT_MODEL,
                        "audience": "CREATOR",
                        "cost_usd": str(cost_usd),
                        "creator_month_usd": str(month_total),
                    },
                )
            except ValueError as exc:
                log_event(
                    logger, logging.ERROR, "ai_spend_pricing_error",
                    workspace_id=creator_profile_id, request_id=request_id,
                    fields={"error": str(exc)},
                )
        if not billed:
            # Nothing was billed, so the hold must go back rather than sit until
            # its TTL and count against the next paste this creator makes.
            await release_creator(reservation)

        if truncated:
            log_event(
                logger, logging.WARNING, "brief_extract_truncated",
                workspace_id=creator_profile_id, request_id=request_id,
                fields={
                    "error_code": EXTRACTION_FAILED_CODE,
                    "stop_reason": stop_reason,
                    "output_tokens": output_tokens,
                    "max_tokens": BRIEF_EXTRACT_MAX_TOKENS,
                    "provider_ok": bool(result.ok),
                    "billed": billed,
                },
            )
            return _error_body(EXTRACTION_FAILED_CODE)

        if not result.ok or result.tool_input is None:
            log_event(
                logger, logging.WARNING, "brief_extract_provider_failed",
                workspace_id=creator_profile_id, request_id=request_id,
                fields={
                    "provider_error": result.error,
                    "error_code": EXTRACTION_FAILED_CODE,
                    "stop_reason": stop_reason,
                },
            )
            return _error_body(EXTRACTION_FAILED_CODE)

        extraction = parse_and_validate_extraction(result.tool_input, raw_text)
    except Exception:
        # Belt and braces around the deterministic-body invariant.
        # complete_with_forced_tool never raises and the validator never raises,
        # but a 5xx escaping this route would make Java label a cap as an outage
        # and, worse, would make a paste look like a platform failure to a
        # creator whose text is already safely persisted.
        await release_creator(reservation)
        logger.exception("brief_extract failed unexpectedly — returning deterministic body")
        return _error_body(EXTRACTION_FAILED_CODE)

    if extraction is None:
        log_event(
            logger, logging.WARNING, "brief_extract_malformed_model_output",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={"error_code": EXTRACTION_FAILED_CODE, "stop_reason": stop_reason},
        )
        return _error_body(EXTRACTION_FAILED_CODE)

    log_event(
        logger, logging.INFO, "brief_extract_completed",
        workspace_id=creator_profile_id, request_id=request_id,
        fields={
            "model": BRIEF_EXTRACT_MODEL,
            "extraction_source": "AI",
            "deliverable_count": len(extraction["deliverables"]),
            "budget_stated": extraction["budget_stated"],
            "summary_lines": shape_of(extraction["summary_lines"]),
        },
    )
    return {"success": True, "data": extraction}
