"""Spring-issued token validation — the FIRST gate on every non-health endpoint.

Two token shapes reach this service, both minted by Spring, never by the browser
holding an LLM key and never a lone static shared secret (Kabir guardrail #2):

- **Service token**: `aud=influora-internal`, TTL <= 5 min. Used for
  `/analyze-site`, `/voice/*`, and Spring-proxied `/chat`.
- **Scoped stream token**: `scope=chat:stream`, single workspace_id, single
  conversation, short TTL (<=60s minted, but we validate exp like any JWT).
  Used for the browser's direct SSE connection to `/chat`.

Validation order on every call, BEFORE any provider call:
1. Verify signature against Spring's JWKS (cache, honor `kid`); reject expired /
   wrong `aud` / wrong `iss` / wrong alg.
2. Assert `token.workspace_id == body.workspace_id` -> 403 on mismatch.
3. Assert token scope matches the endpoint being called -> 403 on mismatch.
4. Any failure -> 401/403, structured error, no provider call, no token spend.

The JWKS source is pluggable (`JwksSource` protocol) so tests can mock it and so
swapping HTTP-JWKS for a static dev key is a one-line change.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx
import jwt
from fastapi import Header, HTTPException, Request, status
from jwt import PyJWKClient

from app.config import get_settings

ALLOWED_ALGS = ("RS256", "ES256")  # asymmetric only; never accept HS256 from JWKS path

SCOPE_SERVICE = "service"
SCOPE_CHAT_STREAM = "chat:stream"

# Maps each endpoint to the scopes allowed to call it.
ENDPOINT_SCOPES: dict[str, tuple[str, ...]] = {
    "chat": (SCOPE_SERVICE, SCOPE_CHAT_STREAM),
    "analyze_site": (SCOPE_SERVICE,),
    "voice_transcribe": (SCOPE_SERVICE,),
    "voice_speak": (SCOPE_SERVICE,),
    # Internal-only: called by Java's BrandSafetyAiClient (Wave C, C3), never by
    # a browser/stream token. Service-scope only, same as analyze_site/voice.
    "brand_safety": (SCOPE_SERVICE,),
}


class AuthError(Exception):
    def __init__(self, status_code: int, code: str, message: str):
        self.status_code = status_code
        self.code = code
        self.message = message
        super().__init__(message)


@dataclass(frozen=True)
class VerifiedToken:
    workspace_id: str
    scope: str
    subject: str | None
    conversation_id: str | None
    claims: dict[str, Any]


class JwksSource(Protocol):
    """Pluggable source of the signing key for a given `kid`. Real implementation
    hits Spring's JWKS endpoint and caches; tests provide a static mock.
    """

    def get_signing_key(self, kid: str | None) -> Any: ...


class HttpJwksSource:
    """Fetches and caches Spring's rotating JWKS via PyJWKClient (honors `kid`,
    caches keys, refetches on unknown `kid`)."""

    def __init__(self, jwks_url: str, cache_seconds: int = 300):
        self._client = PyJWKClient(jwks_url, cache_keys=True, lifespan=cache_seconds)

    def get_signing_key(self, kid: str | None):
        # PyJWKClient resolves the key from the token itself (reads header `kid`),
        # so we fetch via the raw token in `verify_service_token` instead of here.
        raise NotImplementedError("use get_signing_key_from_jwt")

    def get_signing_key_from_jwt(self, token: str):
        return self._client.get_signing_key_from_jwt(token)


class StaticDevJwksSource:
    """Dev-only fallback: a single symmetric secret, used when SPRING_JWKS_URL is
    not configured (local dev). Never used when a JWKS URL is present.

    ADR binding condition #4 (`wiki/decisions/2026-07-07-spring-python-service-auth-
    jwks-gap.md`): this branch must be structurally UNREACHABLE in any prod/staging-
    facing profile, not merely discouraged by convention. `__init__` enforces this as
    a hard code assertion — instantiating this class when `settings.env != "dev"`
    raises immediately, before any token could ever be checked against it. This is
    intentionally redundant with the `_assert_dev_jwks_source_is_dev_only` check in
    `_decode_and_verify` (defense-in-depth: two independent call sites both refuse,
    not one gate that could be individually bypassed by a future refactor).
    """

    def __init__(self, secret: str):
        settings = get_settings()
        if settings.env != "dev":
            raise RuntimeError(
                "StaticDevJwksSource (HS256 dev fallback) must never be constructed "
                f"outside env=dev (got env={settings.env!r}). This is a hard, binding "
                "condition of wiki/decisions/2026-07-07-spring-python-service-auth-"
                "jwks-gap.md — configure SPRING_JWKS_URL for this environment instead "
                "of relying on the shared HS256 dev secret."
            )
        self._secret = secret

    def get_signing_key_from_jwt(self, token: str):
        class _Key:
            def __init__(self, key: str):
                self.key = key

        return _Key(self._secret)


_jwks_source_singleton: JwksSource | None = None


def _assert_dev_jwks_source_is_dev_only(source: Any) -> None:
    """Second, independent enforcement point for ADR binding condition #4 — even if a
    `StaticDevJwksSource` somehow already exists (e.g. injected directly via
    `set_jwks_source_for_testing` in a misconfigured test, or a future refactor that
    bypasses `_get_jwks_source`'s construction-time guard), verifying a real token
    against it while `env != dev` is refused here too. Fails closed (raises), never
    silently falls through to alg validation.
    """
    if isinstance(source, StaticDevJwksSource) and get_settings().env != "dev":
        raise AuthError(
            status.HTTP_401_UNAUTHORIZED,
            "dev_jwks_source_outside_dev",
            "StaticDevJwksSource (HS256 dev fallback) cannot be used outside env=dev",
        )


def _get_jwks_source() -> Any:
    global _jwks_source_singleton
    if _jwks_source_singleton is not None:
        return _jwks_source_singleton
    settings = get_settings()
    if settings.spring_jwks_url:
        _jwks_source_singleton = HttpJwksSource(
            settings.spring_jwks_url, settings.spring_jwks_cache_seconds
        )
    else:
        # StaticDevJwksSource.__init__ itself refuses construction when env != dev
        # (ADR binding condition #4) — this call is where that would raise.
        _jwks_source_singleton = StaticDevJwksSource(settings.dev_shared_jwt_secret)
    return _jwks_source_singleton


def set_jwks_source_for_testing(source: Any) -> None:
    """Test hook — inject a mock JWKS source."""
    global _jwks_source_singleton
    _jwks_source_singleton = source


def reset_jwks_source() -> None:
    global _jwks_source_singleton
    _jwks_source_singleton = None


def _decode_and_verify(token: str, *, expected_aud: str | tuple[str, ...]) -> dict[str, Any]:
    settings = get_settings()
    source = _get_jwks_source()
    # ADR binding condition #4 — code assertion, not just convention: the HS256
    # dev-fallback branch must be unreachable outside env=dev. See
    # _assert_dev_jwks_source_is_dev_only docstring for why this is checked here too,
    # not only at StaticDevJwksSource construction time.
    _assert_dev_jwks_source_is_dev_only(source)

    try:
        unverified_header = jwt.get_unverified_header(token)
    except jwt.PyJWTError as exc:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "malformed_token", str(exc)) from exc

    alg = unverified_header.get("alg")
    if alg not in ALLOWED_ALGS and not (
        alg == "HS256" and isinstance(source, StaticDevJwksSource)
    ):
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "invalid_alg", f"algorithm not allowed: {alg}")

    try:
        signing_key = source.get_signing_key_from_jwt(token)
    except Exception as exc:  # jwt.PyJWKClientError, httpx errors, etc.
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "jwks_lookup_failed", str(exc)) from exc

    try:
        claims = jwt.decode(
            token,
            key=signing_key.key,
            algorithms=list(ALLOWED_ALGS) + (["HS256"] if isinstance(source, StaticDevJwksSource) else []),
            audience=expected_aud,
            issuer=settings.spring_expected_iss,
            options={"require": ["exp", "iat", "aud", "iss"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "expired_token", "token expired") from exc
    except jwt.InvalidAudienceError as exc:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "wrong_audience", "invalid audience") from exc
    except jwt.InvalidIssuerError as exc:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "wrong_issuer", "invalid issuer") from exc
    except jwt.PyJWTError as exc:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "invalid_token", str(exc)) from exc

    return claims


def verify_token(
    token: str,
    *,
    endpoint: str,
    body_workspace_id: str,
) -> VerifiedToken:
    """Full validation pipeline for a bearer token presented to `endpoint`.

    Raises AuthError (mapped to 401/403 by callers) on any failure. Never makes a
    provider call before this returns successfully.
    """
    settings = get_settings()
    expected_aud = (settings.service_token_aud, settings.stream_token_aud)
    claims = _decode_and_verify(token, expected_aud=expected_aud)

    scope = claims.get("scope")
    if not scope:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "missing_scope", "token has no scope claim")

    allowed_scopes = ENDPOINT_SCOPES.get(endpoint, ())
    if scope not in allowed_scopes:
        raise AuthError(
            status.HTTP_403_FORBIDDEN,
            "scope_mismatch",
            f"scope {scope!r} cannot call endpoint {endpoint!r}",
        )

    token_workspace_id = claims.get("workspace_id") or claims.get("workspaceId")
    if not token_workspace_id:
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "missing_workspace_claim", "no workspace_id in token")

    if token_workspace_id != body_workspace_id:
        raise AuthError(
            status.HTTP_403_FORBIDDEN,
            "tenant_mismatch",
            "token.workspace_id does not match request body workspace_id",
        )

    now = int(time.time())
    exp = claims.get("exp")
    if exp is not None and exp < now:
        # Defense-in-depth: jwt.decode already checks this, but keep an explicit
        # check so the invariant is visible and independently testable.
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "expired_token", "token expired")

    return VerifiedToken(
        workspace_id=token_workspace_id,
        scope=scope,
        subject=claims.get("sub"),
        conversation_id=claims.get("conversation_id") or claims.get("conversationId"),
        claims=claims,
    )


def auth_error_to_http(exc: AuthError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail={"code": exc.code, "message": exc.message})


# --- FastAPI dependency helpers ---------------------------------------------


def extract_bearer_token(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthError(status.HTTP_401_UNAUTHORIZED, "missing_bearer", "missing Authorization: Bearer token")
    return authorization.split(" ", 1)[1].strip()


async def require_service_or_stream_token(
    request: Request,
    endpoint_name: str,
    body_workspace_id: str,
    authorization: str | None = Header(default=None),
) -> VerifiedToken:
    """Generic dependency factory body — routes call this with their own endpoint
    name and the workspace_id parsed from the request body, then handle AuthError
    by returning 401/403 with no provider call.
    """
    try:
        token = extract_bearer_token(authorization)
        return verify_token(token, endpoint=endpoint_name, body_workspace_id=body_workspace_id)
    except AuthError as exc:
        raise auth_error_to_http(exc) from exc
