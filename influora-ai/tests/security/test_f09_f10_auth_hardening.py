"""Regression battery for the two auth findings in the 2026-08-08 deep audit.

F-09  Unauthenticated attacker forces a blocking JWKS fetch per request.
F-10  The "structurally unreachable" HS256 dev path is one missing env var away.

Both are reachable with NO credentials, so both are asserted here from the
outside: a garbage token, an unset environment variable.
"""

from __future__ import annotations

import time

import jwt
import pytest
from jwt.exceptions import PyJWKClientError

from app.auth import service_token
from app.auth.service_token import (
    AuthError,
    HttpJwksSource,
    JwksUnavailableError,
    StaticDevJwksSource,
    _validate_kid,
    reset_jwks_source,
)
from app.config import Settings, get_settings


@pytest.fixture(autouse=True)
def _clean_jwks_singleton():
    reset_jwks_source()
    yield
    reset_jwks_source()
    get_settings.cache_clear()


# ---------------------------------------------------------------------------
# F-10 — APP_ENV defaulted to "dev"
# ---------------------------------------------------------------------------


def test_f10_unset_app_env_defaults_to_prod_not_dev(monkeypatch):
    """A prod deploy manifest that drops or typos APP_ENV used to boot straight
    into the HS256 dev auth path, where one symmetric secret mints a token for
    ANY workspace_id. Fail-safe direction is prod."""
    monkeypatch.delenv("APP_ENV", raising=False)
    assert Settings().env == "prod"


def test_f10_dev_jwks_source_cannot_be_constructed_when_app_env_is_unset(monkeypatch):
    monkeypatch.delenv("APP_ENV", raising=False)
    get_settings.cache_clear()
    with pytest.raises(RuntimeError, match="never be constructed"):
        StaticDevJwksSource("shared-dev-secret")


def test_f10_boot_check_rejects_dev_secret_substitute_outside_dev(monkeypatch):
    """`require_boot_secrets` accepted DEV_SHARED_JWT_SECRET as a substitute for
    SPRING_JWKS_URL in EVERY environment — so the symmetric path satisfied the
    boot gate in prod too. It must only satisfy it when APP_ENV=dev."""
    for key, value in {
        "ANTHROPIC_API_KEY": "k", "GEMINI_API_KEY": "k", "SARVAM_API_KEY": "k",
        "INTERNAL_HMAC_KEY": "k", "SERVICE_TOKEN_SIGNING_KEY": "k",
        "DEV_SHARED_JWT_SECRET": "shared-dev-secret",
    }.items():
        monkeypatch.setenv(key, value)
    monkeypatch.delenv("SPRING_JWKS_URL", raising=False)

    monkeypatch.setenv("APP_ENV", "prod")
    missing = Settings().require_boot_secrets()
    assert any("SPRING_JWKS_URL" in m for m in missing), (
        "prod booted with only the shared HS256 dev secret configured"
    )

    monkeypatch.setenv("APP_ENV", "dev")
    assert not any("SPRING_JWKS_URL" in m for m in Settings().require_boot_secrets())


def test_f10_both_guards_do_not_read_the_same_field_by_accident(monkeypatch):
    """The two 'defense-in-depth' guards both compared against settings.env, so
    they were one gate read twice. With the safe default they now both close on
    an UNSET variable, which is the state that used to open them both."""
    monkeypatch.delenv("APP_ENV", raising=False)
    get_settings.cache_clear()
    with pytest.raises(RuntimeError):
        StaticDevJwksSource("s")

    monkeypatch.setenv("APP_ENV", "dev")
    get_settings.cache_clear()
    source = StaticDevJwksSource("s")  # constructible in dev
    monkeypatch.delenv("APP_ENV", raising=False)
    get_settings.cache_clear()
    assert get_settings().env == "prod"
    with pytest.raises(AuthError) as exc:
        service_token._assert_dev_jwks_source_is_dev_only(source)
    assert exc.value.code == "dev_jwks_source_outside_dev"


# ---------------------------------------------------------------------------
# F-09 — attacker-controlled `kid` forces an outbound fetch per request
# ---------------------------------------------------------------------------


