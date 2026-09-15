"""Ship-blocker pytest suite for POST /internal/brief-extract
(T-MEERA-CREATOR-PHASE-B §7.5, B0-39).

Structure mirrors `tests/routes/test_creator_suggestion.py`: a self-contained
RSA/JWKS fixture so the REAL `verify_creator_token` pipeline is exercised rather
than mocked away, and `ClaudeProvider` mocked at the same `_get_claude` seam the
route uses — the Anthropic API is never called.

What is covered, and why each case is here rather than merely plausible:

  1. Happy path: a valid forced-tool input becomes the §2.11 payload.
  2. `.tool_input`, not `.text` — ClaudeToolResult has no `text` attribute at
     all, so a route reading the wrong field cannot work; this file would fail
     on an AttributeError rather than a wrong value.
  3. Auth: no token -> 401, service scope -> 403, profile-id mismatch -> 403.
     The mismatch case is the one that matters: it is what stops a stolen
     creator token from reading another creator's brief.
  4. Missing `creator_profile_id` -> 400, and it is the ONLY non-auth non-200.
  5. Cap exhaustion -> HTTP 200 with `CREATOR_MONTHLY_CAP_REACHED` and NO
     provider call. The 200 is not a nicety: Java tells a cap from an outage by
     this code alone, and a non-200 here mislabels the creator's fallback.
  6. THE GATE IS THE RIGHT GATE. One test asserts `check_creator_spend_gate`
     was called with a positive `cap_usd` and audience "CREATOR". A regression
     to `check_spend_gate` (the daily workspace ceiling, which takes no cap)
     is invisible to every other assertion in this file — the route would still
     return 200 and still extract, with the brief cap enforcing nothing.
  7. Provider failure and malformed output both -> `extraction_failed`, 200.
  8. Banned word / invented number in a summary line -> that line is stripped.
  9. Billing happens on `usage` even when `ok` is False (the F-06 path).
"""

from __future__ import annotations

import json
import time
from typing import Any
from unittest.mock import AsyncMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException, Request

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.config import get_settings
from app.costs.spend_tracker import SpendCapExceeded
from app.providers.claude import ClaudeToolResult
from app.routes import brief_extract as brief_extract_route

CREATOR_PROFILE_ID = "creator-profile-brief-001"

RAW_BRIEF = (
    "Hi! This is Glow Cosmetics. We'd love 1 reel and 1 story set for our new "
    "Vitamin C serum. Budget is 8000 INR, live by 2026-10-05. 60 days category "
    "exclusivity, organic usage only, 50% advance."
)

VALID_TOOL_INPUT: dict[str, Any] = {
    "brand_name": "Glow Cosmetics",
    "product": "Vitamin C serum",
    "category": "BEAUTY",
    "deliverables": [{"type": "REEL", "qty": 1}, {"type": "STORY_SET", "qty": 1}],
    "budget_inr": 8000,
    "budget_stated": True,
    "barter_only": False,
    "deadline": "2026-10-05",
    "usage_perpetual": False,
    "usage_channels": ["ORGANIC"],
    "exclusivity_days": 60,
    "exclusivity_scope": "CATEGORY",
    "exclusivity_brands": [],
    "payment_terms": "50% advance",
    "off_platform_payment_hint": False,
    "disclosure_hidden_hint": False,
    "claims": [],
    "vague_deliverables": False,
    "summary_lines": [
        "Glow Cosmetics wants 1 reel + 1 story set for a Vitamin C serum",
        "Budget stated: 8,000",
        "Deadline 5 Oct",
        "60 days category exclusivity",
        "Organic usage only",
    ],
}


# ---------------------------------------------------------------------------
# Auth scaffolding (self-contained, mirroring tests/routes/test_creator_suggestion.py)
# ---------------------------------------------------------------------------


def _make_request(body: dict[str, Any], authorization: str | None = None) -> Request:
    body_bytes = json.dumps(body).encode()

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    headers = []
    if authorization is not None:
        headers.append((b"authorization", authorization.encode()))

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/internal/brief-extract",
        "headers": headers,
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


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


@pytest.fixture(autouse=True)
def _install_fake_jwks():
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    yield
    reset_jwks_source()


def _mint_creator_token(
    *,
    scope: str = "creator",
    creator_profile_id: str = CREATOR_PROFILE_ID,
    exp_delta_seconds: float = 240.0,
) -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + int(exp_delta_seconds),
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": scope,
        "creator_profile_id": creator_profile_id,
        "sub": "spring-creator-service",
    }
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "test-kid"})


