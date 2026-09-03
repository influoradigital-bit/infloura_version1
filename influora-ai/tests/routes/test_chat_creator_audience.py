"""Meera for Creators Phase A — /chat CREATOR audience wiring (A4, A6, A8).

`verify_token_async` is mocked (same pattern as test_chat_workspace_hard_cap.py);
Spring, Claude and the tool loop are mocked. Covers:

- audience derivation: the VERIFIED token's claim ONLY (fix round 1); never
  the request body, never the unverifiable on-behalf JWT; a `chat:stream`
  token with no userType claim is refused 403, a Spring-only `service` token
  keeps the brand default
- fix round 1 regression: a creator stream token + no `onbehalf_jwt` in the
  body must NEVER reach `run_tool_loop` (the old path derived BRAND, forwarded
  the stream token to Spring, got a 401, failed OPEN to an empty brand Block
  B and offered the brand tool set to an unconsented, over-cap creator)
- consent gate: first creator turn without consent -> 403 CONSENT_REQUIRED,
  zero provider calls; consented -> the turn proceeds
- creator context fails CLOSED (503; 401/403 from Spring -> 403
  context_unauthorized) and audience mismatch fails closed (403)
- brand context: 5xx/network still fail OPEN to an empty Block B; 401/403
  from Spring fail CLOSED (403 context_unauthorized, no prompt, no tools)
- A8 monthly cap -> 429 with the friendly message, zero provider calls
- a CREATOR turn runs the tool loop with an EMPTY tool set and the creator
  persona; a BRAND turn is byte-for-byte the pre-Phase-A path
"""

from __future__ import annotations

import base64
import json
from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth import audience as audience_module
from app.auth.audience import derive_audience
from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringCallError, SpringResponse
from app.config import get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import CREATOR_CAP_MESSAGE
from app.prompt.creator_persona import MEERA_CREATOR_PERSONA
from app.prompt.persona import MEERA_PERSONA
from app.routes import chat as chat_route
from app.tools.loop import LoopEvent

CREATOR_ID = "creator-user-chat-001"


