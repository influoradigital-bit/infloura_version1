"""CORS wiring for the browser-direct Meera SSE stream (deploy blocker 1).

The frontend POSTs to `/chat` cross-origin with Authorization + Content-Type:
application/json (src/hooks/useMeeraStream.ts), which the browser preflights
with OPTIONS. `app/main.py` had no CORSMiddleware at all, so that preflight
fails before the real request is ever sent.

The fix is env-driven and closed by default (app/config.py's
MEERA_ALLOWED_ORIGINS): CORSMiddleware is only installed when the setting is
non-empty, so internal-only callers (Spring, n8n, health checks) never see a
CORS header appear as a side effect.

Because the middleware is wired at app-construction time in app/main.py (not
per-request), these tests reload the module with the env var set/unset around
each case rather than only clearing the lru_cached settings.
"""

from __future__ import annotations

import importlib

from fastapi.testclient import TestClient

import app.main as main_module
from app.config import get_settings


def _reload_main(monkeypatch, allowed_origins: str | None):
    if allowed_origins is None:
        monkeypatch.delenv("MEERA_ALLOWED_ORIGINS", raising=False)
    else:
        monkeypatch.setenv("MEERA_ALLOWED_ORIGINS", allowed_origins)
    get_settings.cache_clear()
    importlib.reload(main_module)
    return main_module.app


def test_no_cors_headers_when_unset(monkeypatch):
    """Default (unset) -- no CORSMiddleware, no CORS headers, no behavior
    change for internal-only callers."""
    app = _reload_main(monkeypatch, None)
    client = TestClient(app)

    resp = client.get("/healthz", headers={"Origin": "https://app.example.com"})

    assert resp.status_code == 200
    assert "access-control-allow-origin" not in {k.lower() for k in resp.headers}


def test_preflight_allows_configured_origin_on_chat(monkeypatch):
    """Configured -- an OPTIONS preflight to /chat from an allowed origin gets
    back the exact origin echo (never a wildcard, since Authorization is a
    bearer header) plus POST in the allowed methods."""
    app = _reload_main(monkeypatch, "https://app.example.com")
    client = TestClient(app)

    resp = client.options(
        "/chat",
        headers={
            "Origin": "https://app.example.com",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization,content-type",
        },
    )

    assert resp.status_code == 200
    assert resp.headers["access-control-allow-origin"] == "https://app.example.com"
    assert "POST" in resp.headers.get("access-control-allow-methods", "")


def test_preflight_rejects_unconfigured_origin(monkeypatch):
    """A configured allow-list must not echo back an origin that isn't on it."""
    app = _reload_main(monkeypatch, "https://app.example.com")
    client = TestClient(app)

    resp = client.options(
        "/chat",
        headers={
            "Origin": "https://evil.example.com",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization,content-type",
        },
    )

    assert "access-control-allow-origin" not in {k.lower() for k in resp.headers}


def teardown_module(module):
    """Leave app.main reloaded back to the unset (closed) default so later
    test modules that `from app.main import app` don't inherit a
    CORS-enabled instance from this module's last reload."""
    import os

    os.environ.pop("MEERA_ALLOWED_ORIGINS", None)
    get_settings.cache_clear()
    importlib.reload(main_module)
