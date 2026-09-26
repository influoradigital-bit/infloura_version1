"""Tests for POST /ai/shoot-check/frame (T-SHOOTCHECK-L2), the Level 2 "frame
check" route -- app/routes/shoot_check.py.

Mirrors tests/routes/test_voice_consent.py and test_voice_creator_cap.py for
the gate paths (this route is a SIBLING of voice.py, same helpers, same
order): drives the real route function directly, `verify_token` mocked, the
Claude provider and Spring client mocked via `_get_claude`/`_get_spring`.

Also covers what's new to this route: multipart image upload validation
(content type / size / empty / truncated, all BEFORE any model call) and the
defensive JSON parse in app/prompt/frame_check.py (fenced JSON, garbage,
over-long lists, a second-person line passing through untouched).
"""

from __future__ import annotations

import json
import logging
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import Request

from app.auth import consent as consent_module
from app.auth.service_token import VerifiedToken
from app.clients.spring import SpringResponse
from app.config import SHOOT_CHECK_MODEL, get_settings
from app.costs import spend_tracker
from app.costs.spend_tracker import CREATOR_CAP_CODE, CREATOR_CAP_MESSAGE
from app.providers.claude import ClaudeTextResult
from app.routes import chat as chat_route
from app.routes import shoot_check as shoot_check_route

CREATOR_ID = "creator-user-shoot-check-001"
BRAND_WS = "ws-brand-shoot-check-001"

# Minimal valid magic-number prefixes -- the route only ever checks the
# first few bytes, so the rest can be arbitrary filler.
JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"\x00" * 200
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"\x00" * 200


# --------------------------------------------------------------------------- helpers


def _verified(user_type: str, workspace_id: str) -> VerifiedToken:
    return VerifiedToken(
        workspace_id=workspace_id,
        scope="service",
        subject="u1",
        conversation_id=None,
        claims={"userType": user_type},
    )


def _multipart_request(
    fields: dict[str, str],
    *,
    image: bytes | None,
    filename: str = "shot.jpg",
    content_type: str = "image/jpeg",
    omit_image: bool = False,
) -> Request:
    boundary = "----shootcheckboundary"
    parts = []
    for name, value in fields.items():
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    if not omit_image:
        parts.append(
            (
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; "
                f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n"
            ).encode()
            + (image or b"")
            + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode())
    body_bytes = b"".join(parts)

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/ai/shoot-check/frame",
        "headers": [
            (b"content-type", f"multipart/form-data; boundary={boundary}".encode()),
            (b"content-length", str(len(body_bytes)).encode()),
        ],
        "query_string": b"",
        "client": ("test", 0),
    }
    return Request(scope, receive)


def _spring_with(context: dict | None) -> MagicMock:
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


def _consented_spring(**context) -> MagicMock:
    data = {"audience": "CREATOR", "consent_accepted": True, **context}
    return _spring_with(data)


def _claude_ok(payload: dict, *, usage: dict | None = None) -> MagicMock:
    claude = MagicMock()
    claude.complete_with_image = AsyncMock(
        return_value=ClaudeTextResult(
            ok=True,
            text=json.dumps(payload),
            usage=usage or {"input_tokens": 500, "output_tokens": 60},
        )
    )
    return claude


def _claude_text(raw_text: str, *, usage: dict | None = None) -> MagicMock:
    claude = MagicMock()
    claude.complete_with_image = AsyncMock(
        return_value=ClaudeTextResult(
            ok=True, text=raw_text, usage=usage or {"input_tokens": 500, "output_tokens": 60}
        )
    )
    return claude


def _claude_provider_error() -> MagicMock:
    claude = MagicMock()
    claude.complete_with_image = AsyncMock(
        return_value=ClaudeTextResult(ok=False, error="provider_error", usage=None)
    )
    return claude


