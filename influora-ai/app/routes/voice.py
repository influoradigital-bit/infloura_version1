"""POST /voice/transcribe and POST /voice/speak.

Cascaded Claude (brain) + Sarvam (ears/mouth). Voice failure ALWAYS falls back
to text, silently, at every stage (§5 of the AI service spec):

| Failure                              | Behavior                                    |
|---------------------------------------|----------------------------------------------|
| Sarvam STT fails / low confidence      | "Didn't catch that - type it instead?"       |
| Grammar-cleanup pass errors            | fall back to raw_transcript (still edit-first)|
| Sarvam TTS fails                       | disable voice-output silently                 |
| Any provider timeout                   | structured error, fallback: "text"            |
| AI spend gate blocked (P2-17)          | same silent fallback as a provider failure    |
| CREATOR before DPDP consent (A6, Q3)   | 403 CONSENT_REQUIRED, same body as /chat; NOT |
|                                        | a silent fallback -- the client must show the |
|                                        | consent screen, not a "type instead" hint      |

`/voice/transcribe` is edit-first: cleaned_text lands in the composer, never
auto-sent. Credit weighting (input=3, reply=4) is surfaced to Spring's meter —
Python computes nothing about wallets/credits itself.

P18 (20-ROHAN-COST-REVIEW.md §3): TTS spoken-reply length is capped at ~200
characters to control voice-output costs. Truncation is graceful (ends at
sentence/word boundary if possible, adds ellipsis). The full reply is always
shown in the chat panel; this only limits what gets vocalized.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

import anyio
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response

from app.auth.audience import AUDIENCE_CREATOR, derive_audience
from app.auth.consent import CONSENT_REQUIRED_CODE, consent_accepted, consent_required_response
from app.auth.service_token import AuthError, auth_error_to_http, verify_token
from app.clients.spring import SpringInternalClient
from app.config import GEMINI_MODEL, get_settings
from app.costs.gate import check_spend_gate
from app.costs.pricing import (
    estimate_cost_usd,
    estimate_sarvam_flat_cost_usd,
    estimate_sarvam_tts_cost_usd,
)
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
from app.providers.gemini import GeminiProvider
from app.providers.sarvam import SarvamProvider, normalize_voice_language
from app.security.redaction import log_event, shape_of

# P18: Maximum characters to send to TTS. Raised 200 -> 500 so a normal Meera
# reply (the intro is ~270 chars) is spoken in FULL instead of being cut off
# mid-thought after the first sentence or two — the 200-char cap was a
# cost-margin guard (20-ROHAN-COST-REVIEW.md §3) but made the voice feel broken.
# 500 chars is ~Rs.1.50/call at the high Sarvam rate and is Sarvam bulbul:v3's
# comfortable single-input length (verified accepted); genuinely long replies
# still truncate gracefully at a sentence boundary below. Revisit if voice-TTS
# spend becomes a real cost line.
TTS_MAX_CHARS = 500

logger = logging.getLogger(__name__)
router = APIRouter()

_sarvam_provider: SarvamProvider | None = None
_gemini_provider: GeminiProvider | None = None
_spring_client: SpringInternalClient | None = None


def _get_sarvam() -> SarvamProvider:
    global _sarvam_provider
    if _sarvam_provider is None:
        _sarvam_provider = SarvamProvider()
    return _sarvam_provider


def _get_gemini() -> GeminiProvider:
    global _gemini_provider
    if _gemini_provider is None:
        _gemini_provider = GeminiProvider()
    return _gemini_provider


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
class VoicePrefs:
    """What one voice call needs from the caller's identity: the audience
    (from the VERIFIED claims), the language for this direction, and -- for a
    creator -- the per-creator monthly cap override Spring carries in the
    same context payload (gate fix round 1, Q7). One Spring fetch serves all
    three so the route never fetches the creator context twice."""

    audience: str | None
    language: str
    creator_cap_override: str | None = None
    # Gate fix round 1 (Priya Q3): A6 consent, read off the SAME context
    # fetch. True means "refuse this call with 403 CONSENT_REQUIRED before
    # any Sarvam/Gemini call". FAIL CLOSED for a creator: no context (fetch
    # failed, empty body), a missing key, a null or a non-bool all set it --
    # only a positive `consent_accepted: true` / non-empty
    # `consent_accepted_at` clears it. Always False for BRAND (no consent
    # screen exists for brands; the brand routes are unchanged).
    consent_required: bool = False


async def resolve_voice_language(
    *,
    verified_claims: dict[str, Any] | None,
    onbehalf_jwt: str,
    workspace_id: str,
    requested: str | None,
    direction: str,
    request_id: str,
    spring: SpringInternalClient | None = None,
) -> str:
    """A5: the BCP-47 language for one STT/TTS call. Thin wrapper over
    `resolve_voice_prefs` kept for callers/tests that only need the language."""
    prefs = await resolve_voice_prefs(
        verified_claims=verified_claims,
        onbehalf_jwt=onbehalf_jwt,
        workspace_id=workspace_id,
        requested=requested,
        direction=direction,
        request_id=request_id,
        spring=spring,
    )
    return prefs.language


async def resolve_voice_prefs(
    *,
    verified_claims: dict[str, Any] | None,
    onbehalf_jwt: str,
    workspace_id: str,
    requested: str | None,
    direction: str,
    request_id: str,
    spring: SpringInternalClient | None = None,
) -> VoicePrefs:
    """A5: the BCP-47 language for one STT (`direction="stt"`) or TTS
    (`direction="tts"`) call, plus the audience and (creator only) the cap
    override read off the same context fetch.

    CREATOR audience: ALWAYS the creator's `creator_language` from their Meera
    preferences, read off Spring's `POST /internal/meera/context`
    (audience=CREATOR) -- for both directions. A client-supplied `lang` never
    overrides a creator's own setting. If the context cannot be fetched the
    call still proceeds (voice must never dead-end) on the configured default.

    BRAND audience: the request's `lang` hint if any, else the configured
    default for that direction (`VOICE_DEFAULT_STT_LANGUAGE` /
    `VOICE_DEFAULT_TTS_LANGUAGE`). Nothing is hardcoded here or in the
    provider any more.
    """
    settings = get_settings()
    default = (
        settings.voice_default_stt_language
        if direction == "stt"
        else settings.voice_default_tts_language
    )
    # Fix round 1 (BLOCKING): audience comes from the VERIFIED token's claims
    # only -- the on-behalf JWT is forwarded to Spring for the context fetch
    # but never decoded here to pick the audience (app/auth/audience.py).
    audience = derive_audience(verified_claims)
    if audience != AUDIENCE_CREATOR:
        return VoicePrefs(
            audience=audience, language=normalize_voice_language(requested, default=default)
        )

    client = spring or _get_spring()
    cap_override: str | None = None
    # Q3: consent starts REQUIRED and is cleared only by a positive signal
    # from a successfully fetched context -- the "voice must never dead-end"
    # rule below covers the LANGUAGE (falls back to hi-IN); it never covers
    # consent, which is DPDP and fails closed exactly like /chat.
    consent_required = True
    try:
        response = await client.get_meera_context(
            workspace_id=workspace_id, audience=AUDIENCE_CREATOR, onbehalf_jwt=onbehalf_jwt
        )
        data = response.data if isinstance(response.data, dict) else {}
        creator_language = data.get("creator_language")
        cap_override = creator_cap_override_from_context(data)
        consent_required = not consent_accepted(data)
    except Exception as exc:  # noqa: BLE001 - voice must never dead-end on a context miss
        log_event(
            logger, logging.WARNING, "voice_creator_language_fetch_failed",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_type": type(exc).__name__, "direction": direction},
        )
        creator_language = None
    # For a creator, the preferences default (hi-IN) is the right fallback in
    # BOTH directions -- not the brand-side en-IN TTS default.
    return VoicePrefs(
        audience=audience,
        language=normalize_voice_language(creator_language, default="hi-IN"),
        creator_cap_override=cap_override,
        consent_required=consent_required,
    )


def _creator_consent_gate(prefs: VoicePrefs, *, workspace_id: str, request_id: str, route: str):
    """Gate fix round 1 (Priya Q3): the A6 consent gate on voice. Returns the
    403 CONSENT_REQUIRED response (same body as /chat, app/auth/consent.py)
    when a CREATOR call arrives before consent is recorded, else None.

    Sits BEFORE the creator monthly gate and the daily spend gate, so an
    unconsented call reserves nothing and reaches neither Sarvam nor Gemini.
    Nothing about this call is persisted or logged beyond the structured
    block event (workspace + request ids, never the audio or text). No-op for
    BRAND."""
    if prefs.audience != AUDIENCE_CREATOR or not prefs.consent_required:
        return None
    log_event(
        logger, logging.INFO, f"{route}_blocked_consent_required",
        workspace_id=workspace_id, request_id=request_id,
        fields={"error_code": CONSENT_REQUIRED_CODE},
    )
    return consent_required_response()


async def _creator_voice_gate(
    prefs: VoicePrefs, *, workspace_id: str, request_id: str, route: str
) -> tuple[Any, bool]:
    """Gate fix round 1 (Q7): CREATOR-audience voice calls share the creator's
    MONTHLY allowance with /chat -- previously voice never touched that
    counter, so a creator at their cap could keep spending on Sarvam STT/TTS
    indefinitely and voice spend never counted toward the chat cap.

    Returns `(creator_reservation, blocked)`. A block is logged here; the
    caller returns its own route-shaped fallback envelope (voice never
    dead-ends with a raw 4xx/5xx) carrying `CREATOR_CAP_CODE` and the same
    friendly message /chat uses. No-op `(None, False)` for BRAND.
    """
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
    """Give back whatever this call still holds. Both releases are idempotent,
    so this is safe after a settle (`record_spend` / `record_creator_spend`
    already released) and on the paths that billed nothing."""
    await release(daily_reservation)
    await release_creator(creator_reservation)


async def _record_ai_spend(
    cost_usd: Decimal,
    *,
    workspace_id: str,
    request_id: str,
    route: str,
    model: str,
    reservation: Any = None,
    audience: str | None = None,
    creator_reservation: Any = None,
) -> None:
    """Shared `record_spend` + `ai_spend` structured-log call for this route's
    three provider call sites (STT, Gemini cleanup, TTS) -- same shape as the
    inline "record_spend then log_event" block every other route repeats
    once, factored out here since voice.py has three call sites across two
    endpoints rather than one.

    Gate fix round 1 (Q7): a CREATOR-audience call ALSO accrues against the
    creator's monthly allowance (`workspace_id` is the creator's user id on
    that audience), settling the hold `_creator_voice_gate` took.
    """
    spend_today = await record_spend(cost_usd, workspace_id, reservation=reservation)
    creator_month_total = None
    if audience == AUDIENCE_CREATOR:
        creator_month_total = await record_creator_spend(
            cost_usd, workspace_id, reservation=creator_reservation
        )
    log_event(
        logger, logging.INFO, "ai_spend",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "route": route,
            "model": model,
            "audience": audience,
            "cost_usd": str(cost_usd),
            "spend_today_usd": str(spend_today),
            "creator_month_usd": (
                str(creator_month_total) if creator_month_total is not None else None
            ),
        },
    )


def _truncate_for_tts(text: str, max_chars: int = TTS_MAX_CHARS) -> str:
    """P18: Truncate text for TTS to control cost margin.

    Truncates gracefully:
    1. If text fits, return as-is.
    2. Otherwise, try to end at a sentence boundary (. ! ?) within the limit.
    3. Failing that, end at a word boundary within the limit.
    4. Add ellipsis to indicate truncation.

    The full reply is always visible in the chat panel; this only limits what
    gets vocalized.
    """
    if len(text) <= max_chars:
        return text

    # Leave room for ellipsis
    limit = max_chars - 3
    truncated = text[:limit]

    # Try to find a sentence boundary (. ! ?) for a natural stopping point.
    # Search backwards from the end for the last sentence-ending punctuation.
    for i in range(len(truncated) - 1, -1, -1):
        if truncated[i] in ".!?":
            # Include the punctuation, no ellipsis needed for sentence end
            return truncated[: i + 1]

    # No sentence boundary found; fall back to word boundary.
    # Find the last space to avoid cutting mid-word.
    last_space = truncated.rfind(" ")
    if last_space > 0:
        return truncated[:last_space] + "..."

    # No space found (single long word); just truncate.
    return truncated + "..."


def _transcribe_fallback(
    message: str = "Didn't catch that - type it instead?", *, code: str | None = None
) -> dict[str, Any]:
    """The route's single silent-fallback shape. `code` is set only for the
    creator monthly cap (Q7) so the client can show the friendly cap message
    verbatim instead of the generic retry hint."""
    payload: dict[str, Any] = {
        "raw_transcript": None,
        "cleaned_text": None,
        "lang_detected": None,
        "fallback": True,
        "message": message,
    }
    if code:
        payload["code"] = code
    return payload


@router.post("/voice/transcribe")
async def voice_transcribe(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    form = await request.form()
    workspace_id = form.get("workspace_id")
    audio_file = form.get("audio")

    if not workspace_id or audio_file is None:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id and audio are required"},
        )

    try:
        # F-09: off the event loop (blocking JWKS fetch).
        verified = await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization),
                endpoint="voice_transcribe",
                body_workspace_id=str(workspace_id),
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # A5: same on-behalf resolution as /chat -- an explicit form field, else
    # the bearer itself (Spring-proxied calls forward the user JWT that way).
    onbehalf_jwt = str(form.get("onbehalf_jwt") or "") or _bearer(authorization)
    requested_lang = form.get("lang")
    prefs = await resolve_voice_prefs(
        verified_claims=getattr(verified, "claims", None),
        onbehalf_jwt=onbehalf_jwt,
        workspace_id=str(workspace_id),
        requested=str(requested_lang) if isinstance(requested_lang, str) else None,
        direction="stt",
        request_id=request_id,
    )
    stt_language = prefs.language

    # Q3: A6 consent first -- before any hold is taken or any provider is
    # called. Same 403 CONSENT_REQUIRED body as /chat.
    consent_block = _creator_consent_gate(
        prefs, workspace_id=str(workspace_id), request_id=request_id, route="voice_transcribe"
    )
    if consent_block is not None:
        return consent_block

    # Q7: a creator's MONTHLY allowance is shared with /chat -- checked (and
    # reserved) before the daily gate and before either provider call.
    creator_reservation, cap_blocked = await _creator_voice_gate(
        prefs, workspace_id=str(workspace_id), request_id=request_id, route="voice_transcribe"
    )
    if cap_blocked:
        return _transcribe_fallback(CREATOR_CAP_MESSAGE, code=CREATOR_CAP_CODE)

    # P2-17 spend gate: checked before either provider call this route makes
    # (Sarvam STT, then Gemini cleanup) -- a block here skips both, same
    # "auth-first, then gate, then provider calls" ordering as chat.py /
    # brand_safety.py / analyze_site.py. Degrades to the same silent
    # STT-failure fallback rather than a raw 5xx, per this route's own
    # "never a dead end" contract (see module docstring's failure table).
    gate = await check_spend_gate(
        workspace_id=str(workspace_id),
        # F-05: see app/costs/gate.py — hold budget across the provider call.
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        await release_creator(creator_reservation)
        log_event(
            logger, logging.WARNING, "voice_transcribe_blocked_spend_gate",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return _transcribe_fallback()

    # A multipart part that arrives as a plain TEXT form field (not a file) is a
    # `str`, which has no `.read()`. Calling it crashed the route with an
    # unhandled 500 — mypy flagged this exact line, and a fresh-context auditor
    # independently reported "plain text form field crashes the route". This is
    # a client error on a route whose own contract is "never a dead end", so it
    # degrades to the same honest fallback every other input failure uses.
    if isinstance(audio_file, str) or not hasattr(audio_file, "read"):
        await _release_holds(gate.reservation, creator_reservation)
        log_event(
            logger, logging.WARNING, "voice_transcribe_bad_upload",
            workspace_id=str(workspace_id), request_id=request_id,
            fields={"received_type": type(audio_file).__name__},
        )
        return _transcribe_fallback()

    audio_bytes = await audio_file.read()
    log_event(
        logger, logging.INFO, "voice_transcribe_started",
        workspace_id=str(workspace_id), request_id=request_id,
        fields={"audio": shape_of(audio_bytes), "language": stt_language},
    )

    sarvam = _get_sarvam()
    stt_result = await sarvam.transcribe(audio_bytes, language=stt_language)

    # F-06: bill on `billed`, not on `ok`. `empty_transcript` is returned AFTER
    # a successful, billed HTTP 200 — POSTing silence in a loop used to buy free
    # unlimited STT while the daily counter never moved.
    if stt_result.billed:
        await _record_ai_spend(
            estimate_sarvam_flat_cost_usd(),
            workspace_id=str(workspace_id),
            request_id=request_id,
            route="voice_transcribe_stt",
            model="sarvam-stt",
            reservation=gate.reservation,
            audience=prefs.audience,
            creator_reservation=creator_reservation,
        )

    if not stt_result.ok:
        await _release_holds(gate.reservation, creator_reservation)
        return _transcribe_fallback()

    gemini = _get_gemini()
    cleanup_result = await gemini.cleanup_transcript(stt_result.raw_transcript or "")

    if cleanup_result.usage:
        try:
            cost_usd = estimate_cost_usd(GEMINI_MODEL, cleanup_result.usage)
            await _record_ai_spend(
                cost_usd,
                workspace_id=str(workspace_id),
                request_id=request_id,
                route="voice_transcribe_cleanup",
                model=GEMINI_MODEL,
                reservation=gate.reservation,
                audience=prefs.audience,
                creator_reservation=creator_reservation,
            )
        except ValueError as exc:
            log_event(
                logger, logging.ERROR, "ai_spend_pricing_error",
                workspace_id=str(workspace_id), request_id=request_id,
                fields={"error": str(exc)},
            )

    if cleanup_result.ok:
        cleaned_text = cleanup_result.cleaned_text
    else:
        # Grammar-cleanup pass errored -> fall back to raw_transcript, still edit-first.
        log_event(
            logger, logging.WARNING, "voice_cleanup_failed",
            workspace_id=str(workspace_id), request_id=request_id, fields={"error": cleanup_result.error},
        )
        cleaned_text = stt_result.raw_transcript

    await _release_holds(gate.reservation, creator_reservation)
    return {
        "raw_transcript": stt_result.raw_transcript,
        "cleaned_text": cleaned_text,
        "lang_detected": stt_result.lang_detected,
        "fallback": False,
    }


@router.post("/voice/speak")
async def voice_speak(request: Request, authorization: str | None = Header(default=None)):
    request_id = str(uuid.uuid4())
    body = await request.json()
    workspace_id = body.get("workspace_id")
    text = body.get("text")

    if not workspace_id or not text:
        raise HTTPException(
            status_code=400,
            detail={"code": "missing_fields", "message": "workspace_id and text are required"},
        )

    try:
        # F-09: off the event loop (blocking JWKS fetch).
        verified = await anyio.to_thread.run_sync(
            lambda: verify_token(
                _bearer(authorization), endpoint="voice_speak", body_workspace_id=workspace_id
            )
        )
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc

    # A5: creator turns speak in the creator's own language; brand turns use
    # the request's `lang` hint or the configured default. Nothing hardcoded.
    onbehalf_jwt = str(body.get("onbehalf_jwt") or "") or _bearer(authorization)
    prefs = await resolve_voice_prefs(
        verified_claims=getattr(verified, "claims", None),
        onbehalf_jwt=onbehalf_jwt,
        workspace_id=workspace_id,
        requested=body.get("lang") if isinstance(body.get("lang"), str) else None,
        direction="tts",
        request_id=request_id,
    )
    tts_language = prefs.language

    # Q3: A6 consent first -- same ordering and body as transcribe / chat.
    consent_block = _creator_consent_gate(
        prefs, workspace_id=workspace_id, request_id=request_id, route="voice_speak"
    )
    if consent_block is not None:
        return consent_block

    # Q7: the creator's MONTHLY allowance covers TTS too.
    creator_reservation, cap_blocked = await _creator_voice_gate(
        prefs, workspace_id=workspace_id, request_id=request_id, route="voice_speak"
    )
    if cap_blocked:
        return {"fallback": True, "message": CREATOR_CAP_MESSAGE, "code": CREATOR_CAP_CODE}

    # P2-17 spend gate: checked before the Sarvam TTS call below, same
    # ordering/degrade-not-500 rationale as voice_transcribe's gate above.
    gate = await check_spend_gate(
        workspace_id=workspace_id,
        reserve_usd=get_settings().ai_reservation_per_call_usd or None,
    )
    if not gate.allowed:
        await release_creator(creator_reservation)
        log_event(
            logger, logging.WARNING, "voice_speak_blocked_spend_gate",
            workspace_id=workspace_id, request_id=request_id,
            fields={"error_code": gate.error_code},
        )
        return {"fallback": True, "message": "voice reply unavailable"}

    # P18: Cap TTS text at ~200 chars for cost control (20-ROHAN-COST-REVIEW.md §3).
    # The full reply is visible in chat; this only limits vocalization.
    original_len = len(text)
    tts_text = _truncate_for_tts(text)
    was_truncated = len(tts_text) < original_len

    log_event(
        logger, logging.INFO, "voice_speak_started",
        workspace_id=workspace_id, request_id=request_id,
        fields={
            "text": shape_of(tts_text),
            "truncated": was_truncated,
            "original_len": original_len,
            "language": tts_language,
        },
    )

    sarvam = _get_sarvam()
    result = await sarvam.speak(tts_text, lang=tts_language)

    # P2 (Ash AI review): TTS is char-scaled, not the flat STT estimate.
    #
    # F-07: bill `result.billed_chars` — the length of `speakable(chunk)`, the
    # text Sarvam actually received — not `len(tts_text)`. speakable() expands
    # rupee amounts and initialisms before the post, so "Budget ₹15,000–₹75,000
    # per creator for UGC" is 42 chars pre-normalization and 68 as charged: a
    # systematic ~40% under-bill on exactly the budget-quoting replies Meera
    # produces most.
    #
    # F-06: bill on `billed`, not on `ok`. `invalid_audio_response` and
    # `empty_audio` are both returned AFTER a successful, billed HTTP 200, and a
    # multi-chunk reply can fail on a later chunk with earlier chunks already
    # billed.
    if result.billed:
        await _record_ai_spend(
            estimate_sarvam_tts_cost_usd(result.billed_chars or len(tts_text)),
            workspace_id=workspace_id,
            request_id=request_id,
            route="voice_speak_tts",
            model="sarvam-tts",
            reservation=gate.reservation,
            audience=prefs.audience,
            creator_reservation=creator_reservation,
        )
    await _release_holds(gate.reservation, creator_reservation)

    if not result.ok:
        # Silent fallback -- text reply already rendered client-side; voice-output
        # simply doesn't play. Return a small JSON signal instead of audio bytes
        # so the frontend can disable the voice-output UI without an error wall.
        return {"fallback": True, "message": "voice reply unavailable"}

    return Response(content=result.audio_bytes, media_type=result.content_type or "audio/wav")
