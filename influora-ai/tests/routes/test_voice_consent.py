"""Gate fix round 1 (Priya Q3) — the A6 DPDP consent gate on /voice/*.

Priya: "the voice endpoints have no consent check. voice.py verifies a token
and then goes straight to `resolve_voice_prefs` and the creator cap gate --
there is no `consent_accepted` test anywhere in that file."

These drive the real route functions (`verify_token` mocked, same pattern as
tests/routes/test_voice_creator_cap.py; Sarvam/Gemini/Spring mocked) and
assert, for BOTH /voice/transcribe and /voice/speak:

- an UNCONSENTED creator is refused with HTTP 403 and the SAME
  CONSENT_REQUIRED body /chat returns (top-level `code` + `error.code`), with
  ZERO Sarvam calls, ZERO Gemini calls, and nothing reserved on either ledger
- the gate FAILS CLOSED: a context with no `consent_accepted` key, a null, a
  string "true", an empty context body, and a Spring fetch failure are all
  refused -- the "voice never dead-ends" language fallback does not extend
  to consent
- a CONSENTED creator (bool true, or a non-empty `consent_accepted_at`)
  proceeds to Sarvam as before
- BRAND calls never see the gate (no consent screen exists for brands)
- the gate runs BEFORE the creator monthly cap gate, so an unconsented
  creator who is also at cap gets CONSENT_REQUIRED, not the cap message
"""

from __future__ import annotations

import json
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth import consent as consent_module
from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringResponse
from app.config import get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import CREATOR_CAP_CODE
from app.providers.gemini import CleanupResult
from app.providers.sarvam import SpeakResult, TranscribeResult
from app.routes import chat as chat_route
from app.routes import voice as voice_route

CREATOR_ID = "creator-user-voice-consent-001"
BRAND_WS = "ws-brand-voice-consent-001"


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
    boundary = "----voiceconsentboundary"
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


def _spring_with(context: dict | None) -> MagicMock:
    """A Spring mock whose CREATOR context is exactly `context` (None -> an
    empty body). Unlike the cap tests' helper this does NOT inject
    `consent_accepted`, so each test states its consent state explicitly."""
    spring = MagicMock()
    data = context if context is not None else {}
    spring.get_meera_context = AsyncMock(
        return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
    )
    return spring


def _spring_down() -> MagicMock:
    spring = MagicMock()
    spring.get_meera_context = AsyncMock(side_effect=RuntimeError("spring down"))
    return spring


def _sarvam() -> MagicMock:
    sarvam = MagicMock()
    sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=True, raw_transcript="hello", lang_detected="hi-IN", billed=True)
    )
    sarvam.speak = AsyncMock(
        return_value=SpeakResult(ok=True, audio_bytes=b"RIFF", content_type="audio/wav", billed=True, billed_chars=5)
    )
    return sarvam


def _gemini() -> MagicMock:
    gemini = MagicMock()
    gemini.cleanup_transcript = AsyncMock(
        return_value=CleanupResult(ok=True, cleaned_text="Hello.", usage={"input_tokens": 10, "output_tokens": 2})
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


async def _speak(user_type: str, workspace_id: str, sarvam: MagicMock, spring: MagicMock):
    request = _json_request({"workspace_id": workspace_id, "text": "hello there"})
    with patch.object(voice_route, "verify_token", return_value=_verified(user_type, workspace_id)), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        return await voice_route.voice_speak(request, authorization="Bearer token")


async def _transcribe(user_type: str, workspace_id: str, sarvam: MagicMock, spring: MagicMock, gemini: MagicMock):
    request = _multipart_request({"workspace_id": workspace_id}, b"RIFFfakewav")
    with patch.object(voice_route, "verify_token", return_value=_verified(user_type, workspace_id)), \
         patch.object(voice_route, "_get_sarvam", return_value=sarvam), \
         patch.object(voice_route, "_get_gemini", return_value=gemini), \
         patch.object(voice_route, "_get_spring", return_value=spring):
        return await voice_route.voice_transcribe(request, authorization="Bearer token")


def _assert_consent_refusal(response) -> None:
    """403 + the exact body /chat's `_consent_required_response` produces."""
    assert response.status_code == 403
    payload = json.loads(response.body)
    assert payload["code"] == "CONSENT_REQUIRED"
    assert payload["error"]["code"] == "CONSENT_REQUIRED"
    assert payload["action"] == "show_consent_screen"
    assert payload["message"] == consent_module.CONSENT_REQUIRED_MESSAGE
    # Byte-for-byte the same body the chat route returns.
    assert payload == json.loads(chat_route._consent_required_response().body)


async def _assert_nothing_held_or_billed(workspace_id: str) -> None:
    assert await spend_tracker.get_creator_month_total(workspace_id) == Decimal(0)
    assert await spend_tracker.get_reserved_creator(workspace_id) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)
    assert await spend_tracker.get_workspace_total_today(workspace_id) == Decimal(0)


