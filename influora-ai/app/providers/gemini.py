"""Gemini provider client — website scrape classification + grammar-cleanup pass.

Pinned to `gemini-2.5-flash-lite` (app/config.py GEMINI_MODEL) — this IS the P0
fix; the deprecated `gemini-2.0-flash` id must never appear here again.

Used for:
- Website analyzer classify step (niche, tone dial, catalog, palette) from
  already-scraped, already-sanitized page text (routes/analyze_site.py handles
  the scrape + SSRF guard + HTML sanitization BEFORE calling this).
- Optional grammar-cleanup pass for voice transcripts (cheap, bulk, quality
  tolerant — per §6 routing table).

Resilience: breaker opens on sustained failure -> scrape path degrades to
"paste a link" signal; grammar-cleanup falls back to raw_transcript. Neither
failure mode should ever raise an unhandled exception to the caller — callers
get a structured result with an `ok: bool`.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

from google import genai
from google.genai import types as genai_types

from app.config import GEMINI_MODEL, get_settings
from app.providers.claude import CircuitBreaker, CircuitOpenError

logger = logging.getLogger(__name__)

_CLASSIFY_SYSTEM_INSTRUCTION = (
    "You are a classifier for a D2C brand's website. Given sanitized page text "
    "(scripts/iframes already stripped), extract a compact JSON object describing "
    "the brand: niche_tags (list of short tags), tone_dial "
    "(formality 0-1, energy 0-1, emoji_ok bool, cultural_context string), "
    "brand_color (hex, best guess or null), and product_catalog (list of "
    "{name, price, currency} best-effort, empty list if unclear). Respond with "
    "ONLY the JSON object, no prose, no markdown fences."
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
                },
                required=["name", "price", "currency"],
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
    models (GEMINI_MODEL is `gemini-2.5-flash-lite`) Google separately bills
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


class GeminiProvider:
    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._client = genai.Client(api_key=settings.gemini_api_key)
        self._breaker = CircuitBreaker(
            failure_threshold=settings.breaker.failure_threshold,
            recovery_seconds=settings.breaker.recovery_seconds,
        )

    async def classify_site(self, sanitized_page_text: str) -> ClassifyResult:
        """Classify already-sanitized (SSRF-guarded, script/iframe-stripped) page
        text into a structured brand profile. Never raises — returns ok=False on
        any provider failure so the caller can degrade gracefully.
        """
        try:
            self._breaker.before_call()
        except CircuitOpenError as exc:
            return ClassifyResult(ok=False, error=f"circuit_open: {exc}")

        try:
            response = await self._client.aio.models.generate_content(
                model=GEMINI_MODEL,
                contents=sanitized_page_text,
                config=genai_types.GenerateContentConfig(
                    system_instruction=_CLASSIFY_SYSTEM_INSTRUCTION,
                    temperature=0.2,
                    max_output_tokens=1024,
                    response_mime_type="application/json",
                    response_schema=_CLASSIFY_RESPONSE_SCHEMA,
                ),
            )
            self._breaker.on_success()
        except Exception as exc:  # provider/network error -> degrade, don't crash
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
            response = await self._client.aio.models.generate_content(
                model=GEMINI_MODEL,
                contents=raw_transcript,
                config=genai_types.GenerateContentConfig(
                    system_instruction=_CLEANUP_SYSTEM_INSTRUCTION,
                    temperature=0.1,
                    max_output_tokens=512,
                ),
            )
            self._breaker.on_success()
        except Exception as exc:
            self._breaker.on_failure()
            logger.warning("gemini cleanup_transcript failed: %s", type(exc).__name__)
            return CleanupResult(ok=False, error="provider_error")

        usage = _usage_from_response(response)

        cleaned = (response.text or "").strip()
        if not cleaned:
            return CleanupResult(ok=False, error="empty_response", usage=usage)
        return CleanupResult(ok=True, cleaned_text=cleaned, usage=usage)
