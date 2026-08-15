"""POST /internal/trendspark/nudge — the ONE cheap AI phrasing call for Trend-Spark.

Internal-only: called by Java's `TrendSparkAiClient` (T4), never by a browser.
Auth is the same Spring-issued service-token pattern as /internal/brand-safety
and /analyze-site (app.auth.service_token): scope="service", aud=influora-internal,
the token's workspace_id must match the request body. Any auth failure -> 401/403
with no provider call, no token spend.

Contract (must match `TrendSparkAiDtos.java` / `TrendSparkAiClient.java` byte-for-byte):
  request : {workspace_id, brand_name, campaign_type, trend_text, mode, videos:[{video_id,title,themes}]}
  response: {success, data:{message, video_ids}}        # NO price field, by construction

Guardrails (schema-lock §4, tone-guide §6/§7):
  - Cheap model (Haiku-class, TRENDSPARK_MODEL) — never Opus/Sonnet.
  - Defensive parse: strip code fences, json.loads in try/except, validate message
    is non-empty + sane length + <=2 sentences + no forbidden pet-names + mode rules.
  - video_ids ⊆ the ids we were sent (drop hallucinated ones); reject any echoed price.
  - On ANY failure (provider error, malformed output, failed validation, spend gate)
    -> deterministic templated fallback, still HTTP 200 with data.message. NEVER a 500
    for a phrasing miss.
  - brand_name / trend_text are UNTRUSTED (prompt-injection defense) — delimited via
    app.prompt.untrusted before they ever reach the model.
  - PII-free structured log per nudge: model + message_source (AI|FALLBACK), never the
    raw message or brand strings (only shapes).
"""

from __future__ import annotations

import json
import logging
import re
import uuid
from typing import Any

import anyio
from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.config import TRENDSPARK_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.trendspark import (
    MODE_OWN_CONTENT,
    MODE_SNAPSBY,
    build_system_prompt,
    build_user_message,
    fallback_message,
    normalize_mode,
)
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

# OWN_CONTENT must never surface the marketplace (tone-guide §3/§6 rule 5).
# Trendspark-specific (unlike the 5 regexes above, now shared via
# app.prompt.validators per Priya R1 Conflict 7) — this one stays local since
# it only applies to trendspark's OWN_CONTENT mode branch.
#
# F-20: this banned "video" and "buy". The sibling creator route removed exactly
# those two words after Ash's ruling (see app/routes/creator_suggestion.py's
# `_MARKETPLACE_RE`) because they are ordinary English in a content idea and
# banning them forced a near-permanent fallback — and the fallback happens AFTER
# the paid Haiku call, so every rejected nudge is billed and thrown away. The
# fix was applied there and never here. The system prompt's tone control is what
# keeps this voice from sounding like a marketplace pitch; the only thing this
# regex needs to catch is the literal brand name leaking through.
_OWN_CONTENT_FORBIDDEN_RE = re.compile(r"\bsnapsby\b", re.IGNORECASE)


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


def _normalize_videos(raw: Any, max_videos: int) -> tuple[list[dict[str, Any]], list[str]]:
    """Return (normalized_videos, sent_ids). Silently drops malformed entries and
    caps the count — this is a phrasing helper, not a strict validator, so bad
    video shapes degrade to fewer videos, never a 400."""
    videos: list[dict[str, Any]] = []
    sent_ids: list[str] = []
    if not isinstance(raw, list):
        return videos, sent_ids
    for item in raw:
        if not isinstance(item, dict):
            continue
        vid = item.get("video_id")
        if not isinstance(vid, str) or not vid.strip():
            continue
        title = item.get("title") if isinstance(item.get("title"), str) else ""
        raw_themes = item.get("themes")
        themes = [t for t in raw_themes if isinstance(t, str)] if isinstance(raw_themes, list) else []
        videos.append({"video_id": vid, "title": title, "themes": themes})
        sent_ids.append(vid)
        if len(videos) >= max_videos:
            break
    return videos, sent_ids


def parse_and_validate(
    raw_text: str,
    *,
    mode: str,
    sent_ids: list[str],
    max_message_chars: int,
) -> tuple[str, list[str]] | None:
    """Defensive parse + validation of the model's text output. Returns
    (message, validated_video_ids) on success, or None on ANY failure (so the
    route falls back). Never raises.

    Enforces (tone-guide §6): valid JSON, non-empty message, length cap,
    <=2 sentences, no forbidden pet-names, no echoed price, mode rules, and
    video_ids ⊆ sent_ids (invented ids dropped).
    """
    text = _CODE_FENCE_RE.sub("", raw_text.strip()).strip()
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None

    message = parsed.get("message")
    if not isinstance(message, str):
        return None
    message = message.strip()
    if not message or len(message) > max_message_chars:
        return None
    if _statement_count(message) > 2:
        return None
    if _has_forbidden_petname(message):
        return None
    if has_invented_price(message):  # echoed/invented price -> reject
        return None
    if mode == MODE_OWN_CONTENT and _OWN_CONTENT_FORBIDDEN_RE.search(message):
        return None

    # video_ids ⊆ sent ids (hallucination kill-switch); OWN_CONTENT -> always empty.
    validated_ids: list[str] = []
    if mode == MODE_SNAPSBY:
        raw_ids = parsed.get("video_ids")
        sent_set = set(sent_ids)
        seen: set[str] = set()
        if isinstance(raw_ids, list):
            for vid in raw_ids:
                if isinstance(vid, str) and vid in sent_set and vid not in seen:
                    validated_ids.append(vid)
                    seen.add(vid)
    return message, validated_ids


