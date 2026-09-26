"""POST /ai/shoot-check/frame — Level 2 "frame check": one photo in, three
fixes out (T-SHOOTCHECK-L2).

SIBLING of `app/routes/voice.py`, not a new pattern. Same gate order, same
helpers, same "never a raw 5xx, always a deterministic fallback body" shape:

    creator consent gate (A6, DPDP)
      -> creator monthly-cap reservation (Q7, no-op for BRAND)
      -> daily workspace spend-ceiling reservation (P2-17)
      -> upload validation (content type / size / non-empty)
      -> the ONE Claude vision call (app.providers.claude.complete_with_image)
      -> record spend (settles both reservations)
      -> release both holds on ANY failure path

Auth is the SAME service-token shape voice.py uses (`endpoint="shoot_check_frame"`,
scope=SCOPE_SERVICE, `workspace_id` in the form body must match the verified
token, `onbehalf_jwt` form field or the bearer itself forwarded to Spring for
the CREATOR context fetch) -- this is not the creator-scoped
`verify_creator_token` pattern brief_extract.py/creator_suggestion.py use,
because the caller here (like voice.py's callers) presents a workspace-scoped
service/stream token, not a creator-profile-scoped one; CREATOR vs BRAND is
still derived from the VERIFIED token's claims (`app.auth.audience`), exactly
as voice.py does it.

Metering, not charging (see app/config.py's `shoot_check_credit_cost_credits`
docstring): every check this route completes past the gates is logged
(workspace_id + request_id + timestamp, already carried by every `log_event`
call; shot_label as a SHAPE only, never the raw text; whether the check
produced usable AI fixes or fell back) via the same structured `ai_spend`-
style logging every other route in this service uses. There is no credit-
charge call anywhere in this file -- Swapnil has not ruled on whether a frame
check should cost one, and creator turns charge zero everywhere else today
(`creditsCharged(0)`, `influora-api/.../MeeraSessionService.java`).

Upload handling: the image is read into memory once (`await image.read()`),
base64-encoded in memory for the provider call, and never written to disk or
logged in any form -- this service is stateless per request by design (see
app/main.py's module docstring: "no DB, no session store, no local disk
writes"), and this route adds nothing that would break that.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

import anyio
from fastapi import APIRouter, Header, HTTPException, Request

from app.auth.audience import AUDIENCE_CREATOR, derive_audience
from app.auth.consent import CONSENT_REQUIRED_CODE, consent_accepted, consent_required_response
from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.clients.spring import SpringInternalClient
from app.config import SHOOT_CHECK_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import estimate_cost_usd
from app.costs.spend_tracker import (
    CREATOR_CAP_CODE,
    CREATOR_CAP_MESSAGE,
    SpendCapExceeded,
    check_creator_spend_gate,
    creator_cap_override_from_context,
    record_creator_spend,
    record_spend,
    release,
    release_creator,
)
from app.prompt.frame_check import build_system_prompt, build_user_text, fallback_response, parse_frame_check_response
from app.providers.claude import ClaudeProvider
from app.security.redaction import log_event, shape_of

logger = logging.getLogger(__name__)
router = APIRouter()

# Upload validation. Only these two content types are accepted -- a frame
# check is a phone-camera photo, never a video, PDF, or arbitrary blob.
ALLOWED_CONTENT_TYPES = frozenset({"image/jpeg", "image/png"})

# Minimal magic-number signatures. Used to reject an empty or truncated file
# whose declared Content-Type lies about what bytes actually arrived --
# `image_file.content_type` alone is client-supplied and unverified.
_JPEG_MAGIC = b"\xff\xd8\xff"
_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

_claude_provider: ClaudeProvider | None = None
_spring_client: SpringInternalClient | None = None


def _get_claude() -> ClaudeProvider:
    global _claude_provider
    if _claude_provider is None:
        _claude_provider = ClaudeProvider()
    return _claude_provider


def _get_spring() -> SpringInternalClient:
    global _spring_client
    if _spring_client is None:
        _spring_client = SpringInternalClient()
    return _spring_client


def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return ""


@dataclass(frozen=True)
class FrameCheckPrefs:
    """What one frame-check call needs from the caller's identity: the
    audience (from the VERIFIED token's claims) and -- for a creator -- the
    per-creator monthly cap override and the A6 consent flag, both read off
    the same Spring context fetch `voice.py`'s `VoicePrefs` reads them from.

    Deliberately NOT `voice.py`'s `VoicePrefs` reused directly: this route
    has no direction/language concept (no `resolve_voice_language` sibling),
    so a shared dataclass would carry a `language` field this route can never
    populate meaningfully. The GATE LOGIC below still calls the exact same
    shared helpers (`consent_accepted`, `check_creator_spend_gate`,
    `creator_cap_override_from_context`) voice.py calls -- only the
    language-specific wrapper is not reused, because there is nothing here
    for it to wrap.
    """

    audience: str | None
    creator_cap_override: str | None = None
    # Fail CLOSED for a creator, same as voice.py's VoicePrefs.consent_required:
    # no context (fetch failed, empty body), a missing key, a null, or a non-bool
    # all leave this True -- only a version-aware `consent_accepted: true` clears
    # it. Always False for BRAND.
    consent_required: bool = False


async def resolve_frame_check_prefs(
    *,
    verified_claims: dict[str, Any] | None,
    onbehalf_jwt: str,
    workspace_id: str,
    request_id: str,
    spring: SpringInternalClient | None = None,
) -> FrameCheckPrefs:
    """Sibling of `voice.resolve_voice_prefs`, minus the language resolution
    this route has no use for. One Spring context fetch (CREATOR audience
    only) serves both the consent flag and the cap override, same as voice.py.
    """
    audience = derive_audience(verified_claims)
    if audience != AUDIENCE_CREATOR:
        return FrameCheckPrefs(audience=audience)

    client = spring or _get_spring()
    cap_override: str | None = None
    # Starts REQUIRED; only a successfully fetched context carrying the
    # version-aware `consent_accepted: true` clears it (K-4, app/auth/consent.py).
    consent_required = True
    try:
        response = await client.get_meera_context(
            workspace_id=workspace_id, audience=AUDIENCE_CREATOR, onbehalf_jwt=onbehalf_jwt
        )
        data = response.data if isinstance(response.data, dict) else {}
        cap_override = creator_cap_override_from_context(data)
        consent_required = not consent_accepted(data)
    except Exception as exc:  # noqa: BLE001 - a context-fetch failure must not crash the route
        log_event(
            logger, logging.WARNING, "shoot_check_frame_context_fetch_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_type": type(exc).__name__},
        )
        # consent_required stays True (fail closed); cap_override stays None
        # (process-wide default applies) -- same "language may degrade, DPDP
        # consent never does" split voice.py documents.
    return FrameCheckPrefs(
        audience=audience, creator_cap_override=cap_override, consent_required=consent_required
    )


def _consent_gate(prefs: FrameCheckPrefs, *, workspace_id: str, request_id: str, route: str):
    """Same shape as `voice._creator_consent_gate`: the A6 403 CONSENT_REQUIRED
    response (identical body to /chat and /voice/*) when a CREATOR call
    arrives before consent is recorded, else None. No-op for BRAND. Runs
    before any reservation is taken and before the upload is even read."""
    if prefs.audience != AUDIENCE_CREATOR or not prefs.consent_required:
        return None
    log_event(
        logger, logging.INFO, f"{route}_blocked_consent_required",
        workspace_id=workspace_id, request_id=request_id,
        fields={"error_code": CONSENT_REQUIRED_CODE},
    )
    return consent_required_response()


async def _creator_cap_gate(
    prefs: FrameCheckPrefs, *, workspace_id: str, request_id: str, route: str
) -> tuple[Any, bool]:
    """Same shape as `voice._creator_voice_gate`: reserves against the
    creator's shared monthly allowance (the SAME counter /chat and
    /voice/* use). Returns `(creator_reservation, blocked)`; a block is
    logged here, the caller returns the route's own fallback envelope.
    No-op `(None, False)` for BRAND."""
    if prefs.audience != AUDIENCE_CREATOR:
        return None, False
    try:
        reservation = await check_creator_spend_gate(
            workspace_id,
            prefs.audience,
            reserve_usd=get_settings().ai_reservation_per_call_usd or None,
            cap_usd=prefs.creator_cap_override,
        )
    except SpendCapExceeded:
        log_event(
            logger, logging.WARNING, f"{route}_blocked_creator_monthly_cap",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": CREATOR_CAP_CODE},
        )
        return None, True
    return reservation, False


async def _release_holds(daily_reservation: Any, creator_reservation: Any) -> None:
    """Give back whatever this call still holds. Both releases are idempotent
    (safe after a settle, and on the paths that billed nothing) -- identical
    helper to `voice._release_holds`."""
    await release(daily_reservation)
    await release_creator(creator_reservation)


def _looks_like_declared_type(content_type: str | None, data: bytes) -> bool:
    """True when `data` actually starts with the magic bytes for the
    DECLARED `content_type`. Catches three things at once: a zero-byte file
    (an empty `data` never starts with either magic), a truncated upload cut
    off before its magic bytes finished arriving, and a mislabeled upload
    (any other file relabeled `image/jpeg`/`image/png`) -- `content_type`
    alone is a client-supplied header and proves nothing on its own."""
    if content_type == "image/jpeg":
        return data.startswith(_JPEG_MAGIC)
    if content_type == "image/png":
        return data.startswith(_PNG_MAGIC)
    return False


def _log_frame_check_metered(
    *, workspace_id: str, request_id: str, shot_label: str | None, success: bool
) -> None:
    """The metering line (see this module's + app/config.py's docstrings):
    one structured log record per completed check, NEVER the image bytes or
    the model's text -- `shot_label` rides through as a shape only, exactly
    like every other free-text field this service logs
    (app/security/redaction.py's `shape_of`)."""
    log_event(
        logger, logging.INFO, "shoot_check_frame_metered",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "route": "shoot_check_frame",
            "model": SHOOT_CHECK_MODEL,
            "success": success,
            "shot_label": shape_of(shot_label),
            "credits_charged": get_settings().shoot_check_credit_cost_credits,
        },
    )


@router.post("/ai/shoot-check/frame")
async def shoot_check_frame(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    form = await request.form()
    workspace_id = form.get("workspace_id")
    image_file = form.get("image")
    shot_label_raw = form.get("shot_label")
    shot_label = str(shot_label_raw) if isinstance(shot_label_raw, str) else None

    if not workspace_id or image_file is None:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id and image are required"},
        )

    try:
        # F-09 pattern (voice.py): off the event loop (blocking JWKS fetch).
        verified = await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization),
                endpoint="shoot_check_frame",
                body_workspace_id=str(workspace_id),
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    onbehalf_jwt = str(form.get("onbehalf_jwt") or "") or _bearer(authorization)
    prefs = await resolve_frame_check_prefs(
        verified_claims=getattr(verified, "claims", None),
        onbehalf_jwt=onbehalf_jwt,
        workspace_id=str(workspace_id),
        request_id=request_id,
    )

    # 1) A6 consent -- before any hold is taken or any provider is called.
    consent_block = _consent_gate(
        prefs, workspace_id=str(workspace_id), request_id=request_id, route="shoot_check_frame"
    )
    if consent_block is not None:
        return consent_block

    # 2) Creator's MONTHLY allowance (shared with /chat and /voice/*).
    creator_reservation, cap_blocked = await _creator_cap_gate(
        prefs, workspace_id=str(workspace_id), request_id=request_id, route="shoot_check_frame"
    )
    if cap_blocked:
        return {**fallback_response(), "fallback": True, "message": CREATOR_CAP_MESSAGE, "code": CREATOR_CAP_CODE}

    # 3) Daily workspace spend ceiling -- checked before the upload is even
    # read, same ordering voice.py uses for its own provider calls.
    gate = await check_spend_gate(
        workspace_id=str(workspace_id),
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        await release_creator(creator_reservation)
        log_event(
            logger, logging.WARNING, "shoot_check_frame_blocked_spend_gate",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return {**fallback_response(), "fallback": True}

    # 4) Upload validation -- content type, size, non-empty/non-truncated.
    # Bytes are never written to disk and never logged (see this module's
    # docstring); only shapes/booleans reach the logger below.
    if isinstance(image_file, str) or not hasattr(image_file, "read"):
        await _release_holds(gate.reservation, creator_reservation)
        log_event(
            logger, logging.WARNING, "shoot_check_frame_bad_upload",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"received_type": type(image_file).__name__},
        )
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {**fallback_response(), "fallback": True}

    content_type = getattr(image_file, "content_type", None)
    if content_type not in ALLOWED_CONTENT_TYPES:
        await _release_holds(gate.reservation, creator_reservation)
        log_event(
            logger, logging.WARNING, "shoot_check_frame_rejected_content_type",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"content_type": content_type},
        )
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {
            **fallback_response(),
            "fallback": True,
            "fixes": ["That file isn't a JPEG or PNG photo -- please upload one of those and try again."],
        }

    settings = get_settings()
    image_bytes = await image_file.read()

    if len(image_bytes) > settings.shoot_check_max_image_bytes:
        await _release_holds(gate.reservation, creator_reservation)
        log_event(
            logger, logging.WARNING, "shoot_check_frame_rejected_oversize",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"image": shape_of(image_bytes)},
        )
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {
            **fallback_response(),
            "fallback": True,
            "fixes": ["That photo is too large -- please use one under 1.5 MB and try again."],
        }

    if not image_bytes or not _looks_like_declared_type(content_type, image_bytes):
        await _release_holds(gate.reservation, creator_reservation)
        log_event(
            logger, logging.WARNING, "shoot_check_frame_rejected_empty_or_truncated",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"image": shape_of(image_bytes)},
        )
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {
            **fallback_response(),
            "fallback": True,
            "fixes": ["That photo looks empty or didn't fully upload -- please try again."],
        }

    log_event(
        logger, logging.INFO, "shoot_check_frame_started",
        workspace_id=str(workspace_id), request_id=request_id,
        fields={"image": shape_of(image_bytes), "content_type": content_type, "shot_label": shape_of(shot_label)},
    )

    # 5) The one model call.
    claude = _get_claude()
    result = await claude.complete_with_image(
        system=build_system_prompt(),
        user_text=build_user_text(shot_label),
        image_bytes=image_bytes,
        image_media_type=content_type,
        model=SHOOT_CHECK_MODEL,
        max_tokens=settings.shoot_check_max_tokens,
    )

    # 6) Record spend (settles both reservations) whenever the provider
    # actually billed usage -- matches voice.py's "record on usage, not on
    # ok" F-06 discipline.
    if result.usage:
        try:
            cost_usd = estimate_cost_usd(SHOOT_CHECK_MODEL, result.usage)
            spend_today = await record_spend(cost_usd, str(workspace_id), reservation=gate.reservation)
            creator_month_total = None
            if prefs.audience == AUDIENCE_CREATOR:
                creator_month_total = await record_creator_spend(
                    cost_usd, str(workspace_id), reservation=creator_reservation
                )
            log_event(
                logger, logging.INFO, "ai_spend",
                workspace_id=str(workspace_id), request_id=request_id,
                fields={
                    "route": "shoot_check_frame",
                    "model": SHOOT_CHECK_MODEL,
                    "audience": prefs.audience,
                    "cost_usd": str(cost_usd),
                    "spend_today_usd": str(spend_today),
                    "creator_month_usd": (
                        str(creator_month_total) if creator_month_total is not None else None
                    ),
                },
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                workspace_id=str(workspace_id), request_id=request_id,
                fields={"error": str(exc)},
            )
    # 7) Safety-net release, unconditional -- mirrors voice.py's own shape. A
    # successful `record_spend`/`record_creator_spend` above already released/
    # settled its own reservation (both releases are idempotent), so this is
    # a no-op on the happy path and the guard against every OTHER path that
    # could leave a hold unsettled: no usage at all (provider error, circuit
    # open) or `estimate_cost_usd` raising `ValueError` before either
    # `record_*` call ran.
    await _release_holds(gate.reservation, creator_reservation)

    if not result.ok or not result.text:
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {**fallback_response(), "fallback": True}

    parsed = parse_frame_check_response(result.text)
    if parsed is None:
        log_event(
            logger, logging.WARNING, "shoot_check_frame_malformed_model_output",
            workspace_id=str(workspace_id), request_id=request_id, fields={},
        )
        _log_frame_check_metered(
            workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=False
        )
        return {**fallback_response(), "fallback": True}

    log_event(
        logger, logging.INFO, "shoot_check_frame_completed",
        workspace_id=str(workspace_id), request_id=request_id,
        fields={
            "fixes_count": len(parsed["fixes"]),
            "settings_count": len(parsed["settings"]),
            "ok_count": len(parsed["ok"]),
        },
    )
    _log_frame_check_metered(
        workspace_id=str(workspace_id), request_id=request_id, shot_label=shot_label, success=True
    )
    return {**parsed, "fallback": False}
