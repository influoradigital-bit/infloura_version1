"""Tests for POST /voice/transcribe and /voice/speak's P2-17 AI spend gate +
spend-recording wiring (Sarvam STT, Gemini cleanup, Sarvam TTS).

Before this fix, voice.py imported nothing from app.costs: the kill-switch /
daily ceiling gate was never enforced on any of its three provider calls, and
none of the three ever recorded spend (Sarvam's flat per-call rate was an
unused constant, per app/costs/pricing.py). These tests drive the full ASGI
app (multipart /voice/transcribe, JSON /voice/speak) with a real minted
service token against a fake JWKS source, same scaffolding as
tests/routes/test_trend_tag.py, and mock the provider seams
(_get_sarvam/_get_gemini) the route calls through.

Per voice.py's own "never a dead end" contract, a gate block degrades to the
SAME silent fallback shape as a provider failure (200, not a raw 5xx) -- these
tests pin that behavior too, not just "the provider wasn't called".
"""

from __future__ import annotations

import time
from decimal import Decimal
from unittest.mock import AsyncMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.config import GEMINI_MODEL, get_settings
from app.costs import spend_tracker
from app.costs.pricing import estimate_cost_usd, estimate_sarvam_flat_cost_usd
from app.providers.gemini import CleanupResult
from app.providers.sarvam import SpeakResult, TranscribeResult

TRANSCRIBE_PATH = "/voice/transcribe"
SPEAK_PATH = "/voice/speak"


def _gen_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


class _StaticKey:
    def __init__(self, key):
        self.key = key


class _FakeJwksSource:
    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        return _StaticKey(self._legitimate_public_pem)


LEGIT_PRIVATE_PEM, LEGIT_PUBLIC_PEM = _gen_rsa_keypair()
WORKSPACE_ID = "ws-voice-spend-gate-001"


@pytest.fixture(autouse=True)
async def _install_fake_jwks_and_reset_state(monkeypatch):
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    reset_jwks_source()
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    monkeypatch.delenv("AI_DAILY_SPEND_CEILING_USD", raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


def _client() -> TestClient:
    """TestClient WITHOUT lifespan -- skips the boot-secret startup hook,
    same rationale as tests/routes/test_trend_tag.py / test_trendspark_registration.py."""
    from app.main import app

    return TestClient(app)


def _mint_service_token(*, endpoint_scope: str = "service") -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 240,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": endpoint_scope,
        "workspace_id": WORKSPACE_ID,
        "sub": "spring-service",
    }
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "test-kid"})


def _auth() -> dict[str, str]:
    return {"Authorization": f"Bearer {_mint_service_token()}"}


# ── /voice/transcribe -- spend gate ─────────────────────────────────────────


def test_transcribe_kill_switch_blocks_with_zero_provider_calls(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()

    with patch("app.routes.voice._get_sarvam") as mock_get_sarvam, patch(
        "app.routes.voice._get_gemini"
    ) as mock_get_gemini:
        resp = _client().post(
            TRANSCRIBE_PATH,
            data={"workspace_id": WORKSPACE_ID},
            files={"audio": ("test.wav", b"fake-audio-bytes", "audio/wav")},
            headers=_auth(),
        )
        mock_get_sarvam.assert_not_called()
        mock_get_gemini.assert_not_called()

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["fallback"] is True
    assert data["raw_transcript"] is None


def test_transcribe_ceiling_breach_blocks_with_zero_provider_calls(monkeypatch):
    import asyncio

    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "1.0")
    get_settings.cache_clear()
    asyncio.run(spend_tracker.record_spend(Decimal("1.00"), workspace_id=None))

    with patch("app.routes.voice._get_sarvam") as mock_get_sarvam:
        resp = _client().post(
            TRANSCRIBE_PATH,
            data={"workspace_id": WORKSPACE_ID},
            files={"audio": ("test.wav", b"fake-audio-bytes", "audio/wav")},
            headers=_auth(),
        )
        mock_get_sarvam.assert_not_called()

    assert resp.status_code == 200, resp.text
    assert resp.json()["fallback"] is True


