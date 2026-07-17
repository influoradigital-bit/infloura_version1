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
import json

import httpx
import pytest

from app.providers.sarvam import SarvamProvider, _decode_tts_audio

# A minimal but real WAV header + a byte of payload. The point is that these
# bytes are NOT valid UTF-8 and NOT JSON — if the provider ever regresses to
# returning `response.content`, no assertion here can accidentally pass.
FAKE_WAV = b"RIFF$\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x80>\x00\x00\x00}\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00\xff"


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
