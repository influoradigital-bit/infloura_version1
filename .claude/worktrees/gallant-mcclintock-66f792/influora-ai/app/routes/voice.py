"""POST /voice/transcribe and POST /voice/speak.

Cascaded Claude (brain) + Sarvam (ears/mouth). Voice failure ALWAYS falls back
to text, silently, at every stage (§5 of the AI service spec):

| Failure                              | Behavior                                    |
|---------------------------------------|----------------------------------------------|
| Sarvam STT fails / low confidence      | "Didn't catch that - type it instead?"       |
| Grammar-cleanup pass errors            | fall back to raw_transcript (still edit-first)|
| Sarvam TTS fails                       | disable voice-output silently                 |
| Any provider timeout                   | structured error, fallback: "text"            |

`/voice/transcribe` is edit-first: cleaned_text lands in the composer, never
auto-sent. Credit weighting (input=3, reply=4) is surfaced to Spring's meter —
Python computes nothing about wallets/credits itself.

P18 (20-ROHAN-COST-REVIEW.md §3): TTS spoken-reply length is capped at ~200
characters to control voice-output costs. Truncation is graceful (ends at
sentence/word boundary if possible, adds ellipsis). The full reply is always
shown in the chat panel; this only limits what gets vocalized.
"""

from __future__ import annotations

# P18: Maximum characters to send to TTS. At the high Sarvam rate (Rs.30/10k chars),
# 200 chars costs Rs.0.60 — within the 4-credit allowance (~Rs.0.88). Longer replies
# would push margins negative (see 20-ROHAN-COST-REVIEW.md §3).
TTS_MAX_CHARS = 200

import logging
import uuid

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response

from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.providers.gemini import GeminiProvider
from app.providers.sarvam import SarvamProvider
from app.security.redaction import log_event, shape_of

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
        verify_token(_bearer(authorization), endpoint="voice_transcribe", body_workspace_id=str(workspace_id))
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    audio_bytes = await audio_file.read()
    log_event(
        logger, logging.INFO, "voice_transcribe_started",
        workspace_id=str(workspace_id), request_id=request_id, fields={"audio": shape_of(audio_bytes)},
    )

    sarvam = _get_sarvam()
    stt_result = await sarvam.transcribe(audio_bytes)

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
        verify_token(_bearer(authorization), endpoint="voice_speak", body_workspace_id=workspace_id)
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

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

    if not result.ok:
        # Silent fallback -- text reply already rendered client-side; voice-output
        # simply doesn't play. Return a small JSON signal instead of audio bytes
        # so the frontend can disable the voice-output UI without an error wall.
        return {"fallback": True, "message": "voice reply unavailable"}

    return Response(content=result.audio_bytes, media_type=result.content_type or "audio/wav")
