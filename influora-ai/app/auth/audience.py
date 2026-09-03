"""Audience derivation for Meera turns — Phase A (A4) of Meera for Creators.

`audience` selects which persona, which Block B and which tool set a turn
gets. It is the info barrier's first switch (the cache key already carries
it — `app.prompt.assembler.cache_key_for`), and the DPDP consent gate (A6)
and the per-creator monthly cap (A8) both hang off it. So it is decided by
exactly ONE source: the VERIFIED token's claims.

Fix round 1 (BLOCKING): an earlier revision fell back to the on-behalf
JWT's `userType`, decoded WITHOUT signature verification (Python cannot
verify that HS256 token). That made `audience` client-downgradeable: a
creator holding a valid `chat:stream` token who simply omitted `onbehalf_jwt`
from the body made the route derive BRAND, skip the consent gate and the
creator cap, and run the brand persona with the brand tool set. The fallback
is deleted. `StreamTokenService.mint` (Spring) now mints `userType` into the
stream token itself — signature-checked by `app.auth.service_token` before
this module ever sees the claims — so the verified token always decides.

`derive_audience` returns `None` when the verified claims carry no
recognised audience. What the caller does with `None` is a per-route
decision (see `app.routes.chat`): a `chat:stream` token without the claim is
refused outright (fail closed -- every stream token is minted with it now,
so its absence is either a stale token from before this fix or a forged
shape), while a Spring-only `service` token keeps the pre-Phase-A brand
default because no browser can hold one.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)

AUDIENCE_BRAND = "BRAND"
AUDIENCE_CREATOR = "CREATOR"
KNOWN_AUDIENCES = frozenset({AUDIENCE_BRAND, AUDIENCE_CREATOR})

# Claim names accepted on the VERIFIED token, in priority order. `userType`
# is what `StreamTokenService.mint` (Spring, ES256, JWKS-verified) writes.
AUDIENCE_CLAIM_KEYS: tuple[str, ...] = ("audience", "userType", "user_type")


def derive_audience(verified_claims: dict[str, Any] | None) -> str | None:
    """Reads the audience off the VERIFIED token's claims -- and nothing else.

    Returns "BRAND" / "CREATOR", or `None` when no recognised claim is
    present. Any `userType` other than BRAND/CREATOR (e.g. ADMIN) is treated
    as unknown too -- there is no admin persona, and an unknown audience must
    never silently pick one.
    """
    if not isinstance(verified_claims, dict):
        return None
    for key in AUDIENCE_CLAIM_KEYS:
        value = verified_claims.get(key)
        if isinstance(value, str):
            upper = value.strip().upper()
            if upper in KNOWN_AUDIENCES:
                return upper
    return None
