"""POST /voice/transcribe and POST /voice/speak.

Cascaded Claude (brain) + Sarvam (ears/mouth). Voice failure ALWAYS falls back
to text, silently, at every stage (§5 of the AI service spec):

| Failure                              | Behavior                                    |
|---------------------------------------|----------------------------------------------|
| Sarvam STT fails / low confidence      | "Didn't catch that - type it instead?"       |
| Grammar-cleanup pass errors            | fall back to raw_transcript (still edit-first)|
| Sarvam TTS fails                       | disable voice-output silently                 |
| Any provider timeout                   | structured error, fallback: "text"            |
| AI spend gate blocked (P2-17)          | same silent fallback as a provider failure    |

`/voice/transcribe` is edit-first: cleaned_text lands in the composer, never
auto-sent. Credit weighting (input=3, reply=4) is surfaced to Spring's meter —
Python computes nothing about wallets/credits itself.

P18 (20-ROHAN-COST-REVIEW.md §3): TTS spoken-reply length is capped at ~200
characters to control voice-output costs. Truncation is graceful (ends at
sentence/word boundary if possible, adds ellipsis). The full reply is always
shown in the chat panel; this only limits what gets vocalized.
"""

from __future__ import annotations

import logging
import uuid
from decimal import Decimal
from typing import Any

import anyio
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.config import GEMINI_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import (
    estimate_cost_usd,
    estimate_sarvam_flat_cost_usd,
    estimate_sarvam_tts_cost_usd,
)
from app.costs.spend_tracker import record_spend
from app.providers.gemini import GeminiProvider
from app.providers.sarvam import SarvamProvider
from app.security.redaction import log_event, shape_of

# P18: Maximum characters to send to TTS. Raised 200 -> 500 so a normal Meera
# reply (the intro is ~270 chars) is spoken in FULL instead of being cut off
# mid-thought after the first sentence or two — the 200-char cap was a
# cost-margin guard (20-ROHAN-COST-REVIEW.md §3) but made the voice feel broken.
# 500 chars is ~Rs.1.50/call at the high Sarvam rate and is Sarvam bulbul:v3's
# comfortable single-input length (verified accepted); genuinely long replies
# still truncate gracefully at a sentence boundary below. Revisit if voice-TTS
# spend becomes a real cost line.
TTS_MAX_CHARS = 500

logger = logging.getLogger(__name__)
router = APIRouter()

_sarvam_provider: SarvamProvider | None = None
_gemini_provider: GeminiProvider | None = None


def _get_sarvam() -> SarvamProvider:
    global _sarvam_provider
    if _sarvam_provider is None:
        _sarvam_provider = SarvamProvider()
    return _sarvam_provider


def _get_gemini() -> GeminiProvider:
    global _gemini_provider
    if _gemini_provider is None:
        _gemini_provider = GeminiProvider()
    return _gemini_provider


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


async def _record_ai_spend(
    cost_usd: Decimal,
    *,
    workspace_id: str,
    request_id: str,
    route: str,
    model: str,
    reservation: Any = None,
) -> None:
    """Shared `record_spend` + `ai_spend` structured-log call for this route's
    three provider call sites (STT, Gemini cleanup, TTS) -- same shape as the
    inline "record_spend then log_event" block every other route repeats
    once, factored out here since voice.py has three call sites across two
    endpoints rather than one.
    """
    spend_today = await record_spend(cost_usd, workspace_id, reservation=reservation)
    log_event(
        logger, logging.INFO, "ai_spend",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "route": route,
            "model": model,
            "cost_usd": str(cost_usd),
            "spend_today_usd": str(spend_today),
        },
    )