def _b64url(obj: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


def _onbehalf_jwt(user_type: str | None) -> str:
    payload: dict[str, Any] = {"sub": "u1"}
    if user_type is not None:
        payload["userType"] = user_type
    return f"{_b64url({'alg': 'HS256', 'typ': 'JWT'})}.{_b64url(payload)}.signature"


def _make_request(body: dict) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/chat",
        "headers": [],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _verified(
    claims: dict | None = None,
    workspace_id: str = CREATOR_ID,
    scope: str = "chat:stream",
) -> VerifiedToken:
    """A verified stream token. Defaults to the shape StreamTokenService.mint
    now produces for a creator: `userType=CREATOR` IN THE VERIFIED CLAIMS
    (fix round 1). Pass `claims={}` explicitly to model the pre-fix mint."""
    return VerifiedToken(
        workspace_id=workspace_id,
        scope=scope,
        subject="u1",
        conversation_id=None,
        claims={"userType": "CREATOR"} if claims is None else claims,
    )


def _body(user_type: str | None = "CREATOR", **extra) -> dict:
    body = {
        "workspace_id": CREATOR_ID,
        "conversation_id": "conv-1",
        "onbehalf_jwt": _onbehalf_jwt(user_type),
        "conversation": [{"role": "user", "content": "hi"}],
    }
    body.update(extra)
    return body


def _creator_context(consented: bool = True, **extra) -> dict:
    ctx = {
        "workspace_id": CREATOR_ID,
        "audience": "CREATOR",
        "display_name": "Priya Shah",
        "first_name": "Priya",
        "city": "Pune",
        "tier": "MICRO",
        "categories": ["Fashion"],
        "creator_language": "hi-IN",
        "brand_tone": "FRIENDLY",
        "floors": {"reel_floor": "9,999", "story_set_floor": "800", "post_floor": "1,500"},
        "metrics_summary": {"followers": "12,400 followers"},
        "deals_summary": {"active_count": 1, "completed_count": 2, "total_earned_inr": "5,000"},
        "approval_level": 0,
        "represented": False,
        "identity": {"kyc_done": True, "gstin_present": False},
        "consent_accepted": consented,
    }
    ctx.update(extra)
    return ctx


def _spring(context: dict | None = None, error: Exception | None = None) -> MagicMock:
    spring = MagicMock()
    if error is not None:
        spring.get_meera_context = AsyncMock(side_effect=error)
    else:
        data = context or {}
        spring.get_meera_context = AsyncMock(
            return_value=SpringResponse(status_code=200, data=data, raw={"data": data})
        )
    spring.persist_assistant_message = AsyncMock(
        return_value=SpringResponse(status_code=200, data={}, raw={})
    )
    spring.release_turn_credit = AsyncMock(
        return_value=SpringResponse(status_code=200, data={}, raw={})
    )
    return spring


def _fake_tool_loop(recorded: dict):
    """Replaces `run_tool_loop`: records its kwargs and yields one text token
    plus a done event, so the SSE generator runs its full success path."""

    async def _loop(**kwargs):
        recorded.update(kwargs)
        yield LoopEvent(type="token", text="Namaste Priya.")
        yield LoopEvent(
            type="done",
            finish_reason="stop",
            usage={"input_tokens": 10, "output_tokens": 5},
            stop_reason="end_turn",
        )

    return _loop


async def _drain(response) -> str:
    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk if isinstance(chunk, str) else chunk.decode())
    return "".join(chunks)


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in ("AI_SPEND_KILL_SWITCH", "WORKSPACE_DAILY_HARD_CAP_USD", "AI_CREATOR_MONTHLY_CAP_USD", "REDIS_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    for var in ("AI_DAILY_SPEND_CEILING_USD", "AI_CREATOR_MONTHLY_CAP_USD"):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


# ---------------------------------------------------------------- derive_audience


def test_derive_audience_reads_verified_claims_only():
    assert derive_audience({"userType": "BRAND"}) == "BRAND"
    assert derive_audience({"userType": "CREATOR"}) == "CREATOR"
    assert derive_audience({"audience": "creator"}) == "CREATOR"
    assert derive_audience({"user_type": " Creator "}) == "CREATOR"
    # No recognised claim -> None (the ROUTE decides what None means; the
    # old module-level BRAND fallback is gone).
    assert derive_audience({}) is None
    assert derive_audience(None) is None
    assert derive_audience({"userType": "ADMIN"}) is None
    assert derive_audience({"userType": 42}) is None
    assert derive_audience({"messageId": "m1"}) is None


def test_unverified_jwt_fallback_is_deleted():
    """Fix round 1: the module must not offer any way to read an audience off
    an unverified JWT, and `derive_audience` must not accept one."""
    assert not hasattr(audience_module, "unverified_jwt_claims")
    assert "base64" not in dir(audience_module)
    with pytest.raises(TypeError):
        derive_audience({"userType": "BRAND"}, _onbehalf_jwt("CREATOR"))  # type: ignore[call-arg]


# ---------------------------------------------------------------- fix round 1 regression


@pytest.mark.asyncio
async def test_creator_stream_token_without_onbehalf_jwt_never_reaches_tool_loop():
    """The reproduced probe: an unconsented, over-cap creator holding a valid
    `chat:stream` token omits `onbehalf_jwt` from the body. The route used to
    fall back to the bearer, derive BRAND, forward the stream token to Spring
    (401), fail OPEN to an empty brand Block B and offer the brand tool set.
    Now the verified token says CREATOR and nothing downstream runs.

    Gate fix round 1 (Q7): the creator cap is now checked AFTER the context
    fetch (the per-creator override lives in the context), so on this probe
    the context fetch fails first -- Spring 401s the forwarded stream token
    -- and the turn is refused 403 context_unauthorized. Still fail-closed:
    no persona, no tools, no provider call; the daily hold is released."""
    await spend_tracker.record_creator_spend(Decimal("0.75"), CREATOR_ID)
    # Spring rejects a stream token presented as an on-behalf token.
    spring = _spring(error=SpringCallError(401, "ON_BEHALF_JWT_INVALID", "bad token"))
    body = _body()
    del body["onbehalf_jwt"]
    request = _make_request(body)

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude, \
         patch.object(chat_route, "run_tool_loop") as mock_loop:
        response = await chat_route.chat(request, authorization="Bearer creator-stream-token")

    assert response.status_code == 403
    assert json.loads(response.body)["error"]["code"] == "context_unauthorized"
    mock_loop.assert_not_called()
    mock_claude.assert_not_called()
    assert await spend_tracker.get_reserved_global() == Decimal(0)
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


@pytest.mark.asyncio
async def test_creator_stream_token_without_onbehalf_jwt_under_cap_fails_closed_on_spring_401():
    """Same probe, creator under cap: the CREATOR context fetch forwards the
    stream token, Spring 401s, and the route answers 403 context_unauthorized
    -- never the brand persona, never an empty Block B, never a tool loop."""
    spring = _spring(error=SpringCallError(401, "ON_BEHALF_JWT_INVALID", "bad token"))
    body = _body()
    del body["onbehalf_jwt"]

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude, \
         patch.object(chat_route, "run_tool_loop") as mock_loop:
        response = await chat_route.chat(
            _make_request(body), authorization="Bearer creator-stream-token"
        )

    assert response.status_code == 403
    assert json.loads(response.body)["error"]["code"] == "context_unauthorized"
    mock_loop.assert_not_called()
    mock_claude.assert_not_called()
    # The context was requested as CREATOR (verified claim), with the bearer
    # forwarded -- and Spring's rejection ended the turn.
    spring.get_meera_context.assert_awaited_once_with(
        workspace_id=CREATOR_ID, audience="CREATOR", onbehalf_jwt="creator-stream-token"
    )


@pytest.mark.asyncio
async def test_stream_token_without_usertype_claim_is_refused_even_with_creator_onbehalf_jwt():
    """A pre-fix stream token shape (no userType claim) can no longer be
    steered by the body's on-behalf JWT in EITHER direction: it is refused
    before any Spring or provider call."""
    spring = _spring(_creator_context())

    for user_type in ("CREATOR", "BRAND", None):
        body = _body(user_type=user_type)
        with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified(claims={}))), \
             patch.object(chat_route, "_get_spring", return_value=spring), \
             patch.object(chat_route, "_get_claude") as mock_claude, \
             patch.object(chat_route, "run_tool_loop") as mock_loop:
            response = await chat_route.chat(_make_request(body), authorization=None)

        assert response.status_code == 403, user_type
        assert json.loads(response.body)["error"]["code"] == "audience_unverified"
        mock_loop.assert_not_called()
        mock_claude.assert_not_called()
        spring.get_meera_context.assert_not_awaited()


