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
from typing import Any

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
    # F-06: True when the provider actually completed a billed HTTP 200, even
    # though this result is ok=False. Three paths returned ok=False AFTER a
    # successful, billed call — STT `empty_transcript`, TTS
    # `invalid_audio_response` / `empty_audio` — and every caller recorded spend
    # only on success. POSTing 10s of silence to /voice/transcribe in a loop
    # therefore bought free unlimited STT: Sarvam billed every call and the
    # daily counter never moved. Callers MUST bill on `billed`, not on `ok`.
    billed: bool = False


@dataclass
class SpeakResult:
    ok: bool
    audio_bytes: bytes | None = None
    content_type: str | None = None
    error: str | None = None
    # F-06: see TranscribeResult.billed.
    billed: bool = False
    # Characters actually POSTed to Sarvam (F-07): `speak()` sends
    # `speakable(chunk)`, which expands rupee amounts and initialisms, so the
    # pre-normalization length the caller has is NOT what Sarvam bills for.
    billed_chars: int = 0


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


# ---------------------------------------------------------------------------
# speakable() — TTS text normalizer (Platform-AI Phase 1, W2b / A5 constraint).
#
# Sarvam's TTS reads text literally: "₹15,000" comes out as symbol-by-symbol
# noise, a leading "#" on a hashtag reads as "hash" or is silently dropped
# depending on the voice, and "UGC" gets mangled as a single mispronounced
# word instead of three letters. This runs on the text actually POSTED to
# Sarvam, never on what's shown in the chat panel (that stays exactly as
# written). Per Priya's A5 constraint, this MUST run per-chunk right before
# each Sarvam call (not once on the whole reply) so it's already shaped
# correctly for V3's future per-sentence streamed-TTS pattern — see `speak()`
# below, which calls this inside the per-chunk loop.
# ---------------------------------------------------------------------------

_ONES = (
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
)
_TENS = (
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
)


def _int_to_words(n: int) -> str:
    """Small integer-to-English-words converter, standard (not Indian lakh/crore)
    thousand-grouping to match the persona's spoken style. Good for the price/
    budget ranges Meera actually says out loud (single-to-low-crore INR
    amounts); falls back to the digit string for anything larger or negative
    rather than guessing.
    """
    if n < 0 or n >= 1_000_000_000:
        return str(n)
    if n < 20:
        return _ONES[n]
    if n < 100:
        tens, rem = divmod(n, 10)
        return _TENS[tens] + (f"-{_ONES[rem]}" if rem else "")
    if n < 1000:
        hundreds, rem = divmod(n, 100)
        return _ONES[hundreds] + " hundred" + (f" {_int_to_words(rem)}" if rem else "")
    if n < 1_000_000:
        thousands, rem = divmod(n, 1000)
        return _int_to_words(thousands) + " thousand" + (f" {_int_to_words(rem)}" if rem else "")
    millions, rem = divmod(n, 1_000_000)
    return _int_to_words(millions) + " million" + (f" {_int_to_words(rem)}" if rem else "")


def _parse_amount(raw: str) -> float | None:
    cleaned = raw.replace(",", "").strip()
    try:
        return float(cleaned)
    except ValueError:
        return None


def _amount_to_words(raw: str) -> str:
    value = _parse_amount(raw)
    if value is None:
        return raw
    if value == int(value):
        return _int_to_words(int(value))
    whole = int(value)
    frac_digits = raw.replace(",", "").split(".", 1)[1] if "." in raw else ""
    words = _int_to_words(whole)
    if frac_digits:
        words += " point " + " ".join(_ONES[int(d)] for d in frac_digits if d.isdigit())
    return words


# ₹15,000  |  ₹15,000–₹75,000  |  ₹15,000-75,000  (en-dash or hyphen, second ₹ optional)
_RUPEE_RE = re.compile(r"₹\s*([\d,]+(?:\.\d+)?)\s*(?:[–-]\s*₹?\s*([\d,]+(?:\.\d+)?))?")
_HASHTAG_RE = re.compile(r"#(\w+)")
_UGC_RE = re.compile(r"\bUGC\b")


def _replace_rupee_amount(match: re.Match[str]) -> str:
    raw_low, raw_high = match.group(1), match.group(2)
    if raw_high is None:
        return f"{_amount_to_words(raw_low)} rupees"

    low_val, high_val = _parse_amount(raw_low), _parse_amount(raw_high)
    # Same-magnitude thousands range ("₹15,000–₹75,000") reads more naturally
    # as "fifteen to seventy-five thousand rupees" than repeating "thousand"
    # twice — matches how a person actually says a budget band out loud.
    if (
        low_val is not None
        and high_val is not None
        and low_val > 0
        and high_val > 0
        and low_val % 1000 == 0
        and high_val % 1000 == 0
    ):
        return (
            f"{_int_to_words(int(low_val // 1000))} to "
            f"{_int_to_words(int(high_val // 1000))} thousand rupees"
        )
    return f"{_amount_to_words(raw_low)} to {_amount_to_words(raw_high)} rupees"


