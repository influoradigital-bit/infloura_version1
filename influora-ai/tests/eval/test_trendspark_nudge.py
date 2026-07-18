"""Eval + unit tests for POST /internal/trendspark/nudge (Trend-Spark T8, Ash).

Golden cases are built from the Meera tone guide
(`influora-api/src/main/resources/trendspark/meera-tone-guide.md`):
  - 5 GOOD nudges (§4) -> AI output passes validation, is returned as message_source=AI
  - 5 BAD nudges (§5)  -> fail validation -> deterministic templated fallback

Every assertion the DoD calls out is covered: <=2 statements, no forbidden
pet-names, video_ids ⊆ input, and fallback triggering on malformed model output.
The Anthropic API is NEVER called — `ClaudeProvider.complete_text` is mocked at the
same `_get_claude` seam the route uses (mirrors tests/routes/test_brand_safety.py).

Route-level auth tests mint a real RS256 token against a fake JWKS source so the
service-token dependency is exercised for real, not mocked away.
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
from fastapi import Request

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.config import get_settings
from app.providers.claude import ClaudeTextResult
from app.routes import trendspark as trendspark_route
from app.routes.trendspark import parse_and_validate

WORKSPACE_ID = "ws-trendspark-001"


# ---------------------------------------------------------------------------
# Auth scaffolding (same pattern as tests/routes/test_brand_safety.py)
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
        "path": "/internal/trendspark/nudge",
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


def _mint_service_token(
    *,
    scope: str = "service",
    aud: str | None = None,
    workspace_id: str = WORKSPACE_ID,
    exp_delta_seconds: float = 240.0,
) -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + int(exp_delta_seconds),
        "aud": aud if aud is not None else settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": scope,
        "workspace_id": workspace_id,
        "sub": "spring-service",
    }
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "test-kid"})


def _mock_claude(text: str | None, *, ok: bool = True):
    """Patch _get_claude so complete_text returns a canned ClaudeTextResult."""
    mock_claude = AsyncMock()
    mock_claude.complete_text = AsyncMock(
        return_value=ClaudeTextResult(ok=ok, text=text, usage=None)
    )
    return patch.object(trendspark_route, "_get_claude", return_value=mock_claude), mock_claude


async def _call(body: dict[str, Any], model_text: str | None, *, ok: bool = True):
    token = _mint_service_token()
    request = _make_request(body, authorization=f"Bearer {token}")
    ctx, _ = _mock_claude(model_text, ok=ok)
    with ctx:
        return await trendspark_route.trendspark_nudge(request, authorization=f"Bearer {token}")


# ---------------------------------------------------------------------------
# Golden GOOD nudges (tone-guide §4) — AI output passes validation
# ---------------------------------------------------------------------------

GOOD_SNAPSBY_1 = {
    "body": {
        "workspace_id": WORKSPACE_ID,
        "brand_name": "GlowStrength",
        "campaign_type": "HYPE",
        "trend_text": "Salman Khan action film release",
        "mode": "SNAPSBY",
        "videos": [
            {"video_id": "vid_001", "title": "Gym motivation reel", "themes": ["strength"]},
            {"video_id": "vid_002", "title": "Workout energy video", "themes": ["energy"]},
            {"video_id": "vid_003", "title": "Strength transformation", "themes": ["action"]},
        ],
    },
    "model": json.dumps(
        {
            "message": (
                "Salman's new film is all raw strength this week — same energy "
                "GlowStrength sells. I found 3 ready fitness videos you could launch "
                "today while the buzz is hot. Want a peek?"
            ),
            "video_ids": ["vid_001", "vid_002", "vid_003"],
        }
    ),
}

GOOD_OWN_CONTENT_1 = {
    "body": {
        "workspace_id": WORKSPACE_ID,
        "brand_name": "Aura Skin",
        "campaign_type": "SEASONAL",
        "trend_text": "Diwali",
        "mode": "OWN_CONTENT",
        "videos": [],
    },
    "model": json.dumps(
        {
            "message": (
                "Diwali's all about that festive glow — perfect timing for Aura Skin. "
                "Your recent reel on night routines? Post it this week while everyone's "
                "prepping for the celebrations."
            ),
            "video_ids": [],
        }
    ),
}

GOOD_SNAPSBY_PRIDE = {
    "body": {
        "workspace_id": WORKSPACE_ID,
        "brand_name": "Hind Exports",
        "campaign_type": "PRIDE",
        "trend_text": "India cricket win",
        "mode": "SNAPSBY",
        "videos": [
            {"video_id": "vid_a", "title": "Export quality spice", "themes": ["pride"]},
            {"video_id": "vid_b", "title": "Indian quality reel", "themes": ["victory"]},
        ],
    },
    "model": json.dumps(
        {
            "message": (
                "India just won — pride is running high. Hind Exports can ride this "
                "energy with 2 ready export videos celebrating Indian quality. Take a look?"
            ),
            "video_ids": ["vid_a", "vid_b"],
        }
    ),
}

GOOD_OWN_CONTENT_2 = {
    "body": {
        "workspace_id": WORKSPACE_ID,
        "brand_name": "FitFuel",
        "campaign_type": "EDUCATIONAL",
        "trend_text": "morning routine trend",
        "mode": "OWN_CONTENT",
        "videos": [],
    },
    "model": json.dumps(
        {
            "message": (
                "Morning-routine energy is everywhere right now — right up FitFuel's "
                "alley. Your recent breakfast prep clip is a perfect fit, share it today."
            ),
            "video_ids": [],
        }
    ),
}

GOOD_SNAPSBY_SEASONAL = {
    "body": {
        "workspace_id": WORKSPACE_ID,
        "brand_name": "Urja Lights",
        "campaign_type": "SEASONAL",
        "trend_text": "Diwali decoration ideas",
        "mode": "SNAPSBY",
        "videos": [
            {"video_id": "vid_x", "title": "Festive lights reel", "themes": ["festive", "glow"]},
        ],
    },
    "model": json.dumps(
        {
            "message": (
                "Diwali decor is trending and that festive glow is exactly Urja Lights' "
                "moment. I found a ready festive video you could post today. Want a look?"
            ),
            "video_ids": ["vid_x"],
        }
    ),
}

GOOD_CASES = [
    GOOD_SNAPSBY_1,
    GOOD_OWN_CONTENT_1,
    GOOD_SNAPSBY_PRIDE,
    GOOD_OWN_CONTENT_2,
    GOOD_SNAPSBY_SEASONAL,
]

_FORBIDDEN = ("darling", "sweetie", "babe", "honey")


@pytest.mark.asyncio
@pytest.mark.parametrize("case", GOOD_CASES)
async def test_good_nudges_pass_validation_and_return_ai(case):
    response = await _call(case["body"], case["model"])
    assert response["success"] is True
    message = response["data"]["message"]
    video_ids = response["data"]["video_ids"]

    # Matches the AI message we fed in (not the fallback template).
    assert message == json.loads(case["model"])["message"]

    # <=2 statements (question CTA excluded).
    assert trendspark_route._statement_count(message) <= 2
    # No forbidden pet-names.
    lowered = message.lower()
    assert not any(w in lowered for w in _FORBIDDEN)
    # video_ids ⊆ input ids.
    sent = {v["video_id"] for v in case["body"]["videos"]}
    assert set(video_ids) <= sent
    # Brand name present (personalization rule).
    assert case["body"]["brand_name"].split()[0].lower() in lowered
    # OWN_CONTENT never surfaces the marketplace.
    if case["body"]["mode"] == "OWN_CONTENT":
        assert "snapsby" not in lowered
        assert video_ids == []


# ---------------------------------------------------------------------------
# BAD nudges (tone-guide §5) — fail validation -> templated fallback
# ---------------------------------------------------------------------------


def _fallback_marker(response: dict[str, Any]) -> bool:
    """A response is the deterministic fallback if its message matches the §7 template
    shape ('there's a ... trend around ...')."""
    return "there's a" in response["data"]["message"].lower()


BASE_SNAPSBY_BODY = {
    "workspace_id": WORKSPACE_ID,
    "brand_name": "GlowStrength",
    "campaign_type": "HYPE",
    "trend_text": "Salman Khan action film",
    "mode": "SNAPSBY",
    "videos": [
        {"video_id": "vid_001", "title": "Gym reel", "themes": ["strength"]},
        {"video_id": "vid_002", "title": "Energy reel", "themes": ["energy"]},
        {"video_id": "vid_003", "title": "Transformation", "themes": ["action"]},
    ],
}


@pytest.mark.asyncio
async def test_bad_petname_falls_back():
    # tone-guide §5 Bad 1 — romantic pet-name.
    model = json.dumps(
        {
            "message": "Hey darling, Salman's film is trending — GlowStrength could use this, love.",
            "video_ids": ["vid_001"],
        }
    )
    response = await _call(BASE_SNAPSBY_BODY, model)
    assert response["success"] is True
    assert _fallback_marker(response)


@pytest.mark.asyncio
async def test_bad_urgency_spam_falls_back():
    # tone-guide §5 Bad 2 — pushy/urgency spam (>2 statements).
    model = json.dumps(
        {
            "message": "ACT NOW! Salman's film is HUGE and you're missing out! BUY these 3 videos before the trend dies! LIMITED TIME!",
            "video_ids": ["vid_001", "vid_002", "vid_003"],
        }
    )
    response = await _call(BASE_SNAPSBY_BODY, model)
    assert _fallback_marker(response)


@pytest.mark.asyncio
async def test_bad_invented_price_falls_back():
    # tone-guide §5 Bad 4 — invented facts incl. price (₹).
    model = json.dumps(
        {
            "message": "Salman's film made ₹500 crore day one — GlowStrength should ride it with videos at ₹1,000 each.",
            "video_ids": ["vid_001"],
        }
    )
    response = await _call(BASE_SNAPSBY_BODY, model)
    assert _fallback_marker(response)


@pytest.mark.asyncio
async def test_bad_mode_confusion_own_content_pushes_snapsby_falls_back():
    # tone-guide §5 Bad 5 — OWN_CONTENT mode but pushes Snapsby videos.
    body = {**BASE_SNAPSBY_BODY, "mode": "OWN_CONTENT", "videos": []}
    model = json.dumps(
        {
            "message": "Diwali's coming — Aura Skin should grab these 3 Snapsby videos I found!",
            "video_ids": [],
        }
    )
    response = await _call(body, model)
    assert _fallback_marker(response)


@pytest.mark.asyncio
async def test_bad_malformed_json_falls_back():
    # Not JSON at all -> defensive parse fails -> fallback.
    response = await _call(BASE_SNAPSBY_BODY, "Sure! Here's a nudge: GlowStrength should post now.")
    assert _fallback_marker(response)


# ---------------------------------------------------------------------------
# Fallback triggers + video_ids ⊆ input (hallucination kill-switch)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_provider_failure_falls_back():
    response = await _call(BASE_SNAPSBY_BODY, None, ok=False)
    assert response["success"] is True
    assert _fallback_marker(response)
    # SNAPSBY fallback still points at the sent ids.
    assert set(response["data"]["video_ids"]) == {"vid_001", "vid_002", "vid_003"}


@pytest.mark.asyncio
async def test_hallucinated_video_id_dropped():
    # Model returns a real id + an invented one -> invented one dropped, AI kept.
    model = json.dumps(
        {
            "message": "Salman's raw strength energy is GlowStrength's moment. I found a ready reel to ride it. Want a peek?",
            "video_ids": ["vid_001", "vid_HALLUCINATED"],
        }
    )
    response = await _call(BASE_SNAPSBY_BODY, model)
    assert response["success"] is True
    assert response["data"]["video_ids"] == ["vid_001"]  # invented id dropped, AI message kept


@pytest.mark.asyncio
async def test_code_fenced_json_is_parsed():
    model = "```json\n" + json.dumps(
        {
            "message": "Salman's raw strength is GlowStrength's moment — a ready reel is waiting. Want a peek?",
            "video_ids": ["vid_002"],
        }
    ) + "\n```"
    response = await _call(BASE_SNAPSBY_BODY, model)
    assert response["data"]["video_ids"] == ["vid_002"]
    assert "there's a" not in response["data"]["message"].lower()  # AI, not fallback


@pytest.mark.asyncio
async def test_spend_gate_block_falls_back_no_provider_call():
    # Kill-switch on -> no provider call, deterministic fallback, still 200.
    token = _mint_service_token()
    request = _make_request(BASE_SNAPSBY_BODY, authorization=f"Bearer {token}")
    ctx, mock_claude = _mock_claude("{}")

    async def _blocked_gate(workspace_id=None):
        from app.costs.gate import SpendGateResult

        return SpendGateResult(allowed=False, error_code="AI_KILL_SWITCH_ACTIVE", error_message="off")

    with ctx, patch.object(trendspark_route, "check_spend_gate", _blocked_gate):
        response = await trendspark_route.trendspark_nudge(request, authorization=f"Bearer {token}")

    assert response["success"] is True
    assert _fallback_marker(response)
    mock_claude.complete_text.assert_not_called()


# ---------------------------------------------------------------------------
# Auth enforcement
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_token_rejected_401():
    request = _make_request(BASE_SNAPSBY_BODY, authorization=None)
    with patch.object(trendspark_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await trendspark_route.trendspark_nudge(request, authorization=None)
        assert getattr(exc_info.value, "status_code", None) == 401
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_wrong_scope_rejected_403():
    settings = get_settings()
    stream_token = _mint_service_token(scope="chat:stream", aud=settings.stream_token_aud)
    request = _make_request(BASE_SNAPSBY_BODY, authorization=f"Bearer {stream_token}")
    with pytest.raises(Exception) as exc_info:
        await trendspark_route.trendspark_nudge(request, authorization=f"Bearer {stream_token}")
    assert getattr(exc_info.value, "status_code", None) == 403


@pytest.mark.asyncio
async def test_workspace_mismatch_rejected_403():
    token = _mint_service_token(workspace_id="ws-other")
    request = _make_request(BASE_SNAPSBY_BODY, authorization=f"Bearer {token}")
    with pytest.raises(Exception) as exc_info:
        await trendspark_route.trendspark_nudge(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 403


@pytest.mark.asyncio
async def test_missing_workspace_id_rejected_400():
    body = {k: v for k, v in BASE_SNAPSBY_BODY.items() if k != "workspace_id"}
    request = _make_request(body, authorization="Bearer whatever")
    with pytest.raises(Exception) as exc_info:
        await trendspark_route.trendspark_nudge(request, authorization="Bearer whatever")
    assert getattr(exc_info.value, "status_code", None) == 400


# ---------------------------------------------------------------------------
# parse_and_validate unit tests (no provider, no auth) — the validation core
# ---------------------------------------------------------------------------


def test_parse_valid_snapsby():
    out = parse_and_validate(
        json.dumps({"message": "GlowStrength, ride the raw strength wave. A reel is ready.", "video_ids": ["v1"]}),
        mode="SNAPSBY",
        sent_ids=["v1", "v2"],
        max_message_chars=300,
    )
    assert out is not None
    assert out[1] == ["v1"]


def test_parse_rejects_over_length():
    assert parse_and_validate(
        json.dumps({"message": "x" * 301, "video_ids": []}),
        mode="SNAPSBY",
        sent_ids=[],
        max_message_chars=300,
    ) is None


def test_parse_rejects_three_statements():
    assert parse_and_validate(
        json.dumps({"message": "One thing. Two thing. Three thing.", "video_ids": []}),
        mode="SNAPSBY",
        sent_ids=[],
        max_message_chars=300,
    ) is None


def test_parse_allows_two_statements_plus_question():
    assert parse_and_validate(
        json.dumps({"message": "First point here. Second point here. Want a peek?", "video_ids": []}),
        mode="SNAPSBY",
        sent_ids=[],
        max_message_chars=300,
    ) is not None


def test_parse_rejects_price():
    assert parse_and_validate(
        json.dumps({"message": "Grab it for ₹999 today.", "video_ids": []}),
        mode="SNAPSBY",
        sent_ids=[],
        max_message_chars=300,
    ) is None


def test_parse_own_content_rejects_marketplace_words():
    assert parse_and_validate(
        json.dumps({"message": "Post these Snapsby videos now.", "video_ids": []}),
        mode="OWN_CONTENT",
        sent_ids=[],
        max_message_chars=300,
    ) is None


def test_parse_love_verb_not_flagged_but_vocative_is():
    # "love" as a verb is fine.
    assert parse_and_validate(
        json.dumps({"message": "Your fans will love this festive energy from AuraSkin.", "video_ids": []}),
        mode="OWN_CONTENT",
        sent_ids=[],
        max_message_chars=300,
    ) is not None
    # "love" as a vocative pet-name is rejected.
    assert parse_and_validate(
        json.dumps({"message": "Post it today, love.", "video_ids": []}),
        mode="OWN_CONTENT",
        sent_ids=[],
        max_message_chars=300,
    ) is None
