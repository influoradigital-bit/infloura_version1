"""Gate fix round 2 (Q7, Priya caveat 1) -- the creator cap's concurrency
scope is asserted at boot and reported on /readyz, so an ops change
(`--workers 2`, a compose `command:` override, a second replica without
Redis) can no longer void the cap silently.
"""

from __future__ import annotations

import importlib

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.config import get_settings
from app.costs.worker_guard import (
    CREATOR_CAP_SCOPE_PER_PROCESS,
    CREATOR_CAP_SCOPE_SHARED,
    configured_worker_count,
    creator_cap_scope,
)


@pytest.mark.parametrize(
    ("argv", "environ", "expected"),
    [
        (["uvicorn", "app.main:app"], {}, 1),
        (["uvicorn", "app.main:app", "--workers", "1"], {}, 1),
        (["uvicorn", "app.main:app", "--workers", "4"], {}, 4),
        (["uvicorn", "app.main:app", "--workers=3"], {}, 3),
        (["uvicorn", "app.main:app", "-w", "2"], {}, 2),
        (["uvicorn", "app.main:app"], {"WEB_CONCURRENCY": "5"}, 5),
        # CLI wins over the env var, matching uvicorn's own precedence.
        (["uvicorn", "app.main:app", "--workers", "2"], {"WEB_CONCURRENCY": "5"}, 2),
        # Garbage never turns into "more than one".
        (["uvicorn", "app.main:app", "--workers", "lots"], {}, 1),
        (["uvicorn", "app.main:app"], {"WEB_CONCURRENCY": "0"}, 1),
        (["uvicorn", "app.main:app", "--workers"], {}, 1),
    ],
)
def test_configured_worker_count(argv, environ, expected):
    assert configured_worker_count(argv, environ) == expected


def test_single_worker_without_redis_is_per_process_but_safe():
    scope = creator_cap_scope(redis_configured=False, argv=["uvicorn"], environ={})
    assert scope.scope == CREATOR_CAP_SCOPE_PER_PROCESS
    assert scope.safe is True
    assert scope.boot_error() is None


def test_multi_worker_without_redis_is_a_boot_error():
    scope = creator_cap_scope(
        redis_configured=False, argv=["uvicorn", "--workers", "2"], environ={}
    )
    assert scope.scope == CREATOR_CAP_SCOPE_PER_PROCESS
    assert scope.safe is False
    error = scope.boot_error()
    assert error is not None
    assert "2 uvicorn workers" in error
    assert "REDIS_URL" in error
    assert "AI_CREATOR_MONTHLY_CAP_USD" in error


def test_multi_worker_with_redis_is_shared_and_safe():
    scope = creator_cap_scope(
        redis_configured=True, argv=["uvicorn", "--workers", "4"], environ={}
    )
    assert scope.scope == CREATOR_CAP_SCOPE_SHARED
    assert scope.safe is True
    assert scope.boot_error() is None


# ---------------------------------------------------------------------------
# Wiring in app.main
# ---------------------------------------------------------------------------


def _reload_main(monkeypatch, *, argv: list[str], redis_url: str | None):
    monkeypatch.setattr("sys.argv", argv)
    if redis_url is None:
        monkeypatch.delenv("REDIS_URL", raising=False)
    else:
        monkeypatch.setenv("REDIS_URL", redis_url)
    monkeypatch.delenv("WEB_CONCURRENCY", raising=False)
    get_settings.cache_clear()
    importlib.reload(main_module)
    return main_module


@pytest.mark.asyncio
async def test_startup_refuses_multi_worker_without_redis(monkeypatch):
    module = _reload_main(monkeypatch, argv=["uvicorn", "app.main:app", "--workers", "2"], redis_url=None)
    monkeypatch.setattr(type(module.settings), "require_boot_secrets", lambda self: [])

    with pytest.raises(RuntimeError, match="2 uvicorn workers"):
        await module._refuse_boot_on_missing_secrets()


@pytest.mark.asyncio
async def test_startup_accepts_single_worker_without_redis(monkeypatch):
    module = _reload_main(monkeypatch, argv=["uvicorn", "app.main:app", "--workers", "1"], redis_url=None)
    monkeypatch.setattr(type(module.settings), "require_boot_secrets", lambda self: [])

    await module._refuse_boot_on_missing_secrets()  # no raise


def test_readyz_reports_scope_and_fails_closed_on_multi_worker_without_redis(monkeypatch):
    module = _reload_main(monkeypatch, argv=["uvicorn", "app.main:app", "--workers", "2"], redis_url=None)
    resp = TestClient(module.app).get("/readyz")

    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "not_ready"
    assert body["creator_cap_scope"] == CREATOR_CAP_SCOPE_PER_PROCESS
    assert body["workers"] == 2


def test_readyz_single_worker_without_redis_reports_per_process(monkeypatch):
    """The legitimate single-instance / dev mode: per-process, and readiness
    is decided by the other checks (keys), not by the cap scope."""
    module = _reload_main(monkeypatch, argv=["uvicorn", "app.main:app"], redis_url=None)
    body = TestClient(module.app).get("/readyz").json()

    assert body["creator_cap_scope"] == CREATOR_CAP_SCOPE_PER_PROCESS
    assert body["workers"] == 1
    assert body["redis"] == "not_configured"


def test_readyz_reports_shared_scope_when_redis_answers(monkeypatch):
    module = _reload_main(
        monkeypatch, argv=["uvicorn", "app.main:app", "--workers", "3"],
        redis_url="redis://redis:6379",
    )

    async def _ok():
        return "ok", True

    monkeypatch.setattr(module, "_redis_ready", _ok)
    body = TestClient(module.app).get("/readyz").json()

    assert body["creator_cap_scope"] == CREATOR_CAP_SCOPE_SHARED
    assert body["workers"] == 3


def test_readyz_set_but_unreachable_redis_with_multi_worker_is_not_ready(monkeypatch):
    """REDIS_URL set but PING failing already reported not-ready; with more
    than one worker the reported scope must also drop to per_process, because
    that is what the holds have actually degraded to."""
    module = _reload_main(
        monkeypatch, argv=["uvicorn", "app.main:app", "--workers", "3"],
        redis_url="redis://redis:6379",
    )

    async def _down():
        return "ping_failed", False

    monkeypatch.setattr(module, "_redis_ready", _down)
    resp = TestClient(module.app).get("/readyz")

    assert resp.status_code == 503
    assert resp.json()["creator_cap_scope"] == CREATOR_CAP_SCOPE_PER_PROCESS
