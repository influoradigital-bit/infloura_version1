"""Priya round-6 blockers: F-05 and F-09 pinned on EVERY route, not one each.

The audit's own worked example for F-05 is *200 concurrent brand-safety
requests*. `brand_safety.py` had no route-level pin — setting `reserve_usd=None`
there restored the failure verbatim (200 of 200 admitted against $0.10 of
headroom) with 615 tests green. Same for analyze_site, creator_suggestion,
trendspark and both voice endpoints. Only chat and trend_tag were pinned.

F-09 consequence (b) is *"awaited un-offloaded from six routes"*. Only
`voice_transcribe` was pinned; `/chat` and `voice_speak` were not, and neither
was the JWKS client timeout — control #3 of the finding's own three controls.

This is the third recurrence of the same shape (ledger F-0085), so these tests
are written to cover the whole surface rather than the one site that happened
to be reviewed.
"""

from __future__ import annotations

import asyncio
import json
import time
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import AuthError, VerifiedToken
from app.costs import spend_tracker
from app.costs.gate import SpendGateResult


@pytest.fixture(autouse=True)
async def _reset():
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()
    yield
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()


def _json_request(body: dict, path: str = "/x") -> Request:
    raw = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": raw, "more_body": False}

    return Request(
        {"type": "http", "method": "POST", "path": path,
         "headers": [(b"content-type", b"application/json")],
         "query_string": b"", "client": ("test", 0)},
        receive,
    )


def _multipart_request(fields: dict, file_field) -> Request:
    boundary = "----pin6"
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
        for k, v in fields.items()
    ]
    name, filename, blob = file_field
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; '
        f'filename="{filename}"\r\nContent-Type: audio/wav\r\n\r\n'.encode() + blob + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    raw = b"".join(parts)

    async def receive():
        return {"type": "http.request", "body": raw, "more_body": False}

    return Request(
        {"type": "http", "method": "POST", "path": "/voice/transcribe",
         "headers": [(b"content-type", f"multipart/form-data; boundary={boundary}".encode())],
         "query_string": b"", "client": ("test", 0)},
        receive,
    )


# ---------------------------------------------------------------------------
# F-05 — every gated route must hold budget across its provider call
# ---------------------------------------------------------------------------


def _gate_spy(record: list):
    """Stands in for check_spend_gate and records the reservation it was asked
    for. Observing the CALL is behavioural — it fails on `reserve_usd=None`
    regardless of how the source is written."""

    async def spy(workspace_id=None, **kwargs):
        record.append({"workspace_id": workspace_id, **kwargs})
        return SpendGateResult(allowed=True, reservation=None)

    return spy


async def _drive(module_name: str, monkeypatch):
    """Invoke each gated route with its own minimal valid input, everything past
    the gate stubbed. Returns the route module so the caller can inspect the
    spy. Behavioural: we watch the CALL the route makes, not its source."""
    import importlib

    from app.config import get_settings

    get_settings.cache_clear()
    module = importlib.import_module(f"app.routes.{module_name}")
    verified = VerifiedToken(workspace_id="ws1", scope="service", subject="s",
                             conversation_id=None, claims={"messageId": "m1"})

    if module_name == "analyze_site":
        monkeypatch.setattr(module, "guarded_fetch", lambda url, **kw: (b"<html></html>", url))
        monkeypatch.setattr(module, "_get_gemini", lambda: MagicMock(
            classify_site=AsyncMock(return_value=MagicMock(ok=False, error="stub", usage=None))))
        await module.perform_site_analysis(url="https://b.test/", workspace_id="ws1")

    elif module_name == "brand_safety":
        monkeypatch.setattr(module, "verify_token", lambda *a, **k: verified)
        monkeypatch.setattr(module, "_get_claude", lambda: MagicMock(
            complete_with_forced_tool=AsyncMock(return_value=MagicMock(ok=False, error="s", usage=None))))
        await module.brand_safety(
            _json_request({"workspace_id": "ws1", "items": [{"content_id": "c", "caption": "hi"}]}),
            authorization="Bearer x")

    elif module_name == "creator_suggestion":
        monkeypatch.setattr(module, "verify_creator_token", lambda *a, **k: MagicMock())
        monkeypatch.setattr(module, "_get_claude", lambda: MagicMock(
            complete_text=AsyncMock(return_value=MagicMock(ok=False, error="s", usage=None))))
        await module.creator_suggestion(
            _json_request({"creator_profile_id": "cp1", "trend_text": "t"}),
            authorization="Bearer x")

    elif module_name == "trendspark":
        monkeypatch.setattr(module, "verify_token", lambda *a, **k: verified)
        monkeypatch.setattr(module, "_get_claude", lambda: MagicMock(
            complete_text=AsyncMock(return_value=MagicMock(ok=False, error="s", usage=None))))
        await module.trendspark_nudge(
            _json_request({"workspace_id": "ws1", "brand_name": "b", "trend_text": "t",
                           "mode": "OWN_CONTENT"}),
            authorization="Bearer x")

    elif module_name == "trend_tag":
        monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "secret")
        monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")
        get_settings.cache_clear()
        monkeypatch.setattr(module, "_get_claude", lambda: MagicMock(
            complete_text=AsyncMock(return_value=MagicMock(ok=False, error="s", usage=None))))
        await module.trendspark_tag(
            _json_request({"trend_text": "diwali sound", "category": "music"}),
            authorization="Bearer secret")

    elif module_name == "voice":
        monkeypatch.setattr(module, "verify_token", lambda *a, **k: verified)
        monkeypatch.setattr(module, "_get_sarvam", lambda: MagicMock(
            transcribe=AsyncMock(return_value=MagicMock(ok=False, error="e", billed=False)),
            speak=AsyncMock(return_value=MagicMock(ok=False, error="e", billed=False, billed_chars=0))))
        await module.voice_transcribe(
            _multipart_request({"workspace_id": "ws1"}, ("audio", "a.wav", b"RIFF")),
            authorization="Bearer x")
        await module.voice_speak(
            _json_request({"workspace_id": "ws1", "text": "hello"}), authorization="Bearer x")

    elif module_name == "chat":
        from app.tools.loop import LoopEvent

        async def _loop(**kwargs):
            yield LoopEvent(type="done", finish_reason="stop", usage=None)

        monkeypatch.setattr(module, "verify_token_async", AsyncMock(return_value=verified))
        monkeypatch.setattr(module, "run_tool_loop", _loop)
        monkeypatch.setattr(module, "_get_spring", MagicMock(return_value=MagicMock(
            persist_assistant_message=AsyncMock(), release_turn_credit=AsyncMock())))
        request = _json_request({"workspace_id": "ws1", "conversation_id": "c1",
                                 "stream_token": "x", "conversation": [], "onbehalf_jwt": "j"})
        request.is_disconnected = AsyncMock(return_value=False)
        response = await module.chat(request, authorization="Bearer x")
        async for _chunk in response.body_iterator:
            pass

    return module