def speakable(text: str) -> str:
    """Normalizes text for TTS: ₹ amounts/ranges -> spoken words, hashtags lose
    their leading '#' (still spoken as the word, not the symbol), "UGC" is
    expanded to letter-by-letter "U G C" so it's pronounced as an initialism
    instead of a mangled single word. Applied to the text actually posted to
    Sarvam; the chat panel always shows the original, unmodified reply.
    """
    if not text:
        return text
    result = _RUPEE_RE.sub(_replace_rupee_amount, text)
    result = _HASHTAG_RE.sub(r"\1", result)
    result = _UGC_RE.sub("U G C", result)
    return result


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
        # Read every segment first, then write once inside a context manager —
        # the writer used to be opened bare and closed only on the success path,
        # so an exception mid-loop leaked the wave writer (SIM115).
        decoded: list[tuple[Any, bytes]] = []
        for seg in segments:
            with wave.open(io.BytesIO(seg), "rb") as reader:
                decoded.append((reader.getparams(), reader.readframes(reader.getnframes())))
        if not decoded:
            return None
        params = decoded[0][0]
        out_buf = io.BytesIO()
        with wave.open(out_buf, "wb") as writer:
            writer.setnchannels(params.nchannels)
            writer.setsampwidth(params.sampwidth)
            writer.setframerate(params.framerate)
            for _params, frames in decoded:
                writer.writeframes(frames)
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
        except Exception as exc:  # noqa: BLE001 - timeout, HTTP error, network error -> degrade
            self._breaker_stt.on_failure()
            logger.warning("sarvam transcribe failed: %s", type(exc).__name__)
            return TranscribeResult(ok=False, error="provider_error")

        transcript = data.get("transcript")
        if not transcript:
            # F-06: billed. The HTTP 200 above already cost money.
            return TranscribeResult(ok=False, error="empty_transcript", billed=True)

        return TranscribeResult(
            ok=True,
            raw_transcript=transcript,
            lang_detected=data.get("language_code", "hi-IN"),
            billed=True,
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
        billed_chars = 0
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
                    # speakable() runs HERE, per-chunk, right before the call --
                    # not once on the whole reply -- per Priya's A5 constraint
                    # (future-proofs for V3's per-sentence streamed TTS, where
                    # each "chunk" really will be one sentence).
                    spoken_chunk = speakable(chunk)
                    # F-07: Sarvam bills the text it RECEIVES. voice.py billed
                    # `len(tts_text)` — the pre-normalization string — but
                    # speakable() expands rupee amounts and initialisms before
                    # the post, so "Budget Rs.15,000-Rs.75,000 per creator for
                    # UGC" is 42 chars billed against 68 charged: a systematic
                    # ~40% under-bill on exactly the budget-quoting replies
                    # Meera produces most. Count what actually goes out.
                    billed_chars += len(spoken_chunk)
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
                            "inputs": [spoken_chunk],
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
        except Exception as exc:  # noqa: BLE001 - timeout, HTTP error, network error -> degrade
            self._breaker_tts.on_failure()
            logger.warning("sarvam speak failed: %s", type(exc).__name__)
            # F-07/F-06: a multi-chunk reply can fail on a later chunk after
            # earlier ones were already posted and billed. Report what went out.
            return SpeakResult(
                ok=False,
                error="provider_error",
                billed=billed_chars > 0,
                billed_chars=billed_chars,
            )

        # Decode AFTER the network turn succeeds so a malformed audio body
        # degrades to a text fallback WITHOUT tripping the breaker — the same
        # contract the single-request path always had. One segment per chunk.
        segments: list[bytes] = []
        for data in raw_datas:
            audio_bytes = _decode_tts_audio(data)
            if audio_bytes is None:
                return SpeakResult(
                    ok=False, error="invalid_audio_response", billed=True, billed_chars=billed_chars
                )
            if not audio_bytes:
                return SpeakResult(
                    ok=False, error="empty_audio", billed=True, billed_chars=billed_chars
                )
            segments.append(audio_bytes)

        combined = _concat_wavs(segments)
        if combined is None:
            return SpeakResult(
                    ok=False, error="invalid_audio_response", billed=True, billed_chars=billed_chars
                )

        return SpeakResult(
            ok=True,
            audio_bytes=combined,
            content_type="audio/wav",
            billed=True,
            billed_chars=billed_chars,
        )
