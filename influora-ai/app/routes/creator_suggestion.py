"""POST /internal/creator-suggestion — the ONE cheap AI phrasing call for the
Creator AI Co-pilot Tier-1 daily suggestion.

Internal-only: called by Java's `CreatorSuggestionAiClient`, never by a
browser. Auth is a SIBLING of the trendspark/brand-safety service-token
pattern (`app.auth.service_token`), but keyed on `creator_profile_id`, not
`workspace_id` — creator tokens carry a distinct `creator` scope
(`verify_creator_token`, additive to `app.auth.service_token`, does not touch
`verify_token()`). Any auth failure -> 401/403 with no provider call, no
token spend.

Built per `wiki/build/creator-copilot-ai-route-plan.md` (post-R2, trimmed)
and `wiki/build/creator-copilot-priya-review-r1.md`'s binding ruling — this
route is a sibling of `app/routes/trendspark.py` (mirrors its orchestration
order, auth-then-gate-then-call-then-validate-then-fallback shape almost
exactly), NOT an edit to it.

Contract (must match Vikram's `CreatorSuggestionAiClient` / DTOs
byte-for-byte once frozen):
  request : {creator_profile_id, theme_matched, trend_text}
  response: {success, data:{headline, content_idea, message_source}}

**Per Priya's R1 ruling (Conflict 5 + addendum): NO `caption_snippet` field
anywhere in this contract.** Theme-tagging is 100% deterministic Java
(`ThemeMatchService.themesForText`) — zero Python/AI involvement. This
phrasing call receives ONLY `theme_matched` (closed-vocab, server-derived
enum) + `trend_text` (scraped/third-party, UNTRUSTED). Combined, NO creator
caption text reaches any model in Tier-1 at all.

Guardrails:
  - Cheap model (Haiku-class, CREATOR_COPILOT_MODEL) — never Opus/Sonnet.
  - Defensive parse: strip code fences, json.loads in try/except, validate
    headline/content_idea are non-empty + sane length + <=2 sentences each +
    no forbidden pet-names + no echoed price + no marketplace BRAND NAME
    (the reject list is deliberately minimal — Ash's ruling — see
    `_MARKETPLACE_RE` below; unlike trendspark's mode-gated OWN_CONTENT
    restriction, this check is unconditional here).
  - On ANY failure (provider error, malformed output, failed validation,
    spend gate) -> deterministic templated fallback, still HTTP 200. NEVER a
    500 for a phrasing miss (same invariant as trendspark).
  - `theme_matched` is validated against the SAME closed vocab
    (`app.prompt.trend_tag.THEME_SET`) before it ever reaches the prompt;
    off-vocab input is treated as empty (fail closed), never passed through
    as an invented theme string. `trend_text` is the ONLY untrusted free-text
    field in this route — delimited via `app.prompt.untrusted` (through
    `app.prompt.creator_suggestion.build_user_message`) before it ever
    reaches the model.
  - PII-free structured log per suggestion: model + message_source
    (AI|FALLBACK), never the raw headline/content_idea/trend text (only
    shapes). `creator_profile_id` is logged in the clear (internal id, the
    tracing correlation key — same treatment `workspace_id` gets throughout
    trendspark.py), per Priya's R1 ruling.
  - Validators (`_CODE_FENCE_RE`, `_has_forbidden_petname`, `_PRICE_RE`,
    `_statement_count`) are IMPORTED from the shared
    `app.prompt.validators` module (Priya R1 Conflict 7) — not duplicated.
    `_MARKETPLACE_RE` below is creator-specific and stays local per that same
    ruling ("lives in the creator route/prompt, not the shared module").

Explicitly NOT in this route (parent spec §8, don't gold-plate):
  - No `video_ids` / catalog / SNAPSBY mode — Tier-1 creators have no
    marketplace.
  - No per-creator/day cap logic here — that's Java's `CreatorNudgeService`
    (DB-constraint-backed, checked BEFORE this route is ever called). The
    spend gate below is a second, independent layer (defense-in-depth), not
    a substitute.
  - No mutation of `creator_nudge_log` — Java writes that row after this
    response returns.
"""

from __future__ import annotations

import json
import logging
import re
import uuid
from typing import Any

