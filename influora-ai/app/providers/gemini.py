"""Gemini provider client — website scrape classification + grammar-cleanup pass.

Pinned to `gemini-2.5-flash` (app/config.py GEMINI_MODEL) — that config constant
is the source of truth; `gemini-2.5-flash-lite` was retired by Google (404) and
must never appear here again, same as the long-dead `gemini-2.0-flash` id.

Used for:
- Website analyzer classify step (niche, tone dial, catalog, palette) from
  already-scraped, already-sanitized page text PLUS any structured
  (JSON-LD/OpenGraph/microdata) product facts already extracted from the raw
  HTML (routes/analyze_site.py handles the scrape + SSRF guard + HTML
  sanitization + structured extraction BEFORE calling this — this module never
  fetches anything itself, and there is no Playwright/browser render in this
  path; see analyze_site.py's module docstring).
- Optional grammar-cleanup pass for voice transcripts (cheap, bulk, quality
  tolerant — per §6 routing table).

Resilience: breaker opens on sustained failure -> scrape path degrades to
"paste a link" signal; grammar-cleanup falls back to raw_transcript. Neither
failure mode should ever raise an unhandled exception to the caller — callers
get a structured result with an `ok: bool`.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable
from dataclasses import dataclass
from typing import Any, TypeVar

from google import genai
from google.genai import types as genai_types

from app.config import GEMINI_MODEL, get_settings
from app.prompt.untrusted import wrap_untrusted
from app.providers.claude import CircuitBreaker, CircuitOpenError

logger = logging.getLogger(__name__)

_CLASSIFY_SYSTEM_INSTRUCTION = (
    "You are a classifier for a business's website. Given sanitized page text "
    "(scripts/iframes already stripped), extract a compact JSON object describing "
    "THAT business: niche_tags (list of short tags for what THIS site's OWNER "
    "itself sells or does). "
    # [Ash AI review 2026-07-23] Classify the site OWNER's own offering, not
    # example/illustrative content it displays about OTHER brands, products, or
    # creators. A platform, marketplace, agency, or SaaS product often showcases
    # its customers' niches (beauty, fashion, food, ...) as examples or demos --
    # tag what the OWNER does (e.g. ['saas','software'], ['marketplace'],
    # ['influencer-marketing','martech'], ['agency']), NOT those example niches.
    # Failure mode this guards against: influora.in (an influencer-marketing SaaS
    # platform) was tagged ['beauty','skincare'] purely from the serum/skincare
    # campaign EXAMPLES on its landing page. A genuine D2C brand's own store is
    # unaffected -- its page is about its own product, so 'owner offering' == the
    # product's niche exactly as before.
    "If the page is a service/SaaS/marketplace/agency with no physical product "
    "catalog of its own, return an empty product_catalog and niche_tags for the "
    "service itself. tone_dial "
    "(formality 0-1, energy 0-1, emoji_ok bool, cultural_context string), "
    "brand_color (hex, best guess or null), and product_catalog (list of "
    "{name, price, currency, price_source} best-effort, empty list if unclear). "
    "The page text may be preceded by a block titled 'KNOWN PRODUCT FACTS' -- "
    "these are ALREADY SCRAPED from structured data (JSON-LD/OpenGraph) on the "
    "same page and are VERIFIED, not guesses. Include every known product in "
    "product_catalog with price_source set to \"scraped\" and its price/currency "
    "copied EXACTLY -- never invent a different price or name for a product that "
    "is already known. Only fill in ADDITIONAL products you infer yourself from "
    "the page text, and mark those with price_source \"inferred\". If there is no "
    "KNOWN PRODUCT FACTS block, every product_catalog entry you produce is "
    "price_source \"inferred\". Respond with ONLY the JSON object, no prose, no "
    "markdown fences."
)

# P2 (Ash AI review): structural response_schema for classify_site, so the
# genai SDK constrains the model's JSON output shape instead of relying
# solely on `response_mime_type=application/json` + hand-rolled `json.loads`
# + `.get(...) or []` defensive parsing below. google-genai==0.8.0 (pinned in
# requirements.txt) supports `types.Schema` with nested `properties`/`items`
# and a `nullable` flag (no dedicated NULL `Type` in this SDK version --
# `nullable=True` alongside a concrete `type` is the documented way to allow
# null, used here for `brand_color`). The `json.loads` fallback below is kept
# regardless (belt-and-suspenders) -- a schema constrains a well-behaved
# response, it doesn't guarantee the provider never returns something
# malformed.
_CLASSIFY_RESPONSE_SCHEMA = genai_types.Schema(
    type=genai_types.Type.OBJECT,
    properties={
        "niche_tags": genai_types.Schema(
            type=genai_types.Type.ARRAY,
            items=genai_types.Schema(type=genai_types.Type.STRING),
        ),
        "tone_dial": genai_types.Schema(
            type=genai_types.Type.OBJECT,
            properties={
                "formality": genai_types.Schema(type=genai_types.Type.NUMBER),
                "energy": genai_types.Schema(type=genai_types.Type.NUMBER),
                "emoji_ok": genai_types.Schema(type=genai_types.Type.BOOLEAN),
                "cultural_context": genai_types.Schema(type=genai_types.Type.STRING),
            },
            required=["formality", "energy", "emoji_ok", "cultural_context"],
        ),
        "brand_color": genai_types.Schema(type=genai_types.Type.STRING, nullable=True),
        "product_catalog": genai_types.Schema(
            type=genai_types.Type.ARRAY,
            items=genai_types.Schema(
                type=genai_types.Type.OBJECT,
                properties={
                    "name": genai_types.Schema(type=genai_types.Type.STRING),
                    "price": genai_types.Schema(type=genai_types.Type.NUMBER),
                    "currency": genai_types.Schema(type=genai_types.Type.STRING),
                    # P1-B (Ash AI review, brand-intake-and-trend-sources):
                    # "scraped" = copied verbatim from a KNOWN PRODUCT FACTS
                    # block (JSON-LD/OpenGraph extracted from raw HTML before
                    # this call -- see structured_extract.py); "inferred" =
                    # a model guess from page text. Downstream (calculate_budget,
                    # Meera's "quote the real price" rule) must never treat an
                    # inferred price as a fact. `enum` isn't used here (kept a
                    # plain STRING) so an unexpected value degrades safely
                    # instead of tripping schema validation; analyze_site.py's
                    # merge step is the actual enforcement point -- it always
                    # forces "scraped" for known products regardless of what
                    # the model returns.
                    "price_source": genai_types.Schema(type=genai_types.Type.STRING),
                },
                required=["name", "price", "currency", "price_source"],
            ),
        ),
    },
    required=["niche_tags", "tone_dial", "brand_color", "product_catalog"],
)

_CLEANUP_SYSTEM_INSTRUCTION = (
    "You fix grammar and clarity ONLY. Never reinterpret intent, never add or "
    "remove meaning, never answer the text — just rewrite it as clean, natural "
    "English (or Hinglish->English) while preserving every instruction, name, "
    "number, and intent exactly. Respond with ONLY the cleaned text."
)


@dataclass
class ClassifyResult:
    ok: bool
    niche_tags: list[str] | None = None
    tone_dial: dict[str, Any] | None = None
    brand_color: str | None = None
    product_catalog: list[dict[str, Any]] | None = None
    error: str | None = None
    usage: dict[str, Any] | None = None


@dataclass
class CleanupResult:
    ok: bool
    cleaned_text: str | None = None
    error: str | None = None
    usage: dict[str, Any] | None = None


def _usage_from_response(response: Any) -> dict[str, Any] | None:
    """Extracts token usage from a `generate_content` response in the same
    `{"input_tokens": int, "output_tokens": int}` shape `ClaudeProvider`
    already produces, so callers can hand it straight to
    `app.costs.pricing.estimate_cost_usd` without a Gemini-specific branch.

    The genai SDK exposes this as `response.usage_metadata` (prompt/candidates
    token counts) -- not a per-token usage dict like Anthropic's, so it's
    translated here rather than returned raw. Returns None (never raises) if
    the response has no usage_metadata, matching the "usage may legitimately
    be absent" contract the pricing/spend-tracker call sites already expect.

    P2 (Ash AI review, wiki/ai-review/partial-fixes-batch-ai-review.md):
    `output_tokens` folds in `thoughts_token_count` when present. For 2.5-class
    models (GEMINI_MODEL is `gemini-2.5-flash`, config.py) Google separately bills
    "thinking" tokens under `usage_metadata.thoughts_token_count`, distinct
    from `candidates_token_count` (the visible output) -- reading only
    `candidates_token_count` systematically UNDER-counts spend against the
    P2-17 daily ceiling. `thoughts_token_count` is commonly small/absent for
    this model, but it's still real billed spend when present, so it's added
    in rather than ignored.
    """
    usage_metadata = getattr(response, "usage_metadata", None)
    if usage_metadata is None:
        return None
    candidates_tokens = getattr(usage_metadata, "candidates_token_count", None) or 0
    thoughts_tokens = getattr(usage_metadata, "thoughts_token_count", None) or 0
    return {
        "input_tokens": getattr(usage_metadata, "prompt_token_count", None),
        "output_tokens": candidates_tokens + thoughts_tokens,
    }


_T = TypeVar("_T")


class GeminiTimeoutError(Exception):
    """The Gemini call was abandoned at the configured deadline."""


async def _with_deadline(awaitable: Awaitable[_T], seconds: float) -> _T:
    """Abandon `awaitable` after `seconds`.

    EV-045 — `app.config.Timeouts` has declared `gemini_connect = 3.0` and
    `gemini_read = 20.0` since the file was written, and NOTHING read either
    of them: `grep -rn 'gemini_connect|gemini_read' app/` matched only their
    own definitions. Every other provider in this package honours its
    timeouts (claude.py passes `anthropic.Timeout(...)`, sarvam.py and
    clients/spring.py pass `httpx.Timeout(...)`); the Gemini client was
    constructed as a bare `genai.Client(api_key=...)`, so a hung Gemini
    endpoint held `/analyze-site` — and the request thread serving it — open
    indefinitely.

    `asyncio.wait_for` rather than an SDK timeout option because it is the one
    mechanism that is guaranteed to hold across google-genai versions: the
    pinned `google-genai==0.8.0` has no `timeout` field on `HttpOptions` at
    all (it arrived in the 1.x line), so wiring one there would bind to
    nothing on the pinned SDK and silently do nothing in production — the
    exact failure mode this fix exists to remove. `wait_for` cancels the task
    and returns the coroutine's slot to the event loop at the deadline
    regardless of what the SDK underneath does.
    """
    try:
        return await asyncio.wait_for(awaitable, timeout=seconds)
    except asyncio.TimeoutError as exc:
        raise GeminiTimeoutError(f"gemini call exceeded {seconds}s") from exc


class GeminiProvider:
    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._client = genai.Client(api_key=settings.gemini_api_key)
        self._breaker = CircuitBreaker(
            failure_threshold=settings.breaker.failure_threshold,
            recovery_seconds=settings.breaker.recovery_seconds,
        )

    def _deadline_seconds(self) -> float:
        """EV-045: one wall-clock budget per call, connect + read.

        Read from settings at CALL time rather than cached on the instance in
        `__init__`. Several tests (and any future caller) build a provider with
        `GeminiProvider.__new__(GeminiProvider)` and set only the two
        collaborators they care about, so an attribute that exists only when
        `__init__` ran would make the timeout silently absent on exactly the
        instances nobody thought about -- an AttributeError swallowed by the
        broad `except Exception` around the call, reported as
        `error="provider_error"`. A method has no such hole.
        """
        timeouts = get_settings().timeouts
        return timeouts.gemini_connect + timeouts.gemini_read

    async def classify_site(
        self, sanitized_page_text: str, known_products: list[dict[str, Any]] | None = None
    ) -> ClassifyResult:
        """Classify already-sanitized (SSRF-guarded, script/iframe-stripped) page
        text into a structured brand profile. Never raises — returns ok=False on
        any provider failure so the caller can degrade gracefully.

        `known_products` (P1-B) are FACTS already scraped from the raw HTML's
        structured data (JSON-LD/OpenGraph -- see app/prompt/structured_extract.py),
        prepended to the prompt as a "KNOWN PRODUCT FACTS" block so the model
        fills only the gaps instead of re-guessing a price/name that's already
        known. Callers still get the model's product_catalog back verbatim here
        -- analyze_site.py's merge step is what forcibly re-asserts scraped facts
        over anything the model returns, this parameter only shapes the prompt.
        """
        try:
            self._breaker.before_call()
        except CircuitOpenError as exc:
            return ClassifyResult(ok=False, error=f"circuit_open: {exc}")

        contents = sanitized_page_text
        if known_products:
            # C2 (Kabir P1-B audit): this block is built from scraped page
            # data (product names come straight off the target site's own
            # JSON-LD/OpenGraph markup) -- a hostile product name could try
            # to forge a `</untrusted_*>` close tag or inject instructions
            # via a plain json.dumps escape alone. Route it through the same
            # wrap_untrusted() neutralization as the scraped page text below
            # (wrap_untrusted_scrape -> _wrap_untrusted -> wrap_untrusted) so
            # `<`/`>` can never form a tag boundary here either. The content
            # stays semantically "authoritative for FACTS" (the system
            # instruction still tells the model these are verified scraped
            # facts, and merge_known_products force-reasserts them
            # afterwards regardless of what the model does with the prompt)
            # -- only the raw bytes are neutralized, so this cannot weaken
            # the money-safety guard.
            known_block = json.dumps(known_products, ensure_ascii=False)
            known_wrapped = wrap_untrusted("known_product_facts", known_block)
            contents = (
                f"KNOWN PRODUCT FACTS (scraped, authoritative):\n{known_wrapped}\n\n{sanitized_page_text}"
            )

        try:
            response = await _with_deadline(
                self._client.aio.models.generate_content(
                    model=GEMINI_MODEL,
                    contents=contents,
                    config=genai_types.GenerateContentConfig(
                        system_instruction=_CLASSIFY_SYSTEM_INSTRUCTION,
                        temperature=0.2,
                    # [Ash AI-review 2026-07-23] gemini-2.5-flash is a THINKING model and its
                    # thinking tokens count against max_output_tokens (see the note at
                    # _usage_from_response). At 1024 the reasoning over a 20k-char page — now asking
                    # it to separate the site owner's own niche from example/customer content —
                    # exhausted the budget before the JSON was emitted, so response.text came back
                    # empty/truncated -> json.loads -> "unparseable_response" -> analysis FAILED
                    # (brand category stuck on the stale value). The JSON itself is only a few
                    # hundred tokens; 4096 leaves ample room for thinking + the full object.
                        max_output_tokens=4096,
                        response_mime_type="application/json",
                        response_schema=_CLASSIFY_RESPONSE_SCHEMA,
                    ),
                ),
                self._deadline_seconds(),
            )
            self._breaker.on_success()
        except Exception as exc:  # noqa: BLE001 - provider/network error/timeout -> degrade, don't crash
            # A timeout counts as a provider failure for the breaker on purpose:
            # a hung endpoint is exactly the condition the breaker exists to
            # stop hammering.
            self._breaker.on_failure()
            logger.warning("gemini classify_site failed: %s", type(exc).__name__)
            return ClassifyResult(ok=False, error="provider_error")

        usage = _usage_from_response(response)

        try:
            data = json.loads(response.text or "{}")
        except (ValueError, AttributeError):
            return ClassifyResult(ok=False, error="unparseable_response", usage=usage)

        return ClassifyResult(
            ok=True,
            niche_tags=data.get("niche_tags") or [],
            tone_dial=data.get("tone_dial") or {},
            brand_color=data.get("brand_color"),
            product_catalog=data.get("product_catalog") or [],
            usage=usage,
        )

    async def cleanup_transcript(self, raw_transcript: str) -> CleanupResult:
        """Light grammar-cleanup pass for voice transcripts. Meaning-preserving
        only. Falls back to the raw transcript (ok=False) on any failure so the
        caller can still show something in the edit-first composer.
        """
        try:
            self._breaker.before_call()
        except CircuitOpenError as exc:
            return CleanupResult(ok=False, error=f"circuit_open: {exc}")

        try:
            response = await _with_deadline(
                self._client.aio.models.generate_content(
                    model=GEMINI_MODEL,
                    contents=raw_transcript,
                    config=genai_types.GenerateContentConfig(
                        system_instruction=_CLEANUP_SYSTEM_INSTRUCTION,
                        temperature=0.1,
                        max_output_tokens=512,
                    ),
                ),
                self._deadline_seconds(),
            )
            self._breaker.on_success()
        except Exception as exc:  # noqa: BLE001 - provider/network error/timeout -> degrade, don't crash
            self._breaker.on_failure()
            logger.warning("gemini cleanup_transcript failed: %s", type(exc).__name__)
            return CleanupResult(ok=False, error="provider_error")

        usage = _usage_from_response(response)

        cleaned = (response.text or "").strip()
        if not cleaned:
            return CleanupResult(ok=False, error="empty_response", usage=usage)
        return CleanupResult(ok=True, cleaned_text=cleaned, usage=usage)
