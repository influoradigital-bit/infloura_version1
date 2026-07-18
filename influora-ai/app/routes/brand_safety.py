"""POST /internal/brand-safety — GARM/NLP brand-safety classification.

Wave C task C2 (wiki/tech/REMAINING_WORK_PLAN.md). Internal-only: called by
Java's `BrandSafetyAiClient` (task C3, not this repo), never by a browser.
Auth is the same Spring-issued service-token pattern as /analyze-site and
/voice/* (app.auth.service_token) — scope="service", aud=influora-internal,
workspace_id in the token must match the request body. Any auth failure is
401/403 with no provider call, no token spend, per that module's contract.

Input: a batch of creator content items (caption text + minimal metadata).
Per the locked ADR (wiki/decisions/2026-07-06-brand-safety-caption-storage.md),
captions are persisted by C1 during Java's metrics polling and supplied here
by the caller — this service never fetches from Meta and holds no DB
connection of its own (stateless, per app/main.py's module contract).

Output: one GARM classification + sentiment per input item, in the same
order, suitable for Java's BrandSafetyScoreService (C3) to write into
`creator_scores.brand_safety_score` / `garm_flags` / `content_sentiment`.

Caption text is NEVER logged raw (Kabir guardrail #3) — only shapes/lengths
via app.security.redaction.shape_of, matching every other route in this repo.
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.config import BRAND_SAFETY_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.brand_safety import build_system_block, build_user_message
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of
from app.tools.schemas import (
    CONTENT_SENTIMENTS,
    GARM_CATEGORIES,
    GARM_RISK_LEVELS,
    get_analyze_creator_content_schema,
)

logger = logging.getLogger(__name__)
router = APIRouter()

_claude_provider: ClaudeProvider | None = None

_GARM_CATEGORY_SET = frozenset(GARM_CATEGORIES)
_GARM_RISK_SET = frozenset(GARM_RISK_LEVELS)
_SENTIMENT_SET = frozenset(CONTENT_SENTIMENTS)


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


def _validate_items(
    body: dict[str, Any],
    max_items: int,
    max_caption_chars: int,
    max_meta_field_chars: int,
) -> list[dict[str, Any]]:
    """Validates and normalizes the `items` array. Raises HTTPException(400)
    with a typed error code on any malformed input — never raises an
    unhandled exception, and never silently drops/coerces a bad item.

    content_id, media_type, and posted_at are all length-capped at
    `max_meta_field_chars` (red-team LOW-4, brand-safety-endpoint-C2-security-
    review.md HIGH-2 rework) for the same DoS reason `caption` is capped at
    `max_caption_chars`: all three are interpolated into the prompt's
    per-item meta line (app/prompt/brand_safety.py build_user_message), so an
    unbounded value is an unbounded prompt / token-cost surface, independent
    of the separate escaping fix for that same meta line.
    """
    items = body.get("items")
    if not isinstance(items, list) or len(items) == 0:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_items", "message": "items must be a non-empty array"},
        )
    if len(items) > max_items:
        raise HTTPException(
            status_code=400,
            detail={
                "code": "too_many_items",
                "message": f"items must contain at most {max_items} entries per call",
            },
        )

    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for idx, raw_item in enumerate(items):
        if not isinstance(raw_item, dict):
            raise HTTPException(
                status_code=400,
                detail={"code": "invalid_item", "message": f"items[{idx}] must be an object"},
            )
        content_id = raw_item.get("content_id")
        if not isinstance(content_id, str) or not content_id.strip():
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "invalid_item",
                    "message": f"items[{idx}].content_id is required and must be a non-empty string",
                },
            )
        if len(content_id) > max_meta_field_chars:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "content_id_too_long",
                    "message": (
                        f"items[{idx}].content_id must be at most {max_meta_field_chars} "
                        "characters"
                    ),
                },
            )
        if content_id in seen_ids:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "duplicate_content_id",
                    "message": f"content_id {content_id!r} appears more than once in items",
                },
            )
        seen_ids.add(content_id)

        caption = raw_item.get("caption")
        if caption is not None and not isinstance(caption, str):
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "invalid_item",
                    "message": f"items[{idx}].caption must be a string or null",
                },
            )
        if caption is not None and len(caption) > max_caption_chars:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "caption_too_long",
                    "message": (
                        f"items[{idx}].caption must be at most {max_caption_chars} "
                        "characters"
                    ),
                },
            )

        media_type = raw_item.get("media_type") if isinstance(raw_item.get("media_type"), str) else None
        if media_type is not None and len(media_type) > max_meta_field_chars:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "media_type_too_long",
                    "message": (
                        f"items[{idx}].media_type must be at most {max_meta_field_chars} "
                        "characters"
                    ),
                },
            )

        posted_at = raw_item.get("posted_at") if isinstance(raw_item.get("posted_at"), str) else None
        if posted_at is not None and len(posted_at) > max_meta_field_chars:
            raise HTTPException(
                status_code=400,
                detail={
                    "code": "posted_at_too_long",
                    "message": (
                        f"items[{idx}].posted_at must be at most {max_meta_field_chars} "
                        "characters"
                    ),
                },
            )

        normalized.append(
            {
                "content_id": content_id,
                "caption": caption or "",
                "media_type": media_type,
                "posted_at": posted_at,
            }
        )
    return normalized


def _validate_model_result(tool_input: dict[str, Any], expected_ids: list[str]) -> list[dict[str, Any]] | None:
    """Defense-in-depth validation of Claude's tool_use input against the
    schema's own contract, in case the model returns something malformed
    despite the forced tool_choice (e.g. missing a category, an out-of-enum
    value, wrong item count). Returns None (never raises) if the result can't
    be trusted, so the route can degrade to a typed 502 instead of forwarding
    a garbage score to Java.
    """
    results = tool_input.get("items")
    if not isinstance(results, list) or len(results) != len(expected_ids):
        return None

    validated: list[dict[str, Any]] = []
    for expected_id, result in zip(expected_ids, results):
        if not isinstance(result, dict):
            return None
        if result.get("content_id") != expected_id:
            return None

        flags = result.get("garm_flags")
        if not isinstance(flags, list):
            return None
        flag_categories = set()
        for flag in flags:
            if not isinstance(flag, dict):
                return None
            category = flag.get("category")
            risk = flag.get("risk")
            if category not in _GARM_CATEGORY_SET or risk not in _GARM_RISK_SET:
                return None
            flag_categories.add(category)
        if flag_categories != _GARM_CATEGORY_SET:
            # Every category must be scored — a partial set could quietly
            # imply "no concern" for a category the model skipped.
            return None

        sentiment = result.get("content_sentiment")
        if sentiment not in _SENTIMENT_SET:
            return None

        score = result.get("brand_safety_score")
        if not isinstance(score, (int, float)) or not (0 <= score <= 100):
            return None

        sentiment_score = result.get("sentiment_score")
        if not isinstance(sentiment_score, (int, float)) or not (-1.0 <= sentiment_score <= 1.0):
            return None

        validated.append(
            {
                "content_id": expected_id,
                "garm_flags": flags,
                "content_sentiment": sentiment,
                "sentiment_score": float(sentiment_score),
                "brand_safety_score": float(score),
                "overall_rationale": result.get("overall_rationale") or "",
            }
        )
    return validated


@router.post("/internal/brand-safety")
async def brand_safety(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")

    if not workspace_id:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id is required"},
        )

    try:
        verify_token(_bearer(authorization), endpoint="brand_safety", body_workspace_id=workspace_id)
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # P2-17: kill-switch / daily ceiling gate -- checked before any provider
    # (Claude) call, zero provider calls made if blocked. Placed after the
    # workspace_id/auth checks above (same "auth first" ordering as chat.py
    # and analyze_site.py) but before the Claude call itself.
    gate = await check_spend_gate(workspace_id=workspace_id)
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "brand_safety_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        raise HTTPException(
            status_code=503,
            detail={"code": gate.error_code, "message": gate.error_message},
        )

    settings = get_settings()
    normalized_items = _validate_items(
        body,
        settings.brand_safety_max_items_per_call,
        settings.brand_safety_max_caption_chars,
        settings.brand_safety_max_meta_field_chars,
    )

    log_event(
        logger, logging.INFO, "brand_safety_started",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "item_count": len(normalized_items),
            "captions": shape_of([item["caption"] for item in normalized_items]),
        },
    )

    system_block = build_system_block()
    user_message = build_user_message(normalized_items)
    tool_schema = get_analyze_creator_content_schema()

    claude = _get_claude()
    result = await claude.complete_with_forced_tool(
        system_blocks=[system_block],
        messages=[user_message],
        tool_schema=tool_schema,
        max_tokens=settings.brand_safety_max_tokens,
        model=BRAND_SAFETY_MODEL,
    )

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(BRAND_SAFETY_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, workspace_id)
            log_event(
                logger, logging.INFO, "ai_spend",
                workspace_id=workspace_id, request_id=request_id,
                fields={
                    "route": "brand_safety",
                    "model": BRAND_SAFETY_MODEL,
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

    if not result.ok:
        log_event(
            logger, logging.WARNING, "brand_safety_provider_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error": result.error},
        )
        raise HTTPException(
            status_code=502,
            detail={"code": "classification_failed", "message": "could not classify this content"},
        )

    expected_ids = [item["content_id"] for item in normalized_items]
    validated_results = _validate_model_result(result.tool_input or {}, expected_ids)

    if validated_results is None:
        log_event(
            logger, logging.WARNING, "brand_safety_malformed_model_output",
            workspace_id=workspace_id, request_id=request_id,
            fields={"expected_count": len(expected_ids)},
        )
        raise HTTPException(
            status_code=502,
            detail={
                "code": "malformed_classification",
                "message": "classification result did not match the expected shape",
            },
        )

    log_event(
        logger, logging.INFO, "brand_safety_completed",
        workspace_id=workspace_id, request_id=request_id,
        fields={"item_count": len(validated_results), "usage": result.usage},
    )

    return {"success": True, "data": {"items": validated_results}}
