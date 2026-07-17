"""Mints the `X-Meera-Service-Token` — the credential Python presents to Spring
on every `/internal/meera/*` call.

This is the OTHER direction from `app.auth.service_token` (which verifies
tokens *Spring* mints for calls *into* Python). This module mints the token
*Python* sends *to* Spring, matched byte-for-byte against what
`com.influora.security.InternalServiceTokenFilter` (Java) requires:

    iss  = "meera-python"           (InternalServiceTokenFilter.SERVICE_TOKEN_ISSUER)
    aud  = "influora-internal"      (InternalServiceTokenFilter.SERVICE_TOKEN_AUDIENCE)
    alg  = HS256                    (Keys.hmacShaKeyFor over the shared signing secret)
    iat  = now (always set -- the Java filter computes exp-iat and requires
           iat to be present; never omit it)
    exp  = iat + ttl_seconds, ttl_seconds <= MAX_TTL_SECONDS (60s ceiling,
           InternalServiceTokenProperties.MAX_TTL_SECONDS) -- NOT 5 minutes.
           The old docstring in app/clients/spring.py said "<=5 min"; that was
           wrong / aspirational and is fixed alongside this module. The Java
           side is the source of truth and it hard-caps at 60s regardless of
           what any client requests.

Signed with `settings.service_token_signing_key` -- a secret DISTINCT from
`internal_hmac_key` (which signs the X-Meera-Signature request HMAC) and from
every other key in the system, matching `InternalServiceTokenProperties`'s
`signingSecret` (token) vs `hmacSigningSecret` (request signature) split on
the Java side ([SEC: MF-3, LB-1] -- blast-radius separation between "prove
this call came from Python" and "prove this request body wasn't tampered
with").
"""

from __future__ import annotations

import time

import jwt

from app.config import get_settings

SERVICE_TOKEN_ISSUER = "meera-python"
SERVICE_TOKEN_AUDIENCE = "influora-internal"

# Hard ceiling matching InternalServiceTokenProperties.MAX_TTL_SECONDS on the
# Java side exactly. Never request a longer TTL than this -- Spring rejects
# any service token whose (exp - iat) exceeds it, no matter what we ask for.
MAX_TTL_SECONDS = 60


class ServiceTokenMintError(Exception):
    """Raised when a service token cannot be minted (missing signing key)."""


def mint_service_token(*, ttl_seconds: int | None = None) -> str:
    """Mints a fresh `X-Meera-Service-Token` for one internal call.

    Tokens are minted per-call (not cached/reused across requests) since the
    ceiling is only 60s -- there is no benefit to caching and it keeps the
    "short-lived" property honest under clock drift between processes.
    """
    settings = get_settings()
    if not settings.service_token_signing_key:
        raise ServiceTokenMintError(
            "SERVICE_TOKEN_SIGNING_KEY is not configured -- cannot mint X-Meera-Service-Token"
        )

    effective_ttl = MAX_TTL_SECONDS if ttl_seconds is None else min(ttl_seconds, MAX_TTL_SECONDS)
    if effective_ttl <= 0:
        raise ServiceTokenMintError("ttl_seconds must be positive")

    now = int(time.time())
    claims = {
        "iss": SERVICE_TOKEN_ISSUER,
        "aud": SERVICE_TOKEN_AUDIENCE,
        "iat": now,
        "exp": now + effective_ttl,
        "sub": "meera-ai-service",
    }
    return jwt.encode(claims, settings.service_token_signing_key, algorithm="HS256")