@router.post("/internal/trendspark/nudge")
async def trendspark_nudge(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")

    if not workspace_id:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id is required"},
        )

    try:
        # F-09: off the event loop (blocking JWKS fetch).
        await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization), endpoint="trendspark", body_workspace_id=workspace_id
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    settings = get_settings()

    # Normalize inputs defensively. Untrusted brand/trend strings are length-capped
    # (DoS on prompt tokens) by truncation rather than 400 — a nudge must still be
    # produced; the deterministic fallback below never fails.
    brand_name = str(body.get("brand_name") or "")[: settings.trendspark_max_brand_name_chars]
    campaign_type = str(body.get("campaign_type") or "")[:32]
    trend_text = str(body.get("trend_text") or "")[: settings.trendspark_max_trend_text_chars]
    mode = normalize_mode(body.get("mode"))
    videos, sent_ids = _normalize_videos(body.get("videos"), settings.trendspark_max_videos)

    def _fallback_response() -> dict[str, Any]:
        msg = fallback_message(
            brand_name=brand_name,
            campaign_type=campaign_type or "new",
            trend_text=trend_text or "a trend in your niche",
            mode=mode,
            video_count=len(sent_ids),
        )
        vids = sent_ids if mode == MODE_SNAPSBY else []
        log_event(
            logger, logging.INFO, "trendspark_nudge_completed",
            workspace_id=workspace_id, request_id=request_id,
            fields={
                "model": TRENDSPARK_MODEL,
                "message_source": "FALLBACK",
                "mode": mode,
                "video_ids_count": len(vids),
                "message": shape_of(msg),
            },
        )
        return {"success": True, "data": {"message": msg, "video_ids": vids}}

    # Spend gate: on kill-switch / ceiling, skip the AI call and return the
    # deterministic fallback (still 200) — the user never sees an error and no
    # provider tokens are spent.
    gate = await check_spend_gate(
        workspace_id=workspace_id,
        # F-05: hold budget between this check and the recorded spend, so
        # concurrent callers see each other's in-flight cost instead of all
        # reading the same stale total. Settled by record_spend below; expires
        # on its own if this request dies before it gets there.
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "trendspark_nudge_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _fallback_response()

    log_event(
        logger, logging.INFO, "trendspark_nudge_started",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "mode": mode,
            "campaign_type": campaign_type,
            "video_count": len(videos),
            "brand_name": shape_of(brand_name),
            "trend_text": shape_of(trend_text),
        },
    )

    system_prompt = build_system_prompt(mode)
    user_message = build_user_message(
        brand_name=brand_name,
        campaign_type=campaign_type,
        trend_text=trend_text,
        mode=mode,
        videos=videos,
    )

    claude = _get_claude()
    result = await claude.complete_text(
        system=system_prompt,
        user=user_message,
        model=TRENDSPARK_MODEL,
        max_tokens=settings.trendspark_max_tokens,
    )

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(TRENDSPARK_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, workspace_id, reservation=gate.reservation)
            log_event(
                logger, logging.INFO, "ai_spend",
                workspace_id=workspace_id, request_id=request_id,
                fields={
                    "route": "trendspark_nudge",
                    "model": TRENDSPARK_MODEL,
                    "cost_usd": str(cost_usd),
                    "spend_today_usd": str(spend_today),
                },
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                workspace_id=workspace_id, request_id=request_id,
                fields={"error": str(exc)},
            )

    if not result.ok or not result.text:
        return _fallback_response()

    validated = parse_and_validate(
        result.text,
        mode=mode,
        sent_ids=sent_ids,
        max_message_chars=settings.trendspark_max_message_chars,
    )
    if validated is None:
        log_event(
            logger, logging.WARNING, "trendspark_nudge_malformed_model_output",
            workspace_id=workspace_id, request_id=request_id,
            fields={"mode": mode},
        )
        return _fallback_response()

    message, video_ids = validated
    log_event(
        logger, logging.INFO, "trendspark_nudge_completed",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "model": TRENDSPARK_MODEL,
            "message_source": "AI",
            "mode": mode,
            "video_ids_count": len(video_ids),
            "message": shape_of(message),
        },
    )
    return {"success": True, "data": {"message": message, "video_ids": video_ids}}
