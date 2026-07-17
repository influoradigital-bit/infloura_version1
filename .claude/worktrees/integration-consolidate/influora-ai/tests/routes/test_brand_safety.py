"""Tests for POST /internal/brand-safety (Wave C task C2).

Covers:
- internal-auth enforcement (no token -> 401, matching the RT-TOK-1 pattern
  in tests/security/test_service_token.py; wrong scope -> 403)
- happy path: GARM flags + sentiment returned for supplied captions, one
  result per item, in order
- malformed input handled gracefully (missing/empty items, bad item shape,
  duplicate content_id, too-many-items cap)
- malformed model output (missing category, bad enum, wrong item count)
  degrades to a typed 502 instead of forwarding garbage to the caller
- per-caption length cap (MEDIUM-2, brand-safety-endpoint-C2-security-review.md)
  rejects oversized captions with a typed 400 before any provider call

See tests/prompt/test_brand_safety_prompt.py for the untrusted-caption
delimiter boundary tests (HIGH-1 fix coverage).

Route-level auth tests mint a real token against a fake JWKS source (same
approach as tests/security/test_service_token.py) so the auth dependency is
exercised for real rather than mocked away. Provider-behavior tests patch
`ClaudeProvider.complete_with_forced_tool` directly, since exercising the
real Anthropic SDK is out of scope for this test module (ClaudeProvider's own
plumbing has no dedicated test file to mirror here, so we patch at the same
seam the route code calls through).
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
from app.providers.claude import ClaudeToolResult
from app.routes import brand_safety as brand_safety_route
from app.tools.schemas import GARM_CATEGORIES


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
        "path": "/internal/brand-safety",
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
WORKSPACE_ID = "ws-brand-safety-001"


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


def _flag(category: str, risk: str = "floor", rationale: str = "no concern") -> dict[str, Any]:
    return {"category": category, "risk": risk, "rationale": rationale}


def _all_floor_flags() -> list[dict[str, Any]]:
    return [_flag(cat) for cat in GARM_CATEGORIES]


def _valid_model_output(content_ids: list[str]) -> dict[str, Any]:
    return {
        "items": [
            {
                "content_id": cid,
                "garm_flags": _all_floor_flags(),
                "content_sentiment": "positive",
                "sentiment_score": 0.4,
                "brand_safety_score": 95.0,
                "overall_rationale": "friendly product post, no concerns found.",
            }
            for cid in content_ids
        ]
    }


# ---------------------------------------------------------------------------
# Auth enforcement
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_token_rejected_401():
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=None)

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=None)

        assert getattr(exc_info.value, "status_code", None) == 401
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_garbage_token_rejected_401():
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization="Bearer not-a-real-jwt")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization="Bearer not-a-real-jwt")

    assert getattr(exc_info.value, "status_code", None) == 401


@pytest.mark.asyncio
async def test_wrong_scope_rejected_403():
    """A chat:stream-scoped token must not be able to call this internal
    endpoint -- only service-scoped tokens may (ENDPOINT_SCOPES['brand_safety'])."""
    settings = get_settings()
    stream_token = _mint_service_token(scope="chat:stream", aud=settings.stream_token_aud)
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {stream_token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {stream_token}")

    assert getattr(exc_info.value, "status_code", None) == 403


@pytest.mark.asyncio
async def test_workspace_mismatch_rejected_403():
    token = _mint_service_token(workspace_id="ws-other-workspace")
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 403


@pytest.mark.asyncio
async def test_missing_workspace_id_rejected_400_before_auth():
    body = {"items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization="Bearer whatever")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization="Bearer whatever")

    assert getattr(exc_info.value, "status_code", None) == 400


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_returns_garm_flags_and_sentiment_for_supplied_captions():
    token = _mint_service_token()
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [
            {"content_id": "media-1", "caption": "Loving this new skincare set!", "media_type": "IMAGE"},
            {"content_id": "media-2", "caption": "Check out my link in bio for a deal.", "media_type": "REEL"},
        ],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    mock_result = ClaudeToolResult(ok=True, tool_input=_valid_model_output(["media-1", "media-2"]))

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(return_value=mock_result)
        mock_get_claude.return_value = mock_claude

        response = await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert response["success"] is True
    items = response["data"]["items"]
    assert len(items) == 2
    assert [item["content_id"] for item in items] == ["media-1", "media-2"]
    for item in items:
        assert len(item["garm_flags"]) == len(GARM_CATEGORIES)
        assert {flag["category"] for flag in item["garm_flags"]} == set(GARM_CATEGORIES)
        assert item["content_sentiment"] in ("positive", "neutral", "negative")
        assert 0 <= item["brand_safety_score"] <= 100
        assert -1.0 <= item["sentiment_score"] <= 1.0

    mock_claude.complete_with_forced_tool.assert_awaited_once()


@pytest.mark.asyncio
async def test_empty_caption_item_still_produces_a_result():
    """An item with no caption text (empty string) must still get a result --
    not silently dropped -- so Java always gets exactly one row per input."""
    token = _mint_service_token()
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "media-empty", "caption": ""}],
    }
    request = _make_request(body, authorization=f"Bearer {token}")
    mock_result = ClaudeToolResult(ok=True, tool_input=_valid_model_output(["media-empty"]))

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(return_value=mock_result)
        mock_get_claude.return_value = mock_claude

        response = await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert len(response["data"]["items"]) == 1
    assert response["data"]["items"][0]["content_id"] == "media-empty"


# ---------------------------------------------------------------------------
# Malformed input handling
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_missing_items_rejected_400():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_empty_items_array_rejected_400():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": []}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_item_missing_content_id_rejected_400():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"caption": "no id here"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_non_object_item_rejected_400():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": ["not-an-object"]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_duplicate_content_id_rejected_400():
    token = _mint_service_token()
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [
            {"content_id": "dup", "caption": "a"},
            {"content_id": "dup", "caption": "b"},
        ],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_too_many_items_rejected_400():
    token = _mint_service_token()
    settings = get_settings()
    too_many = settings.brand_safety_max_items_per_call + 1
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": f"m{i}", "caption": "x"} for i in range(too_many)],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_caption_wrong_type_rejected_400():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": 12345}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with pytest.raises(Exception) as exc_info:
        await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")
    assert getattr(exc_info.value, "status_code", None) == 400


@pytest.mark.asyncio
async def test_oversized_caption_rejected_400_before_model_call():
    """MEDIUM-2 (red-team review): per-caption INPUT length was unbounded --
    a multi-MB caption was accepted and passed straight into the prompt
    (token/cost DoS). This must now reject with a typed 4xx BEFORE the
    provider is ever called, consistent with the other _validate_items
    checks -- never a 500, never a wasted model call."""
    token = _mint_service_token()
    settings = get_settings()
    oversized_caption = "a" * (settings.brand_safety_max_caption_chars + 1)
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "m1", "caption": oversized_caption}],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 400
        detail = getattr(exc_info.value, "detail", None)
        if isinstance(detail, dict):
            assert detail.get("code") == "caption_too_long"
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_caption_at_exact_max_length_is_accepted():
    """Boundary check: a caption exactly at the configured max is valid --
    the cap must reject strictly-over, not off-by-one at the limit."""
    token = _mint_service_token()
    settings = get_settings()
    max_len_caption = "a" * settings.brand_safety_max_caption_chars
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "m1", "caption": max_len_caption}],
    }
    request = _make_request(body, authorization=f"Bearer {token}")
    mock_result = ClaudeToolResult(ok=True, tool_input=_valid_model_output(["m1"]))

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(return_value=mock_result)
        mock_get_claude.return_value = mock_claude

        response = await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert response["success"] is True


# ---------------------------------------------------------------------------
# content_id / media_type / posted_at length caps (LOW-4, HIGH-2 rework --
# brand-safety-endpoint-C2-security-review.md). Mirrors the caption length
# cap tests above: these fields are interpolated into the prompt's meta line
# just like caption is interpolated into the wrapped body, so an unbounded
# value is the same token/cost DoS surface caption already had.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_oversized_content_id_rejected_400_before_model_call():
    token = _mint_service_token()
    settings = get_settings()
    oversized_id = "m" * (settings.brand_safety_max_meta_field_chars + 1)
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": oversized_id, "caption": "hi"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 400
        detail = getattr(exc_info.value, "detail", None)
        if isinstance(detail, dict):
            assert detail.get("code") == "content_id_too_long"
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_content_id_at_exact_max_length_is_accepted():
    token = _mint_service_token()
    settings = get_settings()
    max_len_id = "m" * settings.brand_safety_max_meta_field_chars
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": max_len_id, "caption": "hi"}]}
    request = _make_request(body, authorization=f"Bearer {token}")
    mock_result = ClaudeToolResult(ok=True, tool_input=_valid_model_output([max_len_id]))

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(return_value=mock_result)
        mock_get_claude.return_value = mock_claude

        response = await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert response["success"] is True


@pytest.mark.asyncio
async def test_oversized_media_type_rejected_400_before_model_call():
    token = _mint_service_token()
    settings = get_settings()
    oversized_media_type = "x" * (settings.brand_safety_max_meta_field_chars + 1)
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "m1", "caption": "hi", "media_type": oversized_media_type}],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 400
        detail = getattr(exc_info.value, "detail", None)
        if isinstance(detail, dict):
            assert detail.get("code") == "media_type_too_long"
        mock_get_claude.assert_not_called()


@pytest.mark.asyncio
async def test_oversized_posted_at_rejected_400_before_model_call():
    token = _mint_service_token()
    settings = get_settings()
    oversized_posted_at = "2" * (settings.brand_safety_max_meta_field_chars + 1)
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [{"content_id": "m1", "caption": "hi", "posted_at": oversized_posted_at}],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

        assert getattr(exc_info.value, "status_code", None) == 400
        detail = getattr(exc_info.value, "detail", None)
        if isinstance(detail, dict):
            assert detail.get("code") == "posted_at_too_long"
        mock_get_claude.assert_not_called()


# ---------------------------------------------------------------------------
# Model output validation / degradation
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_provider_failure_degrades_to_502():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(
            return_value=ClaudeToolResult(ok=False, error="provider_error")
        )
        mock_get_claude.return_value = mock_claude

        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 502


@pytest.mark.asyncio
async def test_model_output_missing_category_degrades_to_502():
    """If the model returns garm_flags missing a category, the route must not
    forward a partial/garbage score to the caller -- it must fail closed."""
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    incomplete_output = {
        "items": [
            {
                "content_id": "m1",
                "garm_flags": [_flag(cat) for cat in list(GARM_CATEGORIES)[:-1]],  # missing one
                "content_sentiment": "neutral",
                "sentiment_score": 0.0,
                "brand_safety_score": 100.0,
                "overall_rationale": "fine",
            }
        ]
    }

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(
            return_value=ClaudeToolResult(ok=True, tool_input=incomplete_output)
        )
        mock_get_claude.return_value = mock_claude

        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 502


@pytest.mark.asyncio
async def test_model_output_wrong_item_count_degrades_to_502():
    token = _mint_service_token()
    body = {
        "workspace_id": WORKSPACE_ID,
        "items": [
            {"content_id": "m1", "caption": "hello"},
            {"content_id": "m2", "caption": "world"},
        ],
    }
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(
            return_value=ClaudeToolResult(ok=True, tool_input=_valid_model_output(["m1"]))  # only 1, expected 2
        )
        mock_get_claude.return_value = mock_claude

        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 502


@pytest.mark.asyncio
async def test_model_output_invalid_enum_value_degrades_to_502():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    bad_output = {
        "items": [
            {
                "content_id": "m1",
                "garm_flags": [
                    _flag(cat, risk="extreme" if cat == GARM_CATEGORIES[0] else "floor")
                    for cat in GARM_CATEGORIES
                ],
                "content_sentiment": "neutral",
                "sentiment_score": 0.0,
                "brand_safety_score": 100.0,
                "overall_rationale": "fine",
            }
        ]
    }

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(
            return_value=ClaudeToolResult(ok=True, tool_input=bad_output)
        )
        mock_get_claude.return_value = mock_claude

        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 502


@pytest.mark.asyncio
async def test_model_output_content_id_mismatch_degrades_to_502():
    token = _mint_service_token()
    body = {"workspace_id": WORKSPACE_ID, "items": [{"content_id": "m1", "caption": "hello"}]}
    request = _make_request(body, authorization=f"Bearer {token}")

    with patch.object(brand_safety_route, "_get_claude") as mock_get_claude:
        mock_claude = AsyncMock()
        mock_claude.complete_with_forced_tool = AsyncMock(
            return_value=ClaudeToolResult(ok=True, tool_input=_valid_model_output(["some-other-id"]))
        )
        mock_get_claude.return_value = mock_claude

        with pytest.raises(Exception) as exc_info:
            await brand_safety_route.brand_safety(request, authorization=f"Bearer {token}")

    assert getattr(exc_info.value, "status_code", None) == 502
