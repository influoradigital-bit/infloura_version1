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
BRIEF_EXTRACT_MAX_TOKENS = int(os.getenv("BRIEF_EXTRACT_MAX_TOKENS", "1024"))

# T-PHASEB-LIVE-0918 [vikram · 2026-09-18] — Ash's fix 3: Indian briefs write
# money as shorthand ("15k", "1.5L", "1.5 lakh", "1 crore") that never appears
# in the brief as the plain rupee figure the model must output. Grounding a
# structured amount against the brief's LITERAL digits alone would reject a
# correct 15000 for a "15k" brief, so grounding is checked against this
# shorthand-expanded set instead (see `_amounts_in_inr`).
# Source: ash-answers.md §1 ("No Indian money shorthand") and §3.
_SHORTHAND_RE = re.compile(
    r"(?P<num>\d+(?:\.\d+)?)\s*(?P<unit>k|l|lac|lakhs?|cr|crores?)\b",
    re.IGNORECASE,
)
_SHORTHAND_MULTIPLIERS: dict[str, float] = {
    "k": 1_000,
    "l": 100_000,
    "lac": 100_000,
    "lakh": 100_000,
    "lakhs": 100_000,
    "cr": 10_000_000,
    "crore": 10_000_000,
    "crores": 10_000_000,
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
# the creator will price against it. Matches digit groups with optional
# thousands separators and decimals; the comparison itself is done on the digits
# alone (see `_numbers_in`), so "8,000" in a line matches "8000" in the brief.
_NUMBER_RE = re.compile(r"\d[\d,  ]*(?:\.\d+)?")

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
    figures elsewhere."""
    found: set[str] = set()
    for match in _NUMBER_RE.finditer(_normalize_digits(text)):
        digits = re.sub(r"[^\d.]", "", match.group(0))
        if not digits:
            continue
        if "." in digits:
            whole, _, frac = digits.partition(".")
            digits = whole if frac.strip("0") == "" else whole + frac
        found.add(digits.lstrip("0") or "0")
    return found


def _amounts_in_inr(text: str) -> set[str]:
    """`_numbers_in`, plus every Indian money-shorthand amount in `text`
    expanded to its plain rupee figure: "15k" -> 15000, "1.5L"/"1.5 lakh" ->
    150000, "1 crore" -> 10000000. This is the grounding set for budget_inr,
    barter_mrp_inr, usage_months and exclusivity_days (Ash's fix 2 and 3,
    ash-answers.md G1/§1) — a structured amount is kept only when it, or its
    plain-figure equivalent, actually appears in the brief; a brief that says
    "15k" never contains the literal digits "15000", so checking only
    `_numbers_in` would wrongly reject the correctly-converted figure."""
    normalized = _normalize_digits(text or "")
    values = set(_numbers_in(normalized))
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
    summary line survive uncaught. What remains here (deliverable qty,
    max_revisions) are small counts not covered by that grounding fix."""
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


def _grounded_deadline(value: str | None, raw_text: str) -> str | None:
    """Keeps `deadline` only when it is a real, non-past ISO date whose day and
    year both appear as digits somewhere in the brief. Ash's probe P2: "Post by
    Diwali" (a relative date, no digits at all) produced an invented deadline
    that also happened to be in the past — either defect alone is disqualifying
    here; a relative date with no digits can never pass the digit check even in
    a year where the model's guess lands in the future."""
    if value is None:
        return None
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        return None
    if parsed < datetime.now(timezone.utc).date():
        return None
    digit_runs = set(re.findall(r"\d+", _normalize_digits(raw_text)))
    day_forms = {str(parsed.day), f"{parsed.day:02d}"}
    if not (digit_runs & day_forms):
        return None
    if str(parsed.year) not in digit_runs:
        return None
    return value


def _grounded_brand_name(value: str | None, raw_text: str) -> str | None:
    """Keeps `brand_name` only when it appears (case-insensitively) in the
    brief. Ash's probe P6: the model named "Nykaa" for a Glowup brief; a name
    the brief never wrote feeds `DealRiskService`'s blocked-brand/competitor
    checks, so an invented one can cause a wrong result there."""
    if value is None:
        return None
    return value if value.casefold() in (raw_text or "").casefold() else None


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


def _clean_string_list(value: Any, *, max_items: int, max_chars: int) -> list[str]:
    if not isinstance(value, list):
        return []
    cleaned: list[str] = []
    for item in value[:max_items]:
        text = _clean_text(item, max_chars=max_chars)
        if text is not None:
            cleaned.append(text)
    return cleaned


def _clean_deliverables(value: Any) -> list[dict[str, Any]]:
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
        lines.append({"type": kind, "qty": qty if qty is not None else 1})
    return lines


def _acceptable_summary_line(line: str, allowed_numbers: set[str]) -> bool:
    """One summary line the creator may actually be shown.

    Rejects a line that carries a banned word, addresses the creator with a
    pet-name, or states a number the brief never contained. The number check is
    the load-bearing one: the model has been shown no rate, no floor and no
    quote, so a figure in its output that is not in the brief was invented, and
    the creator would read it as the brand's offer.
    """
    if _BANNED_WORD_RE.search(line):
        return False
    if _has_forbidden_petname(line):
        return False
    return _numbers_in(line) <= allowed_numbers


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
    # G1/G2/G3): the brief's own numbers (literal + Indian-shorthand
    # expansions) are the single grounding set for every structured amount
    # below, so "budget stated as 15k" grounds an extracted 15000 while an
    # invented 50000 does not. Source: ash-answers.md §1 and §3.
    grounded_amounts = _amounts_in_inr(raw_text)

    deliverables = _clean_deliverables(tool_input.get("deliverables"))
    budget_stated = bool(tool_input.get("budget_stated"))
    budget_inr = _grounded_amount(
        _clean_number(tool_input.get("budget_inr"), minimum=0, maximum=1_000_000_000),
        grounded_amounts,
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
        "barter_only": bool(tool_input.get("barter_only")),
        "barter_mrp_inr": _grounded_amount(
            _clean_number(
                tool_input.get("barter_mrp_inr"), minimum=0, maximum=1_000_000_000
            ),
            grounded_amounts,
        ),
        "deadline": _grounded_deadline(
            _clean_text(tool_input.get("deadline"), max_chars=40), raw_text
        ),
        "usage_months": _grounded_int(
            _clean_int(tool_input.get("usage_months"), minimum=0, maximum=600),
            grounded_amounts,
        ),
        "usage_perpetual": bool(tool_input.get("usage_perpetual")),
        "usage_channels": _clean_enum_list(
            tool_input.get("usage_channels"), BRIEF_USAGE_CHANNELS
        ),
        "exclusivity_days": _grounded_int(
            _clean_int(tool_input.get("exclusivity_days"), minimum=0, maximum=3650),
            grounded_amounts,
        ),
        "exclusivity_scope": _clean_enum(
            tool_input.get("exclusivity_scope"), BRIEF_EXCLUSIVITY_SCOPES
        ),
        "exclusivity_brands": _clean_string_list(
            tool_input.get("exclusivity_brands"), max_items=20, max_chars=120
        ),
        "max_revisions": _clean_int(
            tool_input.get("max_revisions"), minimum=0, maximum=50
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
        and _acceptable_summary_line(line, allowed_numbers)
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
    creator_language = _clean_text(body.get("creator_language"), max_chars=16)

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
        stop_reason = getattr(result, "stop_reason", None)

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
            fields={"error_code": EXTRACTION_FAILED_CODE},
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