@pytest.fixture(autouse=True)
async def _reset(monkeypatch):
    for var in ("AI_SPEND_KILL_SWITCH", "AI_CREATOR_MONTHLY_CAP_USD", "REDIS_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    for var in ("AI_DAILY_SPEND_CEILING_USD", "AI_CREATOR_MONTHLY_CAP_USD", "AI_SPEND_KILL_SWITCH"):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


async def _call(
    user_type: str,
    workspace_id: str,
    *,
    claude: MagicMock,
    spring: MagicMock,
    image: bytes | None = JPEG_BYTES,
    content_type: str = "image/jpeg",
    filename: str = "shot.jpg",
    shot_label: str | None = None,
    omit_image: bool = False,
):
    fields = {"workspace_id": workspace_id}
    if shot_label is not None:
        fields["shot_label"] = shot_label
    request = _multipart_request(
        fields, image=image, filename=filename, content_type=content_type, omit_image=omit_image
    )
    with patch.object(
        shoot_check_route, "verify_token", return_value=_verified(user_type, workspace_id)
    ), patch.object(shoot_check_route, "_get_claude", return_value=claude), patch.object(
        shoot_check_route, "_get_spring", return_value=spring
    ):
        return await shoot_check_route.shoot_check_frame(request, authorization="Bearer token")


async def _assert_nothing_held(workspace_id: str) -> None:
    assert await spend_tracker.get_reserved_creator(workspace_id) == Decimal(0)
    assert await spend_tracker.get_reserved_global() == Decimal(0)


# --------------------------------------------------------------------------- consent gate


@pytest.mark.asyncio
async def test_unconsented_creator_is_refused_with_zero_claude_calls():
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})
    spring = _spring_with({"audience": "CREATOR", "consent_accepted": False})

    response = await _call("CREATOR", CREATOR_ID, claude=claude, spring=spring)

    assert response.status_code == 403
    payload = json.loads(response.body)
    assert payload["code"] == "CONSENT_REQUIRED"
    assert payload["error"]["code"] == "CONSENT_REQUIRED"
    assert payload == json.loads(chat_route._consent_required_response().body)
    claude.complete_with_image.assert_not_awaited()
    await _assert_nothing_held(CREATOR_ID)


@pytest.mark.asyncio
async def test_context_fetch_failure_fails_closed():
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})
    response = await _call("CREATOR", CREATOR_ID, claude=claude, spring=_spring_down())

    assert response.status_code == 403
    payload = json.loads(response.body)
    assert payload["code"] == "CONSENT_REQUIRED"
    claude.complete_with_image.assert_not_awaited()


@pytest.mark.asyncio
async def test_brand_never_sees_the_consent_gate():
    claude = _claude_ok({"fixes": ["Move closer to the window."], "settings": [], "ok": []})
    spring = MagicMock()
    spring.get_meera_context = AsyncMock()

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=spring)

    assert result["fallback"] is False
    claude.complete_with_image.assert_awaited_once()
    spring.get_meera_context.assert_not_awaited()


# --------------------------------------------------------------------------- creator monthly cap gate


@pytest.mark.asyncio
async def test_creator_at_cap_is_blocked_with_zero_claude_calls():
    # Spend exactly the configured monthly cap (it was 0.75 when this test was written; launch
    # raised it to 2.00 on 2026-09-22), so the test pins "at the cap", not a number.
    await spend_tracker.record_creator_spend(spend_tracker.creator_monthly_cap_usd(), CREATOR_ID)
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})

    result = await _call("CREATOR", CREATOR_ID, claude=claude, spring=_consented_spring())

    assert result["fallback"] is True
    assert result["code"] == CREATOR_CAP_CODE
    assert result["message"] == CREATOR_CAP_MESSAGE
    claude.complete_with_image.assert_not_awaited()
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


@pytest.mark.asyncio
async def test_creator_under_cap_spend_lands_on_the_shared_monthly_ledger():
    claude = _claude_ok(
        {"fixes": ["Move the light in front of you."], "settings": ["Turn on the grid."], "ok": ["Good background."]}
    )
    result = await _call("CREATOR", CREATOR_ID, claude=claude, spring=_consented_spring())

    assert result["fallback"] is False
    assert await spend_tracker.get_creator_month_total(CREATOR_ID) > Decimal(0)
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


# --------------------------------------------------------------------------- daily spend gate


@pytest.mark.asyncio
async def test_kill_switch_blocks_with_zero_claude_calls_and_releases_creator_hold(monkeypatch):
    monkeypatch.setenv("AI_SPEND_KILL_SWITCH", "true")
    get_settings.cache_clear()
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})

    result = await _call("CREATOR", CREATOR_ID, claude=claude, spring=_consented_spring())

    assert result["fallback"] is True
    claude.complete_with_image.assert_not_awaited()
    assert await spend_tracker.get_reserved_creator(CREATOR_ID) == Decimal(0)


# --------------------------------------------------------------------------- upload validation (all BEFORE any model call)


@pytest.mark.asyncio
async def test_wrong_content_type_rejected_before_model_call():
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})

    result = await _call(
        "BRAND", BRAND_WS, claude=claude, spring=MagicMock(),
        image=b"hello world", content_type="text/plain", filename="notes.txt",
    )

    assert result["fallback"] is True
    claude.complete_with_image.assert_not_awaited()


@pytest.mark.asyncio
async def test_oversize_image_rejected_before_model_call(monkeypatch):
    monkeypatch.setenv("SHOOT_CHECK_MAX_IMAGE_BYTES", "1000")
    get_settings.cache_clear()
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})
    too_big = b"\xff\xd8\xff\xe0" + b"\x00" * 2000

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock(), image=too_big)

    assert result["fallback"] is True
    claude.complete_with_image.assert_not_awaited()
    monkeypatch.delenv("SHOOT_CHECK_MAX_IMAGE_BYTES", raising=False)
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_empty_file_rejected_before_model_call():
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock(), image=b"")

    assert result["fallback"] is True
    claude.complete_with_image.assert_not_awaited()


