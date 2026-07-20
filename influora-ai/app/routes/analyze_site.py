"""POST /analyze-site — scrape + classify a brand website.

Called by Spring's async onboarding job (service token, aud=influora-internal).

EVERY outbound fetch to the brand-supplied URL goes through `ssrf_guard`. The
scraped HTML is sanitized (script/iframe/active-content stripped) BEFORE it
ever reaches a prompt — Gemini only ever sees inert text, never live markup
that could carry a prompt-injection payload disguised as page content.

End-to-end target: <= 45s (async job).
"""

from __future__ import annotations

import logging
import re
import uuid

from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.config import GEMINI_MODEL
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.assembler import wrap_untrusted_scrape
from app.providers.gemini import GeminiProvider
from app.security.redaction import log_event, shape_of
from app.security.ssrf_guard import SsrfBlockedError, guarded_fetch

logger = logging.getLogger(__name__)
router = APIRouter()

_gemini_provider: GeminiProvider | None = None

# Strips script/style/iframe/object/embed tags and their contents, plus any
# on*="" event-handler attributes and javascript: URIs, before the text ever
# reaches a prompt. This is a defense-in-depth text sanitizer; Playwright's
# rendered DOM text extraction (not raw innerHTML) is the primary control.
_ACTIVE_TAG_RE = re.compile(
    r"<\s*(script|style|iframe|object|embed|noscript)[^>]*>.*?<\s*/\s*\1\s*>",
    re.IGNORECASE | re.DOTALL,
)
_SELF_CLOSING_ACTIVE_TAG_RE = re.compile(
    r"<\s*(script|style|iframe|object|embed)[^>]*/?>", re.IGNORECASE
)
_EVENT_HANDLER_ATTR_RE = re.compile(r'\son\w+\s*=\s*(?:"[^"]*"|\'[^\']*\'|[^\s>]+)', re.IGNORECASE)
_JS_URI_RE = re.compile(r'javascript\s*:', re.IGNORECASE)
_HTML_TAG_RE = re.compile(r"<[^>]+>")


def _get_gemini() -> GeminiProvider:
    global _gemini_provider
    if _gemini_provider is None:
        _gemini_provider = GeminiProvider()
    return _gemini_provider


def strip_active_content(html: str) -> str:
    """Removes script/style/iframe/object/embed blocks, event-handler
    attributes, and javascript: URIs, then strips remaining tags to leave
    plain inert text. This runs on every scrape result before it is wrapped as
    untrusted content and handed to Gemini.
    """
    cleaned = _ACTIVE_TAG_RE.sub(" ", html)
    cleaned = _SELF_CLOSING_ACTIVE_TAG_RE.sub(" ", cleaned)
    cleaned = _EVENT_HANDLER_ATTR_RE.sub("", cleaned)
    cleaned = _JS_URI_RE.sub("blocked:", cleaned)
    text_only = _HTML_TAG_RE.sub(" ", cleaned)
    # Collapse excessive whitespace left behind by tag stripping.
    return re.sub(r"\s+", " ", text_only).strip()


async def perform_site_analysis(
    url: str, workspace_id: str, request_id: str | None = None
) -> dict:
    """Core scrape+classify pipeline, callable outside the HTTP route.

    Used by BOTH the POST /analyze-site route (Spring's onboarding job) AND the
    Meera chat tool loop (`analyze_site` is a local, Python-native chat tool —
    when the brand pastes a product URL, Meera calls this to read the REAL page
    instead of guessing/hallucinating the product and price). NEVER raises for a
    provider/fetch failure — always returns a dict; the caller decides how to
    surface it (the route maps a spend-block to 503; the tool loop hands the
    dict back to Claude as a tool_result). Auth/gate ordering is unchanged from
    the original route.
    """
    request_id = request_id or str(uuid.uuid4())

    # P2-17: kill-switch / daily ceiling gate -- checked before any provider
    # (Gemini) call, zero provider calls made if blocked.
    gate = await check_spend_gate(workspace_id=workspace_id)
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "analyze_site_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return {
            "success": False,
            "error": {"code": gate.error_code, "message": gate.error_message},
            "spend_blocked": True,
        }

    log_event(
        logger, logging.INFO, "analyze_site_started",
        workspace_id=workspace_id, request_id=request_id, fields={"url": shape_of(url)},
    )

    try:
        raw_bytes, final_url = guarded_fetch(url)
    except SsrfBlockedError as exc:
        log_event(
            logger, logging.WARNING, "analyze_site_ssrf_blocked",
            workspace_id=workspace_id, request_id=request_id, fields={"reason": str(exc)},
        )
        return {
            "success": False,
            "error": {"code": "url_rejected", "message": "this URL could not be safely fetched"},
            "degraded": "paste_a_link",
        }

    try:
        html_text = raw_bytes.decode("utf-8", errors="ignore")
    except Exception:
        html_text = ""

    sanitized_text = strip_active_content(html_text)
    if not sanitized_text:
        return {
            "success": False,
            "error": {"code": "empty_page", "message": "no readable content found"},
            "degraded": "paste_a_link",
        }

    # Wrap as untrusted data before it ever touches a prompt — Gemini's classify
    # call embeds this as data, and any embedded "ignore previous instructions"
    # style payload in the page text is inert here (Gemini is only asked to
    # extract structured fields, not to follow instructions from the page).
    untrusted_payload = wrap_untrusted_scrape(sanitized_text[:20000])

    gemini = _get_gemini()
    result = await gemini.classify_site(untrusted_payload)

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(GEMINI_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, workspace_id)
            log_event(
                logger, logging.INFO, "ai_spend",
                workspace_id=workspace_id, request_id=request_id,
                fields={
                    "route": "analyze_site",
                    "model": GEMINI_MODEL,
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
            logger, logging.WARNING, "analyze_site_classify_failed",
            workspace_id=workspace_id, request_id=request_id, fields={"error": result.error},
        )
        return {
            "success": False,
            "error": {"code": "classify_failed", "message": "could not classify this site"},
            "degraded": "paste_a_link",
        }

    return {
        "success": True,
        "data": {
            "source_url": final_url,
            "niche_tags": result.niche_tags,
            "tone_dial": result.tone_dial,
            "brand_color": result.brand_color,
            "product_catalog": result.product_catalog,
        },
    }


@router.post("/analyze-site")
async def analyze_site(request: Request, authorization: str | None = Header(default=None)):
    """POST /analyze-site — auth, then run the shared scrape+classify pipeline.
    Preserves the original external contract: 400 on missing fields, 401/403 on
    bad token, 503 on spend-gate block, otherwise the success/degraded dict.
    """
    body = await request.json()
    workspace_id = body.get("workspace_id")
    url = body.get("url")

    if not workspace_id or not url:
        raise HTTPException(status_code=400, detail={"code": "missing_fields", "message": "workspace_id and url are required"})

    token = authorization.split(" ", 1)[1].strip() if authorization and authorization.lower().startswith("bearer ") else ""
    try:
        verify_token(token, endpoint="analyze_site", body_workspace_id=workspace_id)
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    result = await perform_site_analysis(url=url, workspace_id=workspace_id)
    if result.get("spend_blocked"):
        raise HTTPException(status_code=503, detail=result["error"])
    return result