import anyio
from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_creator_token
from app.config import CREATOR_COPILOT_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.creator_suggestion import (
    build_system_prompt,
    build_user_message,
    fallback_message,
)
from app.prompt.trend_tag import THEME_SET
from app.prompt.validators import (
    _CODE_FENCE_RE,
    _has_forbidden_petname,
    _statement_count,
    has_invented_price,
)
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of

logger = logging.getLogger(__name__)
router = APIRouter()

_claude_provider: ClaudeProvider | None = None

# Creator tone must never surface the marketplace BRAND NAME -- Tier-1
# creators have no OWN_CONTENT/SNAPSBY branch to gate this on (unlike
# trendspark's `mode == MODE_OWN_CONTENT` conditional), so this check is
# UNCONDITIONAL. Creator-specific (per Priya's R1 ruling, Conflict 7): stays
# local to this route, not the shared app/prompt/validators.py module.
#
# Ash's ruling (Priya code-review Fix #3): the reject list is deliberately
# MINIMAL for the creator context -- ONLY the brand name itself. "buy" and
# "video"/"videos" are ordinary, legitimate words in a creator content idea
# ("post a video today", "buy some time before recording") and banning them
# forced near-permanent fallback. This is narrower than trendspark's
# `_OWN_CONTENT_FORBIDDEN_RE` (which bans buy/videos too) because that check
# guards a BRAND-vendor voice that must never sound like a marketplace pitch
# at all; the creator voice has no pitch to make in the first place -- the
# system prompt's tone control (no product/marketplace concept) already
# does that work, so the only thing this regex needs to catch is the literal
# brand name leaking through.
_MARKETPLACE_RE = re.compile(r"\bsnapsby\b", re.IGNORECASE)


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


def _normalize_theme(raw: Any) -> str:
    """Closed-vocab guard (schema-lock parity with trend_tag.py's THEME_SET):
    off-vocab / non-string input fails closed to an empty theme rather than
    ever passing an invented theme string to the model. Case-insensitive,
    matching `validate_themes`'s `.strip().lower()` normalization in
    app/prompt/trend_tag.py.
    """
    if not isinstance(raw, str):
        return ""
    theme = raw.strip().lower()
    return theme if theme in THEME_SET else ""


def parse_and_validate(
    raw_text: str,
    *,
    max_headline_chars: int,
    max_content_idea_chars: int,
) -> tuple[str, str] | None:
    """Defensive parse + validation of the model's text output. Returns
    (headline, content_idea) on success, or None on ANY failure (so the
    route falls back). Never raises.

    Enforces: valid JSON, non-empty headline/content_idea, length caps,
    <=2 sentences each, no forbidden pet-names, no echoed price, and no
    marketplace BRAND NAME (unconditional; deliberately minimal reject list
    per Ash's ruling — see `_MARKETPLACE_RE` above for why "buy"/"video(s)"
    are NOT banned here).
    """
    text = _CODE_FENCE_RE.sub("", raw_text.strip()).strip()
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None

    headline = parsed.get("headline")
    content_idea = parsed.get("content_idea")
    if not isinstance(headline, str) or not isinstance(content_idea, str):
        return None
    headline = headline.strip()
    content_idea = content_idea.strip()

    if not headline or len(headline) > max_headline_chars:
        return None
    if not content_idea or len(content_idea) > max_content_idea_chars:
        return None

    for field_value in (headline, content_idea):
        if _statement_count(field_value) > 2:
            return None
        if _has_forbidden_petname(field_value):
            return None
        if has_invented_price(field_value):  # echoed/invented price -> reject
            return None
        if _MARKETPLACE_RE.search(field_value):  # brand name must never surface
            return None

    return headline, content_idea


