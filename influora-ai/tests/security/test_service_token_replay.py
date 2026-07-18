"""Kabir red-team FIX 1 — single-use enforcement of the `jti` claim on
`chat:stream`-scoped tokens, exercised through the real `verify_token_async`
pipeline (real ES256 signing/verification via the same fake-JWKS pattern as
tests/security/test_service_token.py), not just replay_guard.py in isolation.

Spring's `StreamTokenService` (StreamTokenService.java:82) mints a random
UUID `jti` per `chat:stream` token and its javadoc claims this service
enforces single-use -- these tests make that claim true.
"""

from __future__ import annotations

import time
import uuid

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.auth import replay_guard
from app.auth.service_token import (
    AuthError,
    reset_jwks_source,
    set_jwks_source_for_testing,
    verify_token_async,
)
from app.config import get_settings


def _gen_ec_keypair():
    key = ec.generate_private_key(ec.SECP256R1())
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


LEGIT_PRIVATE_PEM, LEGIT_PUBLIC_PEM = _gen_ec_keypair()
WORKSPACE_ID = "ws-replay-001"


@pytest.fixture(autouse=True)
async def _install_fake_jwks_and_reset_state():
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    await replay_guard.reset_for_testing()
    yield
    reset_jwks_source()
    await replay_guard.reset_for_testing()


def _mint_stream_token(*, jti: str | None = "default-jti", exp_delta_seconds: float = 45.0) -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + int(exp_delta_seconds),
        "aud": settings.stream_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "chat:stream",
        "workspace_id": WORKSPACE_ID,
        "conversation_id": "conv-1",
        "sub": "user-1",
    }
    if jti is not None:
        claims["jti"] = jti
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="ES256", headers={"kid": "test-kid"})


def _mint_service_token() -> str:
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 240,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "workspace_id": WORKSPACE_ID,
        "sub": "spring-service",
        # Deliberately no jti -- service tokens don't carry one.
    }
    return jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="ES256", headers={"kid": "test-kid"})


@pytest.mark.asyncio
async def test_first_use_of_stream_token_is_accepted():
    token = _mint_stream_token(jti=f"jti-{uuid.uuid4()}")
    verified = await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)
    assert verified.scope == "chat:stream"
    assert verified.workspace_id == WORKSPACE_ID


@pytest.mark.asyncio
async def test_second_use_of_same_stream_token_is_rejected_as_replay():
    jti = f"jti-{uuid.uuid4()}"
    token = _mint_stream_token(jti=jti)

    first = await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)
    assert first.scope == "chat:stream"

    with pytest.raises(AuthError) as exc_info:
        await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)

    assert exc_info.value.status_code == 409
    assert exc_info.value.code == "token_replayed"


@pytest.mark.asyncio
async def test_stream_token_missing_jti_is_rejected():
    token = _mint_stream_token(jti=None)

    with pytest.raises(AuthError) as exc_info:
        await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)

    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "missing_jti"


@pytest.mark.asyncio
async def test_service_scope_token_is_unaffected_by_replay_guard():
    """Service tokens (scope='service') are used repeatedly across many
    /chat calls by Spring-proxied traffic by design -- the replay guard must
    only ever apply to scope='chat:stream', never to service tokens, even
    though service tokens here carry no jti at all."""
    token = _mint_service_token()

    first = await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)
    assert first.scope == "service"

    # Reusing the exact same service token again must NOT raise -- no jti
    # check applies to this scope.
    second = await verify_token_async(token, endpoint="chat", body_workspace_id=WORKSPACE_ID)
    assert second.scope == "service"


@pytest.mark.asyncio
async def test_two_distinct_stream_tokens_each_get_their_own_first_use():
    token_a = _mint_stream_token(jti=f"jti-{uuid.uuid4()}")
    token_b = _mint_stream_token(jti=f"jti-{uuid.uuid4()}")

    verified_a = await verify_token_async(token_a, endpoint="chat", body_workspace_id=WORKSPACE_ID)
    verified_b = await verify_token_async(token_b, endpoint="chat", body_workspace_id=WORKSPACE_ID)

    assert verified_a.scope == "chat:stream"
    assert verified_b.scope == "chat:stream"