@pytest.mark.asyncio
async def test_service_token_without_usertype_keeps_brand_default():
    """A Spring-only `service` token (never held by a browser) still gets the
    pre-Phase-A brand path when it carries no userType."""
    spring = _spring({"display_name": "Brand Inc"})
    recorded: dict = {}
    verified = _verified(claims={}, workspace_id="ws-brand-001", scope="service")

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _fake_tool_loop(recorded)):
        response = await chat_route.chat(
            _make_request(_body(user_type="BRAND", workspace_id="ws-brand-001")), authorization=None
        )
        assert response.status_code == 200
        await _drain(response)

    spring.get_meera_context.assert_awaited_once_with(
        workspace_id="ws-brand-001", audience="BRAND", onbehalf_jwt=_onbehalf_jwt("BRAND")
    )
    assert recorded["system_blocks"][0]["text"].startswith(MEERA_PERSONA.splitlines()[0])


@pytest.mark.asyncio
async def test_brand_context_401_or_403_fails_closed_not_empty_block_b():
    """Fix round 1 (2): an auth rejection from Spring on the BRAND context
    fetch is no longer degraded to an empty Block B."""
    for status_code in (401, 403):
        spring = _spring(error=SpringCallError(status_code, "ON_BEHALF_JWT_INVALID", "bad token"))
        verified = _verified(claims={"userType": "BRAND"}, workspace_id="ws-brand-001")

        with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)), \
             patch.object(chat_route, "_get_spring", return_value=spring), \
             patch.object(chat_route, "_get_claude") as mock_claude, \
             patch.object(chat_route, "run_tool_loop") as mock_loop:
            response = await chat_route.chat(
                _make_request(_body(user_type="BRAND", workspace_id="ws-brand-001")), authorization=None
            )

        assert response.status_code == 403, status_code
        payload = json.loads(response.body)
        assert payload["error"]["code"] == "context_unauthorized"
        assert "escr" + "ow" not in payload["error"]["message"].lower()
        mock_loop.assert_not_called()
        mock_claude.assert_not_called()