def _truncate_for_tts(text: str, max_chars: int = TTS_MAX_CHARS) -> str:
    """P18: Truncate text for TTS to control cost margin.

    Truncates gracefully:
    1. If text fits, return as-is.
    2. Otherwise, try to end at a sentence boundary (. ! ?) within the limit.
    3. Failing that, end at a word boundary within the limit.
    4. Add ellipsis to indicate truncation.

    The full reply is always visible in the chat panel; this only limits what
    gets vocalized.
    """
    if len(text) <= max_chars:
        return text

    # Leave room for ellipsis
    limit = max_chars - 3
    truncated = text[:limit]

    # Try to find a sentence boundary (. ! ?) for a natural stopping point.
    # Search backwards from the end for the last sentence-ending punctuation.
    for i in range(len(truncated) - 1, -1, -1):
        if truncated[i] in ".!?":
            # Include the punctuation, no ellipsis needed for sentence end
            return truncated[: i + 1]

    # No sentence boundary found; fall back to word boundary.
    # Find the last space to avoid cutting mid-word.
    last_space = truncated.rfind(" ")
    if last_space > 0:
        return truncated[:last_space] + "..."

    # No space found (single long word); just truncate.
    return truncated + "..."


@router.post("/voice/transcribe")
async def voice_transcribe(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    form = await request.form()
    workspace_id = form.get("workspace_id")
    audio_file = form.get("audio")

    if not workspace_id or audio_file is None:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id and audio are required"},
        )

    try:
        # F-09: off the event loop (blocking JWKS fetch).
        await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization),
                endpoint="voice_transcribe",
                body_workspace_id=str(workspace_id),
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # P2-17 spend gate: checked before either provider call this route makes
    # (Sarvam STT, then Gemini cleanup) -- a block here skips both, same
    # "auth-first, then gate, then provider calls" ordering as chat.py /
    # brand_safety.py / analyze_site.py. Degrades to the same silent
    # STT-failure fallback rather than a raw 5xx, per this route's own
    # "never a dead end" contract (see module docstring's failure table).
    gate = await check_spend_gate(
        workspace_id=str(workspace_id),
        # F-05: see app/costs/gate.py — hold budget across the provider call.
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "voice_transcribe_blocked_spend_gate",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return {
            "raw_transcript": None,
            "cleaned_text": None,
            "lang_detected": None,
            "fallback": True,
            "message": "Didn't catch that - type it instead?",
        }

    # A multipart part that arrives as a plain TEXT form field (not a file) is a
    # `str`, which has no `.read()`. Calling it crashed the route with an
    # unhandled 500 — mypy flagged this exact line, and a fresh-context auditor
    # independently reported "plain text form field crashes the route". This is
    # a client error on a route whose own contract is "never a dead end", so it
    # degrades to the same honest fallback every other input failure uses.
    if isinstance(audio_file, str) or not hasattr(audio_file, "read"):
        log_event(
            logger, logging.WARNING, "voice_transcribe_bad_upload",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"received_type": type(audio_file).__name__},
        )
        return {
            "raw_transcript": None,
            "cleaned_text": None,
            "lang_detected": None,
            "fallback": True,
            "message": "Didn't catch that - type it instead?",
        }

    audio_bytes = await audio_file.read()
    log_event(
        logger, logging.INFO, "voice_transcribe_started",
        workspace_id=str(workspace_id), request_id=request_id, fields={"audio": shape_of(audio_bytes)},
    )

    sarvam = _get_sarvam()
    stt_result = await sarvam.transcribe(audio_bytes)

    # F-06: bill on `billed`, not on `ok`. `empty_transcript` is returned AFTER
    # a successful, billed HTTP 200 — POSTing silence in a loop used to buy free
    # unlimited STT while the daily counter never moved.
    if stt_result.billed:
        await _record_ai_spend(
            estimate_sarvam_flat_cost_usd(),
            workspace_id=str(workspace_id),
            request_id=request_id,
            route="voice_transcribe_stt",
            model="sarvam-stt",
            reservation=gate.reservation,
        )

    if not stt_result.ok:
        return {
            "raw_transcript": None,
            "cleaned_text": None,
            "lang_detected": None,
            "fallback": True,
            "message": "Didn't catch that - type it instead?",
        }

    gemini = _get_gemini()
    cleanup_result = await gemini.cleanup_transcript(stt_result.raw_transcript or "")

    if cleanup_result.usage:
        try:
            cost_usd = estimate_cost_usd(GEMINI_MODEL, cleanup_result.usage)
            await _record_ai_spend(
                cost_usd,
                workspace_id=str(workspace_id),
                request_id=request_id,
                route="voice_transcribe_cleanup",
                model=GEMINI_MODEL,
                reservation=gate.reservation,
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                workspace_id=str(workspace_id), request_id=request_id,
                fields={"error": str(exc)},
            )

    if cleanup_result.ok:
        cleaned_text = cleanup_result.cleaned_text
    else:
        # Grammar-cleanup pass errored -> fall back to raw_transcript, still edit-first.
        log_event(
            logger, logging.WARNING, "voice_cleanup_failed",
            workspace_id=str(workspace_id), request_id=request_id, fields={"error": cleanup_result.error},
        )
        cleaned_text = stt_result.raw_transcript

    return {
        "raw_transcript": stt_result.raw_transcript,
        "cleaned_text": cleaned_text,
        "lang_detected": stt_result.lang_detected,
        "fallback": False,
    }


