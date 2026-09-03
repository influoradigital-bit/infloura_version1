"""A5 — the voice route resolves the language per audience and passes it to
BOTH Sarvam calls: CREATOR turns always use `creator_language` from the
creator's Meera preferences (via Spring's context), BRAND turns use the
request hint or the configured default. `verify_token` is mocked (same
pattern as the other route tests); Sarvam/Gemini/Spring are mocked.
"""

from __future__ import annotations

import base64
import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringResponse
from app.config import get_settings
from app.costs import spend_tracker
from app.providers.sarvam import SpeakResult, TranscribeResult
from app.routes import voice as voice_route

WORKSPACE_ID = "creator-user-voice-001"


def _b64url(obj: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


def _onbehalf_jwt(user_type: str) -> str:
    return f"{_b64url({'alg': 'HS256'})}.{_b64url({'sub': 'u1', 'userType': user_type})}.sig"


def _verified(claims: dict | None = None) -> VerifiedToken:
    return VerifiedToken(
        workspace_id=WORKSPACE_ID,
        scope="chat:stream",
        subject="u1",
        conversation_id=None,
        claims=claims or {},
    )


def _json_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/voice/speak",
        "headers": [(b"content-type", b"application/json")],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _multipart_request(fields: dict[str, str], audio: bytes) -> Request:
    boundary = "----voicetestboundary"
    parts = []
    for name, value in fields.items():
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    parts.append(
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"a.wav\"\r\n"
            "Content-Type: audio/wav\r\n\r\n"
        ).encode()
        + audio
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    body_bytes = b"".join(parts)

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/voice/transcribe",
        "headers": [
            (b"content-type", f"multipart/form-data; boundary={boundary}".encode()),
            (b"content-length", str(len(body_bytes)).encode()),
        ],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _spring_with_creator_language(language: str | None) -> MagicMock:
    spring = MagicMock()
    # Q3: these are language tests, so they run as a CONSENTED creator.
    data = {"audience": "CREATOR", "consent_accepted": True}
    if language:
        data["creator_language"] = language
    spring.get_meera_context = AsyncMock(
        return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
    )
    return spring


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in ("VOICE_DEFAULT_STT_LANGUAGE", "VOICE_DEFAULT_TTS_LANGUAGE", "AI_SPEND_KILL_SWITCH"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    for var in ("VOICE_DEFAULT_STT_LANGUAGE", "VOICE_DEFAULT_TTS_LANGUAGE", "AI_DAILY_SPEND_CEILING_USD"):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


# ---------------------------------------------------------------- resolve_voice_language


@pytest.mark.asyncio
async def test_creator_language_comes_from_preferences_for_both_directions():
    spring = _spring_with_creator_language("en-IN")
    for direction in ("stt", "tts"):
        lang = await voice_route.resolve_voice_language(
            # Fix round 1: the audience is the VERIFIED claim; the on-behalf JWT
            # is only forwarded to Spring for the context fetch.
            verified_claims={"userType": "CREATOR"},
            onbehalf_jwt=_onbehalf_jwt("CREATOR"),
            workspace_id=WORKSPACE_ID,
            requested="ta-IN",  # a client hint never overrides the creator's own setting
            direction=direction,
            request_id="r",
            spring=spring,
        )
        assert lang == "en-IN"
    assert spring.get_meera_context.await_count == 2
    spring.get_meera_context.assert_awaited_with(
        workspace_id=WORKSPACE_ID, audience="CREATOR", onbehalf_jwt=_onbehalf_jwt("CREATOR")
    )


@pytest.mark.asyncio
async def test_creator_language_falls_back_to_hindi_when_context_is_missing_it():
    spring = _spring_with_creator_language(None)
    lang = await voice_route.resolve_voice_language(
        verified_claims={"userType": "CREATOR"},
        onbehalf_jwt="",
        workspace_id=WORKSPACE_ID,
        requested=None,
        direction="tts",
        request_id="r",
        spring=spring,
    )
    assert lang == "hi-IN"


@pytest.mark.asyncio
async def test_creator_language_survives_a_context_fetch_failure():
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(side_effect=RuntimeError("spring down"))
    lang = await voice_route.resolve_voice_language(
        verified_claims={"userType": "CREATOR"},
        onbehalf_jwt="",
        workspace_id=WORKSPACE_ID,
        requested=None,
        direction="stt",
        request_id="r",
        spring=spring,
    )
    assert lang == "hi-IN"  # voice never dead-ends


@pytest.mark.asyncio
async def test_brand_uses_request_hint_then_configured_default(monkeypatch):
    spring = MagicMock()
    spring.get_meera_context = AsyncMock()
    lang = await voice_route.resolve_voice_language(
        verified_claims={"userType": "BRAND"},
        onbehalf_jwt="",
        workspace_id="ws-1",
        requested="hi-IN",
        direction="tts",
        request_id="r",
        spring=spring,
    )
    assert lang == "hi-IN"
    spring.get_meera_context.assert_not_awaited()  # brand never fetches a creator context

    monkeypatch.setenv("VOICE_DEFAULT_TTS_LANGUAGE", "ta-IN")
    monkeypatch.setenv("VOICE_DEFAULT_STT_LANGUAGE", "en-IN")
    get_settings.cache_clear()
    assert (
        await voice_route.resolve_voice_language(
            verified_claims={}, onbehalf_jwt="", workspace_id="ws-1",
            requested=None, direction="tts", request_id="r", spring=spring,
        )
        == "ta-IN"
    )
    assert (
        await voice_route.resolve_voice_language(
            verified_claims={}, onbehalf_jwt="", workspace_id="ws-1",
            requested=None, direction="stt", request_id="r", spring=spring,
        )
        == "en-IN"
    )


# ---------------------------------------------------------------- route wiring


@pytest.mark.asyncio
async def test_voice_speak_passes_creator_language_to_sarvam():
    sarvam = MagicMock()
    sarvam.speak = AsyncMock(
        return_value=SpeakResult(ok=True, audio_bytes=b"RIFF", content_type="audio/wav", billed=True, billed_chars=5)
    )
    spring = _spring_with_creator_language("en-IN")
    request = _json_request({"workspace_id": WORKSPACE_ID, "text": "hello there", "lang": "ta-IN"})

    with patch.object(voice_route, "verify_token", return_value=_verified({"userType": "CREATOR"})), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        response = await voice_route.voice_speak(request, authorization="Bearer token")

    assert response.status_code == 200
    sarvam.speak.assert_awaited_once()
    assert sarvam.speak.await_args.kwargs["lang"] == "en-IN"


@pytest.mark.asyncio
async def test_voice_speak_brand_uses_request_lang(monkeypatch):
    sarvam = MagicMock()
    sarvam.speak = AsyncMock(
        return_value=SpeakResult(ok=True, audio_bytes=b"RIFF", content_type="audio/wav", billed=True, billed_chars=5)
    )
    spring = MagicMock()
    spring.get_meera_context = AsyncMock()
    request = _json_request({"workspace_id": "ws-1", "text": "hello there", "lang": "hi-IN"})
    verified = VerifiedToken(workspace_id="ws-1", scope="chat:stream", subject="u", conversation_id=None, claims={"userType": "BRAND"})

    with patch.object(voice_route, "verify_token", return_value=verified), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        await voice_route.voice_speak(request, authorization="Bearer token")

    assert sarvam.speak.await_args.kwargs["lang"] == "hi-IN"
    spring.get_meera_context.assert_not_awaited()


@pytest.mark.asyncio
async def test_voice_transcribe_passes_creator_language_to_sarvam():
    sarvam = MagicMock()
    sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=True, raw_transcript="hello", lang_detected="en-IN", billed=True)
    )
    gemini = MagicMock()
    cleanup = MagicMock()
    cleanup.ok = True
    cleanup.cleaned_text = "Hello."
    cleanup.usage = None
    gemini.cleanup_transcript = AsyncMock(return_value=cleanup)
    spring = _spring_with_creator_language("en-IN")
    request = _multipart_request({"workspace_id": WORKSPACE_ID, "lang": "ta-IN"}, b"RIFFfakewav")

    with patch.object(voice_route, "verify_token", return_value=_verified({"userType": "CREATOR"})), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_gemini", return_value=gemini), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        result = await voice_route.voice_transcribe(request, authorization="Bearer token")

    assert result["fallback"] is False
    assert result["cleaned_text"] == "Hello."
    sarvam.transcribe.assert_awaited_once()
    assert sarvam.transcribe.await_args.kwargs["language"] == "en-IN"
