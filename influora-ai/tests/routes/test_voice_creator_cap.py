"""Gate fix round 1 (Q7) — voice shares the creator's MONTHLY allowance.

Priya: "VOICE DOES NOT SHARE THE COUNTER. app/routes/voice.py imports only
`record_spend` and calls only `check_spend_gate` -- it never calls
check_creator_spend_gate or record_creator_spend. A creator at their monthly
cap can keep spending on Sarvam STT/TTS indefinitely, and voice spend never
counts toward the chat cap."

These drive the real route functions (`verify_token` mocked, same pattern as
tests/routes/test_voice_language.py; Sarvam/Gemini/Spring mocked) and assert:

- a creator AT cap: transcribe and speak both return the route's silent
  fallback envelope carrying `CREATOR_MONTHLY_CAP_REACHED` + the same friendly
  message /chat uses, with ZERO Sarvam calls
- a creator UNDER cap: STT, cleanup and TTS spend all land on the creator's
  monthly ledger (so voice spend counts toward the chat cap) and the hold the
  gate took is settled, not leaked
- voice spend and chat spend are ONE counter: voice can push a creator to the
  cap that /chat then enforces
- BRAND voice turns never touch the creator ledger
- the per-creator override in the context payload applies to voice too
"""

from __future__ import annotations

import base64
import json
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringResponse
from app.config import GEMINI_MODEL, get_settings
from app.costs import spend_tracker
from app.costs.pricing import (
    estimate_cost_usd,
    estimate_sarvam_flat_cost_usd,
    estimate_sarvam_tts_cost_usd,
)
from app.costs.spend_tracker import CREATOR_CAP_CODE, CREATOR_CAP_MESSAGE, SpendCapExceeded
from app.providers.gemini import CleanupResult
from app.providers.sarvam import SpeakResult, TranscribeResult
from app.routes import voice as voice_route

CREATOR_ID = "creator-user-voice-cap-001"
BRAND_WS = "ws-brand-voice-cap-001"


def _b64url(obj: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


def _onbehalf_jwt(user_type: str) -> str:
    return f"{_b64url({'alg': 'HS256'})}.{_b64url({'sub': 'u1', 'userType': user_type})}.sig"


def _verified(user_type: str, workspace_id: str) -> VerifiedToken:
    return VerifiedToken(
        workspace_id=workspace_id,
        scope="service",
        subject="u1",
        conversation_id=None,
        claims={"userType": user_type},
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
    boundary = "----voicecapboundary"
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


def _spring(**context) -> MagicMock:
    spring = MagicMock()
    # Q3: the cap tests are about the cap, so they run as a CONSENTED creator.
    data = {"audience": "CREATOR", "creator_language": "hi-IN", "consent_accepted": True, **context}
    spring.get_meera_context = AsyncMock(
        return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
    )
    return spring


def _sarvam(text_len: int = 11) -> MagicMock:
    sarvam = MagicMock()
    sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=True, raw_transcript="hello", lang_detected="hi-IN", billed=True)
    )
    sarvam.speak = AsyncMock(
        return_value=SpeakResult(
            ok=True, audio_bytes=b"RIFF", content_type="audio/wav", billed=True, billed_chars=text_len
        )
    )
    return sarvam


CLEANUP_USAGE = {"input_tokens": 100, "output_tokens": 20}


def _gemini() -> MagicMock:
    gemini = MagicMock()
    gemini.cleanup_transcript = AsyncMock(
        return_value=CleanupResult(ok=True, cleaned_text="Hello.", usage=CLEANUP_USAGE)
    )
    return gemini


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in ("AI_SPEND_KILL_SWITCH", "AI_CREATOR_MONTHLY_CAP_USD", "REDIS_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    for var in ("AI_DAILY_SPEND_CEILING_USD", "AI_CREATOR_MONTHLY_CAP_USD"):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


async def _speak(user_type: str, workspace_id: str, sarvam: MagicMock, spring: MagicMock, text: str = "hello there"):
    request = _json_request({"workspace_id": workspace_id, "text": text})
    with patch.object(voice_route, "verify_token", return_value=_verified(user_type, workspace_id)), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        return await voice_route.voice_speak(request, authorization="Bearer token")


async def _transcribe(user_type: str, workspace_id: str, sarvam: MagicMock, spring: MagicMock, gemini: MagicMock | None = None):
    request = _multipart_request({"workspace_id": workspace_id}, b"RIFFfakewav")
    with patch.object(voice_route, "verify_token", return_value=_verified(user_type, workspace_id)), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_gemini", return_value=gemini or _gemini()), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        return await voice_route.voice_transcribe(request, authorization="Bearer token")


# ---------------------------------------------------------------- at cap: blocked


@pytest.mark.asyncio
async def test_creator_at_cap_transcribe_is_blocked_with_zero_sarvam_calls():
    await spend_tracker.record_creator_spend(Decimal("0.75"), CREATOR_ID)
    sarvam = _sarvam()
    gemini = _gemini()

    result = await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring(), gemini)

    assert result["fallback"] is True
    assert result["code"] == CREATOR_CAP_CODE
    assert result["message"] == CREATOR_CAP_MESSAGE  # the creator reads this verbatim
    assert result["raw_transcript"] is None
    sarvam.transcribe.assert_not_awaited()
    gemini.cleanup_transcript.assert_not_awaited()
    # Nothing was billed and nothing is held.
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) == Decimal("0.75")
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_creator_at_cap_speak_is_blocked_with_zero_sarvam_calls():
    await spend_tracker.record_creator_spend(Decimal("0.75"), CREATOR_ID)
    sarvam = _sarvam()

    result = await _speak("CREATOR", CREATOR_ID, sarvam, _spring())

    assert isinstance(result, dict)  # the fallback envelope, not audio bytes
    assert result["fallback"] is True
    assert result["code"] == CREATOR_CAP_CODE
    assert result["message"] == CREATOR_CAP_MESSAGE
    sarvam.speak.assert_not_awaited()
    assert await spend_tracker.get_reserved_global() == Decimal(0)


