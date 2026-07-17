"""Runtime configuration for the Meera AI reasoner service.

Loads everything from environment variables (a secrets manager in prod injects these
as env vars; locally `.env` via `.env.example` as a template). This module holds NO
real secrets — only the shape of what's required and safe non-secret defaults.

Kabir guardrail #6: this service's only secrets are the Claude / Gemini / Sarvam API
keys plus the internal HMAC key and the Spring JWKS URL. It never holds Razorpay or
DB credentials — blast-radius isolation from the money core.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache


def _get_bool(name: str, default: bool) -> bool:
    val = os.getenv(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


def _get_float(name: str, default: float) -> float:
    val = os.getenv(name)
    if val is None or val == "":
        return default
    try:
        return float(val)
    except ValueError:
        return default


def _get_int(name: str, default: int) -> int:
    val = os.getenv(name)
    if val is None or val == "":
        return default
    try:
        return int(val)
    except ValueError:
        return default


# ---------------------------------------------------------------------------
# Pinned model / prompt versions — this constant IS the P0 fix. Do not point
# this at gemini-2.0-flash again; that model id is deprecated.
# ---------------------------------------------------------------------------
GEMINI_MODEL = "gemini-2.5-flash-lite"
CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5-20250929")
PROMPT_VERSION = "meera-2026.07.05"

# India / approved regions only (Kabir guardrail #3) — informational; enforced by
# provider client base URLs / region config below.
APPROVED_LLM_REGIONS = ("asia-south1", "ap-south-1", "in")


@dataclass(frozen=True)
class ProviderTimeouts:
    """Per-provider connect/read timeouts, seconds. Tight per §6 of the AI service spec."""

    claude_connect: float = 3.0
    claude_first_token: float = 8.0
    claude_read: float = 30.0

    gemini_connect: float = 3.0
    gemini_read: float = 20.0

    sarvam_connect: float = 3.0
    sarvam_stt_read: float = 10.0
    sarvam_tts_read: float = 8.0

    spring_connect: float = 2.0
    spring_read: float = 5.0

    scrape_total: float = 30.0


@dataclass(frozen=True)
class RetryPolicy:
    """Retries only for idempotent GET-like calls. Never blind-retry money-tool forwards."""

    max_retries: int = 2
    backoff_base_seconds: float = 0.25
    backoff_jitter_seconds: float = 0.15


@dataclass(frozen=True)
class CircuitBreakerConfig:
    failure_threshold: int = 5
    recovery_seconds: float = 30.0
    half_open_max_calls: int = 1


@dataclass(frozen=True)
class Settings:
    # --- Service identity / environment ---
    env: str = field(default_factory=lambda: os.getenv("APP_ENV", "dev"))
    service_name: str = "influora-ai"
    log_level: str = field(default_factory=lambda: os.getenv("LOG_LEVEL", "INFO"))

    # --- Provider API keys (secrets-manager injected; never committed) ---
    anthropic_api_key: str = field(default_factory=lambda: os.getenv("ANTHROPIC_API_KEY", ""))
    gemini_api_key: str = field(default_factory=lambda: os.getenv("GEMINI_API_KEY", ""))
    sarvam_api_key: str = field(default_factory=lambda: os.getenv("SARVAM_API_KEY", ""))

    # --- Spring auth integration ---
    spring_jwks_url: str = field(default_factory=lambda: os.getenv("SPRING_JWKS_URL", ""))
    spring_jwks_cache_seconds: int = field(
        default_factory=lambda: _get_int("SPRING_JWKS_CACHE_SECONDS", 300)
    )
    spring_expected_iss: str = field(
        default_factory=lambda: os.getenv("SPRING_JWT_ISSUER", "influora-api")
    )
    service_token_aud: str = field(
        default_factory=lambda: os.getenv("SERVICE_TOKEN_AUD", "influora-internal")
    )
    stream_token_aud: str = field(
        default_factory=lambda: os.getenv("STREAM_TOKEN_AUD", "meera-stream")
    )
    # Fallback / dev-only symmetric verification key. In prod, JWKS (asymmetric) is used;
    # this is only consulted when SPRING_JWKS_URL is unset (local dev), never in prod.
    dev_shared_jwt_secret: str = field(
        default_factory=lambda: os.getenv("DEV_SHARED_JWT_SECRET", "")
    )

    # --- Internal request signing (Python -> Spring /internal/meera/*) ---
    internal_hmac_key: str = field(default_factory=lambda: os.getenv("INTERNAL_HMAC_KEY", ""))
    internal_hmac_key_id: str = field(
        default_factory=lambda: os.getenv("INTERNAL_HMAC_KEY_ID", "v1")
    )

    # --- X-Meera-Service-Token minting (Python -> Spring /internal/meera/*) ---
    # DISTINCT secret from internal_hmac_key above -- this signs the service-token
    # JWT itself (iss=meera-python, aud=influora-internal, HS256, exp-iat<=60s);
    # internal_hmac_key signs the X-Meera-Signature request HMAC. Must match
    # Spring's `influora.internal-service-token.signing-secret` byte-for-byte
    # (InternalServiceTokenProperties.signingSecret / InternalServiceTokenFilter).
    service_token_signing_key: str = field(
        default_factory=lambda: os.getenv("SERVICE_TOKEN_SIGNING_KEY", "")
    )

    # --- Spring internal base URL ---
    # Must include the /api/v1 context-path -- influora-api's
    # server.servlet.context-path applies to every controller, including
    # MeeraInternalController's /internal/meera/* routes.
    spring_internal_base_url: str = field(
        default_factory=lambda: os.getenv("SPRING_INTERNAL_BASE_URL", "http://localhost:8080/api/v1")
    )

    # --- SSRF guard egress allow-list (analyze-site) ---
    ssrf_allowed_schemes: tuple[str, ...] = ("https",)
    ssrf_max_redirects: int = field(default_factory=lambda: _get_int("SSRF_MAX_REDIRECTS", 2))
    ssrf_max_response_bytes: int = field(
        default_factory=lambda: _get_int("SSRF_MAX_RESPONSE_BYTES", 5_000_000)
    )
    ssrf_fetch_timeout_seconds: float = field(
        default_factory=lambda: _get_float("SSRF_FETCH_TIMEOUT_SECONDS", 15.0)
    )

    # --- Tool loop ---
    tool_loop_max_iterations: int = field(
        default_factory=lambda: _get_int("TOOL_LOOP_MAX_ITERATIONS", 6)
    )

    # --- SSE ---
    sse_heartbeat_seconds: float = field(
        default_factory=lambda: _get_float("SSE_HEARTBEAT_SECONDS", 15.0)
    )

    # --- Grouped configs ---
    timeouts: ProviderTimeouts = field(default_factory=ProviderTimeouts)
    retry: RetryPolicy = field(default_factory=RetryPolicy)
    breaker: CircuitBreakerConfig = field(default_factory=CircuitBreakerConfig)

    def require_boot_secrets(self) -> list[str]:
        """Return a list of missing/weak required secrets. Empty list == OK to boot.

        Per D0 DoD: "Missing/weak key -> refuse boot". main.py calls this at startup.
        """
        missing: list[str] = []
        if not self.anthropic_api_key:
            missing.append("ANTHROPIC_API_KEY")
        if not self.gemini_api_key:
            missing.append("GEMINI_API_KEY")
        if not self.sarvam_api_key:
            missing.append("SARVAM_API_KEY")
        if not self.spring_jwks_url and not self.dev_shared_jwt_secret:
            missing.append("SPRING_JWKS_URL (or DEV_SHARED_JWT_SECRET for local dev)")
        if not self.internal_hmac_key:
            missing.append("INTERNAL_HMAC_KEY")
        if not self.service_token_signing_key:
            missing.append("SERVICE_TOKEN_SIGNING_KEY")
        return missing


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
