"""Wave E task E-JWKS (`wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md`).

Covers what `test_service_token.py` doesn't:

  1. A token signed the way Spring's NEW `BrandSafetyServiceTokenService` /
     `StreamTokenService` actually sign (ES256, with a `kid` header resolved via a
     JWKS-shaped source) verifies successfully through the real JWKS code path.
  2. An HS256 token is rejected on that same (non-`StaticDevJwksSource`) path --
     ALLOWED_ALGS is not relaxed by this task, exactly as the ADR requires.
  3. ADR binding condition #4 -- `StaticDevJwksSource`'s HS256 branch is a hard,
     code-level assertion unreachable whenever `env != dev`, not just a documented
     convention. Both enforcement points (construction-time in `StaticDevJwksSource
     .__init__`, and the independent `_assert_dev_jwks_source_is_dev_only` check
     inside `_decode_and_verify`) are exercised directly.

Mirrors `test_service_token.py`'s existing pattern: a real in-process EC keypair and
a fake JWKS source standing in for Spring's published `/.well-known/jwks.json`, so
signature verification is exercised for real, never mocked away.
"""

from __future__ import annotations

import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

from app.auth.service_token import (
    AuthError,
    StaticDevJwksSource,
    _assert_dev_jwks_source_is_dev_only,
    reset_jwks_source,
    set_jwks_source_for_testing,
    verify_token,
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


class _FakeSpringJwksSource:
    """Stands in for Spring's real JWKS endpoint (`HttpJwksSource` in prod) -- only
    recognizes the 'legitimate' EC public key, exactly like a real JWKS lookup would.
    """

    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        return _StaticKey(self._legitimate_public_pem)


SPRING_PRIVATE_PEM, SPRING_PUBLIC_PEM = _gen_ec_keypair()


@pytest.fixture(autouse=True)
def _install_fake_spring_jwks():
    set_jwks_source_for_testing(_FakeSpringJwksSource(SPRING_PUBLIC_PEM))
    yield
    reset_jwks_source()


def _mint_es256(
    *,
    aud=None,
    iss: str | None = None,
    scope: str = "service",
    workspace_id: str = "ws-real-001",
    exp_delta_seconds: float = 60.0,
    kid: str = "spring-es256-1",
):
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + int(exp_delta_seconds),
        "aud": aud if aud is not None else settings.service_token_aud,
        "iss": iss if iss is not None else settings.spring_expected_iss,
        "scope": scope,
        "workspace_id": workspace_id,
    }
    return jwt.encode(claims, SPRING_PRIVATE_PEM, algorithm="ES256", headers={"kid": kid})


# ---------------------------------------------------------------------------
# 1 & 2 -- Spring's real (post-E-JWKS) signing shape verifies; HS256 does not
# ---------------------------------------------------------------------------


def test_spring_signed_es256_token_verifies_via_jwks_path():
    """A token minted the way BrandSafetyServiceTokenService/StreamTokenService now
    actually sign (ES256 + kid) must verify cleanly through the real JWKS path."""
    token = _mint_es256()
    verified = verify_token(token, endpoint="brand_safety", body_workspace_id="ws-real-001")
    assert verified.workspace_id == "ws-real-001"
    assert verified.scope == "service"


def test_spring_signed_es256_token_verifies_for_stream_scope_too():
    """ADR binding condition #2 ('both flows, one fix') -- the stream-token shape
    (aud=meera-stream, scope=chat:stream) must ALSO verify via the same ES256/JWKS
    path, not just the brand-safety service-token shape."""
    settings = get_settings()
    token = _mint_es256(aud=settings.stream_token_aud, scope="chat:stream")
    verified = verify_token(token, endpoint="chat", body_workspace_id="ws-real-001")
    assert verified.scope == "chat:stream"


def test_hs256_token_rejected_on_the_real_jwks_path():
    """The ADR's single most important constraint: ALLOWED_ALGS must NOT be relaxed
    to accept HS256 on the JWKS (non-StaticDevJwksSource) path, even after this task
    lands Spring's asymmetric signing. An HS256 token presented against a real JWKS
    source (not a StaticDevJwksSource) must be rejected as invalid_alg."""
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 60,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "workspace_id": "ws-real-001",
    }
    # Signed with an arbitrary HS256 secret -- irrelevant which one, since the alg
    # check must reject this before any signature verification is even attempted.
    hs256_token = jwt.encode(claims, "some-hs256-secret-value", algorithm="HS256")

    with pytest.raises(AuthError) as exc_info:
        verify_token(hs256_token, endpoint="brand_safety", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "invalid_alg"


def test_rs256_also_still_accepted_on_jwks_path():
    """Defense-in-depth: ES256 didn't silently replace RS256 in ALLOWED_ALGS --
    both remain accepted asymmetric algorithms on the JWKS path."""
    from cryptography.hazmat.primitives.asymmetric import rsa

    rsa_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    rsa_private_pem = rsa_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    rsa_public_pem = rsa_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    set_jwks_source_for_testing(_FakeSpringJwksSource(rsa_public_pem))

    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 60,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "workspace_id": "ws-real-001",
    }
    token = jwt.encode(claims, rsa_private_pem, algorithm="RS256", headers={"kid": "rsa-1"})
    verified = verify_token(token, endpoint="brand_safety", body_workspace_id="ws-real-001")
    assert verified.workspace_id == "ws-real-001"