# ---------------------------------------------------------------- unconsented: refused, zero provider calls


UNCONSENTED_CONTEXTS = [
    pytest.param({"audience": "CREATOR", "creator_language": "hi-IN", "consent_accepted": False}, id="false"),
    pytest.param({"audience": "CREATOR", "creator_language": "hi-IN"}, id="key-missing"),
    pytest.param({"audience": "CREATOR", "creator_language": "hi-IN", "consent_accepted": None}, id="null"),
    pytest.param({"audience": "CREATOR", "creator_language": "hi-IN", "consent_accepted": "true"}, id="string-true"),
    pytest.param({"audience": "CREATOR", "consent_accepted_at": "   "}, id="blank-timestamp"),
    pytest.param({}, id="empty-context"),
]


@pytest.mark.asyncio
@pytest.mark.parametrize("context", UNCONSENTED_CONTEXTS)
async def test_unconsented_creator_transcribe_is_refused_with_zero_provider_calls(context):
    sarvam = _sarvam()
    gemini = _gemini()

    response = await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring_with(context), gemini)

    _assert_consent_refusal(response)
    sarvam.transcribe.assert_not_awaited()
    gemini.cleanup_transcript.assert_not_awaited()
    await _assert_nothing_held_or_billed(CREATOR_ID)


@pytest.mark.asyncio
@pytest.mark.parametrize("context", UNCONSENTED_CONTEXTS)
async def test_unconsented_creator_speak_is_refused_with_zero_provider_calls(context):
    sarvam = _sarvam()

    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring_with(context))

    _assert_consent_refusal(response)
    sarvam.speak.assert_not_awaited()
    await _assert_nothing_held_or_billed(CREATOR_ID)


@pytest.mark.asyncio
async def test_context_fetch_failure_fails_closed_on_both_routes():
    """The language fallback ("voice never dead-ends") must not become a
    consent bypass: Spring down -> consent unknown -> refused."""
    sarvam = _sarvam()
    gemini = _gemini()

    response = await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring_down(), gemini)
    _assert_consent_refusal(response)
    sarvam.transcribe.assert_not_awaited()
    gemini.cleanup_transcript.assert_not_awaited()

    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring_down())
    _assert_consent_refusal(response)
    sarvam.speak.assert_not_awaited()
    await _assert_nothing_held_or_billed(CREATOR_ID)


@pytest.mark.asyncio
async def test_consent_gate_runs_before_the_creator_cap_gate():
    """Ordering matches /chat: consent, then the monthly cap. An unconsented
    creator who is ALSO at cap must see CONSENT_REQUIRED (the consent screen
    is the only thing that can move them forward), not the cap message."""
    await spend_tracker.record_creator_spend(Decimal("0.75"), CREATOR_ID)
    sarvam = _sarvam()

    response = await _speak(
        "CREATOR", CREATOR_ID, sarvam,
        _spring_with({"audience": "CREATOR", "creator_language": "hi-IN", "consent_accepted": False}),
    )

    _assert_consent_refusal(response)
    assert CREATOR_CAP_CODE not in response.body.decode()
    sarvam.speak.assert_not_awaited()
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


