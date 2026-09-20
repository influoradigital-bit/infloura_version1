"""EV-006 -- committed env.example values must never satisfy the boot-secrets gate.

env.example once carried live Anthropic/Sarvam keys; they are now REPLACE_WITH_YOUR_* placeholders.
`require_boot_secrets` only checked non-empty, so a box started from a copied env.example booted
clean and then 401'd on the first provider call. It also accepted the committed dev signing keys
(dev-...-change-in-production-...) with APP_ENV=prod.
"""

from __future__ import annotations

import pytest

from app.config import Settings

_REAL = {
    "ANTHROPIC_API_KEY": "provider-key-a-0001",
    "GEMINI_API_KEY": "provider-key-g-0001",
    "SARVAM_API_KEY": "provider-key-s-0001",
    "INTERNAL_HMAC_KEY": "hmac-key-0001-random-looking-value",
    "SERVICE_TOKEN_SIGNING_KEY": "svc-key-0001-random-looking-value",
    "SPRING_JWKS_URL": "http://spring:8080/api/v1/.well-known/jwks.json",
}


def _env(monkeypatch, app_env: str, **overrides: str) -> Settings:
    for key, value in {**_REAL, **overrides}.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setenv("APP_ENV", app_env)
    return Settings()


def test_baseline_real_values_boot_clean(monkeypatch):
    assert _env(monkeypatch, "prod").require_boot_secrets() == []


@pytest.mark.parametrize("name", ["ANTHROPIC_API_KEY", "GEMINI_API_KEY", "SARVAM_API_KEY"])
@pytest.mark.parametrize("app_env", ["prod", "dev"])
def test_env_example_provider_placeholder_refuses_boot(monkeypatch, name, app_env):
    placeholder = "REPLACE_WITH_YOUR_" + name
    missing = _env(monkeypatch, app_env, **{name: placeholder}).require_boot_secrets()
    assert any(m.startswith(name) and "placeholder" in m for m in missing), missing


@pytest.mark.parametrize("name", ["INTERNAL_HMAC_KEY", "SERVICE_TOKEN_SIGNING_KEY"])
def test_committed_dev_signing_key_refuses_boot_outside_dev(monkeypatch, name):
    dev_value = "dev-" + name.lower() + "-change-in-production-min-32-chars"
    missing = _env(monkeypatch, "prod", **{name: dev_value}).require_boot_secrets()
    assert any(m.startswith(name) for m in missing), missing


@pytest.mark.parametrize("name", ["INTERNAL_HMAC_KEY", "SERVICE_TOKEN_SIGNING_KEY"])
def test_committed_dev_signing_key_still_boots_in_dev(monkeypatch, name):
    dev_value = "dev-" + name.lower() + "-change-in-production-min-32-chars"
    assert _env(monkeypatch, "dev", **{name: dev_value}).require_boot_secrets() == []