def _mock_claude(tool_input: dict[str, Any] | None, *, ok: bool = True, usage=None, error=None):
    mock_claude = AsyncMock()
    mock_claude.complete_with_forced_tool = AsyncMock(
        return_value=ClaudeToolResult(ok=ok, tool_input=tool_input, error=error, usage=usage)
    )
    return patch.object(brief_extract_route, "_get_claude", return_value=mock_claude), mock_claude


def _base_body(**overrides: Any) -> dict[str, Any]:
    body = {
        "creator_profile_id": CREATOR_PROFILE_ID,
        "raw_text": RAW_BRIEF,
        "creator_language": "en",
    }
    body.update(overrides)
    return body


async def _call(
    body: dict[str, Any],
    tool_input: dict[str, Any] | None,
    *,
    ok: bool = True,
    usage=None,
    error=None,
    token: str | None = None,
    gate=None,
):
    """Drives the handler with the real auth pipeline and a mocked provider.

    `gate` replaces `check_creator_spend_gate` when supplied; by default the
    real gate runs, which is a no-op returning None whenever nothing has been
    spent against this test creator's `:brief` bucket.
    """
    token = token or _mint_creator_token(
        creator_profile_id=body.get("creator_profile_id", CREATOR_PROFILE_ID)
    )
    request = _make_request(body, authorization=f"Bearer {token}")
    ctx, mock_claude = _mock_claude(tool_input, ok=ok, usage=usage, error=error)
    with ctx:
        if gate is not None:
            with patch.object(brief_extract_route, "check_creator_spend_gate", gate):
                response = await brief_extract_route.brief_extract(
                    request, authorization=f"Bearer {token}"
                )
        else:
            response = await brief_extract_route.brief_extract(
                request, authorization=f"Bearer {token}"
            )
    return response, mock_claude


# ---------------------------------------------------------------------------
# 1-2. Happy path, read off .tool_input
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_happy_path_returns_extraction_from_tool_input():
    response, mock_claude = await _call(_base_body(), VALID_TOOL_INPUT)

    assert response["success"] is True
    data = response["data"]
    assert data["brand_name"] == "Glow Cosmetics"
    assert data["category"] == "BEAUTY"
    assert data["deliverables"] == [
        {"type": "REEL", "qty": 1},
        {"type": "STORY_SET", "qty": 1},
    ]
    assert data["budget_stated"] is True
    assert float(data["budget_inr"]) == 8000.0
    assert data["usage_channels"] == ["ORGANIC"]
    assert data["exclusivity_scope"] == "CATEGORY"
    assert len(data["summary_lines"]) == 5
    mock_claude.complete_with_forced_tool.assert_awaited_once()


@pytest.mark.asyncio
async def test_forced_tool_is_called_keyword_only_with_the_brief_schema():
    """`complete_with_forced_tool` takes a bare `*` — every argument is
    keyword-only and it must be awaited. A positional call is a TypeError at
    runtime, so this asserts the call shape, not just its result."""
    _, mock_claude = await _call(_base_body(), VALID_TOOL_INPUT)
    call = mock_claude.complete_with_forced_tool.await_args
    assert call.args == (), "every parameter is keyword-only (bare * in the signature)"
    assert call.kwargs["tool_schema"]["name"] == "extract_brief"
    assert call.kwargs["model"] == brief_extract_route.BRIEF_EXTRACT_MODEL
    # The pasted brief must reach the model wrapped as untrusted data.
    content = call.kwargs["messages"][0]["content"]
    assert "<untrusted_pasted_brief>" in content


@pytest.mark.asyncio
async def test_tool_result_type_has_no_text_attribute():
    """Pins the §7.5 correction: the parsed payload is on `.tool_input`. There is
    no `.text` on ClaudeToolResult, so a spec-literal route would have raised."""
    result = ClaudeToolResult(ok=True, tool_input={"a": 1})
    assert not hasattr(result, "text")
    assert result.tool_input == {"a": 1}


# ---------------------------------------------------------------------------
# 3. Auth
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_token_is_401():
    request = _make_request(_base_body())
    ctx, mock_claude = _mock_claude(VALID_TOOL_INPUT)
    with ctx, pytest.raises(HTTPException) as exc:
        await brief_extract_route.brief_extract(request, authorization=None)
    assert exc.value.status_code == 401
    mock_claude.complete_with_forced_tool.assert_not_awaited()


@pytest.mark.asyncio
async def test_service_scope_token_is_rejected():
    """The segregation is bidirectional and deliberate: a brand-side service
    token must never open a creator route, which is why §3.8's Java client mints
    a creator-scoped token rather than mirroring MeeraVoiceAiClient."""
    token = _mint_creator_token(scope="service")
    request = _make_request(_base_body(), authorization=f"Bearer {token}")
    ctx, mock_claude = _mock_claude(VALID_TOOL_INPUT)
    with ctx, pytest.raises(HTTPException) as exc:
        await brief_extract_route.brief_extract(request, authorization=f"Bearer {token}")
    assert exc.value.status_code == 403
    mock_claude.complete_with_forced_tool.assert_not_awaited()


