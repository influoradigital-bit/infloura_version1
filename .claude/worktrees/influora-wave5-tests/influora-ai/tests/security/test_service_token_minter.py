"""Tests for app/auth/service_token_minter.py -- the Python -> Spring
`X-Meera-Service-Token` minter.

Covers the two must-fix items this module exists to close:
  - nothing minted this token before (it now exists and is the ONLY path
    SpringInternalClient uses to attach the header);
  - the TTL ceiling is 60s (matching Java's
    InternalServiceTokenProperties.MAX_TTL_SECONDS), not the 5-minute figure
    the old spring.py docstring mistakenly advertised.
"""

from __future__ import annotations

import time

import jwt
import pytest

from app.auth.service_token_minter import (
    MAX_TTL_SECONDS,
    SERVICE_TOKEN_AUDIENCE,
    SERVICE_TOKEN_ISSUER,
    ServiceTokenMintError,
    mint_service_token,
)
from app.config import get_settings


@pytest.fixture(autouse=True)
def _configure_signing_key(monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN_SIGNING_KEY", "test-service-token-signing-key-32-bytes-min")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _decode_unverified(token: str) -> dict:
    return jwt.decode(token, options={"verify_signature": False})


def test_mint_sets_iss_aud_alg_matching_java_filter():
    token = mint_service_token()
    header = jwt.get_unverified_header(token)
    claims = _decode_unverified(token)

    assert header["alg"] == "HS256"
    assert claims["iss"] == SERVICE_TOKEN_ISSUER == "meera-python"
    assert claims["aud"] == SERVICE_TOKEN_AUDIENCE == "influora-internal"


def test_mint_always_sets_iat():
    """InternalServiceTokenFilter (Java) computes exp - iat and NPEs if iat is
    absent -- the minter must never omit it (this is also what closes finding
    #3 from the Java side, but the contract starts here: never emit a token
    without iat in the first place).
    """
    token = mint_service_token()
    claims = _decode_unverified(token)
    assert "iat" in claims
    assert isinstance(claims["iat"], int)


def test_mint_default_ttl_is_60_seconds_not_5_minutes():
    """The TTL ceiling is 60s (InternalServiceTokenProperties.MAX_TTL_SECONDS),
    not the 5-minute figure the old spring.py docstring advertised.
    """
    before = int(time.time())
    token = mint_service_token()
    claims = _decode_unverified(token)
    ttl = claims["exp"] - claims["iat"]

    assert ttl == MAX_TTL_SECONDS == 60
    assert claims["iat"] >= before


def test_mint_clamps_requested_ttl_above_ceiling():
    """Even if a caller asks for a longer TTL (e.g. accidentally passing the
    old 300s/5min figure), the minter clamps to the 60s ceiling -- it must
    never produce a token Spring would reject on TTL grounds.
    """
    token = mint_service_token(ttl_seconds=300)
    claims = _decode_unverified(token)
    ttl = claims["exp"] - claims["iat"]
    assert ttl == MAX_TTL_SECONDS


def test_mint_honors_shorter_requested_ttl():
    token = mint_service_token(ttl_seconds=10)
    claims = _decode_unverified(token)
    ttl = claims["exp"] - claims["iat"]
    assert ttl == 10


def test_mint_rejects_non_positive_ttl():
    with pytest.raises(ServiceTokenMintError):
        mint_service_token(ttl_seconds=0)


def test_mint_raises_when_signing_key_missing(monkeypatch):
    monkeypatch.delenv("SERVICE_TOKEN_SIGNING_KEY", raising=False)
    get_settings.cache_clear()
    try:
        with pytest.raises(ServiceTokenMintError):
            mint_service_token()
    finally:
        get_settings.cache_clear()


def test_mint_produces_a_verifiable_hs256_token():
    """Sanity check: a party holding the shared secret can verify what we
    minted (mirrors what the Java InternalServiceTokenFilter does with
    Keys.hmacShaKeyFor over the same secret).
    """
    settings = get_settings()
    token = mint_service_token()
    claims = jwt.decode(
        token,
        key=settings.service_token_signing_key,
        algorithms=["HS256"],
        audience=SERVICE_TOKEN_AUDIENCE,
        issuer=SERVICE_TOKEN_ISSUER,
    )
    assert claims["sub"] == "meera-ai-service"
