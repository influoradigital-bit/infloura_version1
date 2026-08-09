"""POST /analyze-site — scrape + classify a brand website.

Called by Spring's async onboarding job (service token, aud=influora-internal).

EVERY outbound fetch to the brand-supplied URL goes through `ssrf_guard`. The
scraped HTML is sanitized (script/iframe/active-content stripped) BEFORE it
ever reaches a prompt — Gemini only ever sees inert text, never live markup
that could carry a prompt-injection payload disguised as page content.

P1-B: before sanitization throws the tags away, `app.prompt.structured_extract`
parses the RAW HTML for schema.org JSON-LD / OpenGraph / microdata product
facts (name/price/currency) — these are FACTS, not model guesses, and are
handed to Gemini as a "KNOWN PRODUCT FACTS" block so it fills only gaps.
`merge_known_products` then re-asserts those facts over the model's own
output before this route returns, so a scraped price can never be silently
overwritten by a hallucinated one downstream (calculate_budget, Meera).

End-to-end target: <= 45s (async job).
"""

from __future__ import annotations

import logging
import re
import uuid

import anyio
from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.config import GEMINI_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import record_spend
from app.prompt.assembler import wrap_untrusted_scrape
from app.prompt.structured_extract import ScrapedProduct, extract_structured_facts
from app.providers.gemini import GeminiProvider
from app.security.redaction import log_event, shape_of
from app.security.ssrf_guard import SsrfBlockedError, guarded_fetch

logger = logging.getLogger(__name__)
router = APIRouter()

_gemini_provider: GeminiProvider | None = None

# Strips script/style/iframe/object/embed tags and their contents, plus any
# on*="" event-handler attributes and javascript: URIs, before the text ever
# reaches a prompt. This is the PRIMARY control today: the fetch is a static
# `httpx` GET via `guarded_fetch` (see ssrf_guard.py) -- there is no
# Playwright / headless-browser render anywhere in this path, despite
# `playwright==1.49.1` sitting in requirements.txt. That means client-rendered
# (JS/SPA) storefronts can come back near-empty (see `empty_page` below).
# P1-B (structured JSON-LD/OpenGraph/microdata extraction from the RAW HTML,
# below) recovers most product facts on exactly those sites without a
# browser. Actual JS rendering is a separate, security-gated future task
# (P1-A(ii) -- Priya render-sandbox ruling, SHARED_CONTEXT.md) — a headless
# browser pointed at an attacker-controlled URL is real SSRF/RCE surface and
# must not be wired in casually.
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


_MAX_CATALOG_ITEMS = 25


def _scraped_product_to_dict(product: ScrapedProduct) -> dict:
    """P1-B: scraped facts always win. `price_source: "scraped"` is set here,
    unconditionally -- never derived from anything the model said."""
    return {
        "name": product.name,
        "price": product.price,
        "currency": product.currency,
        "price_source": "scraped",
    }