@pytest.mark.asyncio
async def test_creator_context_401_maps_to_403_not_503():
    spring = _spring(error=SpringCallError(403, "FORBIDDEN", "not your workspace"))

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 403
    assert json.loads(response.body)["error"]["code"] == "context_unauthorized"
    mock_claude.assert_not_called()


# ---------------------------------------------------------------- consent gate (A6)


@pytest.mark.asyncio
async def test_first_creator_turn_without_consent_is_403_and_makes_no_provider_call():
    spring = _spring(_creator_context(consented=False))
    request = _make_request(_body())

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(request, authorization=None)

    assert response.status_code == 403
    payload = json.loads(response.body)
    assert payload["code"] == "CONSENT_REQUIRED"
    assert payload["action"] == "show_consent_screen"
    assert payload["error"]["code"] == "CONSENT_REQUIRED"
    assert "escr" + "ow" not in payload["message"].lower()
    mock_claude.assert_not_called()
    spring.get_meera_context.assert_awaited_once_with(
        workspace_id=CREATOR_ID, audience="CREATOR", onbehalf_jwt=_onbehalf_jwt("CREATOR")
    )


@pytest.mark.asyncio
async def test_missing_consent_key_fails_closed():
    ctx = _creator_context()
    del ctx["consent_accepted"]
    spring = _spring(ctx)

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 403
    assert json.loads(response.body)["code"] == "CONSENT_REQUIRED"
    mock_claude.assert_not_called()


def test_consent_accepted_reads_bool_or_timestamp_only():
    assert chat_route.consent_accepted({"consent_accepted": True})
    assert chat_route.consent_accepted({"consent_accepted_at": "2026-09-03T14:30:00Z"})
    assert not chat_route.consent_accepted({"consent_accepted": "true"})  # a string is not consent
    assert not chat_route.consent_accepted({"consent_accepted": False})
    assert not chat_route.consent_accepted({"consent_accepted_at": None})
    assert not chat_route.consent_accepted({"consent_accepted_at": "  "})
    assert not chat_route.consent_accepted({})
    assert not chat_route.consent_accepted(None)


# ---------------------------------------------------------------- fail closed


@pytest.mark.asyncio
async def test_creator_context_fetch_failure_fails_closed_not_empty_block_b():
    spring = _spring(error=SpringCallError(500, "spring_error", "boom"))

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 503
    assert json.loads(response.body)["error"]["code"] == "creator_context_unavailable"
    mock_claude.assert_not_called()


@pytest.mark.asyncio
async def test_audience_mismatch_from_spring_fails_closed():
    """The on-behalf JWT said CREATOR but Spring re-derived the principal as
    BRAND: no creator prompt may be built on an unverified routing hint."""
    spring = _spring({"audience": "BRAND", "display_name": "Brand Inc", "consent_accepted": True})

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 403
    assert json.loads(response.body)["error"]["code"] == "audience_mismatch"
    mock_claude.assert_not_called()


