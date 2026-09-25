"""F-audit-A1 -- the per-creator monthly AI spending cap and influora-ai's own
DPDP consent re-check never applied to creator media calls (voice transcribe/
speak, and Shoot Check's frame check), because the service token Java's
`MeeraVoiceAiClient` minted (via `BrandSafetyServiceTokenService.mint`)
carried only `workspace_id`+`scope` -- `derive_audience`'s
`AUDIENCE_CLAIM_KEYS` found nothing on it, so every creator call silently ran
the pre-Phase-A brand default: no cap, no consent gate.

The fix has two Java-side halves (see their javadoc for the full design):
  1. `BrandSafetyServiceTokenService.mint(workspaceId, userType)` now
     OPTIONALLY carries a `userType` claim.
  2. `MeeraVoiceAiClient.checkFrameForCreator`/`speakForCreator`/
     `transcribeForCreator` mint WITH `userType=CREATOR` and forward a REAL,
     creator-scoped on-behalf JWT (minted by Spring's `OnBehalfTokenService`,
     never the service bearer) as `onbehalf_jwt` -- resolving the hazard that
     `resolve_frame_check_prefs`/`resolve_voice_prefs` call Spring's context
     endpoint with `onbehalf_jwt`, and the service bearer would fail that
     call's signature/audience check outright (`OnBehalfAuthResolver` verifies
     against a completely different contract -- see
     `BrandSafetyServiceTokenService#mint(String, String)`'s javadoc), which
     would fail EVERY creator call closed (403 CONSENT_REQUIRED) even for an
     already-consented creator.

This file proves the influora-ai side of that fix with NOTHING faked at the
seams that matter:
  - the service token is signed and verified for REAL (a real EC keypair, the
    real `verify_token`/`_decode_and_verify` path via a fake JWKS SOURCE, not
    a fake `verify_token` function and not a hand-built claims dict) -- see
    `_mint_service_token`;
  - the route under test is the REAL ASGI app via `TestClient`, not the bare
    route function;
  - Spring is mocked ONLY at the httpx transport boundary (`SpringInternalClient`
    with `httpx.MockTransport`, same pattern as `tests/clients/test_spring_client.py`),
    so `resolve_frame_check_prefs`'s own code (audience derivation, the
    Spring call, `consent_accepted`/`creator_cap_override_from_context`) all
    run for real;
  - only the actual model provider (`_get_claude`) is a plain mock -- there is
    no Claude account to call in a test.
"""

from __future__ import annotations

import functools
import json
import time
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app.auth.service_token import reset_jwks_source, set_jwks_source_for_testing
from app.clients.spring import SpringInternalClient
from app.config import get_settings
from app.costs import spend_tracker
from app.providers.claude import ClaudeTextResult

FRAME_PATH = "/ai/shoot-check/frame"
CREATOR_USER_ID = "creator-user-f-audit-a1-001"

# The forwarded on-behalf JWT is opaque to influora-ai (it only forwards it;
# Spring's OnBehalfAuthResolver is the one that verifies it -- out of scope
# for a Python test). A distinct sentinel string, never equal to the
# service-token bearer, is what proves influora-ai used the FORWARDED value
# rather than silently falling back to the bearer (the exact hazard this fix
# closes -- see resolve_frame_check_prefs -> onbehalf_jwt fallback logic).
REAL_ONBEHALF_JWT_SENTINEL = "real-java-minted-onbehalf-token-not-the-bearer"

# F-audit-A3's tightened `_looks_like_declared_type` requires a real EOI
# marker -- this fixture satisfies both that check and the size ceiling.
JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"\x00" * 200 + b"\xff\xd9"


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
    """Stands in for Spring's published JWKS endpoint -- resolves any `kid`
    to the one 'legitimate' EC public key, same as `PyJWKClient` would once
    it fetched Spring's real `/.well-known/jwks.json`."""

    def __init__(self, legitimate_public_pem: bytes):
        self._legitimate_public_pem = legitimate_public_pem

    def get_signing_key_from_jwt(self, token: str):
        return _StaticKey(self._legitimate_public_pem)


SPRING_PRIVATE_PEM, SPRING_PUBLIC_PEM = _gen_ec_keypair()


