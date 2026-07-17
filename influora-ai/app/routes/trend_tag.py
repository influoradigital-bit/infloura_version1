"""POST /internal/trendspark/tag — the Trend-Spark LLM Recovery Tagger.

The "smart AI" recovery pass over the deterministic n8n theme-tagger. When
`trendspark/n8n/theme-tagger.js` returns `themes: []` (no keyword/niche match)
the n8n workflow would DROP that trend. Before dropping, it POSTs the raw text
here; a cheap Haiku-class model maps it onto the SAME closed vocabulary
(app/prompt/trend_tag.py). Anything off-vocab is stripped; if nothing valid
survives we return `recovered: false` and n8n drops the row exactly as before —
so this pass can only ADD correctly-tagged trends, never write garbage.

AUTH — DELIBERATE EXCEPTION, TRACKED AS TECH DEBT
-------------------------------------------------
Every other internal route (trendspark, brand_safety, analyze_site, voice) is
gated by a Spring-issued service-token JWT (app.auth.service_token). This one is
NOT: it is called directly by an n8n Code/HTTP node, and n8n cannot mint a
Spring service token. Per the Security Engineer guide ("Special Case: TrendSpark
LLM Recovery Tagger Auth") it uses a STATIC shared secret instead. That is a
weaker primitive than the JWT path (Kabir guardrail #2 — "never a lone static
shared secret") and is accepted ONLY as a v1 shortcut with the following
compensating controls, all enforced below:

  1. Secret is env-injected (TREND_TAG_INGEST_SECRET), never committed, never
     logged. If unset the endpoint fails closed with 503 (it never runs open).
  2. Bearer compared in CONSTANT TIME (hmac.compare_digest) — no timing oracle.
  3. Per-process fixed-window RATE LIMIT — blunts brute-forcing the secret and
     caps token spend from a compromised/looping caller (429 on exceed).
  4. trend_text/source/category are UNTRUSTED — wrapped before the model
     (prompt-injection defense, app.prompt.untrusted).
  5. Closed-vocab kill-switch — the model can only select from the locked lists;
     invented themes/types are dropped (app.prompt.trend_tag.parse_and_validate).
  6. Spend gate (kill-switch / daily ceiling) — on trip, drop without a model call.
  7. PII-free structured logging — shapes/counts only, never the raw trend text.

Rotate the secret quarterly (Kabir, Secrets Management table). Migrate to a
mutual-auth / signed-ingest primitive when n8n can carry one.
"""

from __future__ import annotations

import hmac
import json
import logging
import re
import time
import uuid
from collections import deque
from typing import Any

from fastapi import APIRouter, Header, HTTPException, Request

from app.config import TREND_TAG_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.trend_tag import (
    build_system_prompt,
    build_user_message,
    parse_and_validate,
    peak_window_for,
)
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of

logger = logging.getLogger(__name__)
router = APIRouter()

_claude_provider: ClaudeProvider | None = None

# ```json ... ``` / ``` ... ``` fence stripping (defensive parse).
_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


# ── Per-process fixed-window rate limiter ────────────────────────────────────
# Best-effort, per-process (documented). n8n is effectively one caller, so a
# global window is enough; keyed by client host too so one noisy source can't
# starve another if this ever fans out. NOT a substitute for an edge WAF rate
# limit in prod — it is the in-app compensating control for the static-secret
# auth (control #3 above).
_RL_WINDOW_SECONDS = 60.0
_rl_hits: dict[str, deque[float]] = {}


def _rate_limited(client_key: str, limit_per_minute: int, *, now: float) -> bool:
    """True if `client_key` has exceeded `limit_per_minute` in the trailing 60s.
    Records this hit when allowed. Disabled (never limits) if limit <= 0."""
    if limit_per_minute <= 0:
        return False
    bucket = _rl_hits.setdefault(client_key, deque())
    cutoff = now - _RL_WINDOW_SECONDS
    while bucket and bucket[0] < cutoff:
        bucket.popleft()
    if len(bucket) >= limit_per_minute:
        return True
    bucket.append(now)
    return False


def _drop_response() -> dict[str, Any]:
    """Fail-closed 'drop the row' result — byte-identical shape to a successful
    recovery, with recovered=false so the n8n caller drops it exactly as it does
    for the deterministic tagger's empty-themes output."""
    return {
        "success": True,
        "data": {
            "themes": [],
            "campaign_type": None,
            "peak_window_days": None,
            "recovered": False,
        },
    }


