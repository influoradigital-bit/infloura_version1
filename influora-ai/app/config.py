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


def _get_optional_float(name: str) -> float | None:
    """Like `_get_float`, but returns None (not a numeric default) when the
    env var is unset/empty -- used for opt-in thresholds where "unset" and
    "0" must be distinguishable (app.costs.gate's per-workspace hard cap,
    Kabir red-team FIX 3)."""
    val = os.getenv(name)
    if val is None or val == "":
        return None
    try:
        return float(val)
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# Pinned model / prompt versions — this constant IS the P0 fix. Do not point
# this at gemini-2.0-flash again; that model id is deprecated.
# ---------------------------------------------------------------------------
# gemini-2.5-flash-lite was retired by Google ("no longer available", 404), which
# broke every Gemini call (analyze_site classify, trendspark, brand-safety). Moved
# to the current stable gemini-2.5-flash (verified 200 against the live API).
GEMINI_MODEL = "gemini-2.5-flash"
CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5-20250929")
PROMPT_VERSION = "meera-2026.07.21.1"

# Trend-Spark (T8) — cheap Haiku-class model for the ONE phrasing call. Never
# Opus/Sonnet (schema-lock §4 / cost §1). Pinned the same way as CLAUDE_MODEL
# above; overridable via env for a future Haiku bump.
TRENDSPARK_MODEL = os.getenv("TRENDSPARK_MODEL", "claude-haiku-4-5-20251001")
# Trend-Spark LLM Recovery Tagger — the "smart AI" recovery pass over the
# deterministic n8n tagger (app/routes/trend_tag.py). Same cheap Haiku class as
# the nudge; defaults to TRENDSPARK_MODEL so both share one pricing row
# (app/costs/pricing.py). Overridable via env for an independent bump.
TREND_TAG_MODEL = os.getenv("TREND_TAG_MODEL", TRENDSPARK_MODEL)
# Persona name injected into the Trend-Spark system prompt — a single config
# constant (schema-lock §6) so it's never hardcoded in app/prompt/trendspark.py.
TRENDSPARK_PERSONA_NAME = os.getenv("TRENDSPARK_PERSONA_NAME", "Meera")