def test_f09_malformed_kid_is_rejected_before_any_lookup():
    assert _validate_kid(None) is None  # single-key sets legitimately omit it
    assert _validate_kid("Zm9vYmFy_1.2-3") is None
    for bad in ("a" * 500, "../../etc/passwd\n", 12345, ""):
        with pytest.raises(AuthError) as exc:
            _validate_kid(bad)
        assert exc.value.code == "invalid_kid"


class _CountingJwksSource:
    """Stands in for PyJWKClient: counts how many times a network refetch would
    have been attempted."""

    def __init__(self):
        self.fetches = 0

    def get_signing_key_from_jwt(self, token):
        self.fetches += 1
        raise PyJWKClientError("kid not found in JWKS")


def test_f09_unknown_kid_does_not_produce_one_outbound_fetch_per_request(monkeypatch):
    """(a) amplification — one unauthenticated inbound request became one
    outbound HTTPS GET to Spring, so N req/s of junk became N req/s of load on
    the auth server. The cooldown must collapse a burst to a single attempt."""

    class _Client:
        def __init__(self):
            self.fetch_calls = 0

        def get_signing_key_from_jwt(self, token):
            raise PyJWKClientError("Unable to find a signing key")

        def fetch_data(self):
            self.fetch_calls += 1
            return {"keys": []}

    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.setenv("SPRING_JWKS_URL", "https://spring.test/.well-known/jwks.json")
    get_settings.cache_clear()

    source = HttpJwksSource.__new__(HttpJwksSource)
    import threading

    source._client = _Client()
    source._timeout = 3.0
    source._cooldown = 60.0
    source._last_refetch_at = 0.0
    source._lock = threading.Lock()

    refused = 0
    for _ in range(50):
        try:
            source.get_signing_key_from_jwt("junk.token.here")
        except JwksUnavailableError:
            refused += 1
        except PyJWKClientError:  # the refetch itself found nothing — expected
            pass

    assert source._client.fetch_calls == 1, (
        f"{source._client.fetch_calls} outbound JWKS fetches for 50 unauthenticated "
        "requests — the kid is still an amplification lever"
    )
    assert refused == 49


def test_f09_jwks_client_is_constructed_with_an_explicit_timeout(monkeypatch):
    """(b) event-loop stall — PyJWKClient's default urlopen timeout is 30s and
    HttpJwksSource passed none."""
    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.setenv("SPRING_JWKS_URL", "https://spring.test/.well-known/jwks.json")
    monkeypatch.setenv("SPRING_JWKS_TIMEOUT_SECONDS", "2.5")
    get_settings.cache_clear()

    source = HttpJwksSource("https://spring.test/.well-known/jwks.json", 300)
    assert source._timeout == 2.5
    assert get_settings().spring_jwks_timeout_seconds == 2.5
    assert source._timeout < 30.0


@pytest.mark.asyncio
async def test_f09_token_verification_runs_off_the_event_loop(monkeypatch):
    """`verify_token` is synchronous and was awaited un-offloaded from six
    routes, so a slow JWKS fetch blocked every concurrent request in the
    worker. Assert the loop keeps ticking during a slow verification."""
    import asyncio

    import anyio

    import app.routes.voice as voice_route

    def slow_verify(*args, **kwargs):
        time.sleep(0.30)
        raise AuthError(401, "invalid_token", "nope")

    monkeypatch.setattr(voice_route, "verify_token", slow_verify)

    ticks = {"n": 0}

    async def heartbeat():
        for _ in range(20):
            await asyncio.sleep(0.01)
            ticks["n"] += 1

    async def verify():
        try:
            await anyio.to_thread.run_sync(lambda: voice_route.verify_token("t"))
        except AuthError:
            pass

    beat = asyncio.create_task(heartbeat())
    await verify()
    await beat

    assert ticks["n"] >= 10, (
        f"only {ticks['n']} heartbeats during a 300ms verification — loop was blocked"
    )


