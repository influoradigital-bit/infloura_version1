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


@dataclass
class CleanupResult:
    ok: bool
    cleaned_text: str | None = None
    error: str | None = None


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
                ),
            )
            self._breaker.on_success()
        except Exception as exc:  # provider/network error -> degrade, don't crash
            self._breaker.on_failure()
            logger.warning("gemini classify_site failed: %s", type(exc).__name__)
            return ClassifyResult(ok=False, error="provider_error")

        try:
            data = json.loads(response.text or "{}")
        except (ValueError, AttributeError):
            return ClassifyResult(ok=False, error="unparseable_response")

        return ClassifyResult(
            ok=True,
            niche_tags=data.get("niche_tags") or [],
            tone_dial=data.get("tone_dial") or {},
            brand_color=data.get("brand_color"),
            product_catalog=data.get("product_catalog") or [],
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

        cleaned = (response.text or "").strip()
        if not cleaned:
            return CleanupResult(ok=False, error="empty_response")
        return CleanupResult(ok=True, cleaned_text=cleaned)