# ---------------------------------------------------------------- under cap: shared counter


@pytest.mark.asyncio
async def test_creator_tts_spend_lands_on_the_monthly_ledger_and_settles_the_hold():
    text = "hello there"
    sarvam = _sarvam(len(text))

    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring(), text=text)

    assert response.status_code == 200
    expected = estimate_sarvam_tts_cost_usd(len(text))
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) == expected
    assert await spend_tracker.get_workspace_total_today(CREATOR_ID) == expected  # daily ledger too
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_creator_stt_and_cleanup_spend_land_on_the_monthly_ledger():
    result = await _transcribe("CREATOR", CREATOR_ID, _sarvam(), _spring())

    assert result["fallback"] is False
    expected = estimate_sarvam_flat_cost_usd() + estimate_cost_usd(GEMINI_MODEL, CLEANUP_USAGE)
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) == expected
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_billed_but_failed_stt_still_charges_the_creator_and_releases_the_hold():
    """F-06's creator half: `empty_transcript` comes back after a billed 200."""
    sarvam = _sarvam()
    sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=False, error="empty_transcript", billed=True)
    )
    result = await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring())

    assert result["fallback"] is True
    assert "code" not in result  # generic fallback, not the cap message
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) == estimate_sarvam_flat_cost_usd()
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


@pytest.mark.asyncio
async def test_unbilled_stt_failure_charges_nothing_and_releases_the_hold():
    sarvam = _sarvam()
    sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=False, error="provider_error", billed=False)
    )
    await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring())
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_voice_spend_counts_toward_the_chat_cap(monkeypatch):
    """ONE counter: voice can walk a creator up to the cap that /chat enforces."""
    monkeypatch.setenv("AI_CREATOR_MONTHLY_CAP_USD", "0.0001")
    get_settings.cache_clear()
    response = await _speak("CREATOR", CREATOR_ID, _sarvam(), _spring())
    assert response.status_code == 200  # first call passed (nothing spent yet)
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) > Decimal(0)

    # The chat gate (same function chat.py calls) now refuses this creator.
    with pytest.raises(SpendCapExceeded):
        await spend_tracker.check_creator_spend_gate(CREATOR_ID, "CREATOR")
    # And so does the next voice call.
    sarvam = _sarvam()
    result = await _speak("CREATOR", CREATOR_ID, sarvam, _spring())
    assert isinstance(result, dict) and result["code"] == CREATOR_CAP_CODE
    sarvam.speak.assert_not_awaited()


@pytest.mark.asyncio
async def test_voice_holds_a_reservation_while_sarvam_is_in_flight():
    """The Q7 race, on voice: a second call arriving mid-TTS sees the hold."""
    await spend_tracker.record_creator_spend(Decimal("0.74"), CREATOR_ID)
    sarvam = _sarvam()
    seen: dict = {}

    async def _speak_and_probe(text, lang):
        seen["held"] = await spend_tracker.get_reserved_creator(CREATOR_ID)
        with pytest.raises(SpendCapExceeded):
            await spend_tracker.check_creator_spend_gate(
                CREATOR_ID, "CREATOR", reserve_usd=get_settings().ai_reservation_per_call_usd
            )
        return SpeakResult(ok=True, audio_bytes=b"RIFF", content_type="audio/wav", billed=True, billed_chars=5)

    sarvam.speak = AsyncMock(side_effect=_speak_and_probe)
    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring())
    assert response.status_code == 200
    assert seen["held"] == Decimal(str(get_settings().ai_reservation_per_call_usd))
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


# ---------------------------------------------------------------- brand + override


@pytest.mark.asyncio
async def test_brand_voice_never_touches_the_creator_ledger():
    await spend_tracker.record_creator_spend(Decimal("100"), BRAND_WS)  # would block a creator
    spring = MagicMock()
    spring.get_meera_context = AsyncMock()
    sarvam = _sarvam()

    response = await _speak("BRAND", BRAND_WS, sarvam, spring)

    assert response.status_code == 200
    sarvam.speak.assert_awaited_once()
    spring.get_meera_context.assert_not_awaited()
    assert await spend_tracker.get_creator_month_total(BRAND_WS) == Decimal("100")  # unchanged


@pytest.mark.asyncio
async def test_per_creator_override_in_context_applies_to_voice():
    await spend_tracker.record_creator_spend(Decimal("1.00"), CREATOR_ID)  # over the 0.75 default
    sarvam = _sarvam()

    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring(ai_monthly_cap_usd="5.00"))

    assert response.status_code == 200
    sarvam.speak.assert_awaited_once()
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) > Decimal("1.00")


@pytest.mark.asyncio
async def test_daily_gate_block_releases_the_creator_hold(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()
    sarvam = _sarvam()

    result = await _speak("CREATOR", CREATOR_ID, sarvam, _spring())

    assert result == {"fallback": True, "message": "voice reply unavailable"}
    sarvam.speak.assert_not_awaited()
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
