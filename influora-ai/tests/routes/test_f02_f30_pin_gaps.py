"""The seven pins Priya's round-5 mutation sweep found missing.

Every fix below was already correct in code. What was missing was a test that
goes red when it is reverted — and for F-09 and F-11 the tests that existed were
worse than none: they monkeypatched the callee, wrapped it in
`anyio.to_thread.run_sync` INSIDE THE TEST BODY, and asserted the loop kept
ticking. That asserts a property of anyio, not of these routes, and it read as
coverage. Both are rewritten here to drive the real route.

Each test in this file has been confirmed to fail when its fix is reverted.
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import time
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth.service_token import AuthError, VerifiedToken
from app.costs import spend_tracker
from app.providers.claude import ClaudeStreamEvent

SERVICE_ROOT = pathlib.Path(__file__).resolve().parents[2]


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    monkeypatch.delenv("AI_SPEND_KILL_SWITCH", raising=False)
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()
    yield
    await spend_tracker.reset_for_testing()
    await spend_tracker.reset_reservations_for_testing()


def _multipart_request(fields: dict, file_field: tuple[str, str, bytes]) -> Request:
    """A real multipart/form-data Request, so the route's own `await
    request.form()` runs — the point is to drive the ROUTE, not a stand-in."""
    boundary = "----pinboundary"
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        )
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
         "headers": [(b"content-type", f"multipart/form-data; boundary={boundary}".encode()),
                     (b"content-length", str(len(raw)).encode())],
         "query_string": b"", "client": ("test", 0)},
        receive,
    )


def _request(body: dict, host: str = "1.2.3.4") -> Request:
    raw = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": raw, "more_body": False}

    return Request(
        {"type": "http", "method": "POST", "path": "/x", "headers": [],
         "query_string": b"", "client": (host, 0)},
        receive,
    )


# ---------------------------------------------------------------------------
# 1. F-02 — the tool loop must accumulate usage BEFORE the cancellation check
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f02_loop_carries_usage_on_the_client_disconnected_event():
    """`loop.py` accumulates usage before checking `is_cancelled`. Swap the
    order and a disconnect emits `usage=None` instead of the tokens the
    provider already billed — F-02 verbatim, tokens burned and $0 recorded.

    claude.py's flush and chat.py's settle are pinned; this third half was not.
    """
    from app.tools.loop import ToolLoopContext, run_tool_loop

    cancelled = {"now": False}

    class _Provider:
        async def stream_turn(self, **kwargs):
            yield ClaudeStreamEvent(type="text", text="Scanning")
            cancelled["now"] = True
            yield ClaudeStreamEvent(
                type="usage",
                usage={"input_tokens": 8000, "output_tokens": 300, "partial": True},
                stop_reason="client_disconnected",
            )

    ctx = ToolLoopContext(
        workspace_id="ws1", onbehalf_jwt="j", max_iterations=6,
        max_tokens=1024, max_tokens_retry=2048,
    )
    events = [
        e async for e in run_tool_loop(
            claude=_Provider(), spring=None, system_blocks=[], initial_messages=[],
            ctx=ctx, is_cancelled=lambda: cancelled["now"],
        )
    ]

    terminal = [e for e in events if e.type in ("error", "done")]
    assert terminal, "the loop never terminated"
    assert terminal[-1].error_code == "client_disconnected"
    assert terminal[-1].usage is not None, (
        "the disconnect event carries no usage — the provider billed these tokens "
        "and the caller will record $0"
    )
    assert terminal[-1].usage["output_tokens"] == 300


# ---------------------------------------------------------------------------
# 2. F-04 — the pricing table must be keyed by LITERAL model ids
# ---------------------------------------------------------------------------


def test_f04_an_env_override_cannot_be_priced_at_the_old_models_rate():
    """Re-key PRICING_TABLE to the CLAUDE_MODEL constant and, with that env var
    pointed at Opus, a 1M/1M turn bills $18.00 instead of $90.00 — the 5x
    under-bill, restored. No test loaded pricing under an env override, so
    nothing could see it.

    Run in a subprocess: proving this needs `app.config` imported with the env
    var already set, and reloading it in-process hands other modules a stale
    `get_settings`.
    """
    import subprocess
    import sys

    probe = (
        "import sys; sys.path.insert(0, '.');"
        "from app.config import CLAUDE_MODEL;"
        "from app.costs.pricing import estimate_cost_usd, PRICING_TABLE;"
        "u={'input_tokens':1000000,'output_tokens':1000000};"
        "hit = CLAUDE_MODEL in PRICING_TABLE;"
        "print('KEY_PRESENT', hit);"
        "\ntry:\n"
        "    print('COST', estimate_cost_usd(CLAUDE_MODEL, u))\n"
        "except ValueError as exc:\n"
        "    print('RAISED', type(exc).__name__)\n"
    )
    # An id that is deliberately NOT in the table. If the table were keyed by the
    # CLAUDE_MODEL *constant* — which is what F-04 was — this key would exist and
    # the lookup would hit, silently pricing an unknown model at a known rate.
    # Keyed by literals, it must miss and raise.
    env = dict(os.environ, CLAUDE_MODEL="claude-not-a-real-model-9")
    out = subprocess.run(
        [sys.executable, "-c", probe], capture_output=True, text=True, env=env,
        cwd=str(SERVICE_ROOT), check=False,
    )
    assert out.returncode == 0, out.stderr[-2000:]
    assert "KEY_PRESENT False" in out.stdout, (
        "PRICING_TABLE contains a key that moved with the CLAUDE_MODEL env var — "
        "the lookup always hits, which is F-04's 'fails loud' comment being false"
    )
    # It is still PRICED — `_resolve_rate` falls back to the most expensive row
    # for the three env-overridable constants so spend is never silently lost
    # (F-04 round 2). What matters is that it cannot be priced at the OLD
    # model's cheaper rate: a constant-keyed table would have billed Sonnet's
    # $18.00 here.
    unpriced_cost = Decimal(out.stdout.split("COST")[1].strip())
    assert unpriced_cost != Decimal("18.00"), (
        "an unpriced CLAUDE_MODEL override billed at the Sonnet rate — the 5x "
        "under-bill F-04 names"
    )
    assert unpriced_cost == Decimal("90.00"), (
        f"expected the conservative most-expensive fallback, got {unpriced_cost}"
    )

    # And with a REAL id, the literal-keyed table prices it correctly: Opus at
    # Opus's rate, not the Sonnet rate the old table would have applied.
    env = dict(os.environ, CLAUDE_MODEL="claude-opus-4-1-20250805")
    out = subprocess.run(
        [sys.executable, "-c", probe], capture_output=True, text=True, env=env,
        cwd=str(SERVICE_ROOT), check=False,
    )
    assert out.returncode == 0, out.stderr[-2000:]
    cost = out.stdout.split("COST")[1].strip()
    assert Decimal(cost) == Decimal("90.00"), (
        f"CLAUDE_MODEL=Opus billed {cost} for a 1M/1M turn instead of 90.00 — the "
        "5x under-bill F-04 names"
    )


# ---------------------------------------------------------------------------
# 3. F-05a — the trend_tag ROUTE, not just the gate helper
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f05_the_trend_tag_route_is_refused_when_its_own_bucket_is_over_cap(monkeypatch):
    """The existing test called `check_spend_gate()` directly and never invoked
    the route, so reverting `trend_tag.py` to a bare `check_spend_gate()` — the
    audit's exact wording — stayed green."""
    import app.routes.trend_tag as trend_tag_route
    from app.config import get_settings

    monkeypatch.setenv("TREND_TAG_INGEST_SECRET", "secret")
    monkeypatch.setenv("TREND_TAG_RATE_LIMIT_PER_MINUTE", "0")
    monkeypatch.setenv("WORKSPACE_DAILY_HARD_CAP_USD", "1.0")
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "10000.0")
    get_settings.cache_clear()

    # Spend it out on the route's OWN bucket — nothing else is over cap.
    await spend_tracker.record_spend(
        Decimal("1.50"), trend_tag_route.TREND_TAG_SPEND_BUCKET
    )

    called = {"model": False}

    def _boom():
        called["model"] = True
        raise AssertionError("the model was called despite the workspace cap")

    monkeypatch.setattr(trend_tag_route, "_get_claude", _boom)

    result = await trend_tag_route.trendspark_tag(
        _request({"trend_text": "diwali sale sound", "category": "music"}),
        authorization="Bearer secret",
    )

    assert result["data"]["recovered"] is False, (
        "the route ran the model with its own spend bucket over cap — it is "
        "still gating on the global ceiling only"
    )
    assert called["model"] is False


# ---------------------------------------------------------------------------
# 4. F-05b — the chat route must actually reserve
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_f05_the_chat_route_holds_budget_across_its_tool_loop(monkeypatch):
    """Drop `reserve_usd` from chat.py and the highest-traffic route holds
    nothing between the gate and the recorded spend. Observed behaviourally:
    while a chat turn is in flight, the reserved total must be non-zero."""
    import app.routes.chat as chat_route
    from app.config import get_settings
    from app.tools.loop import LoopEvent

    get_settings.cache_clear()
    observed = {"reserved_mid_turn": Decimal(0)}

    async def _loop_that_checks_the_hold(**kwargs):
        observed["reserved_mid_turn"] = await spend_tracker.get_reserved_global()
        yield LoopEvent(type="token", text="hi")
        yield LoopEvent(type="done", finish_reason="stop",
                        usage={"input_tokens": 10, "output_tokens": 5})

    request = _request({
        "workspace_id": "ws-f05b", "conversation_id": "c1",
        "stream_token": "x", "conversation": [], "onbehalf_jwt": "j",
    })
    request.is_disconnected = AsyncMock(return_value=False)
    spring = MagicMock()
    spring.persist_assistant_message = AsyncMock()
    spring.release_turn_credit = AsyncMock()

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=VerifiedToken(
        workspace_id="ws-f05b", scope="chat:stream", subject="u",
        conversation_id="c1", claims={"messageId": "m1"},
    ))), patch.object(chat_route, "run_tool_loop", _loop_that_checks_the_hold), patch.object(
        chat_route, "_get_spring", MagicMock(return_value=spring)
    ):
        response = await chat_route.chat(request, authorization="Bearer x")
        async for _chunk in response.body_iterator:
            pass

    assert observed["reserved_mid_turn"] > Decimal(0), (
        "the chat route held no budget while its tool loop was running — the "
        "F-05 race is open on the highest-traffic route"
    )
    # …and it is settled by the time the turn ends.
    assert await spend_tracker.get_reserved_global() == Decimal(0)


# ---------------------------------------------------------------------------
# 5 + 6. F-09 / F-11 — drive the ROUTES, not anyio
# ---------------------------------------------------------------------------


async def _loop_ticks_while(coro) -> int:
    """Run `coro` and count how many times the event loop got a turn."""
    ticks = {"n": 0}

    async def heartbeat():
        for _ in range(60):
            await asyncio.sleep(0.005)
            ticks["n"] += 1

    beat = asyncio.create_task(heartbeat())
    try:
        await coro
    except Exception as exc:  # noqa: BLE001 - the route's own error is not what we measure
        print(f"(route raised {type(exc).__name__}, which is fine — we measure loop ticks)")
    beat.cancel()
    try:
        await beat
    except asyncio.CancelledError:
        pass
    return ticks["n"]


@pytest.mark.asyncio
async def test_f09_the_voice_route_verifies_the_token_off_the_event_loop(monkeypatch):
    """Rewritten: this calls the REAL route. The previous version wrapped the
    offload itself and asserted a property of anyio — reverting voice.py to a
    direct blocking call left it green."""
    import app.routes.voice as voice_route

    def slow_blocking_verify(*args, **kwargs):
        time.sleep(0.25)  # a JWKS fetch on an attacker-chosen kid
        raise AuthError(401, "invalid_token", "nope")

    monkeypatch.setattr(voice_route, "verify_token", slow_blocking_verify)

    ticks = await _loop_ticks_while(
        voice_route.voice_transcribe(
            _multipart_request({"workspace_id": "ws1"}, ("audio", "a.wav", b"RIFF....WAVEfmt ")),
            authorization="Bearer x",
        )
    )
    assert ticks >= 10, (
        f"only {ticks} event-loop turns during a 250ms token verification — the "
        "route is verifying on the event loop again"
    )


@pytest.mark.asyncio
async def test_f11_the_analyze_site_route_fetches_off_the_event_loop(monkeypatch):
    """Rewritten: this calls the REAL `perform_site_analysis`. The previous
    version wrapped `guarded_fetch` in the test body, so reverting
    analyze_site.py to a direct blocking call left it green."""
    import app.routes.analyze_site as analyze_site_route
    from app.config import get_settings

    get_settings.cache_clear()

    def slow_blocking_fetch(url, **kwargs):
        time.sleep(0.25)  # SSRF guard: blocking DNS + a synchronous httpx hop
        return b"<html><body>skincare</body></html>", url

    monkeypatch.setattr(analyze_site_route, "guarded_fetch", slow_blocking_fetch)
    gemini = MagicMock()
    gemini.classify_site = AsyncMock(return_value=MagicMock(ok=False, error="stub", usage=None))
    monkeypatch.setattr(analyze_site_route, "_get_gemini", lambda: gemini)

    ticks = await _loop_ticks_while(
        analyze_site_route.perform_site_analysis(url="https://brand.test/", workspace_id="ws1")
    )
    assert ticks >= 10, (
        f"only {ticks} event-loop turns during a 250ms guarded fetch — one slow "
        "URL still stalls every concurrent SSE stream in this worker"
    )


# ---------------------------------------------------------------------------
# 7. F-30 — the SCORER, not just the aggregator
# ---------------------------------------------------------------------------


def test_f30_the_brand_safety_scorer_marks_an_empty_expected_case_unscored():
    """The aggregator was pinned; the scorer that feeds it was not. Force
    `category_scored: 1.0` and the 5 empty-expected brand-safety cases fold back
    into the discrimination mean (scored_cases 7 -> 12) — the
    0.85-bar-is-really-0.74 dilution, restored. The aggregator test hand-feeds
    the flag as a literal, so it can never observe the scorer producing it."""
    from app.tools.schemas import GARM_CATEGORIES
    from evals.run_eval import score_brand_safety

    all_floor = {"garm_flags": [
        {"category": c, "risk": "floor", "rationale": "ok"} for c in GARM_CATEGORIES
    ]}

    # Nothing expected, nothing flagged — agreeing costs nothing to discriminate.
    trivial = score_brand_safety({"unsafe": False, "flagged_categories": []}, all_floor)
    assert trivial["category_f1"] == 1.0
    assert trivial["category_scored"] == 0.0, (
        "an empty-expected case is being counted as a discriminating one — it "
        "props the F1 mean back up and the stated 0.85 bar becomes ~0.74"
    )

    # A case that genuinely discriminates IS scored.
    real = score_brand_safety(
        {"unsafe": True, "flagged_categories": ["terrorism"]},
        {"garm_flags": [
            {"category": c, "risk": "high" if c == "terrorism" else "floor", "rationale": "x"}
            for c in GARM_CATEGORIES
        ]},
    )
    assert real["category_scored"] == 1.0
