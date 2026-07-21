"""FastAPI app entrypoint — Meera AI reasoner service (influora-ai).

Stateless per request: no DB, no session store, no local disk writes.
Registers /chat, /analyze-site, /voice/transcribe, /voice/speak, /healthz,
/readyz. Wires redaction-based structured JSON logging as the process-wide
logging config.

`/healthz` and `/readyz` are the only unauthenticated endpoints. Every other
route goes through the auth dependency in `app.auth.service_token` at the
route-body level (workspace_id must be read from the body before the token can
be checked against it, so auth is enforced inside each route handler rather
than as a blanket dependency — see each route module).
"""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import CLAUDE_MODEL, GEMINI_MODEL, PROMPT_VERSION, get_settings
from app.routes import analyze_site, chat, voice
from app.security.redaction import configure_logging

# trendspark / brand_safety are imported in their own try/except below (not
# in this top-level import block) so a future regression in either module
# degrades to "route not registered" + a logged error instead of the whole
# service refusing to boot (C-17).

settings = get_settings()
configure_logging(settings.log_level)
logger = logging.getLogger("influora_ai.main")

# Same import shape as app.costs.spend_tracker / app.auth.replay_guard: redis is
# optional, so importing it must never take the service down. But /readyz treats a
# set-but-unimportable/unreachable REDIS_URL as NOT ready (see _redis_ready below) —
# otherwise the daily-spend ceiling and stream-token replay guard silently fall back
# to per-process memory while the pod reports healthy.
try:  # pragma: no cover - import shape, not logic
    import redis.asyncio as redis_asyncio
except ImportError:  # pragma: no cover - redis not installed -> readyz will flag it when REDIS_URL is set
    redis_asyncio = None  # type: ignore[assignment]

app = FastAPI(
    title="Influora AI Service (Meera Reasoner)",
    description="Stateless FastAPI service: chat orchestration, website analyzer, voice. No DB, no money.",
    version=PROMPT_VERSION,
)

# Browser-direct Meera SSE stream (deploy blocker 1) — the SPA POSTs to /chat
# cross-origin with Authorization + Content-Type: application/json
# (src/hooks/useMeeraStream.ts), which triggers a CORS preflight. Only
# installed when MEERA_ALLOWED_ORIGINS is actually set (app/config.py):
# internal-only callers (Spring, n8n, health checks) get no CORS headers and
# no behavior change when it's unset. allow_credentials stays False -- the
# stream token travels in the Authorization header, never a cookie.
if settings.meera_allowed_origins_list:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.meera_allowed_origins_list,
        allow_credentials=False,
        allow_methods=["POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
        max_age=600,
    )

app.include_router(chat.router, tags=["chat"])
app.include_router(analyze_site.router, tags=["analyze-site"])
app.include_router(voice.router, tags=["voice"])

# Wave C / T8: only include_router these if the import itself succeeds — a
# regression that breaks either module's import must degrade to "route
# missing" (logged loud), never take down chat/analyze-site/voice too
# (C-17: previously neither was registered at all because importing them
# raised ImportError before config.py had the settings they need).
try:
    from app.routes import trendspark

    app.include_router(trendspark.router, tags=["trendspark"])
except Exception:
    logger.exception("trendspark router failed to import — /internal/trendspark/nudge NOT registered")

try:
    from app.routes import brand_safety

    app.include_router(brand_safety.router, tags=["brand-safety"])
except Exception:
    logger.exception("brand_safety router failed to import — /internal/brand-safety NOT registered")

try:
    from app.routes import trend_tag

    app.include_router(trend_tag.router, tags=["trendspark-recovery-tagger"])
except Exception:
    logger.exception("trend_tag router failed to import — /internal/trendspark/tag NOT registered")

try:
    from app.routes import creator_suggestion

    app.include_router(creator_suggestion.router, tags=["creator-copilot"])
except Exception:
    logger.exception(
        "creator_suggestion router failed to import — /internal/creator-suggestion NOT registered"
    )


@app.on_event("startup")
async def _refuse_boot_on_missing_secrets() -> None:
    missing = settings.require_boot_secrets()
    if missing:
        logger.error("refusing to boot: missing required secrets/config: %s", ", ".join(missing))
        raise RuntimeError(f"missing required secrets/config: {', '.join(missing)}")
    logger.info(
        "influora-ai booted: env=%s claude_model=%s gemini_model=%s prompt_version=%s",
        settings.env,
        CLAUDE_MODEL,
        GEMINI_MODEL,
        PROMPT_VERSION,
    )


@app.get("/healthz")
async def healthz():
    """Liveness — no auth. Process is up."""
    return {"status": "ok"}


async def _redis_ready() -> tuple[str, bool]:
    """Probe the shared Redis store used by the daily-spend ceiling and the
    stream-token replay guard.

    Fail-SAFE when REDIS_URL is unset: that's the legitimate single-instance /
    dev mode where both safeguards run per-process on purpose — reports
    ``not_configured`` and stays ready.

    Fail-CLOSED when REDIS_URL IS set but Redis can't actually back those
    safeguards — the package isn't importable, or a real PING fails. Returning
    not-ready here is the whole point: a set-but-broken REDIS_URL must never
    report ready, because it would mean the ceiling and replay guard have
    silently degraded to per-process memory (per-worker ceiling, per-process
    replay window) without anyone noticing.
    """
    if not settings.redis_url:
        return "not_configured", True
    if redis_asyncio is None:
        logger.error(
            "readyz: REDIS_URL is set but the `redis` package is not importable — "
            "the daily-spend ceiling and stream-token replay guard have silently "
            "fallen back to per-process memory. Reporting NOT ready."
        )
        return "package_unavailable", False
    client = None
    try:
        client = redis_asyncio.from_url(
            settings.redis_url,
            socket_connect_timeout=2,
            socket_timeout=2,
        )
        await client.ping()
        return "ok", True
    except Exception:  # noqa: BLE001 - any failure means Redis can't back the safeguards
        logger.warning(
            "readyz: REDIS_URL is set but PING failed — the daily-spend ceiling and "
            "stream-token replay guard are running on per-process memory. Reporting NOT ready.",
            exc_info=True,
        )
        return "ping_failed", False
    finally:
        if client is not None:
            try:
                await client.aclose()
            except Exception:  # noqa: BLE001 - best-effort cleanup, never fail readiness on close
                logger.debug("readyz: error closing probe Redis client", exc_info=True)


@app.get("/readyz")
async def readyz():
    """Readiness — no auth. Reports provider reachability + keys loaded (shape
    only, never the key values themselves) and, when REDIS_URL is set, whether
    the shared Redis store backing the spend ceiling / replay guard is actually
    reachable.
    """
    keys_loaded = {
        "anthropic": bool(settings.anthropic_api_key),
        "gemini": bool(settings.gemini_api_key),
        "sarvam": bool(settings.sarvam_api_key),
        "internal_hmac": bool(settings.internal_hmac_key),
        "service_token_signing_key": bool(settings.service_token_signing_key),
        "jwks_or_dev_secret": bool(settings.spring_jwks_url or settings.dev_shared_jwt_secret),
    }
    redis_status, redis_ok = await _redis_ready()
    ready = all(keys_loaded.values()) and redis_ok
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ready" if ready else "not_ready",
            "keys_loaded": keys_loaded,
            "redis": redis_status,
            "prompt_version": PROMPT_VERSION,
            "claude_model": CLAUDE_MODEL,
            "gemini_model": GEMINI_MODEL,
        },
    )
