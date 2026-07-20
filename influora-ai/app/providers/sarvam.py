"""Sarvam provider client — STT (Hinglish) + TTS, India-region, latency-sensitive.

Per §5 of the AI service spec: Sarvam is only ears (STT) and mouth (TTS). Claude
is always the brain. ANY Sarvam failure must degrade silently to text — never a
dead end. This module never raises to its callers for provider failures; it
always returns a structured result with `ok: bool` so routes/voice.py can apply
the fallback table verbatim.
"""

from __future__ import annotations

import base64
import binascii
import io
import logging
import re
import wave
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


def _decode_tts_audio(data: object) -> bytes | None:
    """Pulls the audio out of a Sarvam TTS response body and base64-decodes it.

    Documented response shape (api.sarvam.ai `/text-to-speech`):
        {"request_id": "...", "audios": ["<base64-encoded wav>", ...]}
    — one base64 string per element of the `inputs` array in the request. We
    post a single input, so we take the first entry.

    Returns the decoded bytes, or None if the body is not the documented shape
    (missing/empty `audios`, wrong types, or a value that is not valid base64).
    Never raises — voice must degrade to text, never dead-end the caller.
    """
    if not isinstance(data, dict):
        return None

    audios = data.get("audios")
    if not isinstance(audios, list) or not audios:
        return None

    first = audios[0]
    if not isinstance(first, str):
        return None

    try:
        # validate=True so a body of prose/HTML that happens to sit in `audios`
        # is rejected outright rather than silently decoding to garbage bytes
        # that we would then hand back as "audio/wav".
        return base64.b64decode(first, validate=True)
    except (binascii.Error, ValueError):
        return None


# Sarvam bulbul:v3 hard-caps a single `inputs` element at 2500 characters; a
# longer post 400s, which today silently drops the WHOLE reply to the browser
# fallback voice (the "sometimes sounds different" bug). We keep a safety margin
# under the cap and split long replies on sentence boundaries, then stitch the
# per-chunk audio back into one WAV so a long reply still speaks in Priya's voice.
MAX_TTS_CHARS = 2000

# Split after sentence-ending punctuation (incl. the Hindi danda ।) + whitespace.
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?।])\s+")


def _chunk_text(text: str, limit: int = MAX_TTS_CHARS) -> list[str]:
    """Split text into <=limit-char chunks, preferring sentence boundaries and
    never cutting a word. Returns [] for empty input and [text] for text that
    already fits — the common case, since Meera replies are one or two sentences,
    so this is a no-op that leaves the single-request path unchanged.
    """
    text = text.strip()
    if not text:
        return []
    if len(text) <= limit:
        return [text]

    chunks: list[str] = []
    current = ""
    for sentence in _SENTENCE_SPLIT_RE.split(text):
        if not sentence:
            continue
        if len(sentence) > limit:
            # A single over-long sentence — flush what we have, then word-split it.
            if current:
                chunks.append(current)
                current = ""
            chunks.extend(_split_on_words(sentence, limit))
            continue
        if current and len(current) + 1 + len(sentence) > limit:
            chunks.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}" if current else sentence
    if current:
        chunks.append(current)
    return chunks


def _split_on_words(chunk: str, limit: int) -> list[str]:
    """Greedy word-boundary split for a sentence longer than `limit`. A single
    token longer than `limit` (pathological — e.g. a giant URL) is hard-sliced so
    we never emit an over-limit chunk that would 400.
    """
    out: list[str] = []
    current = ""
    for word in chunk.split(" "):
        if len(word) > limit:
            if current:
                out.append(current)
                current = ""
            for i in range(0, len(word), limit):
                out.append(word[i : i + limit])
            continue
        if current and len(current) + 1 + len(word) > limit:
            out.append(current)
            current = word
        else:
            current = f"{current} {word}" if current else word
    if current:
        out.append(current)
    return out