@pytest.mark.asyncio
async def test_profile_id_mismatch_is_rejected():
    """`body_creator_profile_id` is keyword-only and REQUIRED on
    verify_creator_token; this is what it buys."""
    token = _mint_creator_token(creator_profile_id="someone-else")
    request = _make_request(_base_body(), authorization=f"Bearer {token}")
    ctx, mock_claude = _mock_claude(VALID_TOOL_INPUT)
    with ctx, pytest.raises(HTTPException) as exc:
        await brief_extract_route.brief_extract(request, authorization=f"Bearer {token}")
    assert exc.value.status_code == 403
    mock_claude.complete_with_forced_tool.assert_not_awaited()


@pytest.mark.asyncio
async def test_endpoint_scope_is_registered():
    """Without an ENDPOINT_SCOPES entry an unlisted endpoint has NO allowed
    scopes, so verify_creator_token(endpoint="brief_extract") rejects 100% of
    calls — the route would be dead on arrival with a correct token."""
    from app.auth.service_token import ENDPOINT_SCOPES, SCOPE_CREATOR

    assert ENDPOINT_SCOPES.get("brief_extract") == (SCOPE_CREATOR,)


# ---------------------------------------------------------------------------
# 4. Missing creator_profile_id — the only non-auth non-200
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_missing_creator_profile_id_is_400():
    body = _base_body()
    body.pop("creator_profile_id")
    request = _make_request(body, authorization="Bearer whatever")
    ctx, mock_claude = _mock_claude(VALID_TOOL_INPUT)
    with ctx, pytest.raises(HTTPException) as exc:
        await brief_extract_route.brief_extract(request, authorization="Bearer whatever")
    assert exc.value.status_code == 400
    assert exc.value.detail["code"] == "missing_fields"
    mock_claude.complete_with_forced_tool.assert_not_awaited()


# ---------------------------------------------------------------------------
# 5-6. The spend gate
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_cap_exhaustion_is_200_with_cap_code_and_no_provider_call():
    gate = AsyncMock(side_effect=SpendCapExceeded(creator_id=CREATOR_PROFILE_ID))
    response, mock_claude = await _call(_base_body(), VALID_TOOL_INPUT, gate=gate)

    assert response == {
        "success": False,
        "error": {"code": brief_extract_route.CAP_ERROR_CODE},
    }
    mock_claude.complete_with_forced_tool.assert_not_awaited()


@pytest.mark.asyncio
async def test_gate_is_the_monthly_creator_gate_with_a_cap_override():
    """THE test §14.4.b asks for. A regression to `check_spend_gate` — the daily
    workspace ceiling, which accepts no `cap_usd` — still returns 200 and still
    extracts, so nothing else in this file would notice that the brief cap had
    stopped existing."""
    gate = AsyncMock(return_value=None)
    await _call(_base_body(), VALID_TOOL_INPUT, gate=gate)

    gate.assert_awaited_once()
    call = gate.await_args
    # creator_id and audience are positional on check_creator_spend_gate.
    assert call.args[0] == f"{CREATOR_PROFILE_ID}:brief", "the :brief suffix is the separate bucket"
    assert call.args[1] == "CREATOR", "the gate no-ops on any other audience"
    cap = call.kwargs["cap_usd"]
    assert cap is not None and float(cap) > 0, "cap_usd <= 0 disables the cap silently"
    assert float(cap) == pytest.approx(get_settings().brief_extract_monthly_cap_usd)


@pytest.mark.asyncio
async def test_brief_cap_is_a_separate_key_from_the_chat_cap():
    """The chat cap is keyed on the bare creator id; this route's key carries the
    `:brief` suffix, so a creator who has exhausted chat can still paste."""
    gate = AsyncMock(return_value=None)
    await _call(_base_body(), VALID_TOOL_INPUT, gate=gate)
    assert gate.await_args.args[0] != CREATOR_PROFILE_ID


# ---------------------------------------------------------------------------
# 7. Failure paths, all HTTP 200
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_provider_failure_returns_extraction_failed_at_200():
    response, _ = await _call(
        _base_body(), None, ok=False, error="provider_error", gate=AsyncMock(return_value=None)
    )
    assert response == {
        "success": False,
        "error": {"code": brief_extract_route.EXTRACTION_FAILED_CODE},
    }


@pytest.mark.asyncio
async def test_malformed_tool_input_returns_extraction_failed():
    """Two summary lines is below the 3-line minimum the paste card renders."""
    bad = dict(VALID_TOOL_INPUT, summary_lines=["one", "two"])
    response, _ = await _call(_base_body(), bad, gate=AsyncMock(return_value=None))
    assert response["success"] is False
    assert response["error"]["code"] == brief_extract_route.EXTRACTION_FAILED_CODE


