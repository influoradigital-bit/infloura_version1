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
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any

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
_SHORTHAND_UNIT_RE_TEXT = (
    r"k|hazaars?|hazars?|l|lacs?|lakhs?|laakhs?|lkh|cr|crs|crores?|"
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
    return (text or "").translate(_DEVANAGARI_DIGITS)

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
    rf"(\d[\d,]*(?:\.\d+)?)\s*-?\s*(?:days?|दिन){_WORD_END}", re.IGNORECASE
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
_EXCLUSIVITY_CONTEXT_RE = re.compile(r"exclusiv\w*", re.IGNORECASE)
# A heuristic window, not full sentence parsing (same caveat as the brand
# exclusion check below): wide enough to span a short clause ("2 months
# category exclusivity"), narrow enough that an unrelated context word two
# sentences away should not cross-ground a different duration mention.
_DURATION_CONTEXT_WINDOW = 30


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
    context_re: re.Pattern[str],
    *,
    multiplier: float = 1,
) -> set[str]:
    """Like `_numbers_with_unit`, but a match only counts when `context_re`
    (a USAGE or EXCLUSIVITY context word) also appears within
    `_DURATION_CONTEXT_WINDOW` characters of it — see REPAIR ROUND 2 finding
    4 above. `multiplier` converts the unit found into the target field's own
    unit (e.g. a YEAR match feeding usage_months passes multiplier=12)."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in unit_re.finditer(normalized):
        lo = max(0, match.start() - _DURATION_CONTEXT_WINDOW)
        hi = min(len(normalized), match.end() + _DURATION_CONTEXT_WINDOW)
        if context_re.search(normalized[lo:hi]) is None:
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
    return (
        _numbers_with_context_unit(text, _DAY_UNIT_RE, _EXCLUSIVITY_CONTEXT_RE)
        | _numbers_with_context_unit(
            text, _MONTH_UNIT_RE, _EXCLUSIVITY_CONTEXT_RE, multiplier=30
        )
        | _numbers_with_context_unit(
            text, _YEAR_UNIT_RE, _EXCLUSIVITY_CONTEXT_RE, multiplier=365
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
_BUDGET_MARKER_RE_TEXT = (
    rf"{_CURRENCY_MARKER_RE_TEXT}|budgets?|fees?|pay|pays|payment|paying|paid|payout|"
    r"price|pricing|cost|compensation|remuneration|honorarium|offer|offering|"
    r"amount|commercials?|dunge|denge|de\s+sakte|milenge|paisa|paise|"
    r"per\s+(?:reels?|posts?|stor(?:y|ies)|videos?|shorts?|deliverables?|pieces?|integrations?)|"
    r"बजट|फीस|फ़ीस|भुगतान|पेमेंट"
)
_BARTER_MARKER_RE_TEXT = (
    rf"{_CURRENCY_MARKER_RE_TEXT}|worth|barter|bartered|mrp|value|valued|"
    r"retail\s+price|कीमत|मूल्य"
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
    r"comments?|shares?|saves?|plays?|streams?|engagements?|"
    r"फ़ॉलोअर्स|फॉलोअर्स|फॉलोवर्स|व्यूज़|व्यूज"
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
    before_re = re.compile(
        rf"{_MARK_START}(?:{marker_re_text}){_WORD_END}{_MONEY_MARKER_WINDOW_WORDS}\s*[:\-]?\s*$",
        re.IGNORECASE,
    )
    after_re = re.compile(
        rf"\s*[:\-/]?{_MONEY_MARKER_WINDOW_WORDS}\s*{_MARK_START}(?:{marker_re_text}){_WORD_END}",
        re.IGNORECASE,
    )
    before = text[max(0, start - _CONTEXT_LOOKAROUND_CHARS) : start]
    if before_re.search(before) is not None:
        return True
    return after_re.match(text, end) is not None


def _numbers_with_money_marker(text: str, marker_re_text: str) -> set[str]:
    """Every literal number in `text` that sits within two filler words of one
    of the marker words in `marker_re_text`, on either side ("budget is
    8000", "8000 INR", "worth 50000") — except the digits of a shorthand token
    (grounded by their expansion instead) and a number that counts an
    audience ("50000 followers")."""
    found: set[str] = set()
    normalized = _normalize_digits(text or "")
    for match in re.finditer(rf"(?<![\d.,]){_MONEY_NUMBER_RE}", normalized):
        start, end = match.span()
        if _is_shorthand_digits(normalized, end):
            continue
        if _is_audience_count(normalized, start, end):
            continue
        if _has_money_marker_near(normalized, start, end, marker_re_text):
            found |= _numbers_in(match.group(0))
    return found


def _shorthand_expansions_with_money_marker(text: str, marker_re_text: str) -> set[str]:
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
        if _is_audience_count(normalized, start, end):
            continue
        if not _has_money_marker_near(normalized, start, end, marker_re_text):
            continue
        values |= _shorthand_expansions_in(match.group(0))
    return values


def _grounded_budget_amounts(text: str) -> set[str]:
    return _numbers_with_money_marker(
        text, _BUDGET_MARKER_RE_TEXT
    ) | _shorthand_expansions_with_money_marker(text, _BUDGET_MARKER_RE_TEXT)


def _grounded_barter_amounts(text: str) -> set[str]:
    return _numbers_with_money_marker(
        text, _BARTER_MARKER_RE_TEXT
    ) | _shorthand_expansions_with_money_marker(text, _BARTER_MARKER_RE_TEXT)


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


def _dates_in(text: str) -> set[tuple[int, int, int]]:
    """Every full (year, month, day) date literally written in `text`, across
    a handful of common formats. Deliberately requires a YEAR alongside the
    day and month for every form: a bare day+month with no year, a month name
    with no digits, or digits with no unambiguous date shape cannot pin the
    exact date the model's `deadline` is required to match, so none of those
    are collected here."""
    found: set[tuple[int, int, int]] = set()
    normalized = _normalize_digits(text or "")
    for match in _ISO_DATE_RE.finditer(normalized):
        found.add((int(match.group(1)), int(match.group(2)), int(match.group(3))))
    for match in _DMY_DATE_RE.finditer(normalized):
        found.add((int(match.group(3)), int(match.group(2)), int(match.group(1))))
    for match in _DAY_MONTH_YEAR_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(2).lower())
        if month is not None:
            found.add((int(match.group(3)), month, int(match.group(1))))
    for match in _MONTH_DAY_YEAR_RE.finditer(normalized):
        month = _MONTH_NAMES.get(match.group(1).lower())
        if month is not None:
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
    if (parsed.year, parsed.month, parsed.day) not in _dates_in(raw_text):
        return None
    return value


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
    match = re.search(rf"\b{escaped}\b", text, re.IGNORECASE)
    if match is None:
        return None
    lo = max(0, match.start() - _BRAND_EXCLUSION_WINDOW)
    hi = min(len(text), match.end() + _BRAND_EXCLUSION_WINDOW)
    before, after = text[lo : match.start()], text[match.end() : hi]
    if _BRAND_EXCLUSION_TRIGGER_RE.search(before) or _BRAND_EXCLUSION_TRIGGER_RE.search(after):
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
_DELIVERABLE_FILLER = (
    rf"(?:(?!(?:{_ANY_DELIVERABLE_NOUN}){_WORD_END})[A-Za-z]+\s+){{0,2}}?"
)
_DELIVERABLE_COUNT_RES: dict[str, tuple[re.Pattern[str], re.Pattern[str]]] = {
    kind: (
        # "3 reels", "3x reels", "2 Instagram reels", "1 story set"
        re.compile(
            rf"(?<![\d.,])(\d+)\s*(?:x\s*)?{_DELIVERABLE_FILLER}(?:{nouns}){_WORD_END}",
            re.IGNORECASE,
        ),
        # "reels x 3", "Reels: 3", "reel - 2"
        re.compile(
            rf"{_MARK_START}(?:{nouns})\s*(?:x|×|:|-)\s*(\d+)(?![\d.,]?\d)",
            re.IGNORECASE,
        ),
    )
    for kind, nouns in _DELIVERABLE_TYPE_NOUNS.items()
}


def _grounded_deliverable_counts(raw_text: str, kind: str) -> set[str]:
    """The counts the brief states for deliverables of type `kind`."""
    patterns = _DELIVERABLE_COUNT_RES.get(kind)
    if patterns is None:
        return set()
    normalized = _normalize_digits(raw_text or "")
    found: set[str] = set()
    for pattern in patterns:
        for match in pattern.finditer(normalized):
            found |= _numbers_in(match.group(1))
    return found


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
_REVISION_NOUN_RE_TEXT = (
    r"(?:free\s+|rounds?\s+of\s+)?"
    r"(?:revisions?|re-?edits?|edits?|changes?|iterations?|corrections?|"
    r"re-?shoots?|rounds?\s+of\s+(?:feedback|revisions?|edits?|changes?))"
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


def _grounded_revision_counts(raw_text: str) -> set[str]:
    normalized = _normalize_digits(raw_text or "")
    found: set[str] = set()
    for match in _REVISION_COUNT_BEFORE_RE.finditer(normalized):
        token = match.group(1).lower()
        if token.isdigit():
            found |= _numbers_in(token)
        elif token in _SMALL_NUMBER_WORDS:
            found.add(str(_SMALL_NUMBER_WORDS[token]))
    for match in _REVISION_COUNT_AFTER_RE.finditer(normalized):
        found |= _numbers_in(match.group(1))
    return found


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
_USAGE_CHANNEL_TERMS: dict[str, str] = {
    "ORGANIC": r"organic\w*|(?:own|your|creator'?s?)\s+(?:page|handle|feed|account|profile|channel)",
    "PAID_ADS": (
        r"paid\s+(?:ads?|media|usage|promotions?|amplification|distribution|social|campaigns?)|"
        r"(?<![#\w])ads?|advert\w*|boost\w*|dark\s+posts?|performance\s+marketing"
    ),
    "WHITELISTING": (
        r"white-?list\w*|allow-?list\w*|partnership\s+ads?|branded\s+content\s+ads?|"
        r"spark\s+ads?|creator\s+licens\w*"
    ),
    "WEBSITE": (
        r"web\s*site\w*|e-?commerce|landing\s+pages?|product\s+pages?|online\s+store|"
        r"amazon|flipkart|myntra|marketplace\w*"
    ),
    "OFFLINE": (
        r"offline|print|hoardings?|billboards?|ooh|in-?store|retail\s+(?:display|stores?)|"
        r"tv|television|standees?|posters?|packaging"
    ),
}
_USAGE_PERPETUAL_TERMS = (
    r"perpetu\w*|forever|life-?\s*time|unlimited\s+(?:usage|use|period|duration|time|rights)|"
    r"no\s+expir\w*|without\s+expir\w*|indefinite\w*|all[- ]time|permanent\w*|hamesha|हमेशा"
)
_EXCLUSIVITY_TERMS = (
    r"exclusiv\w*|non-?compete|competitors?|competing\s+brands?|rival\s+brands?|"
    r"same\s+category|एक्सक्लूसिव"
)
_BARTER_TERMS = (
    r"barter\w*|in\s+exchange|free\s+products?|gift\w*|complimentary|"
    r"pr\s+(?:package|kit|box)|product\s+(?:only|in\s+return|as\s+payment|seeding)|"
    r"no\s+(?:cash|fee|monetary|payment|budget)|unpaid|non-?paid|बार्टर"
)


def _brief_states_term(raw_text: str, terms_re_text: str) -> bool:
    """True when the brief contains one of `terms_re_text` that is not
    negated by a word directly before it."""
    normalized = raw_text or ""
    pattern = re.compile(rf"{_MARK_START}(?:{terms_re_text}){_WORD_END}", re.IGNORECASE)
    for match in pattern.finditer(normalized):
        before = normalized[max(0, match.start() - 30) : match.start()]
        if _TERM_NEGATION_BEFORE_RE.search(before) is None:
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
    has_exclusivity = _brief_states_term(text, _EXCLUSIVITY_TERMS)
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
    if _brief_states_term(raw_text, _EXCLUSIVITY_TERMS):
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
_SUMMARY_LINE_TERM_CHECKS: tuple[str, ...] = (
    _USAGE_PERPETUAL_TERMS,
    _USAGE_CHANNEL_TERMS["PAID_ADS"],
    _USAGE_CHANNEL_TERMS["WHITELISTING"],
    _USAGE_CHANNEL_TERMS["WEBSITE"],
    _USAGE_CHANNEL_TERMS["OFFLINE"],
    _EXCLUSIVITY_TERMS,
)


def _line_states_ungrounded_term(line: str, raw_text: str) -> bool:
    return any(
        _brief_states_term(line, terms) and not _brief_states_term(raw_text, terms)
        for terms in _SUMMARY_LINE_TERM_CHECKS
    )


def _acceptable_summary_line(
    line: str,
    allowed_numbers: set[str],
    grounded_money: set[str],
    raw_text: str = "",
) -> bool:
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
    if _line_has_unanchored_money_word(line):
        return False
    if not (_plain_numbers_in(line) <= allowed_numbers):
        return False
    if _line_states_ungrounded_term(line, raw_text):
        return False
    line_expansions = _shorthand_expansions_in(line)
    return line_expansions <= grounded_money


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
    grounded_amounts = _amounts_in_inr(raw_text)
    grounded_budget = _grounded_budget_amounts(raw_text)
    grounded_barter = _grounded_barter_amounts(raw_text)
    grounded_months = _grounded_usage_months(raw_text)
    grounded_days = _grounded_exclusivity_days(raw_text)

    deliverables = _clean_deliverables(tool_input.get("deliverables"), raw_text)
    exclusivity_brands = _grounded_exclusivity_brands(
        _clean_string_list(
            tool_input.get("exclusivity_brands"), max_items=20, max_chars=120
        ),
        raw_text,
    )
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
        "brand_name": _grounded_brand_name(
            _clean_text(tool_input.get("brand_name"), max_chars=200), raw_text
        ),
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

    allowed_numbers = grounded_amounts | _own_numbers(extraction)
    raw_lines = tool_input.get("summary_lines")
    candidate_lines = _clean_string_list(
        raw_lines, max_items=BRIEF_SUMMARY_LINES_MAX, max_chars=1_000
    )
    summary_lines = [
        line
        for line in candidate_lines
        if len(line) <= BRIEF_SUMMARY_LINE_MAX_CHARS
        and _acceptable_summary_line(line, allowed_numbers, grounded_amounts, raw_text)
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
    try:
        reservation = await check_creator_spend_gate(
            spend_key,
            "CREATOR",
            reserve_usd=settings.ai_reservation_per_call_usd or None,
            cap_usd=settings.brief_extract_monthly_cap_usd,
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
