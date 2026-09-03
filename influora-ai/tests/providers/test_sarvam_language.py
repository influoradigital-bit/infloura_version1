"""A5 — Sarvam client takes the language from its caller for BOTH STT and
TTS; nothing is hardcoded to hi-IN / en-IN inside the provider any more.
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from app.providers import sarvam as sarvam_module
from app.providers.sarvam import (
    SUPPORTED_VOICE_LANGUAGES,
    VOICE_FALLBACK_LANGUAGE,
    normalize_voice_language,
)


class _FakeTimeouts:
    sarvam_connect = 1.0
    sarvam_stt_read = 1.0
    sarvam_tts_read = 1.0


class _FakeSettings:
    timeouts = _FakeTimeouts()
    sarvam_api_key = "test-key"
    voice_default_stt_language = "hi-IN"
    voice_default_tts_language = "en-IN"


class _NoopBreaker:
    def before_call(self) -> None:
        return None

    def on_success(self) -> None:
        return None

    def on_failure(self) -> None:
        return None


def _provider(settings=None) -> sarvam_module.SarvamProvider:
    provider = sarvam_module.SarvamProvider.__new__(sarvam_module.SarvamProvider)
    provider._settings = settings or _FakeSettings()
    provider._breaker_stt = _NoopBreaker()
    provider._breaker_tts = _NoopBreaker()
    return provider


def _capture_client(monkeypatch, response_json):
    calls: list[dict] = []

    class _FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        async def post(self, path, **kwargs):
            calls.append({"path": path, **kwargs})
            resp = MagicMock()
            resp.raise_for_status = MagicMock()
            resp.json = MagicMock(return_value=response_json)
            return resp

    monkeypatch.setattr(sarvam_module.httpx, "AsyncClient", lambda **kw: _FakeClient())
    return calls


# ---------------------------------------------------------------- normalize


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("hi-IN", "hi-IN"),
        ("en-IN", "en-IN"),
        ("EN-in", "en-IN"),
        ("en_IN", "en-IN"),
        ("hi", "hi-IN"),
        ("en", "en-IN"),
        ("Hinglish", "hi-IN"),
        ("ta-IN", "ta-IN"),
    ],
)
def test_normalize_canonicalises_supported_codes(raw, expected):
    assert normalize_voice_language(raw) == expected


def test_normalize_falls_back_for_unsupported_or_blank():
    assert normalize_voice_language("xx-YY") == VOICE_FALLBACK_LANGUAGE
    assert normalize_voice_language("fr-FR", default="en-IN") == "en-IN"
    assert normalize_voice_language(None, default="en-IN") == "en-IN"
    assert normalize_voice_language("   ", default="en-IN") == "en-IN"
    assert normalize_voice_language(123) == VOICE_FALLBACK_LANGUAGE  # type: ignore[arg-type]
    # An unsupported default is itself corrected to the fallback constant.
    assert normalize_voice_language(None, default="zz-ZZ") == VOICE_FALLBACK_LANGUAGE


def test_hindi_and_english_are_first_class():
    assert {"hi-IN", "en-IN"} <= SUPPORTED_VOICE_LANGUAGES


# ---------------------------------------------------------------- STT


@pytest.mark.asyncio
async def test_transcribe_posts_the_caller_supplied_language(monkeypatch):
    calls = _capture_client(monkeypatch, {"transcript": "hello", "language_code": "en-IN"})
    result = await _provider().transcribe(b"audio", language="en-IN")
    assert result.ok
    assert calls[0]["path"] == "/speech-to-text"
    assert calls[0]["data"]["language_code"] == "en-IN"
    assert result.lang_detected == "en-IN"


@pytest.mark.asyncio
async def test_transcribe_defaults_to_the_configured_stt_language(monkeypatch):
    settings = _FakeSettings()
    settings.voice_default_stt_language = "ta-IN"
    calls = _capture_client(monkeypatch, {"transcript": "vanakkam"})
    result = await _provider(settings).transcribe(b"audio")
    assert calls[0]["data"]["language_code"] == "ta-IN"
    # No language_code in the response -> the requested language, not a hardcoded hi-IN
    assert result.lang_detected == "ta-IN"


@pytest.mark.asyncio
async def test_transcribe_unsupported_language_falls_back_not_400s(monkeypatch):
    calls = _capture_client(monkeypatch, {"transcript": "namaste"})
    await _provider().transcribe(b"audio", language="fr-FR")
    assert calls[0]["data"]["language_code"] == "hi-IN"


# ---------------------------------------------------------------- TTS


@pytest.mark.asyncio
async def test_speak_posts_the_caller_supplied_language(monkeypatch):
    calls = _capture_client(monkeypatch, {"audios": ["UklGRg=="]})
    await _provider().speak("namaste", lang="hi-IN")
    assert calls[0]["path"] == "/text-to-speech"
    assert calls[0]["json"]["target_language_code"] == "hi-IN"


@pytest.mark.asyncio
async def test_speak_defaults_to_the_configured_tts_language(monkeypatch):
    settings = _FakeSettings()
    settings.voice_default_tts_language = "en-IN"
    calls = _capture_client(monkeypatch, {"audios": ["UklGRg=="]})
    await _provider(settings).speak("hello")
    assert calls[0]["json"]["target_language_code"] == "en-IN"


@pytest.mark.asyncio
async def test_speak_normalises_a_bare_language_hint(monkeypatch):
    calls = _capture_client(monkeypatch, {"audios": ["UklGRg=="]})
    await _provider().speak("hello", lang="en")
    assert calls[0]["json"]["target_language_code"] == "en-IN"