ROUTES = ["analyze_site", "brand_safety", "creator_suggestion", "trendspark",
          "trend_tag", "voice", "chat"]


@pytest.mark.parametrize("module_name", ROUTES)
@pytest.mark.asyncio
async def test_f05_every_gated_route_asks_for_a_reservation(module_name, monkeypatch):
    """A route that gates spend but reserves nothing is read-then-spend — the
    exact shape F-05 names, and the shape that survived on six of eight routes.

    Behavioural: the gate is replaced with a spy and the REAL route is invoked;
    the assertion is on the call the route actually made.
    """
    import importlib

    calls: list = []
    module = importlib.import_module(f"app.routes.{module_name}")
    monkeypatch.setattr(module, "check_spend_gate", _gate_spy(calls))

    try:
        await _drive(module_name, monkeypatch)
    except Exception as exc:  # noqa: BLE001 - everything past the gate is stubbed and may 502
        print(f"({module_name} raised {type(exc).__name__} past the gate — irrelevant here)")

    assert calls, f"{module_name} never called the spend gate"
    for call in calls:
        assert call.get("reserve_usd") not in (None, 0), (
            f"{module_name} gated spend but reserved nothing — it holds no budget "
            f"between the check and the recorded spend (call: {call})"
        )


@pytest.mark.asyncio
async def test_f05_brand_safety_the_audits_own_example_holds_budget(monkeypatch):
    """The audit's worked example verbatim: *200 concurrent brand-safety
    requests at ~$0.07 against a $15 ceiling with $14.90 spent*. Setting
    `brand_safety.py`'s `reserve_usd=None` restored 200-of-200 admitted with the
    suite green. Driven through the REAL route."""
    import app.routes.brand_safety as bs
    from app.config import get_settings

    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "15.0")
    monkeypatch.setenv("AI_RESERVATION_PER_CALL_USD", "0.07")
    get_settings.cache_clear()
    await spend_tracker.record_spend(Decimal("14.90"), workspace_id=None)

    claude = MagicMock()
    claude.complete_with_forced_tool = AsyncMock(
        side_effect=lambda **kw: MagicMock(ok=False, error="stub", usage=None)
    )
    monkeypatch.setattr(bs, "_get_claude", lambda: claude)
    monkeypatch.setattr(bs, "verify_token", lambda *a, **k: VerifiedToken(
        workspace_id="ws1", scope="service", subject="s", conversation_id=None, claims={}))

    body = {"workspace_id": "ws1", "items": [{"content_id": "c1", "caption": "hi"}]}

    async def one_call():
        try:
            return await bs.brand_safety(_json_request(body), authorization="Bearer x")
        except Exception as exc:  # noqa: BLE001 - a 503 from the gate is the pass case
            return exc

    results = await asyncio.gather(*[one_call() for _ in range(200)])
    provider_calls = claude.complete_with_forced_tool.await_count

    assert provider_calls <= 2, (
        f"{provider_calls} of 200 concurrent brand-safety requests reached the "
        "provider against $0.10 of headroom — read-then-spend, F-05's own example"
    )
    assert len(results) == 200


