"""RT-TOK-1..3 — red-team battery against app/auth/service_token.py.

Kabir Phase A gate (17-KABIR-REMAINING-TASKS.md §A.2):

  RT-TOK-1  no token / wrong-key / expired / wrong-aud -> all rejected,
            no downstream call, no token spend
  RT-TOK-2  workspace_id mismatch between token and body -> 403
  RT-TOK-3  wrong scope for endpoint -> 403 (chat:stream token calling
            /analyze-site)

Uses `set_jwks_source_for_testing` to inject a real asymmetric RS256 keypair
(generated in-process) so signature verification is exercised for real, not
mocked away -- the "wrong key" case signs with a *different* keypair and must
fail signature verification against the legitimate JWKS source.
"""

from __future__ import annotations

import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

from app.auth.service_token import (
    AuthError,
    reset_jwks_source,
    set_jwks_source_for_testing,
    verify_token,
)
from app.config import get_settings


def _gen_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
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
    """Test double standing in for Spring's JWKS: only recognizes the
    'legitimate' public key. Any token signed with a different private key
    fails signature verification -- exactly like a real JWKS lookup would."""

    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        # A real PyJWKClient always returns *some* key material to try (it
        # doesn't know in advance whether the signature is valid); jwt.decode
        # is what actually rejects a bad signature. We mimic that faithfully.
        return _StaticKey(self._legitimate_public_pem)


LEGIT_PRIVATE_PEM, LEGIT_PUBLIC_PEM = _gen_rsa_keypair()
WRONG_PRIVATE_PEM, _WRONG_PUBLIC_PEM = _gen_rsa_keypair()


@pytest.fixture(autouse=True)
def _install_fake_jwks():
    set_jwks_source_for_testing(_FakeJwksSource(LEGIT_PUBLIC_PEM))
    yield
    reset_jwks_source()


def _mint(
    *,
    private_pem: bytes = LEGIT_PRIVATE_PEM,
    alg: str = "RS256",
    aud=None,
    iss: str | None = None,
    scope: str = "service",
    workspace_id: str = "ws-real-001",
    exp_delta_seconds: float = 240.0,  # <= 5 min per SP-A2
    extra_claims: dict | None = None,
    kid: str = "test-kid-1",
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
        "sub": "spring-service",
    }
    if extra_claims:
        claims.update(extra_claims)
    return jwt.encode(claims, private_pem, algorithm=alg, headers={"kid": kid})


# ---------------------------------------------------------------------------
# RT-TOK-1(a) — no token
# ---------------------------------------------------------------------------