@router.post("/internal/trendspark/tag")
async def trendspark_tag(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    settings = get_settings()
    configured_secret = settings.trend_tag_ingest_secret

    # Control #1: fail closed if the secret is not configured. The endpoint never
    # runs open — an unconfigured secret is an ops error, not an auth bypass.
    if not configured_secret:
        log_event(
            logger, logging.ERROR, "trend_tag_secret_unconfigured",
            request_id=request_id,
            fields={"reason": "TREND_TAG_INGEST_SECRET is not set"},
        )
        raise HTTPException(
            status_code=503,
            detail={"code": "tagger_unconfigured", "message": "recovery tagger is not configured"},
        )

    # Control #2: constant-time bearer comparison (no early-exit timing oracle).
    presented = _bearer(authorization)
    if not presented or not hmac.compare_digest(presented, configured_secret):
        log_event(
            logger, logging.WARNING, "trend_tag_auth_failed",
            request_id=request_id,
            fields={"presented_len": len(presented)},
        )
        raise HTTPException(
            status_code=401, detail={"code": "unauthorized", "message": "invalid ingest secret"}
        )

    # Control #3: rate limit (blunts brute-force + runaway spend).
    client_host = request.client.host if request.client else "unknown"
    now = time.monotonic()
    if _rate_limited(client_host, settings.trend_tag_rate_limit_per_minute, now=now):
        log_event(
            logger, logging.WARNING, "trend_tag_rate_limited",
            request_id=request_id,
            fields={"client_key": shape_of(client_host), "limit": settings.trend_tag_rate_limit_per_minute},
        )
        raise HTTPException(
            status_code=429, detail={"code": "rate_limited", "message": "too many tagging requests"}
        )

    try:
        body = await request.json()
    except (ValueError, TypeError):
        body = {}
    if not isinstance(body, dict):
        body = {}

    # Control #4 inputs: trend_text is required-ish; empty/oversized text degrades
    # to a drop (never a 400 — a bad row must be dropped, not error the workflow).
    trend_text = str(body.get("trend_text") or "").strip()[: settings.trend_tag_max_trend_text_chars]
    if not trend_text:
        return _drop_response()
    source = body.get("source")
    category = str(body.get("category") or "")[:64]

    # Control #6: spend gate — on kill-switch / ceiling, drop without a model call.
    gate = await check_spend_gate()
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "trend_tag_blocked_spend_gate",
            request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _drop_response()

    log_event(
        logger, logging.INFO, "trend_tag_started",
        request_id=request_id,
        fields={"model": TREND_TAG_MODEL, "trend_text": shape_of(trend_text), "source": shape_of(source)},
    )

    system_prompt = build_system_prompt()
    user_message = build_user_message(trend_text=trend_text, source=source, category=category)

    claude = _get_claude()
    result = await claude.complete_text(
        system=system_prompt,
        user=user_message,
        model=TREND_TAG_MODEL,
        max_tokens=settings.trend_tag_max_tokens,
    )

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(TREND_TAG_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, None)
            log_event(
                logger, logging.INFO, "ai_spend",
                request_id=request_id,
                fields={
                    "route": "trend_tag",
                    "model": TREND_TAG_MODEL,
                    "cost_usd": str(cost_usd),
                    "spend_today_usd": str(spend_today),
                },
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                request_id=request_id, fields={"error": str(exc)},
            )

    if not result.ok or not result.text:
        log_event(
            logger, logging.WARNING, "trend_tag_provider_unavailable",
            request_id=request_id, fields={"error": result.error},
        )
        return _drop_response()

    # Control #5: defensive parse + closed-vocab validation.
    text = _CODE_FENCE_RE.sub("", result.text.strip()).strip()
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        parsed = None

    validated = parse_and_validate(parsed, max_themes=settings.trend_tag_max_themes)
    if validated is None:
        log_event(
            logger, logging.INFO, "trend_tag_dropped",
            request_id=request_id,
            fields={"reason": "no_valid_themes_or_type"},
        )
        return _drop_response()

    themes, campaign_type = validated
    peak_window_days = peak_window_for(campaign_type)
    log_event(
        logger, logging.INFO, "trend_tag_recovered",
        request_id=request_id,
        fields={
            "model": TREND_TAG_MODEL,
            "campaign_type": campaign_type,
            "theme_count": len(themes),
            "peak_window_days": peak_window_days,
        },
    )
    return {
        "success": True,
        "data": {
            "themes": themes,
            "campaign_type": campaign_type,
            "peak_window_days": peak_window_days,
            "recovered": True,
        },
    }