# ---------------------------------------------------------------------------
# F-09 — the event-loop stall, pinned on every route that verifies a token
# ---------------------------------------------------------------------------


async def _loop_ticks_while(coro) -> int:
    ticks = {"n": 0}

    async def heartbeat():
        for _ in range(80):
            await asyncio.sleep(0.005)
            ticks["n"] += 1

    beat = asyncio.create_task(heartbeat())
    try:
        await coro
    except Exception as exc:  # noqa: BLE001 - the route's own error is not what we measure
        print(f"(route raised {type(exc).__name__} — we measure loop ticks, not the verdict)")
    beat.cancel()
    try:
        await beat
    except asyncio.CancelledError:
        pass
    return ticks["n"]


@pytest.mark.asyncio
async def test_f09_the_chat_route_verifies_off_the_event_loop(monkeypatch):
    """`/chat` is the highest-traffic route and was unpinned. Reverting
    `service_token.py`'s async wrapper to a direct call parked the loop for the
    whole verification — measured 0 ticks during a 250ms verify, 615 green."""
    import app.routes.chat as chat_route
    from app.auth import service_token

    def slow_blocking_verify(*args, **kwargs):
        time.sleep(0.25)
        raise AuthError(401, "invalid_token", "nope")

    monkeypatch.setattr(service_token, "verify_token", slow_blocking_verify)
    monkeypatch.setattr(chat_route, "verify_token_async", service_token.verify_token_async)

    request = _json_request({
        "workspace_id": "ws1", "conversation_id": "c1", "stream_token": "x",
        "conversation": [], "onbehalf_jwt": "j",
    }, path="/chat")
    request.is_disconnected = AsyncMock(return_value=False)

    with patch.object(chat_route, "_get_spring", MagicMock(return_value=MagicMock())):
        ticks = await _loop_ticks_while(chat_route.chat(request, authorization="Bearer x"))

    assert ticks >= 10, (
        f"only {ticks} event-loop turns during a 250ms token verification on /chat — "
        "the highest-traffic route is verifying on the event loop"
    )


@pytest.mark.asyncio
async def test_f09_the_voice_speak_route_verifies_off_the_event_loop(monkeypatch):
    """The sixth route F-09 names. Unpinned until now."""
    import app.routes.voice as voice_route

    def slow_blocking_verify(*args, **kwargs):
        time.sleep(0.25)
        raise AuthError(401, "invalid_token", "nope")

    monkeypatch.setattr(voice_route, "verify_token", slow_blocking_verify)

    ticks = await _loop_ticks_while(
        voice_route.voice_speak(
            _json_request({"workspace_id": "ws1", "text": "hello"}), authorization="Bearer x"
        )
    )
    assert ticks >= 10, (
        f"only {ticks} event-loop turns during a 250ms verify on /voice/speak"
    )


@pytest.mark.asyncio
async def test_f09_the_transcribe_route_verifies_off_the_event_loop(monkeypatch):
    import app.routes.voice as voice_route

    def slow_blocking_verify(*args, **kwargs):
        time.sleep(0.25)
        raise AuthError(401, "invalid_token", "nope")

    monkeypatch.setattr(voice_route, "verify_token", slow_blocking_verify)

    ticks = await _loop_ticks_while(
        voice_route.voice_transcribe(
            _multipart_request({"workspace_id": "ws1"}, ("audio", "a.wav", b"RIFF")),
            authorization="Bearer x",
        )
    )
    assert ticks >= 10


def test_f09_the_jwks_client_carries_the_configured_timeout(monkeypatch):
    """Control #3 of F-09's three stated controls was unpinned: leaving
    PyJWKClient's 30s default in place kept the suite green. Assert the value
    the client was actually constructed with."""
    from app.auth.service_token import HttpJwksSource
    from app.config import get_settings

    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.setenv("SPRING_JWKS_TIMEOUT_SECONDS", "2")
    get_settings.cache_clear()

    source = HttpJwksSource("https://spring.test/.well-known/jwks.json", 300)
    inner = source._client
    configured = getattr(inner, "timeout", None)
    assert configured is not None, "PyJWKClient was constructed without a timeout"
    assert configured <= 5, (
        f"JWKS client timeout is {configured}s — an unauthenticated request with an "
        "unknown kid can tie up a worker for that long"
    )
    assert configured != 30, "PyJWKClient's 30-second default is back"