@pytest.fixture(autouse=True)
async def _fixtures(monkeypatch):
    set_jwks_source_for_testing(_FakeSpringJwksSource(SPRING_PUBLIC_PEM))
    # SpringInternalClient.call_tool_endpoint mints ITS OWN outbound service
    # token (Python -> Spring direction, a completely different token from
    # the one under test) on every call -- needs a signing key configured or
    # that mint raises before the mocked-transport HTTP layer is reached.
    monkeypatch.setenv("SERVICE_TOKEN_SIGNING_KEY", "test-signing-key-at-least-32-bytes-long!!")
    for var in ("AI_SPEND_KILL_SWITCH", "AI_CREATOR_MONTHLY_CAP_USD", "REDIS_URL"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("AI_DAILY_SPEND_CEILING_USD", "999999")
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()
    yield
    reset_jwks_source()
    for var in (
        "SERVICE_TOKEN_SIGNING_KEY",
        "AI_DAILY_SPEND_CEILING_USD",
        "AI_CREATOR_MONTHLY_CAP_USD",
        "AI_SPEND_KILL_SWITCH",
    ):
        monkeypatch.delenv(var, raising=False)
    get_settings.cache_clear()
    await spend_tracker.reset_for_testing()


def _mint_service_token(*, user_type: str | None, workspace_id: str = CREATOR_USER_ID) -> str:
    """Reproduces EXACTLY the claim shape
    `BrandSafetyServiceTokenService.mint(workspaceId, userType)` mints on the
    Java side: `iss`, `aud`, `workspace_id`, `scope=service`, and -- only when
    `user_type` is given -- `userType`. Signed ES256 with a real EC key,
    never a pre-decoded dict."""
    settings = get_settings()
    now = int(time.time())
    claims: dict = {
        "iat": now,
        "exp": now + 60,
        "aud": settings.service_token_aud,
        "iss": settings.spring_expected_iss,
        "scope": "service",
        "workspace_id": workspace_id,
    }
    if user_type:
        claims["userType"] = user_type
    return jwt.encode(claims, SPRING_PRIVATE_PEM, algorithm="ES256", headers={"kid": "test-kid-a1"})


def _client() -> TestClient:
    """No lifespan -- skips the boot-secret startup hook, same rationale as
    tests/routes/test_trend_tag.py / test_voice_spend_gate.py."""
    from app.main import app

    return TestClient(app)


def _spring_transport(monkeypatch, handler) -> None:
    """Wires `app.routes.shoot_check._get_spring()`'s eventual
    `SpringInternalClient` to a real `httpx.AsyncClient` bound to a
    `MockTransport` -- `SpringInternalClient.__init__` still runs unmodified;
    only the wire transport is swapped. Mirrors
    tests/clients/test_spring_client.py's `_client_with_transport` exactly,
    patched at the module `app.clients.spring` uses (the constructor
    `shoot_check._get_spring()` calls internally), so the ROUTE's own lazy
    singleton is what gets built against the fake transport."""
    real_async_client = httpx.AsyncClient
    monkeypatch.setattr(
        "app.clients.spring.httpx.AsyncClient",
        functools.partial(real_async_client, transport=httpx.MockTransport(handler)),
    )
    # shoot_check.py caches its Spring client in a module-level singleton --
    # clear it so this test's patched transport is what gets constructed.
    import app.routes.shoot_check as shoot_check_route

    shoot_check_route._spring_client = None


def _context_handler(*, consent_accepted: bool, assert_onbehalf=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if assert_onbehalf is not None:
            assert request.headers.get("x-onbehalf-authorization") == assert_onbehalf, (
                "influora-ai must forward the REAL on-behalf JWT it was given, "
                "never fall back to the service bearer"
            )
        body = json.loads(request.content)
        assert body["audience"] == "CREATOR"
        return httpx.Response(
            200,
            json={"data": {"audience": "CREATOR", "consent_accepted": consent_accepted}},
        )

    return handler


def _claude_ok():
    claude = MagicMock()
    claude.complete_with_image = AsyncMock(
        return_value=ClaudeTextResult(
            ok=True,
            # Grounded reply shape (2026-09-25): an "ok"-only reply is no longer a usable check,
            # so the fake cites one real knowledge entry.
            text=json.dumps({
                "what_i_see": "A desk by a window.",
                "steps": [{"kind": "move_you", "text": "Turn partway toward the window.",
                           "note": "Creator is 30-45 deg to window"}],
                "ok": ["Looks good."], "cant_tell": [], "ask": None,
            }),
            usage={"input_tokens": 500, "output_tokens": 60},
        )
    )
    return claude


# --------------------------------------------------------------------------- (a) + (c): consented creator, real verification end to end


@pytest.mark.asyncio
async def test_consented_creator_frame_check_succeeds_and_engages_the_monthly_cap(monkeypatch):
    """(a): the creator monthly cap actually engages (spend lands on the
    creator's own ledger). (c): a consented creator's frame check SUCCEEDS --
    not a 403, not a silent fallback -- through the REAL verify_token path
    and the REAL route, with Spring mocked only at the transport boundary."""
    token = _mint_service_token(user_type="CREATOR")
    _spring_transport(
        monkeypatch,
        _context_handler(consent_accepted=True, assert_onbehalf=REAL_ONBEHALF_JWT_SENTINEL),
    )

    with patch("app.routes.shoot_check._get_claude", return_value=_claude_ok()):
        resp = _client().post(
            FRAME_PATH,
            data={"workspace_id": CREATOR_USER_ID, "onbehalf_jwt": REAL_ONBEHALF_JWT_SENTINEL},
            files={"image": ("shot.jpg", JPEG_BYTES, "image/jpeg")},
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data.get("fallback") is not True, data
    assert data["ok"] == ["Looks good."]

    # (a) -- the creator cap ledger actually moved. Before this fix,
    # derive_audience saw no claim at all, audience stayed None, and
    # `_creator_cap_gate` is a no-op for anything that isn't AUDIENCE_CREATOR
    # -- this assertion is exactly what a reverted fix turns back to Decimal(0).
    assert await spend_tracker.get_creator_month_total(CREATOR_USER_ID) > Decimal(0)


# --------------------------------------------------------------------------- (b): consent still enforced end to end


def test_unconsented_creator_frame_check_is_refused_not_silently_allowed(monkeypatch):
    """(b): with the real userType=CREATOR claim now present, an UNCONSENTED
    creator must still be blocked -- proving the consent gate re-engages
    along with the cap, not just the cap alone."""
    token = _mint_service_token(user_type="CREATOR")
    _spring_transport(
        monkeypatch,
        _context_handler(consent_accepted=False, assert_onbehalf=REAL_ONBEHALF_JWT_SENTINEL),
    )

    with patch("app.routes.shoot_check._get_claude", return_value=_claude_ok()) as mock_claude:
        resp = _client().post(
            FRAME_PATH,
            data={"workspace_id": CREATOR_USER_ID, "onbehalf_jwt": REAL_ONBEHALF_JWT_SENTINEL},
            files={"image": ("shot.jpg", JPEG_BYTES, "image/jpeg")},
            headers={"Authorization": f"Bearer {token}"},
        )
        # Blocked at the consent gate, BEFORE the model is ever called -- no
        # wasted provider spend on a refused request.
        mock_claude.assert_not_called()

    assert resp.status_code == 403, resp.text
    assert resp.json()["code"] == "CONSENT_REQUIRED"


# --------------------------------------------------------------------------- negative control: a BRAND-shaped token is unaffected


def test_brand_shaped_token_without_usertype_claim_is_unaffected_by_this_fix(monkeypatch):
    """Every OTHER caller of BrandSafetyServiceTokenService (brand safety,
    brand voice, trend spark, creator suggestion, analyze site) still mints
    via `mint(workspaceId)` with no userType claim at all. This is the
    negative control: that exact shape must keep behaving exactly as it did
    before this fix -- no consent gate, no creator cap, Spring never called."""
    token = _mint_service_token(user_type=None)

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover - must not be hit
        raise AssertionError("a BRAND-shaped (no userType) call must never reach Spring's context endpoint")

    _spring_transport(monkeypatch, handler)

    with patch("app.routes.shoot_check._get_claude", return_value=_claude_ok()):
        resp = _client().post(
            FRAME_PATH,
            data={"workspace_id": CREATOR_USER_ID},
            files={"image": ("shot.jpg", JPEG_BYTES, "image/jpeg")},
            headers={"Authorization": f"Bearer {token}"},
        )

    assert resp.status_code == 200, resp.text
    assert resp.json().get("fallback") is not True, resp.text
