"""A9 — Sarvam TTS base64 decode (`app/providers/sarvam.py`).

Sarvam's `/text-to-speech` endpoint returns JSON, not a raw audio body:

    {"request_id": "...", "audios": ["<base64-encoded wav>", ...]}

one base64 string per element of the `inputs` array in the request. The
provider previously did `audio_bytes = response.content` and handed the raw
JSON *text* back to `/voice/speak` labelled `audio/wav` — so every voice reply
was a JSON document with a WAV content-type, i.e. unplayable.

These tests pin the decode and the malformed-response handling. They talk to an
httpx.MockTransport, never the network.
"""

from __future__ import annotations

import base64
import functools
import io
import json
import wave

import httpx
import pytest

from app.providers.sarvam import (
    MAX_TTS_CHARS,
    SarvamProvider,
    _chunk_text,
    _concat_wavs,
    _decode_tts_audio,
)

# A minimal but real WAV header + a byte of payload. The point is that these
# bytes are NOT valid UTF-8 and NOT JSON — if the provider ever regresses to
# returning `response.content`, no assertion here can accidentally pass.
FAKE_WAV = b"RIFF$\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x80>\x00\x00\x00}\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00\xff"


def _make_wav(nframes: int, *, sample: int = 1) -> bytes:
    """A real, parseable mono 16-bit/44.1kHz WAV with `nframes` frames — used to
    check the multi-chunk stitch (frame counts must sum). Built with the stdlib
    `wave` module so the header is genuine, not the hand-rolled FAKE_WAV above."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(sample.to_bytes(2, "little") * nframes)
    return buf.getvalue()


def _provider_with_response(monkeypatch, handler) -> SarvamProvider:
    """Builds a SarvamProvider whose httpx client is bound to a MockTransport."""
    provider = SarvamProvider()
    real_client = httpx.AsyncClient
    monkeypatch.setattr(
        "app.providers.sarvam.httpx.AsyncClient",
        functools.partial(real_client, transport=httpx.MockTransport(handler)),
    )
    return provider


def _json_response(payload: dict, status: int = 200):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=payload)

    return handler


# ---------------------------------------------------------------------------
# The happy path: base64 in `audios[0]` becomes real audio bytes.
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_speak_base64_decodes_audios_field_into_real_audio_bytes(monkeypatch):
    encoded = base64.b64encode(FAKE_WAV).decode()
    provider = _provider_with_response(
        monkeypatch, _json_response({"request_id": "req-1", "audios": [encoded]})
    )

    result = await provider.speak("Namaste, your campaign is live.")

    assert result.ok is True
    # The decoded bytes are the actual audio -- not the JSON envelope.
    assert result.audio_bytes == FAKE_WAV
    assert result.content_type == "audio/wav"
    # Regression guard: the old `response.content` behaviour would have handed
    # back the raw JSON body. Assert we are not doing that.
    assert result.audio_bytes.startswith(b"RIFF")
    assert b"audios" not in result.audio_bytes
    assert b"request_id" not in result.audio_bytes


@pytest.mark.asyncio
async def test_speak_takes_first_entry_when_sarvam_returns_multiple_audios(monkeypatch):
    """`audios` is an array (one entry per `inputs` element). We post exactly
    one input, so the first entry is ours."""
    first = base64.b64encode(FAKE_WAV).decode()
    second = base64.b64encode(b"RIFFsecond").decode()
    provider = _provider_with_response(
        monkeypatch, _json_response({"audios": [first, second]})
    )

    result = await provider.speak("hello")

    assert result.ok is True
    assert result.audio_bytes == FAKE_WAV


# ---------------------------------------------------------------------------
# Malformed / hostile responses degrade to ok=False, never raise, never return
# junk labelled as audio. Voice must always be able to fall back to text.
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_speak_non_json_body_is_handled_not_raised(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>502 upstream hiccup</html>")

    provider = _provider_with_response(monkeypatch, handler)

    result = await provider.speak("hello")

    assert result.ok is False
    assert result.error == "provider_error"
    assert result.audio_bytes is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload",
    [
        {"request_id": "req-1"},  # `audios` missing entirely
        {"audios": []},  # present but empty
        {"audios": None},  # present but null
        {"audios": "not-a-list"},  # wrong container type
        {"audios": [None]},  # entry is not a string
        {"audios": [12345]},  # entry is not a string
        {"audios": ["not valid base64 !!!"]},  # entry is not decodable
        {"audios": ["<html>error page</html>"]},  # prose smuggled into the field
    ],
)
async def test_speak_malformed_audios_field_returns_invalid_audio_response(monkeypatch, payload):
    provider = _provider_with_response(monkeypatch, _json_response(payload))

    result = await provider.speak("hello")

    assert result.ok is False
    assert result.error == "invalid_audio_response"
    assert result.audio_bytes is None


@pytest.mark.asyncio
async def test_speak_empty_base64_string_reports_empty_audio(monkeypatch):
    """`audios: [""]` is well-formed base64 that decodes to zero bytes -- a
    distinct condition from a malformed body, and still not playable audio."""
    provider = _provider_with_response(monkeypatch, _json_response({"audios": [""]}))

    result = await provider.speak("hello")

    assert result.ok is False
    assert result.error == "empty_audio"


@pytest.mark.asyncio
async def test_speak_http_error_still_degrades_to_provider_error(monkeypatch):
    provider = _provider_with_response(
        monkeypatch, _json_response({"error": "rate limited"}, status=429)
    )

    result = await provider.speak("hello")

    assert result.ok is False
    assert result.error == "provider_error"


# ---------------------------------------------------------------------------
# Unit-level coverage of the decoder itself.
# ---------------------------------------------------------------------------


def test_decode_tts_audio_round_trips_base64():
    encoded = base64.b64encode(FAKE_WAV).decode()
    assert _decode_tts_audio({"audios": [encoded]}) == FAKE_WAV


@pytest.mark.parametrize(
    "data",
    [
        None,
        "a string, not a dict",
        [],
        {},
        {"audios": {}},
        {"audio": "some-base64"},  # near-miss field name must not be guessed at
    ],
)
def test_decode_tts_audio_returns_none_for_unrecognized_shapes(data):
    assert _decode_tts_audio(data) is None


def test_decode_tts_audio_never_raises_on_a_raw_audio_body():
    """If Sarvam ever *did* return raw audio, the JSON parse upstream would
    already have failed. This just pins that the decoder itself is total."""
    assert _decode_tts_audio(json.loads("{}")) is None


# ---------------------------------------------------------------------------
# Long-reply chunking guard: bulbul:v3 caps a single input at 2500 chars, so a
# long reply is split on sentence boundaries and the per-chunk audio is stitched
# back into one WAV — otherwise the whole reply 400s and drops to the browser
# fallback voice (the "sometimes sounds different" bug).
# ---------------------------------------------------------------------------


def test_chunk_text_keeps_short_text_as_a_single_chunk():
    """The common case — Meera replies are one or two sentences, so chunking is a
    no-op that leaves the single-request path unchanged."""
    text = "Hey, drop your product link and I'll build you a plan."
    assert _chunk_text(text) == [text]


def test_chunk_text_returns_empty_for_blank_input():
    assert _chunk_text("") == []
    assert _chunk_text("   \n  ") == []


def test_chunk_text_splits_long_text_under_the_cap_without_losing_words():
    long_text = ("This is a sentence. " * 400).strip()  # ~7600 chars
    chunks = _chunk_text(long_text)

    assert len(chunks) > 1, "text well over the cap must split"
    assert all(len(c) <= MAX_TTS_CHARS for c in chunks), "no chunk may exceed the cap"
    # Whitespace-insensitive: every word survives the split, nothing dropped.
    assert "".join(chunks).replace(" ", "") == long_text.replace(" ", "")


def test_chunk_text_hard_splits_a_single_giant_token():
    """A pathological unbroken token (e.g. a very long URL) longer than the cap
    is sliced so we never emit an over-limit chunk that would 400."""
    giant = "x" * (MAX_TTS_CHARS * 3)
    chunks = _chunk_text(giant)

    assert len(chunks) >= 3
    assert all(len(c) <= MAX_TTS_CHARS for c in chunks)


def test_concat_wavs_single_segment_is_returned_byte_for_byte():
    """Critical invariant: the one-chunk path must be untouched, so a single
    segment comes back identical (this is what keeps every other test exact)."""
    seg = _make_wav(100)
    assert _concat_wavs([seg]) == seg


def test_concat_wavs_sums_frames_across_segments_into_one_valid_wav():
    combined = _concat_wavs([_make_wav(100), _make_wav(150, sample=2)])
    assert combined is not None

    reader = wave.open(io.BytesIO(combined), "rb")
    assert reader.getnframes() == 250, "frame counts must sum (100 + 150)"
    assert reader.getframerate() == 44100
    assert reader.getnchannels() == 1
    assert reader.getsampwidth() == 2


def test_concat_wavs_returns_none_for_unparseable_or_empty_input():
    assert _concat_wavs([b"not a wav", b"also not a wav"]) is None
    assert _concat_wavs([]) is None


@pytest.mark.asyncio
async def test_speak_short_text_makes_exactly_one_request(monkeypatch):
    """Regression guard for the unchanged single-request path: a short reply is
    one POST and the returned audio is that segment byte-for-byte."""
    seg = _make_wav(80)
    encoded = base64.b64encode(seg).decode()
    inputs_seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        inputs_seen.append(json.loads(request.content)["inputs"][0])
        return httpx.Response(200, json={"audios": [encoded]})

    provider = _provider_with_response(monkeypatch, handler)
    result = await provider.speak("Hey, drop your product link.")

    assert result.ok is True
    assert len(inputs_seen) == 1
    assert result.audio_bytes == seg


@pytest.mark.asyncio
async def test_speak_long_text_is_chunked_across_requests_and_stitched(monkeypatch):
    """A long reply fans out into multiple posts (each within the cap) and the
    per-chunk audio is stitched into one WAV whose frames sum — so it plays in
    Priya's voice instead of dropping to the browser fallback."""
    seg = _make_wav(100)
    encoded = base64.b64encode(seg).decode()
    inputs_seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        inputs_seen.append(json.loads(request.content)["inputs"][0])
        return httpx.Response(200, json={"audios": [encoded]})

    provider = _provider_with_response(monkeypatch, handler)
    long_text = ("This is a sentence. " * 400).strip()
    result = await provider.speak(long_text)

    assert result.ok is True
    assert len(inputs_seen) > 1, "long text must be split across multiple requests"
    assert all(len(chunk) <= MAX_TTS_CHARS for chunk in inputs_seen)
    reader = wave.open(io.BytesIO(result.audio_bytes), "rb")
    assert reader.getnframes() == 100 * len(inputs_seen), "stitched frames must sum"
    assert result.content_type == "audio/wav"


@pytest.mark.asyncio
async def test_speak_request_uses_tuned_v3_params_and_no_pause(monkeypatch):
    """Pins the tuned bulbul:v3 body and guards against `pause` (not a real
    Sarvam parameter) ever being re-added."""
    seg = _make_wav(10)
    encoded = base64.b64encode(seg).decode()
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={"audios": [encoded]})

    provider = _provider_with_response(monkeypatch, handler)
    await provider.speak("hello")

    assert captured["model"] == "bulbul:v3"
    assert captured["speaker"] == "priya"
    assert captured["temperature"] == 0.35
    # 24000 (Sarvam default) on purpose — 44100 doubled the payload and blew the
    # read timeout on this batch-REST path.
    assert captured["speech_sample_rate"] == 24000
    assert "pause" not in captured, "pause is not a real Sarvam param — must not return"
    # v2-only params must never be sent on a v3 request.
    assert "pitch" not in captured
    assert "loudness" not in captured