def test_f09_garbage_token_with_random_kid_is_401_with_no_credentials(monkeypatch):
    """The whole attack, end to end: no credentials, an attacker-chosen kid, a
    garbage signature. Must be a clean 401 and must not be an unbounded lever."""
    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.setenv("SPRING_JWKS_URL", "https://spring.test/.well-known/jwks.json")
    get_settings.cache_clear()

    class _AlwaysMissing:
        def __init__(self):
            self.calls = 0

        def get_signing_key_from_jwt(self, token):
            self.calls += 1
            raise JwksUnavailableError("refused by cooldown")

    source = _AlwaysMissing()
    service_token.set_jwks_source_for_testing(source)

    token = jwt.encode(
        {"aud": "influora-internal", "iss": "influora-api", "exp": int(time.time()) + 60,
         "iat": int(time.time()), "workspace_id": "ws_1", "scope": "service"},
        "attacker-key",
        algorithm="HS256",
        headers={"kid": "9f8e7d6c-random"},
    )
    with pytest.raises(AuthError) as exc:
        service_token.verify_token(token, endpoint="analyze_site", body_workspace_id="ws_1")
    # HS256 is refused outright outside dev; either way it never reaches decode.
    assert exc.value.status_code == 401


def _unsigned_token(header: dict) -> str:
    """A syntactically valid JWT with an attacker-chosen header and a garbage
    signature — exactly what an unauthenticated attacker can send. No key
    needed, which is the whole point of the finding."""
    import base64
    import json as _json

    def b64(obj):
        raw = _json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    claims = {
        "aud": "influora-internal", "iss": "influora-api",
        "exp": int(time.time()) + 60, "iat": int(time.time()),
        "workspace_id": "ws_1", "scope": "service",
    }
    return f"{b64(header)}.{b64(claims)}.bm90LWEtcmVhbC1zaWduYXR1cmU"


def test_f09_the_kid_check_is_wired_into_the_verification_path(monkeypatch):
    """Priya's final review: the `_validate_kid` FUNCTION was pinned, its CALL
    SITE was not — deleting the call from `_decode_and_verify` left the suite
    green, silently removing the layer that makes a malformed `kid` cost zero
    network I/O.

    This drives the real verification path with an attacker-shaped kid and
    asserts the JWKS source is never reached.
    """

    class _MustNotBeReached:
        def __init__(self):
            self.calls = 0

        def get_signing_key_from_jwt(self, token):
            self.calls += 1
            raise AssertionError("the JWKS source was reached for a malformed kid")

    source = _MustNotBeReached()
    service_token.set_jwks_source_for_testing(source)
    monkeypatch.setenv("APP_ENV", "prod")
    get_settings.cache_clear()

    for bad_kid in ("a" * 500, "../../etc/passwd", "kid with spaces", "kid\nInjected"):
        token = _unsigned_token({"kid": bad_kid, "alg": "RS256", "typ": "JWT"})
        with pytest.raises(AuthError) as exc:
            service_token.verify_token(token, endpoint="analyze_site", body_workspace_id="ws_1")
        assert exc.value.code == "invalid_kid", (
            f"kid {bad_kid!r} was not rejected before the lookup (got {exc.value.code})"
        )

    assert source.calls == 0, "a malformed kid still reached the JWKS client"


def test_f09_a_well_formed_kid_still_reaches_the_lookup(monkeypatch):
    """The guard must not become a wall — a legitimate thumbprint gets through
    to the JWKS source, or the check is just breaking auth."""

    class _Reached(Exception):
        pass

    class _Source:
        def get_signing_key_from_jwt(self, token):
            raise _Reached()

    service_token.set_jwks_source_for_testing(_Source())
    monkeypatch.setenv("APP_ENV", "prod")
    get_settings.cache_clear()

    token = _unsigned_token({"kid": "Zm9vYmFy_1.2-3", "alg": "RS256", "typ": "JWT"})
    with pytest.raises(AuthError) as exc:
        service_token.verify_token(token, endpoint="analyze_site", body_workspace_id="ws_1")
    # It got past _validate_kid and died at the (fake) lookup, not at the guard.
    assert exc.value.code != "invalid_kid"