# ---------------------------------------------------------------------------
# 3 -- ADR binding condition #4: StaticDevJwksSource is a hard, code-level,
# env-gated assertion, not just documentation
# ---------------------------------------------------------------------------


def test_static_dev_jwks_source_refuses_construction_outside_dev(monkeypatch):
    """Enforcement point 1: StaticDevJwksSource.__init__ itself raises if
    settings.env != 'dev'."""
    monkeypatch.setenv("APP_ENV", "staging")
    get_settings.cache_clear()
    try:
        with pytest.raises(RuntimeError, match="env=dev"):
            StaticDevJwksSource("some-secret-value-at-least-32-bytes-long!!")
    finally:
        get_settings.cache_clear()


def test_static_dev_jwks_source_refuses_construction_in_prod(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    get_settings.cache_clear()
    try:
        with pytest.raises(RuntimeError):
            StaticDevJwksSource("some-secret-value-at-least-32-bytes-long!!")
    finally:
        get_settings.cache_clear()


def test_static_dev_jwks_source_constructs_fine_in_dev(monkeypatch):
    monkeypatch.setenv("APP_ENV", "dev")
    get_settings.cache_clear()
    try:
        source = StaticDevJwksSource("some-secret-value-at-least-32-bytes-long!!")
        assert source is not None
    finally:
        get_settings.cache_clear()


def test_assert_dev_jwks_source_is_dev_only_raises_outside_dev(monkeypatch):
    """Enforcement point 2 (independent of construction): even if a
    StaticDevJwksSource instance already exists (e.g. constructed while env was
    still 'dev', then env flips, or injected directly via the testing hook),
    _decode_and_verify's call to this function must still refuse to use it."""
    # Construct while env=dev (allowed), matching a realistic "instance created
    # earlier, config drifted" scenario rather than testing a code path that could
    # never be reached.
    monkeypatch.setenv("APP_ENV", "dev")
    get_settings.cache_clear()
    dev_source = StaticDevJwksSource("some-secret-value-at-least-32-bytes-long!!")

    monkeypatch.setenv("APP_ENV", "prod")
    get_settings.cache_clear()
    try:
        with pytest.raises(AuthError) as exc_info:
            _assert_dev_jwks_source_is_dev_only(dev_source)
        assert exc_info.value.status_code == 401
        assert exc_info.value.code == "dev_jwks_source_outside_dev"
    finally:
        get_settings.cache_clear()


def test_assert_dev_jwks_source_is_dev_only_noop_for_non_dev_source():
    """The assertion is scoped to StaticDevJwksSource only -- it must never raise
    for a real (HttpJwksSource-shaped) JWKS source, regardless of env."""
    _assert_dev_jwks_source_is_dev_only(_FakeSpringJwksSource(SPRING_PUBLIC_PEM))  # must not raise


def test_end_to_end_verify_token_refuses_dev_fallback_outside_dev(monkeypatch):
    """Full-stack version of the assertion: if verify_token's resolved JWKS source
    is somehow a StaticDevJwksSource while running outside dev, the whole request
    is rejected -- not merely alg-checked and allowed through."""
    monkeypatch.setenv("APP_ENV", "dev")
    get_settings.cache_clear()
    dev_source = StaticDevJwksSource("some-secret-value-at-least-32-bytes-long!!")
    set_jwks_source_for_testing(dev_source)

    monkeypatch.setenv("APP_ENV", "staging")
    get_settings.cache_clear()
    try:
        now = int(time.time())
        settings = get_settings()
        claims = {
            "iat": now,
            "exp": now + 60,
            "aud": settings.service_token_aud,
            "iss": settings.spring_expected_iss,
            "scope": "service",
            "workspace_id": "ws-real-001",
        }
        # Even a token this dev source COULD verify (HS256, matching secret) must
        # still be refused, because the source itself is illegitimate outside dev.
        token = jwt.encode(
            claims, "some-secret-value-at-least-32-bytes-long!!", algorithm="HS256"
        )
        with pytest.raises(AuthError) as exc_info:
            verify_token(token, endpoint="brand_safety", body_workspace_id="ws-real-001")
        assert exc_info.value.code == "dev_jwks_source_outside_dev"
    finally:
        get_settings.cache_clear()