# ---------------------------------------------------------------- consented: proceeds


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "context",
    [
        pytest.param({"audience": "CREATOR", "creator_language": "en-IN", "consent_accepted": True}, id="bool-true"),
        pytest.param(
            {"audience": "CREATOR", "creator_language": "en-IN", "consent_accepted_at": "2026-09-03T10:00:00Z"},
            id="timestamp",
        ),
    ],
)
async def test_consented_creator_reaches_sarvam_on_both_routes(context):
    sarvam = _sarvam()
    gemini = _gemini()

    result = await _transcribe("CREATOR", CREATOR_ID, sarvam, _spring_with(context), gemini)
    assert result["fallback"] is False
    sarvam.transcribe.assert_awaited_once()
    assert sarvam.transcribe.await_args.kwargs["language"] == "en-IN"  # A5 still honoured

    response = await _speak("CREATOR", CREATOR_ID, sarvam, _spring_with(context))
    assert response.status_code == 200
    sarvam.speak.assert_awaited_once()


# ---------------------------------------------------------------- brand: untouched


@pytest.mark.asyncio
async def test_brand_voice_never_sees_the_consent_gate():
    spring = MagicMock()
    spring.get_meera_context = AsyncMock()
    sarvam = _sarvam()
    gemini = _gemini()

    response = await _speak("BRAND", BRAND_WS, sarvam, spring)
    assert response.status_code == 200
    sarvam.speak.assert_awaited_once()

    result = await _transcribe("BRAND", BRAND_WS, sarvam, spring, gemini)
    assert result["fallback"] is False
    sarvam.transcribe.assert_awaited_once()
    spring.get_meera_context.assert_not_awaited()


# ---------------------------------------------------------------- the seam itself


@pytest.mark.asyncio
async def test_resolve_voice_prefs_exposes_consent_fail_closed():
    """`VoicePrefs.consent_required` is what the routes branch on; pin its
    fail-closed semantics directly so a refactor of the routes cannot
    quietly flip the default."""
    unconsented = await voice_route.resolve_voice_prefs(
        verified_claims={"userType": "CREATOR"}, onbehalf_jwt="", workspace_id=CREATOR_ID,
        requested=None, direction="tts", request_id="r",
        spring=_spring_with({"audience": "CREATOR"}),
    )
    assert unconsented.consent_required is True

    down = await voice_route.resolve_voice_prefs(
        verified_claims={"userType": "CREATOR"}, onbehalf_jwt="", workspace_id=CREATOR_ID,
        requested=None, direction="stt", request_id="r", spring=_spring_down(),
    )
    assert down.consent_required is True
    assert down.language == "hi-IN"  # the language fallback still works

    consented = await voice_route.resolve_voice_prefs(
        verified_claims={"userType": "CREATOR"}, onbehalf_jwt="", workspace_id=CREATOR_ID,
        requested=None, direction="tts", request_id="r",
        spring=_spring_with({"audience": "CREATOR", "consent_accepted": True}),
    )
    assert consented.consent_required is False

    brand = await voice_route.resolve_voice_prefs(
        verified_claims={"userType": "BRAND"}, onbehalf_jwt="", workspace_id=BRAND_WS,
        requested=None, direction="tts", request_id="r", spring=_spring_down(),
    )
    assert brand.consent_required is False


def test_chat_and_voice_share_one_consent_definition():
    """One gate, one body: chat.py re-exports app/auth/consent.py rather than
    keeping its own copy, so the two routes cannot drift apart again."""
    assert chat_route.consent_accepted is consent_module.consent_accepted
    assert voice_route.consent_accepted is consent_module.consent_accepted
    assert chat_route.CONSENT_REQUIRED_CODE == consent_module.CONSENT_REQUIRED_CODE == "CONSENT_REQUIRED"
    assert chat_route.CONSENT_REQUIRED_MESSAGE == consent_module.CONSENT_REQUIRED_MESSAGE
    assert not consent_module.consent_accepted(None)
    assert not consent_module.consent_accepted("yes")
    assert not consent_module.consent_accepted({"consent_accepted": 1})
    assert consent_module.consent_accepted({"consent_accepted_at": "2026-09-03T10:00:00Z"})