# ---------------------------------------------------------------- monthly cap (A8)


@pytest.mark.asyncio
async def test_creator_at_monthly_cap_gets_friendly_429_and_no_provider_call():
    await spend_tracker.record_creator_spend(Decimal("0.75"), CREATOR_ID)
    spring = _spring(_creator_context())

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 429
    payload = json.loads(response.body)
    assert payload["code"] == "CREATOR_MONTHLY_CAP_REACHED"
    assert payload["message"] == CREATOR_CAP_MESSAGE
    mock_claude.assert_not_called()
    # Q7: the cap is checked AFTER the (cheap, non-provider) context fetch so
    # the per-creator override in the payload applies -- exactly one fetch.
    spring.get_meera_context.assert_awaited_once()
    # A refused turn holds nothing on either ledger.
    assert await spend_tracker.get_reserved_global() == Decimal(0)
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


@pytest.mark.asyncio
async def test_per_creator_cap_override_in_context_raises_this_creators_allowance():
    """Q7 "who can raise it": support sets a per-creator override through the
    admin endpoint; Spring carries it in the CREATOR context payload as
    `ai_monthly_cap_usd` (string-rendered like every other number there).
    A creator over the process-wide default but under their own override
    proceeds; no redeploy involved."""
    await spend_tracker.record_creator_spend(Decimal("1.00"), CREATOR_ID)  # > 0.75 default
    spring = _spring(_creator_context(ai_monthly_cap_usd="5.00"))
    recorded: dict = {}

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _fake_tool_loop(recorded)):
        response = await chat_route.chat(_make_request(_body()), authorization=None)
        assert response.status_code == 200
        await _drain(response)

    assert recorded["tools"] == []  # still a creator turn
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) > Decimal("1.00")
    # Q7: the creator hold was settled by the recorded spend, not leaked.
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


@pytest.mark.asyncio
async def test_per_creator_cap_override_can_also_lower_the_allowance():
    await spend_tracker.record_creator_spend(Decimal("0.30"), CREATOR_ID)  # < 0.75 default
    spring = _spring(_creator_context(ai_monthly_cap_usd="0.25"))

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude") as mock_claude:
        response = await chat_route.chat(_make_request(_body()), authorization=None)

    assert response.status_code == 429
    mock_claude.assert_not_called()


@pytest.mark.asyncio
async def test_creator_turn_holds_a_monthly_reservation_while_in_flight():
    """Q7 race: the gate reserves under the same lock as its comparison, so a
    second turn arriving while the first is still streaming sees the hold.
    Reproduced here by inspecting the ledger from INSIDE the fake tool loop."""
    monkeypatch_cap = Decimal("0.75")
    await spend_tracker.record_creator_spend(monkeypatch_cap - Decimal("0.01"), CREATOR_ID)
    spring = _spring(_creator_context())
    seen: dict = {}

    async def _loop(**kwargs):
        seen["held"] = await spend_tracker.get_reserved_creator(CREATOR_ID)
        # A concurrent second turn at this instant must be refused.
        with pytest.raises(spend_tracker.SpendCapExceeded):
            await spend_tracker.check_creator_spend_gate(
                CREATOR_ID, "CREATOR", reserve_usd=get_settings().ai_reservation_per_call_usd
            )
        yield LoopEvent(type="token", text="hi")
        yield LoopEvent(
            type="done", finish_reason="stop",
            usage={"input_tokens": 10, "output_tokens": 5}, stop_reason="end_turn",
        )

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _loop):
        response = await chat_route.chat(_make_request(_body()), authorization=None)
        assert response.status_code == 200
        await _drain(response)

    assert seen["held"] == Decimal(str(get_settings().ai_reservation_per_call_usd))
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)  # settled


