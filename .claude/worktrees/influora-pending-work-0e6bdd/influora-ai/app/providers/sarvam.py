"""Sarvam provider client — STT (Hinglish) + TTS, India-region, latency-sensitive.

Per §5 of the AI service spec: Sarvam is only ears (STT) and mouth (TTS). Claude
is always the brain. ANY Sarvam failure must degrade silently to text — never a
dead end. This module never raises to its callers for provider failures; it
always returns a structured result with `ok: bool` so routes/voice.py can apply
the fallback table verbatim.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from app.config import get_settings
from app.providers.claude import CircuitBreaker, CircuitOpenError

logger = logging.getLogger(__name__)

SARVAM_BASE_URL = "https://api.sarvam.ai"


@dataclass
class TranscribeResult:
    ok: bool
    raw_transcript: str | None = None
    lang_detected: str | None = None
    error: str | None = None


@dataclass
class SpeakResult:
    ok: bool
    audio_bytes: bytes | None = None
    content_type: str | None = None
    error: str | None = None


class SarvamProvider:
    def __init__(self) -> None:
        settings = get_settings()
        self._settings = settings
        self._breaker_stt = CircuitBreaker(
            failure_threshold=settings.breaker.failure_threshold,
            recovery_seconds=settings.breaker.recovery_seconds,
        )
        self._breaker_tts = CircuitBreaker(
            failure_threshold=settings.breaker.failure_threshold,
            recovery_seconds=settings.breaker.recovery_seconds,
        )

    async def transcribe(self, audio_bytes: bytes, *, content_type: str = "audio/wav") -> TranscribeResult:
        """Hinglish-aware STT. Returns ok=False (never raises) on timeout/error so
        the route can respond with "Didn't catch that - type it instead?".
        """
        try:
            self._breaker_stt.before_call()
        except CircuitOpenError as exc:
            return TranscribeResult(ok=False, error=f"circuit_open: {exc}")

        settings = self._settings
        try:
            async with httpx.AsyncClient(
                base_url=SARVAM_BASE_URL,
                timeout=httpx.Timeout(
                    connect=settings.timeouts.sarvam_connect,
                    read=settings.timeouts.sarvam_stt_read,
                    write=settings.timeouts.sarvam_stt_read,
                    pool=settings.timeouts.sarvam_connect,
                ),
            ) as client:
                response = await client.post(
                    "/speech-to-text",
                    headers={"api-subscription-key": settings.sarvam_api_key},
                    files={"file": ("audio", audio_bytes, content_type)},
                    data={"language_code": "hi-IN", "model": "saarika:v2"},
                )
                response.raise_for_status()
                data = response.json()
            self._breaker_stt.on_success()
        except Exception as exc:  # timeout, HTTP error, network error
            self._breaker_stt.on_failure()
            logger.warning("sarvam transcribe failed: %s", type(exc).__name__)
            return TranscribeResult(ok=False, error="provider_error")

        transcript = data.get("transcript")
        if not transcript:
            return TranscribeResult(ok=False, error="empty_transcript")

        return TranscribeResult(
            ok=True,
            raw_transcript=transcript,
            lang_detected=data.get("language_code", "hi-IN"),
        )

    async def speak(self, text: str, *, lang: str = "en-IN") -> SpeakResult:
        """Text -> TTS audio. Returns ok=False on any failure so the caller can
        silently disable voice-output while the already-rendered text reply
        stands on its own.
        """
        try:
            self._breaker_tts.before_call()
        except CircuitOpenError as exc:
            return SpeakResult(ok=False, error=f"circuit_open: {exc}")

        settings = self._settings
        try:
            async with httpx.AsyncClient(
                base_url=SARVAM_BASE_URL,
                timeout=httpx.Timeout(
                    connect=settings.timeouts.sarvam_connect,
                    read=settings.timeouts.sarvam_tts_read,
                    write=settings.timeouts.sarvam_tts_read,
                    pool=settings.timeouts.sarvam_connect,
                ),
            ) as client:
                response = await client.post(
                    "/text-to-speech",
                    headers={"api-subscription-key": settings.sarvam_api_key},
                    json={"inputs": [text], "target_language_code": lang, "speaker": "meera"},
                )
                response.raise_for_status()
                audio_bytes = response.content
            self._breaker_tts.on_success()
        except Exception as exc:
            self._breaker_tts.on_failure()
            logger.warning("sarvam speak failed: %s", type(exc).__name__)
            return SpeakResult(ok=False, error="provider_error")

        if not audio_bytes:
            return SpeakResult(ok=False, error="empty_audio")

        return SpeakResult(ok=True, audio_bytes=audio_bytes, content_type="audio/wav")