def test_rt_tok_1a_missing_token_rejected():
    """No Authorization header reaching verify_token is handled by
    extract_bearer_token at the route layer; here we simulate the equivalent
    -- an empty/garbage token string must fail cleanly, never reach a
    provider call."""
    with pytest.raises(AuthError) as exc_info:
        verify_token("", endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401


# ---------------------------------------------------------------------------
# RT-TOK-1(b) — wrong signing key
# ---------------------------------------------------------------------------


def test_rt_tok_1b_wrong_key_signature_rejected():
    """Token signed by an attacker-controlled key (not Spring's) must fail
    signature verification against the legitimate JWKS-resolved key."""
    forged = _mint(private_pem=WRONG_PRIVATE_PEM)
    with pytest.raises(AuthError) as exc_info:
        verify_token(forged, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "invalid_token"


# ---------------------------------------------------------------------------
# RT-TOK-1(c) — expired token
# ---------------------------------------------------------------------------


def test_rt_tok_1c_expired_token_rejected():
    expired = _mint(exp_delta_seconds=-60.0)  # expired 60s ago
    with pytest.raises(AuthError) as exc_info:
        verify_token(expired, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "expired_token"


# ---------------------------------------------------------------------------
# RT-TOK-1(d) — wrong audience
# ---------------------------------------------------------------------------


def test_rt_tok_1d_wrong_audience_rejected():
    wrong_aud = _mint(aud="some-other-service")
    with pytest.raises(AuthError) as exc_info:
        verify_token(wrong_aud, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "wrong_audience"


def test_rt_tok_1_wrong_issuer_rejected():
    """Bonus per SP-A2: wrong iss must also be rejected (not explicitly a
    lettered sub-case in the doc's RT-TOK-1 list, but named in SP-A2)."""
    wrong_iss = _mint(iss="not-influora-api")
    with pytest.raises(AuthError) as exc_info:
        verify_token(wrong_iss, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "wrong_issuer"


def test_rt_tok_1_no_downstream_call_on_any_failure(monkeypatch):
    """No path that fails verify_token should be capable of reaching a
    provider. We assert this at the contract level: verify_token always
    raises before returning a VerifiedToken for any of the four bad inputs,
    i.e. there is no code path where a caller could accidentally proceed."""
    bad_tokens = [
        "",
        _mint(private_pem=WRONG_PRIVATE_PEM),
        _mint(exp_delta_seconds=-60.0),
        _mint(aud="some-other-service"),
    ]
    for tok in bad_tokens:
        with pytest.raises(AuthError):
            result = verify_token(tok, endpoint="analyze_site", body_workspace_id="ws-real-001")
            pytest.fail(f"verify_token must not return a VerifiedToken for a bad token, got {result!r}")


# ---------------------------------------------------------------------------
# RT-TOK-2 — workspace_id mismatch between token and body -> 403
# ---------------------------------------------------------------------------


def test_rt_tok_2_workspace_mismatch_rejected_403():
    token_for_ws_a = _mint(workspace_id="ws-brand-a-001")
    with pytest.raises(AuthError) as exc_info:
        verify_token(token_for_ws_a, endpoint="analyze_site", body_workspace_id="ws-brand-b-002")
    assert exc_info.value.status_code == 403
    assert exc_info.value.code == "tenant_mismatch"


def test_rt_tok_2_matching_workspace_accepted():
    token = _mint(workspace_id="ws-brand-a-001")
    verified = verify_token(token, endpoint="analyze_site", body_workspace_id="ws-brand-a-001")
    assert verified.workspace_id == "ws-brand-a-001"


def test_rt_tok_2_missing_workspace_claim_rejected():
    """A token with no workspace_id at all must not be treated as a wildcard
    match -- it must fail closed."""
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 240,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "sub": "spring-service",
        # no workspace_id / workspaceId claim
    }
    token = jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "k"})
    with pytest.raises(AuthError) as exc_info:
        verify_token(token, endpoint="analyze_site", body_workspace_id="ws-brand-a-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "missing_workspace_claim"


# ---------------------------------------------------------------------------
# RT-TOK-3 — wrong scope for endpoint -> 403 (chat:stream token on /analyze-site)
# ---------------------------------------------------------------------------


def test_rt_tok_3_stream_scope_cannot_call_analyze_site():
    settings = get_settings()
    stream_token = _mint(aud=settings.stream_token_aud, scope="chat:stream")
    with pytest.raises(AuthError) as exc_info:
        verify_token(stream_token, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 403
    assert exc_info.value.code == "scope_mismatch"


def test_rt_tok_3_stream_scope_can_call_chat():
    settings = get_settings()
    stream_token = _mint(aud=settings.stream_token_aud, scope="chat:stream")
    verified = verify_token(stream_token, endpoint="chat", body_workspace_id="ws-real-001")
    assert verified.scope == "chat:stream"


def test_rt_tok_3_service_scope_cannot_call_undeclared_endpoint():
    """A service-scoped token must not be usable against an endpoint that
    isn't in the ENDPOINT_SCOPES allow-list at all -- fail closed, not
    fail open, for unknown endpoint names."""
    service_token = _mint(scope="service")
    with pytest.raises(AuthError) as exc_info:
        verify_token(service_token, endpoint="some_future_money_endpoint", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 403
    assert exc_info.value.code == "scope_mismatch"


def test_rt_tok_3_missing_scope_claim_rejected():
    settings = get_settings()
    now = int(time.time())
    claims = {
        "iat": now,
        "exp": now + 240,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "workspace_id": "ws-real-001",
        "sub": "spring-service",
        # no scope claim
    }
    token = jwt.encode(claims, LEGIT_PRIVATE_PEM, algorithm="RS256", headers={"kid": "k"})
    with pytest.raises(AuthError) as exc_info:
        verify_token(token, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "missing_scope"


# ---------------------------------------------------------------------------
# Defense-in-depth: alg confusion (none / unsigned) rejected
# ---------------------------------------------------------------------------


def test_alg_none_token_rejected():
    """An attacker-forged unsigned ('none' alg) token must never be accepted,
    even though we didn't sign it with any key at all."""
    import base64
    import json

    header = base64.urlsafe_b64encode(json.dumps({"alg": "none", "typ": "JWT"}).encode()).rstrip(b"=")
    now = int(time.time())
    payload = base64.urlsafe_b64encode(
        json.dumps(
            {
                "iat": now,
                "exp": now + 240,
                "aud": get_settings().service_token_aud,
                "iss": get_settings().spring_expected_iss,
                "scope": "service",
                "workspace_id": "ws-real-001",
            }
        ).encode()
    ).rstrip(b"=")
    forged_token = header.decode() + "." + payload.decode() + "."

    with pytest.raises(AuthError) as exc_info:
        verify_token(forged_token, endpoint="analyze_site", body_workspace_id="ws-real-001")
    assert exc_info.value.status_code == 401
    assert exc_info.value.code == "invalid_alg"
