"""Provider-level pins for F-06 and F-07 (2026-08-08 deep audit).

Priya's final sign-off review found the money leak only half-pinned. The ROUTE
half was tested; the PROVIDER half was not, and the provider half is where the
leak lives:

  - flipping `billed=True` -> `billed=False` at `sarvam.py`'s empty-transcript
    return restored "free unlimited STT" in full, with 598 tests still green;
  - changing `len(spoken_chunk)` -> `len(chunk)` restored F-07's ~40%
    under-bill, with 598 tests still green.

These drive the real `SarvamProvider.transcribe` / `.speak` against a fake HTTP
layer and assert on what the provider REPORTS, so either revert goes red.
"""

from __future__ import annotations

import base64
import io
import wave

import httpx
import pytest

from app.providers.sarvam import SarvamProvider, speakable


def _wav_bytes(frames: int = 100) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(b"\x00\x00" * frames)
    return buf.getvalue()


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class _FakeClient:
    """Stands in for httpx.AsyncClient. Records every POST body."""

    def __init__(self, payload, recorder):
        self._payload = payload
        self._recorder = recorder

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def post(self, url, **kwargs):
        self._recorder.append(kwargs)
        return _FakeResponse(self._payload)


def _provider(monkeypatch, payload, recorder):
    monkeypatch.setattr(
        httpx, "AsyncClient", lambda **kw: _FakeClient(payload, recorder)
    )
    provider = SarvamProvider.__new__(SarvamProvider)
    from app.config import get_settings

    provider._settings = get_settings()
    noop = type(
        "B", (), {"before_call": lambda self: None,
                  "on_success": lambda self: None,
                  "on_failure": lambda self: None},
    )
    provider._breaker_stt = noop()
    provider._breaker_tts = noop()
    return provider


# ---------------------------------------------------------------------------
# F-06 — a billed HTTP 200 that returns nothing usable is still billed
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f06_provider_marks_an_empty_transcript_as_billed(monkeypatch):
    """Sarvam answered 200 and charged for it. `ok=False` must not mean `$0`:
    POSTing silence in a loop was free unlimited STT."""
    provider = _provider(monkeypatch, {"transcript": ""}, [])
    result = await provider.transcribe(b"RIFF....WAVEfmt ")

    assert result.ok is False
    assert result.error == "empty_transcript"
    assert result.billed is True, (
        "a completed, billed STT call reports billed=False — the caller will "
        "record $0 and silence is free again"
    )


@pytest.mark.asyncio
async def test_f06_provider_marks_a_successful_transcript_as_billed(monkeypatch):
    provider = _provider(monkeypatch, {"transcript": "hello", "language_code": "hi-IN"}, [])
    result = await provider.transcribe(b"RIFF....WAVEfmt ")
    assert result.ok is True and result.billed is True


@pytest.mark.asyncio
async def test_f06_provider_does_not_mark_a_transport_failure_as_billed(monkeypatch):
    """The other direction: a call that never completed must not be billed."""

    class _Boom:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        async def post(self, url, **kwargs):
            raise httpx.ConnectError("no route to host")

    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: _Boom())
    provider = _provider(monkeypatch, {}, [])
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: _Boom())

    result = await provider.transcribe(b"x")
    assert result.ok is False
    assert result.error == "provider_error"
    assert result.billed is False


@pytest.mark.asyncio
async def test_f06_provider_marks_bad_tts_audio_as_billed(monkeypatch):
    """`invalid_audio_response` is returned AFTER a billed 200."""
    recorder = []
    provider = _provider(monkeypatch, {"audios": ["not-base64-audio!!"]}, recorder)
    result = await provider.speak("hello there")

    assert result.ok is False
    assert result.billed is True, "a billed TTS call that returned bad audio reports $0"
    assert result.billed_chars > 0


# ---------------------------------------------------------------------------
# F-07 — bill the text Sarvam actually received
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f07_provider_counts_the_normalized_text_it_posted(monkeypatch):
    """`speak()` posts `speakable(chunk)`, which expands rupee amounts and
    initialisms. Counting the RAW chunk under-bills by ~40% on exactly the
    budget-quoting replies Meera produces most."""
    text = "Budget Rs.15,000-Rs.75,000 per creator for UGC"
    expanded = speakable(text)
    assert len(expanded) > len(text), "premise: speakable() must expand this string"

    recorder = []
    audio = base64.b64encode(_wav_bytes()).decode()
    provider = _provider(monkeypatch, {"audios": [audio]}, recorder)

    result = await provider.speak(text)

    assert result.ok is True and result.billed is True
    # What went on the wire is what must be billed.
    posted = "".join(
        str(call["json"].get("text") or call["json"].get("inputs"))
        for call in recorder
        if "json" in call
    )
    assert result.billed_chars == len(expanded), (
        f"billed {result.billed_chars} chars but posted {len(expanded)} — the raw "
        "pre-normalization length is being billed again"
    )
    assert result.billed_chars != len(text)
    if posted:
        assert expanded in posted


@pytest.mark.asyncio
async def test_f07_multi_chunk_reply_counts_every_chunk_posted(monkeypatch):
    """A long reply is split; each chunk is normalized and posted separately."""
    text = ". ".join(["Budget Rs.15,000 per creator for UGC"] * 40) + "."
    recorder = []
    audio = base64.b64encode(_wav_bytes()).decode()
    provider = _provider(monkeypatch, {"audios": [audio]}, recorder)

    result = await provider.speak(text)

    assert result.billed is True
    assert len(recorder) >= 1
    assert result.billed_chars >= len(text), (
        "normalization expands the text, so billed_chars can never be below the raw length"
    )


@pytest.mark.asyncio
async def test_f06_provider_marks_empty_tts_audio_as_billed(monkeypatch):
    """Priya's round-4 gap: `empty_audio` is a path F-06 names EXPLICITLY, and
    it was pinned at neither level. Its sibling `invalid_audio_response` was
    pinned at both — the route test mocks `speak()` with a hand-built result, so
    it never reaches the provider.

    Sarvam answered 200 with a valid-but-empty base64 audio string. It billed
    for that. Flipping this to `billed=False` used to leave 606 tests green.
    """
    recorder = []
    provider = _provider(monkeypatch, {"audios": [""]}, recorder)
    result = await provider.speak("hello there")

    assert result.ok is False
    assert result.error == "empty_audio"
    assert result.billed is True, (
        "a billed TTS call that returned empty audio reports $0 — the caller "
        "records nothing and the ceiling never sees it"
    )
    assert result.billed_chars > 0, "billed but reporting zero characters"
