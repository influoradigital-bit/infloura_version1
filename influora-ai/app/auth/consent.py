"""DPDP consent gate for CREATOR-audience Meera turns (Phase A, A6).

One definition, shared by every route a creator can reach with a verified
token -- `/chat` (app.routes.chat) AND `/voice/transcribe` + `/voice/speak`
(app.routes.voice). Gate fix round 1 (Priya Q3): the voice routes had NO
consent check at all; they verified the token, resolved the creator's
language off the same Spring context that carries `consent_accepted`, and
went straight to Sarvam. Spring blocks an unconsented creator at
`CreatorMeeraController.requireConsent` before anything is persisted, so the
Python gate is defence in depth -- but "defence in depth" that exists on one
of two routes is a hole, not a layer. Lifting the helpers here means a third
creator route cannot forget them by not importing chat.py.

Fail-closed contract (unchanged from chat.py): only a POSITIVE signal from
Spring's CREATOR context -- `consent_accepted: true` or a non-empty
`consent_accepted_at` -- counts as consent. A missing key, a null, a string
"true", a fetch failure, or no context at all all read as NOT consented.
"""

from __future__ import annotations

from typing import Any

from fastapi import status
from fastapi.responses import JSONResponse

CONSENT_REQUIRED_CODE = "CONSENT_REQUIRED"
CONSENT_REQUIRED_MESSAGE = "You must accept Meera's terms before using this feature."
CONSENT_REQUIRED_ACTION = "show_consent_screen"


def consent_accepted(creator_context: dict[str, Any] | None) -> bool:
    """A6: True only when Spring's creator context POSITIVELY says consent was
    recorded -- `consent_accepted: true` or a non-empty `consent_accepted_at`.
    A missing key, a null, or a fetch failure all read as NOT consented (DPDP
    consent fails closed, never open)."""
    if not isinstance(creator_context, dict):
        return False
    if creator_context.get("consent_accepted") is True:
        return True
    accepted_at = creator_context.get("consent_accepted_at")
    return isinstance(accepted_at, str) and bool(accepted_at.strip())


def consent_required_payload() -> dict[str, Any]:
    """The 403 body. Carries the code at BOTH the top level (the frontend
    spec reads `err.response.data.code`, §4.7) and under `error` (the chat
    service's existing `_error_response` envelope, which every other client
    of these routes already parses). Identical on /chat and /voice/* so one
    client handler covers all three."""
    return {
        "code": CONSENT_REQUIRED_CODE,
        "message": CONSENT_REQUIRED_MESSAGE,
        "action": CONSENT_REQUIRED_ACTION,
        "error": {"code": CONSENT_REQUIRED_CODE, "message": CONSENT_REQUIRED_MESSAGE},
    }


def consent_required_response() -> JSONResponse:
    """403 CONSENT_REQUIRED, the same body on every creator route."""
    return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=consent_required_payload())