# Brand-safety GARM classification model (Wave C task C2) — pinned the same
# way as TRENDSPARK_MODEL above. The default is DELIBERATELY Sonnet
# (CLAUDE_MODEL), not a Haiku-class model: GARM labeling is bounded,
# schema-locked classification and Ash's AI review (wiki/ai-review/
# partial-fixes-batch-ai-review.md P1 #2) flags Sonnet as the biggest
# avoidable cost once the brand-safety fan-out scales past its current
# capped/disabled state -- but the flip to Haiku is gated on an A/B against
# a GARM golden set (same doc, "Data & Training Roadmap") so quality is
# proven, not assumed. Do not point this at a Haiku-class model without that
# eval. Overridable via env for that future (evaluated) bump.
BRAND_SAFETY_MODEL = os.getenv("BRAND_SAFETY_MODEL", CLAUDE_MODEL)

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

    # --- Trend-Spark nudge (T8) — input caps + output shape for the one cheap
    # phrasing call. Defaults per app/prompt/trendspark.py's tone-guide rules
    # (<=2 sentences, short brand/trend inputs, small catalog per call).
    trendspark_max_brand_name_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_BRAND_NAME_CHARS", 80)
    )
    trendspark_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_TREND_TEXT_CHARS", 200)
    )
    trendspark_max_videos: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_VIDEOS", 5)
    )
    trendspark_max_message_chars: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_MESSAGE_CHARS", 300)
    )
    trendspark_max_tokens: int = field(
        default_factory=lambda: _get_int("TRENDSPARK_MAX_TOKENS", 300)
    )

    # --- Trend-Spark LLM Recovery Tagger (POST /internal/trendspark/tag) ---
    # Static shared secret for the n8n ingest caller (DELIBERATE exception to the
    # service-token rule — see app/routes/trend_tag.py). Empty => the endpoint
    # fails closed with 503 and never runs open. NOT added to require_boot_secrets:
    # the tagger is opt-in, and the whole service must not refuse to boot just
    # because this one recovery pass is unconfigured.
    trend_tag_ingest_secret: str = field(
        default_factory=lambda: os.getenv("TREND_TAG_INGEST_SECRET", "")
    )
    trend_tag_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_TREND_TEXT_CHARS", 200)
    )
    trend_tag_max_themes: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_THEMES", 6)
    )
    trend_tag_max_tokens: int = field(
        default_factory=lambda: _get_int("TREND_TAG_MAX_TOKENS", 80)
    )
    # Per-process trailing-60s cap. Blunts brute-forcing the static secret and
    # caps runaway token spend. <=0 disables (tests). n8n's real volume is low.
    trend_tag_rate_limit_per_minute: int = field(
        default_factory=lambda: _get_int("TREND_TAG_RATE_LIMIT_PER_MINUTE", 120)
    )

    # --- Brand-safety GARM classification (Wave C task C2) ---
    brand_safety_max_items_per_call: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_ITEMS_PER_CALL", 25)
    )
    brand_safety_max_caption_chars: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_CAPTION_CHARS", 2000)
    )
    brand_safety_max_meta_field_chars: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_META_FIELD_CHARS", 200)
    )
    brand_safety_max_tokens: int = field(
        default_factory=lambda: _get_int("BRAND_SAFETY_MAX_TOKENS", 4096)
    )

    # --- AI spend ceiling + kill-switch (P2-17, Rohan budget proposal
    # 2026-07-12) — defaults exactly as specified in that proposal §3.5. ---
    ai_daily_spend_ceiling_usd: float = field(
        default_factory=lambda: _get_float("AI_DAILY_SPEND_CEILING_USD", 15.0)
    )
    ai_spend_kill_switch: bool = field(
        default_factory=lambda: _get_bool("AI_SPEND_KILL_SWITCH", False)
    )
    # Chat-only soft cap (WARNING-only, not blocking) — see routes/chat.py.
    ai_workspace_daily_soft_cap_usd: float = field(
        default_factory=lambda: _get_float("AI_WORKSPACE_DAILY_SOFT_CAP_USD", 3.0)
    )
    # Kabir red-team FIX 3 — opt-in, BLOCKING per-workspace daily cap enforced
    # by app.costs.gate.check_spend_gate(). None (default/unset) preserves the
    # pre-existing warning-only-only behavior above exactly; set this env var
    # to actually block a workspace once it's spent this much today. Distinct
    # from ai_workspace_daily_soft_cap_usd (which never blocks) — see
    # app/costs/gate.py's module docstring.
    ai_workspace_daily_hard_cap_usd: float | None = field(
        default_factory=lambda: _get_optional_float("WORKSPACE_DAILY_HARD_CAP_USD")
    )

    # --- CORS (browser-direct Meera SSE stream) ---
    # Comma-separated list of exact allowed origins for the browser's cross-origin
    # POST /chat call (Authorization + Content-Type: application/json --
    # src/hooks/useMeeraStream.ts). Empty (default) => CORSMiddleware is never
    # installed at all: internal-only callers (Spring, n8n, tests, curl) are
    # completely unaffected and no CORS headers are ever added -- closed by
    # default. Set to the exact public origin(s) the SPA is served from in any
    # deploy that streams Meera directly from the browser; never "*" (a bearer
    # Authorization header requires an explicit origin echo, not a wildcard).
    meera_allowed_origins: str = field(
        default_factory=lambda: os.getenv("MEERA_ALLOWED_ORIGINS", "")
    )

    @property
    def meera_allowed_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.meera_allowed_origins.split(",") if origin.strip()]

    # --- Shared spend-tracker store (H-25) ---
    # When unset, app.costs.spend_tracker stays on its per-process in-memory
    # counter (documented Phase-1 limitation above). When set, the tracker
    # persists global/per-workspace daily spend in Redis so the ceiling and
    # kill-switch are enforced correctly across multiple worker processes /
    # instances, not per-process. Optional by design — a Redis outage or a
    # missing var must never block chat; the tracker falls back to in-memory
    # per-call on any connection error (see spend_tracker.py).
    redis_url: str = field(default_factory=lambda: os.getenv("REDIS_URL", ""))

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