def merge_known_products(known: list[ScrapedProduct], model_catalog: list[dict] | None) -> list[dict]:
    """Merges structured-extraction FACTS with the model's product_catalog.

    Money-safety contract (P1-B): every scraped product is included verbatim
    with price_source="scraped" and its price is NEVER overwritten by the
    model's output -- even if the model also produced an entry for the same
    product name, the scraped fact wins and the model's duplicate is dropped.
    Anything the model produced beyond the known set is kept as
    price_source="inferred" (forced, regardless of what the model itself
    claimed) so a malformed/dishonest model response can never masquerade as
    a scraped fact downstream.
    """
    merged: list[dict] = [_scraped_product_to_dict(p) for p in known]
    known_names = {p.name.strip().lower() for p in known}

    for item in model_catalog or []:
        if len(merged) >= _MAX_CATALOG_ITEMS:
            break
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name or name.lower() in known_names:
            continue  # already represented by a scraped fact -- never override it
        merged.append(
            {
                "name": name[:200],
                "price": item.get("price"),
                "currency": item.get("currency"),
                "price_source": "inferred",
            }
        )

    return merged[:_MAX_CATALOG_ITEMS]


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
        # F-11: off the event loop. The guarded fetch does blocking DNS + a
        # synchronous httpx round-trip (up to ssrf_fetch_timeout_seconds per hop
        # across ssrf_max_redirects hops); awaiting it inline stalled every
        # concurrent SSE stream in this worker.
        raw_bytes, final_url = await anyio.to_thread.run_sync(lambda: guarded_fetch(url))
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
    except Exception:  # noqa: BLE001 - a decode failure degrades, never 500s
        # Was a SILENT swallow: an undecodable body became an empty page with
        # nothing logged, so "this brand's site analyzed as empty" and "we could
        # not read the bytes at all" were indistinguishable in production.
        log_event(
            logger, logging.WARNING, "analyze_site_decode_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"bytes": len(raw_bytes)},
        )
        html_text = ""

    # P1-B: parse structured product FACTS (JSON-LD/OpenGraph/microdata) from
    # the RAW HTML -- must run BEFORE strip_active_content, which throws away
    # <script>/<meta> tags entirely. This is the already-fetched, SSRF-safe
    # response; extraction makes zero new network calls. Pure stdlib parsing
    # over inert text -- never executed, so this is safe even though it reads
    # <script type="application/ld+json"> content the model itself never sees
    # verbatim (only the parsed JSON facts are forwarded to Gemini, wrapped as
    # untrusted data same as the rest of the page text).
    try:
        known_products = extract_structured_facts(html_text)
    except Exception as exc:  # noqa: BLE001 - extraction is additive, never fatal
        log_event(
            logger, logging.WARNING, "analyze_site_structured_extract_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_type": type(exc).__name__},
        )
        known_products = []

    sanitized_text = strip_active_content(html_text)

    # Priya's render-sandbox ruling (SHARED_CONTEXT.md, 2026-07-21): many
    # Shopify/Wix "empty-looking" SPA pages still emit Product/Offer JSON-LD
    # server-side even when the *visible* text is sparse/empty. If structured
    # extraction recovered something, do NOT bail with empty_page -- classify
    # using the known facts (the model still gets whatever sanitized text
    # exists, which may legitimately be "").  This measurement (how often
    # known_products is non-empty on an otherwise-empty page) is exactly the
    # signal that informs whether the render sidecar (P1-A(ii)) is still
    # needed for the residual set.
    if not sanitized_text and not known_products:
        return {
            "success": False,
            "error": {"code": "empty_page", "message": "no readable content found"},
            "degraded": "paste_a_link",
        }
    if not sanitized_text and known_products:
        log_event(
            logger, logging.INFO, "analyze_site_structured_only_recovery",
            workspace_id=workspace_id, request_id=request_id,
            fields={"scraped_product_count": len(known_products)},
        )

    # Wrap as untrusted data before it ever touches a prompt — Gemini's classify
    # call embeds this as data, and any embedded "ignore previous instructions"
    # style payload in the page text is inert here (Gemini is only asked to
    # extract structured fields, not to follow instructions from the page).
    untrusted_payload = wrap_untrusted_scrape(sanitized_text[:20000])

    known_product_dicts = [
        {"name": p.name, "price": p.price, "currency": p.currency} for p in known_products
    ]

    gemini = _get_gemini()
    result = await gemini.classify_site(untrusted_payload, known_products=known_product_dicts)

    if result.usage:
        try:
            cost_usd = estimate_cost_usd(GEMINI_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, workspace_id, reservation=gate.reservation)
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
            "product_catalog": merge_known_products(known_products, result.product_catalog),
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
        # F-09: off the event loop. verify_token can perform a blocking JWKS
        # fetch, and an unauthenticated request with an unknown `kid` is enough
        # to trigger one — awaiting it inline stalled every coroutine here.
        await anyio.to_thread.run_sync(
            lambda: verify_token(token, endpoint="analyze_site", body_workspace_id=workspace_id)
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    result = await perform_site_analysis(url=url, workspace_id=workspace_id)
    if result.get("spend_blocked"):
        raise HTTPException(status_code=503, detail=result["error"])
    return result