@pytest.mark.asyncio
async def test_brand_turn_is_never_subject_to_the_creator_cap():
    await spend_tracker.record_creator_spend(Decimal("100"), "ws-brand-001")
    spring = _spring({"display_name": "Brand Inc"})
    verified = _verified(claims={"userType": "BRAND"}, workspace_id="ws-brand-001")
    body = _body(user_type="BRAND", workspace_id="ws-brand-001")

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()):
        response = await chat_route.chat(_make_request(body), authorization=None)

    assert response.status_code == 200  # StreamingResponse, not the 429


# ---------------------------------------------------------------- happy path (A4)


@pytest.mark.asyncio
async def test_consented_creator_turn_uses_creator_persona_and_empty_tool_set():
    spring = _spring(_creator_context())
    recorded: dict = {}

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=_verified())), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _fake_tool_loop(recorded)):
        response = await chat_route.chat(_make_request(_body()), authorization=None)
        assert response.status_code == 200
        wire = await _drain(response)

    # The loop was given NO tools and the creator persona.
    assert recorded["tools"] == []
    system_text = json.dumps(recorded["system_blocks"])
    assert MEERA_CREATOR_PERSONA.splitlines()[0] in recorded["system_blocks"][0]["text"]
    assert "You work for Priya here" in recorded["system_blocks"][1]["text"]
    assert "12,400 followers" in system_text
    assert "9,999" in system_text  # the creator's OWN floor is in the CREATOR prompt (not a leak)
    # The brand persona and brand tools are nowhere in a creator turn.
    assert MEERA_PERSONA.splitlines()[0] not in system_text
    assert "calculate_budget" not in system_text
    # The stream completed and the turn was persisted.
    assert "event: token" in wire
    assert "event: done" in wire
    spring.persist_assistant_message.assert_awaited_once()
    # A8: the creator's monthly ledger moved (and the daily one too).
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) > 0
    assert await spend_tracker.get_workspace_total_today(CREATOR_ID) > 0


@pytest.mark.asyncio
async def test_client_body_audience_is_ignored():
    """A brand session cannot flip itself into a CREATOR turn via the body."""
    spring = _spring({"display_name": "Brand Inc"})
    recorded: dict = {}
    verified = _verified(claims={"userType": "BRAND"}, workspace_id="ws-brand-001")
    body = _body(user_type="BRAND", workspace_id="ws-brand-001", audience="CREATOR")

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _fake_tool_loop(recorded)):
        response = await chat_route.chat(_make_request(body), authorization=None)
        await _drain(response)

    spring.get_meera_context.assert_awaited_once_with(
        workspace_id="ws-brand-001", audience="BRAND", onbehalf_jwt=_onbehalf_jwt("BRAND")
    )
    assert [t["name"] for t in recorded["tools"]] != []
    assert recorded["system_blocks"][0]["text"].startswith(MEERA_PERSONA.splitlines()[0])
    assert "Creator context" not in json.dumps(recorded["system_blocks"])
    # A8: brand turns never touch the creator ledger.
    assert await spend_tracker.get_creator_month_total("ws-brand-001") == Decimal(0)


@pytest.mark.asyncio
async def test_brand_turn_still_degrades_to_empty_block_b_on_context_failure():
    """Pre-Phase-A contract preserved: BRAND fails OPEN to an empty Block B."""
    spring = _spring(error=SpringCallError(500, "spring_error", "boom"))
    recorded: dict = {}
    verified = _verified(claims={"userType": "BRAND"}, workspace_id="ws-brand-001")

    with patch.object(chat_route, "verify_token_async", AsyncMock(return_value=verified)), \
         patch.object(chat_route, "_get_spring", return_value=spring), \
         patch.object(chat_route, "_get_claude", return_value=MagicMock()), \
         patch.object(chat_route, "run_tool_loop", _fake_tool_loop(recorded)):
        response = await chat_route.chat(
            _make_request(_body(user_type="BRAND", workspace_id="ws-brand-001")), authorization=None
        )
        assert response.status_code == 200
        await _drain(response)

    assert recorded["system_blocks"][1]["text"].startswith("Brand context for workspace ws-brand-001")