@router.post("/internal/creator-suggestion")
async def creator_suggestion(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    creator_profile_id = body.get("creator_profile_id")

    if not creator_profile_id:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "creator_profile_id is required"},
        )

    try:
        # F-09 (round 2): this was the seventh route, and the only one still
        # calling token verification inline. verify_creator_token shares
        # _decode_and_verify, so an unknown-`kid` miss blocks the event loop
        # for the JWKS timeout here too.
        await anyio.to_thread.run_sync(
            lambda: verify_creator_token(
                _bearer(authorization),
                endpoint="creator_suggestion",
                body_creator_profile_id=creator_profile_id,
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    settings = get_settings()

    # Normalize inputs defensively. `trend_text` is length-capped by
    # truncation rather than 400 -- a suggestion must still be produced; the
    # deterministic fallback below never fails. `theme_matched` fails closed
    # to "" on any off-vocab input (never an invented theme reaches the
    # prompt).
    theme_matched = _normalize_theme(body.get("theme_matched"))
    trend_text = str(body.get("trend_text") or "")[: settings.creator_copilot_max_trend_text_chars]

    def _fallback_response() -> dict[str, Any]:
        headline, content_idea = fallback_message(
            theme_matched=theme_matched or "your niche",
            trend_text=trend_text or "something trending in your space",
        )
        log_event(
            logger, logging.INFO, "creator_suggestion_completed",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={
                "model": CREATOR_COPILOT_MODEL,
                "message_source": "FALLBACK",
                "theme_matched": theme_matched,
                "headline": shape_of(headline),
                "content_idea": shape_of(content_idea),
            },
        )
        return {
            "success": True,
            "data": {"headline": headline, "content_idea": content_idea, "message_source": "FALLBACK"},
        }

    # Spend gate: reuses the existing generic hard-cap plumbing by passing
    # creator_profile_id into the workspace_id parameter slot -- spend_tracker's
    # Redis/in-memory keys are opaque strings, so this works with zero changes
    # to gate.py/spend_tracker.py (plan §1.2 step 4). On kill-switch / ceiling,
    # skip the AI call and return the deterministic fallback (still 200).
    gate = await check_spend_gate(
        workspace_id=creator_profile_id,
        # F-05: hold budget between this check and the recorded spend, so
        # concurrent callers see each other's in-flight cost instead of all
        # reading the same stale total. Settled by record_spend below; expires
        # on its own if this request dies before it gets there.
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "creator_suggestion_blocked_spend_gate",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _fallback_response()

    log_event(
        logger, logging.INFO, "creator_suggestion_started",
        workspace_id=creator_profile_id, request_id=request_id,
        fields={
            "theme_matched": theme_matched,
            "trend_text": shape_of(trend_text),
        },
    )

    system_prompt = build_system_prompt()
    user_message = build_user_message(theme_matched=theme_matched, trend_text=trend_text)

    claude = _get_claude()
    result = await claude.complete_text(
        system=system_prompt,
        user=user_message,
        model=CREATOR_COPILOT_MODEL,
        max_tokens=settings.creator_copilot_max_tokens,
    )

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(CREATOR_COPILOT_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, creator_profile_id, reservation=gate.reservation)
            log_event(
                logger, logging.INFO, "ai_spend",
                workspace_id=creator_profile_id, request_id=request_id,
                fields={
                    "route": "creator_suggestion",
                    "model": CREATOR_COPILOT_MODEL,
                    "cost_usd": str(cost_usd),
                    "spend_today_usd": str(spend_today),
                },
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                workspace_id=creator_profile_id, request_id=request_id,
                fields={"error": str(exc)},
            )

    if not result.ok or not result.text:
        return _fallback_response()

    validated = parse_and_validate(
        result.text,
        max_headline_chars=settings.creator_copilot_max_headline_chars,
        max_content_idea_chars=settings.creator_copilot_max_content_idea_chars,
    )
    if validated is None:
        log_event(
            logger, logging.WARNING, "creator_suggestion_malformed_model_output",
            workspace_id=creator_profile_id, request_id=request_id,
            fields={"theme_matched": theme_matched},
        )
        return _fallback_response()

    headline, content_idea = validated
    log_event(
        logger, logging.INFO, "creator_suggestion_completed",
        workspace_id=creator_profile_id, request_id=request_id,
        fields={
            "model": CREATOR_COPILOT_MODEL,
            "message_source": "AI",
            "theme_matched": theme_matched,
            "headline": shape_of(headline),
            "content_idea": shape_of(content_idea),
        },
    )
    return {
        "success": True,
        "data": {"headline": headline, "content_idea": content_idea, "message_source": "AI"},
    }