@pytest.mark.asyncio
async def test_truncated_file_rejected_before_model_call():
    """Declared image/jpeg, but the bytes are cut off before the JPEG magic
    number even finishes arriving -- a truncated upload, not a valid photo."""
    claude = _claude_ok({"fixes": ["x"], "settings": [], "ok": []})

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock(), image=b"\xff\xd8")

    assert result["fallback"] is True
    claude.complete_with_image.assert_not_awaited()


@pytest.mark.asyncio
async def test_missing_image_field_is_a_400():
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as exc_info:
        await _call("BRAND", BRAND_WS, claude=_claude_ok({}), spring=MagicMock(), omit_image=True)
    assert exc_info.value.status_code == 400


@pytest.mark.asyncio
async def test_png_upload_is_accepted():
    claude = _claude_ok({"fixes": ["Center the product."], "settings": [], "ok": []})

    result = await _call(
        "BRAND", BRAND_WS, claude=claude, spring=MagicMock(),
        image=PNG_BYTES, content_type="image/png", filename="shot.png",
    )

    assert result["fallback"] is False
    claude.complete_with_image.assert_awaited_once()
    assert claude.complete_with_image.await_args.kwargs["image_media_type"] == "image/png"


# --------------------------------------------------------------------------- defensive JSON parse


@pytest.mark.asyncio
async def test_happy_path_returns_the_three_lists():
    claude = _claude_ok(
        {
            "fixes": ["Move the light source in front of you.", "Raise the phone to eye level."],
            "settings": ["Turn on the grid.", "Lock focus before filming."],
            "ok": ["Background is clean."],
        }
    )

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is False
    assert result["fixes"] == ["Move the light source in front of you.", "Raise the phone to eye level."]
    assert result["settings"] == ["Turn on the grid.", "Lock focus before filming."]
    assert result["ok"] == ["Background is clean."]
    assert claude.complete_with_image.await_args.kwargs["model"] == SHOOT_CHECK_MODEL


@pytest.mark.asyncio
async def test_markdown_fenced_json_is_parsed():
    claude = _claude_text('```json\n{"fixes": ["Move closer."], "settings": [], "ok": []}\n```')

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is False
    assert result["fixes"] == ["Move closer."]


@pytest.mark.asyncio
async def test_unparseable_garbage_falls_back_to_a_single_fix_not_500():
    claude = _claude_text("Sure! Here's my feedback: the lighting could be better overall.")

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is True
    assert len(result["fixes"]) == 1
    assert result["settings"] == []
    assert result["ok"] == []


@pytest.mark.asyncio
async def test_provider_failure_falls_back_not_500():
    claude = _claude_provider_error()

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is True
    assert len(result["fixes"]) == 1


@pytest.mark.asyncio
async def test_nine_fixes_is_capped_at_three():
    claude = _claude_ok({"fixes": [f"fix {i}" for i in range(9)], "settings": [], "ok": []})

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is False
    assert len(result["fixes"]) == 3
    assert result["fixes"] == ["fix 0", "fix 1", "fix 2"]


@pytest.mark.asyncio
async def test_second_person_line_passes_through_without_crashing():
    claude = _claude_ok(
        {
            "fixes": ["There's a second person in frame -- consider whether they should be in this shot."],
            "settings": [],
            "ok": [],
        }
    )

    result = await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock())

    assert result["fallback"] is False
    assert "second person" in result["fixes"][0]


# --------------------------------------------------------------------------- privacy: never log or persist the bytes


@pytest.mark.asyncio
async def test_image_bytes_never_appear_in_any_log_record(caplog):
    claude = _claude_ok({"fixes": ["Move closer."], "settings": [], "ok": []})
    caplog.set_level(logging.DEBUG)

    await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock(), image=JPEG_BYTES)

    import base64

    b64 = base64.b64encode(JPEG_BYTES).decode("ascii")
    for record in caplog.records:
        message = record.getMessage()
        assert JPEG_BYTES not in message.encode("utf-8", errors="ignore")
        assert b64 not in message
        fields = getattr(record, "fields", None)
        if fields:
            assert b64 not in json.dumps(fields, default=str)


@pytest.mark.asyncio
async def test_image_bytes_are_never_written_to_disk(monkeypatch):
    import builtins

    written_modes: list[str] = []
    real_open = builtins.open

    def _spy_open(file, mode="r", *args, **kwargs):
        if any(flag in mode for flag in ("w", "a", "x")):
            written_modes.append(mode)
        return real_open(file, mode, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", _spy_open)

    claude = _claude_ok({"fixes": ["Move closer."], "settings": [], "ok": []})
    await _call("BRAND", BRAND_WS, claude=claude, spring=MagicMock(), image=JPEG_BYTES)

    assert written_modes == []