def test_transcribe_success_records_stt_and_cleanup_spend(monkeypatch):
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    get_settings.cache_clear()

    mock_sarvam = AsyncMock()
    mock_sarvam.transcribe = AsyncMock(
        return_value=TranscribeResult(ok=True, raw_transcript="hello brand", lang_detected="hi-IN")
    )
    cleanup_usage = {"input_tokens": 100, "output_tokens": 20}
    mock_gemini = AsyncMock()
    mock_gemini.cleanup_transcript = AsyncMock(
        return_value=CleanupResult(ok=True, cleaned_text="Hello brand", usage=cleanup_usage)
    )

    with patch("app.routes.voice._get_sarvam", return_value=mock_sarvam), patch(
        "app.routes.voice._get_gemini", return_value=mock_gemini
    ):
        resp = _client().post(
            TRANSCRIBE_PATH,
            data={"workspace_id": WORKSPACE_ID},
            files={"audio": ("test.wav", b"fake-audio-bytes", "audio/wav")},
            headers=_auth(),
        )

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["fallback"] is False
    assert data["cleaned_text"] == "Hello brand"

    expected_stt_cost = estimate_sarvam_flat_cost_usd()
    expected_cleanup_cost = estimate_cost_usd(GEMINI_MODEL, cleanup_usage)
    total = asyncio_run_get_global_total()
    assert total == expected_stt_cost + expected_cleanup_cost


def test_transcribe_stt_failure_records_no_spend():
    mock_sarvam = AsyncMock()
    mock_sarvam.transcribe = AsyncMock(return_value=TranscribeResult(ok=False, error="provider_error"))

    with patch("app.routes.voice._get_sarvam", return_value=mock_sarvam), patch(
        "app.routes.voice._get_gemini"
    ) as mock_get_gemini:
        resp = _client().post(
            TRANSCRIBE_PATH,
            data={"workspace_id": WORKSPACE_ID},
            files={"audio": ("test.wav", b"fake-audio-bytes", "audio/wav")},
            headers=_auth(),
        )
        mock_get_gemini.assert_not_called()  # STT failed -> cleanup never attempted

    assert resp.status_code == 200, resp.text
    assert resp.json()["fallback"] is True
    assert asyncio_run_get_global_total() == Decimal("0")


# ── /voice/speak -- spend gate ──────────────────────────────────────────────


def test_speak_kill_switch_blocks_with_zero_provider_calls(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()

    with patch("app.routes.voice._get_sarvam") as mock_get_sarvam:
        resp = _client().post(
            SPEAK_PATH,
            json={"workspace_id": WORKSPACE_ID, "text": "hello there"},
            headers=_auth(),
        )
        mock_get_sarvam.assert_not_called()

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["fallback"] is True
    assert data["message"] == "voice reply unavailable"


def test_speak_success_records_tts_spend():
    mock_sarvam = AsyncMock()
    mock_sarvam.speak = AsyncMock(
        return_value=SpeakResult(ok=True, audio_bytes=b"RIFF....WAVEfmt ", content_type="audio/wav")
    )

    with patch("app.routes.voice._get_sarvam", return_value=mock_sarvam):
        resp = _client().post(
            SPEAK_PATH,
            json={"workspace_id": WORKSPACE_ID, "text": "hello there"},
            headers=_auth(),
        )

    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "audio/wav"
    assert resp.content == b"RIFF....WAVEfmt "

    expected_cost = estimate_sarvam_flat_cost_usd()
    assert asyncio_run_get_global_total() == expected_cost


def test_speak_tts_failure_records_no_spend():
    mock_sarvam = AsyncMock()
    mock_sarvam.speak = AsyncMock(return_value=SpeakResult(ok=False, error="provider_error"))

    with patch("app.routes.voice._get_sarvam", return_value=mock_sarvam):
        resp = _client().post(
            SPEAK_PATH,
            json={"workspace_id": WORKSPACE_ID, "text": "hello there"},
            headers=_auth(),
        )

    assert resp.status_code == 200, resp.text
    assert resp.json()["fallback"] is True
    assert asyncio_run_get_global_total() == Decimal("0")


def asyncio_run_get_global_total() -> Decimal:
    """TestClient's `.post()` runs the ASGI app on its own event loop
    internally and returns synchronously, so by the time control comes back
    here there is no loop already running -- asyncio.run() is safe to spin up
    a fresh one just to read spend_tracker's global total."""
    import asyncio

    return asyncio.run(spend_tracker.get_global_total_today())