def _concat_wavs(segments: list[bytes]) -> bytes | None:
    """Stitch multiple same-format WAV blobs into one playable WAV. A single
    segment is returned unchanged (byte-for-byte), so the one-chunk path — which
    every existing test exercises — is completely untouched. Returns None if any
    segment isn't a parseable WAV, so the caller degrades to a text fallback like
    any other bad-audio case. Never raises.
    """
    if not segments:
        return None
    if len(segments) == 1:
        return segments[0]
    try:
        out_buf = io.BytesIO()
        writer = None
        for seg in segments:
            with wave.open(io.BytesIO(seg), "rb") as reader:
                params = reader.getparams()
                frames = reader.readframes(reader.getnframes())
            if writer is None:
                writer = wave.open(out_buf, "wb")
                writer.setnchannels(params.nchannels)
                writer.setsampwidth(params.sampwidth)
                writer.setframerate(params.framerate)
            writer.writeframes(frames)
        if writer is not None:
            writer.close()
        return out_buf.getvalue()
    except (wave.Error, EOFError, ValueError):
        return None


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

        Long replies are split on sentence boundaries (bulbul:v3 caps a single
        input at 2500 chars) and the per-chunk audio is stitched back into one
        WAV, so a long reply speaks in Priya's voice instead of silently dropping
        to the browser fallback. Short replies (the norm) post exactly one chunk,
        so this path is unchanged for them.
        """
        try:
            self._breaker_tts.before_call()
        except CircuitOpenError as exc:
            return SpeakResult(ok=False, error=f"circuit_open: {exc}")

        chunks = _chunk_text(text)
        if not chunks:
            return SpeakResult(ok=False, error="empty_text")

        settings = self._settings
        raw_datas: list[object] = []
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
                for chunk in chunks:
                    response = await client.post(
                        "/text-to-speech",
                        headers={"api-subscription-key": settings.sarvam_api_key},
                        # Voice tuning (verified against Sarvam's TTS convert API docs, 2026-07:
                        # docs.sarvam.ai/api-reference/text-to-speech/convert):
                        #  - model bulbul:v3 — required for the "priya" speaker; v2 exposes a
                        #    different speaker set (400s on "priya") and v1 is retired.
                        #  - speaker "priya" — the chosen female voice for Meera.
                        #  Tuned for a "sharp + trustworthy expert" tone (not chirpy, not rushed):
                        #  - pace 1.1 — confident and clear, just above natural (v3 range 0.5–2.0).
                        #    Higher (1.15+) starts to read as hurried/salesy and costs credibility.
                        #  - temperature 0.35 — v3-only knob (default 0.6, range 0.01–2.0). Lower =
                        #    steadier, more repeatable delivery call-to-call; this is what stops Priya
                        #    from "sounding different each time". Higher is more expressive but less
                        #    consistent.
                        #  - speech_sample_rate 24000 — Sarvam's default. Deliberately NOT 44100:
                        #    the higher rate ~doubles the audio payload on this latency-critical
                        #    batch-REST path and pushed the call past the read timeout
                        #    (ReadTimeout -> browser-fallback voice). 24kHz is plenty for speech and
                        #    keeps the response fast. (Set explicitly so the choice is documented.)
                        #  NOTE: there is deliberately NO "pause" field — it is NOT a real Sarvam
                        #  parameter (absent from the convert API), so sending it was a silent no-op.
                        #  pitch / loudness / enable_preprocessing are v2-only and correctly omitted
                        #  (v3 auto-preprocesses).
                        json={
                            "inputs": [chunk],
                            "target_language_code": lang,
                            "speaker": "priya",
                            "model": "bulbul:v3",
                            "pace": 1.1,
                            "temperature": 0.35,
                            "speech_sample_rate": 24000,
                        },
                    )
                    response.raise_for_status()
                    # Sarvam's TTS endpoint returns JSON, NOT a raw audio body:
                    # {"request_id": "...", "audios": ["<base64 wav>", ...]} — one
                    # entry per element of the `inputs` array we posted. A non-JSON
                    # body means the provider misbehaved, so it trips the breaker
                    # here alongside timeouts/HTTP errors (same shape as
                    # `transcribe` above).
                    raw_datas.append(response.json())
            self._breaker_tts.on_success()
        except Exception as exc:
            self._breaker_tts.on_failure()
            logger.warning("sarvam speak failed: %s", type(exc).__name__)
            return SpeakResult(ok=False, error="provider_error")

        # Decode AFTER the network turn succeeds so a malformed audio body
        # degrades to a text fallback WITHOUT tripping the breaker — the same
        # contract the single-request path always had. One segment per chunk.
        segments: list[bytes] = []
        for data in raw_datas:
            audio_bytes = _decode_tts_audio(data)
            if audio_bytes is None:
                return SpeakResult(ok=False, error="invalid_audio_response")
            if not audio_bytes:
                return SpeakResult(ok=False, error="empty_audio")
            segments.append(audio_bytes)

        combined = _concat_wavs(segments)
        if combined is None:
            return SpeakResult(ok=False, error="invalid_audio_response")

        return SpeakResult(ok=True, audio_bytes=combined, content_type="audio/wav")