@router.post("/voice/speak")
async def voice_speak(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")
    text = body.get("text")

    if not workspace_id or not text:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id and text are required"},
        )

    try:
        # F-09: off the event loop (blocking JWKS fetch).
        await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization), endpoint="voice_speak", body_workspace_id=workspace_id
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # P2-17 spend gate: checked before the Sarvam TTS call below, same
    # ordering/degrade-not-500 rationale as voice_transcribe's gate above.
    gate = await check_spend_gate(
        workspace_id=workspace_id,
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        log_event(
            logger, logging.WARNING, "voice_speak_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return {"fallback": True, "message": "voice reply unavailable"}

    # P18: Cap TTS text at ~200 chars for cost control (20-ROHAN-COST-REVIEW.md §3).
    # The full reply is visible in chat; this only limits vocalization.
    original_len = len(text)
    tts_text = _truncate_for_tts(text)
    was_truncated = len(tts_text) < original_len

    log_event(
        logger, logging.INFO, "voice_speak_started",
        workspace_id=workspace_id, request_id=request_id,
        fields={"text": shape_of(tts_text), "truncated": was_truncated, "original_len": original_len},
    )

    sarvam = _get_sarvam()
    result = await sarvam.speak(tts_text, lang=body.get("lang", "en-IN"))

    # P2 (Ash AI review): TTS is char-scaled, not the flat STT estimate.
    #
    # F-07: bill `result.billed_chars` — the length of `speakable(chunk)`, the
    # text Sarvam actually received — not `len(tts_text)`. speakable() expands
    # rupee amounts and initialisms before the post, so "Budget ₹15,000–₹75,000
    # per creator for UGC" is 42 chars pre-normalization and 68 as charged: a
    # systematic ~40% under-bill on exactly the budget-quoting replies Meera
    # produces most.
    #
    # F-06: bill on `billed`, not on `ok`. `invalid_audio_response` and
    # `empty_audio` are both returned AFTER a successful, billed HTTP 200, and a
    # multi-chunk reply can fail on a later chunk with earlier chunks already
    # billed.
    if result.billed:
        await _record_ai_spend(
            estimate_sarvam_tts_cost_usd(result.billed_chars or len(tts_text)),
            workspace_id=workspace_id,
            request_id=request_id,
            route="voice_speak_tts",
            model="sarvam-tts",
            reservation=gate.reservation,
        )

    if not result.ok:
        # Silent fallback -- text reply already rendered client-side; voice-output
        # simply doesn't play. Return a small JSON signal instead of audio bytes
        # so the frontend can disable the voice-output UI without an error wall.
        return {"fallback": True, "message": "voice reply unavailable"}

    return Response(content=result.audio_bytes, media_type=result.content_type or "audio/wav")