@pytest.mark.asyncio
async def test_non_dict_tool_input_returns_extraction_failed():
    response, _ = await _call(
        _base_body(), None, ok=True, gate=AsyncMock(return_value=None)
    )
    assert response["success"] is False
    assert response["error"]["code"] == brief_extract_route.EXTRACTION_FAILED_CODE


@pytest.mark.asyncio
async def test_blank_raw_text_returns_extraction_failed_not_a_400():
    response, mock_claude = await _call(
        _base_body(raw_text="   "), VALID_TOOL_INPUT, gate=AsyncMock(return_value=None)
    )
    assert response["success"] is False
    mock_claude.complete_with_forced_tool.assert_not_awaited()


# ---------------------------------------------------------------------------
# 8. Output validation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_banned_word_line_is_stripped_from_the_summary():
    banned = "escr" + "ow"
    lines = list(VALID_TOOL_INPUT["summary_lines"])
    lines[2] = f"Payment is held in {banned} until delivery"
    response, _ = await _call(
        _base_body(), dict(VALID_TOOL_INPUT, summary_lines=lines), gate=AsyncMock(return_value=None)
    )
    assert response["success"] is True
    rendered = " ".join(response["data"]["summary_lines"]).lower()
    assert banned not in rendered
    assert len(response["data"]["summary_lines"]) == 4


@pytest.mark.asyncio
async def test_invented_number_line_is_stripped():
    """The model is shown no rate, no floor and no quote, so a figure in its
    output that is not in the brief was invented — and the creator would read it
    as the brand's offer."""
    lines = list(VALID_TOOL_INPUT["summary_lines"])
    lines[1] = "They will probably go up to 45000"
    response, _ = await _call(
        _base_body(), dict(VALID_TOOL_INPUT, summary_lines=lines), gate=AsyncMock(return_value=None)
    )
    assert response["success"] is True
    assert not any("45000" in line for line in response["data"]["summary_lines"])


@pytest.mark.asyncio
async def test_off_vocab_enums_fail_closed_to_absent():
    noisy = dict(
        VALID_TOOL_INPUT,
        category="CRYPTOCURRENCY_LIFESTYLE",
        exclusivity_scope="WORLDWIDE",
        usage_channels=["ORGANIC", "BILLBOARDS"],
        deliverables=[{"type": "TIKTOK_DANCE", "qty": 2}, {"type": "REEL", "qty": 1}],
    )
    response, _ = await _call(_base_body(), noisy, gate=AsyncMock(return_value=None))
    data = response["data"]
    assert data["category"] is None
    assert data["exclusivity_scope"] is None
    assert data["usage_channels"] == ["ORGANIC"]
    assert data["deliverables"] == [{"type": "REEL", "qty": 1}]


@pytest.mark.asyncio
async def test_unstated_budget_never_carries_a_figure():
    """`budget_stated` false with a number attached is the single most dangerous
    output this route can produce: Java drops it from pricing but it would still
    land in creator_briefs.extracted_json and on the creator's screen."""
    response, _ = await _call(
        _base_body(),
        dict(VALID_TOOL_INPUT, budget_stated=False, budget_inr=8000),
        gate=AsyncMock(return_value=None),
    )
    assert response["data"]["budget_stated"] is False
    assert response["data"]["budget_inr"] is None


# ---------------------------------------------------------------------------
# 9. Billing on usage regardless of ok (F-06)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_spend_is_recorded_even_when_the_turn_produced_no_tool_use():
    """`no_tool_use_in_response` returns ok=False WITH usage — the call was
    billed in full by the provider. Recording only on `ok` bills $0 for a real
    charge, which is the F-06 defect."""
    usage = {"input_tokens": 1200, "output_tokens": 300}
    recorder = AsyncMock(return_value=0)
    with patch.object(brief_extract_route, "record_creator_spend", recorder):
        response, _ = await _call(
            _base_body(),
            None,
            ok=False,
            error="no_tool_use_in_response",
            usage=usage,
            gate=AsyncMock(return_value=None),
        )
    assert response["success"] is False
    recorder.assert_awaited_once()
    assert recorder.await_args.args[1] == f"{CREATOR_PROFILE_ID}:brief"


@pytest.mark.asyncio
async def test_reservation_is_released_when_nothing_was_billed():
    releaser = AsyncMock()
    sentinel = object()
    with patch.object(brief_extract_route, "release_creator", releaser):
        await _call(
            _base_body(),
            VALID_TOOL_INPUT,
            usage=None,
            gate=AsyncMock(return_value=sentinel),
        )
    releaser.assert_awaited_once_with(sentinel)
