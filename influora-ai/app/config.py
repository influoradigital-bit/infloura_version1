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
PROMPT_VERSION = "meera-2026.08.10.1"
# ^ bumped for ME-2 (BrandF.md §115): the request_payment/confirm_launch tool
# bullets in Block A used to tell Meera to "propose a payment"/"propose
# launching" via those tools — but get_tool_schemas() (schemas.py) no longer
# offers either (they're scope-gated out for every real caller today), so the
# old copy described capabilities Meera didn't actually have. Rewritten to
# point the brand at the direct wallet/campaign controls instead, and to
# explicitly forbid claiming she took a funding/launch action she didn't.
#
# Previously: bumped for the 2026-08-08 deep-audit fixes. Persona text changed
# (F-17: the money caveat rail referenced `price_confidence`, a field that
# exists nowhere in the repo — the real field is `price_source`, so the rail
# never fired and Meera quoted model-guessed prices as confirmed facts). Block
# C assembly also changed (F-08/F-15: replayed history no longer produces
# native tool_use /
# tool_result blocks). Both are part of Block A/C, and prompt_version is a
# component of `cache_key_for`, so without this bump sessions cached under `.12`
# would keep serving the stale persona and the old replay shape.
# ^ bumped for the Meera campaign-completion flow build (Vikram, 2026-07-24,
# wiki/build/meera-completion-flow-2026-07-23.md): persona.py gained a
# "Completing a campaign after create_campaign" section (STANDARD Option B --
# derive the next field to ask from the DRAFT STATE the tool returned, one
# field per turn, budget-then-dates, honest "drafted, not live" completion
# CTA; full conversational HYPE -- explicit-signal-only, compose hashtag +
# format lanes, then sourceReelUrl -> perReelRate -> slotCap -> confirm 72h
# window one field per turn, say the rate x slots math out loud once as
# advisory copy, "open it to launch the blitz" CTA, never "it's live"). The
# create_campaign tool schema gained two FLAT optional content fields,
# source_reel_url and format_lanes (no combinators, no money/date fields --
# per_reel_rate/slot_cap stay human-only, enforced in
# CreateCampaignExecutor.java not JSON Schema). Both persona and schema are
# part of Block A's cached prefix, so this bump is required per this file's
# own rule above.
#
# Prior bump: the create_campaign draft-completeness fix (Tier 0 #2 + Tier 1,
# Ash-approved plan 2026-07-23): campaign_type is now OPTIONAL on the
# create_campaign tool schema (added STANDARD, dropped it from `required` —
# this is the root-cause fix for the HYPE-default invisible-draft bug) and 7
# new optional content-composition inputs were added (title, description,
# objectives, platforms, content_types, hashtags, target_audience). The
# persona text changed alongside it: a draft-is-never-live honesty rule, a
# compose-the-draft instruction for create_campaign, and an anti-leak rule
# against reading internal tool-instruction phrasing aloud. Both the tool
# schema and the persona are part of Block A's cached prefix, so this bump is
# required per this file's own rule above — without it, sessions cached under
# `.9` would keep serving the stale schema/persona pair.
#
# Prior bump: Phase 2 item 2.1 (outcome digest) — Block B gains the
# `outcome_digest` section (campaign_outcomes[] + niche_rate_band), and
# prompt_version is a component of `cache_key_for` — without the bump,
# sessions cached under `.8` would keep serving a stale Block B with no
# digest, and eval deltas across that change couldn't be cleanly attributed
# (Ash's Q3 ruling, wiki/build/phase2-ash-review.md).
# Prior bump: Creator AI Co-pilot Tier-1 creator-tone prompt
# (app/prompt/creator_suggestion.py). Single global constant, reused (not
# split) per Priya's R1 ruling §5.1 — the per-row
# `creator_nudge_log.prompt_version` column (Java-side, stamped by Vikram's
# CreatorNudgeService) gives audit granularity, so a second Python constant
# would add a second source of truth for no additional audit power.

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

# Creator AI Co-pilot Tier-1 (POST /internal/creator-suggestion) — same cheap
# Haiku-class model as Trend-Spark for the ONE phrasing call. Defaults to the
# EXACT TRENDSPARK_MODEL string (not a separate literal) so it shares
# TRENDSPARK_MODEL's PRICING_TABLE row automatically via the same
# _resolve_rate fallback pattern pricing.py already has for TREND_TAG_MODEL /
# BRAND_SAFETY_MODEL -- overridable via env for an independent bump, per
# wiki/build/creator-copilot-ai-route-plan.md §5.2.
CREATOR_COPILOT_MODEL = os.getenv("CREATOR_COPILOT_MODEL", TRENDSPARK_MODEL)

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
    # Batch REST /text-to-speech (NOT the streaming WS API) takes ~2.5-4s+ for a
    # 400-500 char Hindi-capable reply; 8.0s was too tight and produced
    # `sarvam speak failed: ReadTimeout`, which drops the reply to the browser
    # fallback voice AND (on repeats) trips the TTS circuit breaker, so voice
    # stays dead through the recovery window. 15s gives a normal reply room to
    # finish. Voice is additive (the text reply is already on screen), so a
    # longer read ceiling costs nothing on the critical path. If TTS latency
    # becomes the bottleneck, move to Sarvam's streaming WebSocket TTS.
    sarvam_tts_read: float = 15.0

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
    # F-10: this defaulted to "dev". A prod deploy manifest that dropped or
    # typo'd APP_ENV booted successfully into the HS256 dev auth path, where
    # anyone holding the one symmetric secret can mint a token for ANY
    # workspace_id. Both "defense-in-depth" guards in auth/service_token.py
    # compare against this same field, so they were never two gates — they were
    # one gate read twice, and an unset env var opened both at once.
    #
    # Fail-safe direction is "prod": an unset APP_ENV must break local dev
    # loudly (set APP_ENV=dev), never silently weaken production auth.
    env: str = field(default_factory=lambda: os.getenv("APP_ENV", "prod"))
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
    # F-09: PyJWKClient's default urlopen timeout is 30s and HttpJwksSource
    # passed none, so one unauthenticated request with an attacker-chosen `kid`
    # could park the event loop for 30 seconds. Tight, explicit, overridable.
    spring_jwks_timeout_seconds: float = field(
        default_factory=lambda: _get_float("SPRING_JWKS_TIMEOUT_SECONDS", 3.0)
    )
    # F-09: minimum seconds between two JWKS refetches triggered by an UNKNOWN
    # kid. Without this, `kid` is fully attacker-controlled and every miss is one
    # outbound HTTPS GET to Spring — N req/s of unauthenticated traffic becomes
    # N req/s of amplified load on the auth server. Inside the cooldown an
    # unknown kid is rejected with zero network I/O.
    spring_jwks_refetch_cooldown_seconds: float = field(
        default_factory=lambda: _get_float("SPRING_JWKS_REFETCH_COOLDOWN_SECONDS", 10.0)
    )
    # F-09: a `kid` longer than this, or carrying characters outside the JWK
    # thumbprint charset, is rejected before any lookup.
    spring_jwks_max_kid_length: int = field(
        default_factory=lambda: _get_int("SPRING_JWKS_MAX_KID_LENGTH", 128)
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
    # Output-token ceiling per Meera chat turn. IMPORTANT: this is a BACKSTOP,
    # not the mechanism that shapes reply length -- Meera's persona (see
    # persona.py's "HARD LENGTH LIMIT" rails) is what keeps spoken replies to
    # ONE-to-TWO short sentences, and does the real work. This constant only
    # needs to be wide enough that a legitimate tool call never gets cut off
    # mid-JSON.
    #
    # P1 BLANK TURN defect (2026-07-24, wiki/ai-review/meera-blank-turn-ai-
    # review.md): 384 was picked purely as a spoken-length ruler and was never
    # re-derived once create_campaign grew to 15 properties (schemas.py). Tool
    # JSON is never spoken, so a narration-sized ceiling is the wrong ruler for
    # it -- a fully-composed HYPE create_campaign call is ~245-365 output
    # tokens of JSON alone, which sat 384 exactly on the truncation boundary.
    # A max_tokens cut mid `input_json_delta` silently discarded the entire
    # tool call (claude.py) with no error, no log -- ~28% of turns went
    # completely blank. 1536 clears every known tool payload with headroom;
    # F1 (claude.py) + F2 (loop.py) make a cut structurally non-silent even if
    # this ceiling is later outgrown again. MUST be pinned explicitly in
    # env.example and every deploy env artifact -- do not let it silently fall
    # back to a code default again.
    meera_chat_max_tokens: int = field(
        default_factory=lambda: _get_int("MEERA_CHAT_MAX_TOKENS", 1536)
    )
    # Wider one-shot ceiling for loop.py's single server-side retry after a
    # truncated tool-planning turn comes back empty (F2). Tool JSON is not
    # spoken, so the narration-length rationale above does not apply to a
    # retry that exists purely to let one tool call finish. This retry is
    # provably pre-tool-forward (no tool_start emitted yet, nothing sent to
    # Spring, no Idempotency-Key consumed), so widening the ceiling here
    # cannot double-execute or double-spend anything.
    meera_chat_max_tokens_retry: int = field(
        default_factory=lambda: _get_int("MEERA_CHAT_MAX_TOKENS_RETRY", 2048)
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

    # --- Creator AI Co-pilot Tier-1 (POST /internal/creator-suggestion) ---
    # Input cap + output shape for the one cheap phrasing call. Mirrors the
    # trendspark_* caps above; no CREATOR_COPILOT_MAX_CAPTION_CHARS -- R1
    # dropped `caption_snippet` from the request contract entirely, so
    # there's nothing left to cap (see app/prompt/creator_suggestion.py).
    creator_copilot_max_trend_text_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_TREND_TEXT_CHARS", 200)
    )
    creator_copilot_max_headline_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_HEADLINE_CHARS", 120)
    )
    creator_copilot_max_content_idea_chars: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_CONTENT_IDEA_CHARS", 300)
    )
    creator_copilot_max_tokens: int = field(
        default_factory=lambda: _get_int("CREATOR_COPILOT_MAX_TOKENS", 300)
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

    # --- F-05 spend reservations ---
    # Pessimistic per-call estimate held between the gate check and the recorded
    # spend, so concurrent callers see each other's in-flight cost instead of
    # all reading the same stale total. Deliberately generous: an over-estimate
    # briefly under-authorizes (a caller waits), an under-estimate lets the
    # ceiling be overshot, which is the failure this exists to prevent. Set to
    # 0 to disable reservations entirely (restores pre-fix behaviour).
    ai_reservation_per_call_usd: float = field(
        default_factory=lambda: _get_float("AI_RESERVATION_PER_CALL_USD", 0.02)
    )
    # Lowered 0.10 -> 0.02 on Priya's sign-off review. At 0.10 x
    # tool_loop_max_iterations (6) a chat turn held $0.60, so a $15 ceiling
    # admitted only 25 concurrent turns per process AT ZERO ACTUAL SPEND — the
    # 26th user got a 503 AI_SPEND_CEILING_REACHED. The F-05 fix must close a
    # money leak, not convert it into an availability cliff. $0.02 x 6 = $0.12
    # per turn (~125 concurrent turns) and still comfortably exceeds a real
    # Sonnet turn's cost, which is what the reservation needs to cover.
    #
    # How long a reservation is held before it self-expires. The chat tool loop
    # can legitimately run for minutes (SSRF fetch is 15s/hop, Gemini read 20s,
    # up to tool_loop_max_iterations turns), so it needs a longer hold than a
    # single provider call. Under-setting this silently reopens the F-05 race
    # for exactly the slow tail the reservation exists to cover.
    ai_reservation_chat_ttl_seconds: float = field(
        default_factory=lambda: _get_float("AI_RESERVATION_CHAT_TTL_SECONDS", 300.0)
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
        # F-10: DEV_SHARED_JWT_SECRET is an acceptable substitute for
        # SPRING_JWKS_URL **only** in env=dev. Accepting it everywhere meant the
        # symmetric-secret path could satisfy the boot check in any environment.
        if not self.spring_jwks_url:
            if self.env == "dev":
                if not self.dev_shared_jwt_secret:
                    missing.append("SPRING_JWKS_URL (or DEV_SHARED_JWT_SECRET for local dev)")
            else:
                missing.append(
                    f"SPRING_JWKS_URL (required when APP_ENV={self.env!r}; the "
                    "DEV_SHARED_JWT_SECRET fallback is accepted only when APP_ENV=dev)"
                )
        if not self.internal_hmac_key:
            missing.append("INTERNAL_HMAC_KEY")
        if not self.service_token_signing_key:
            missing.append("SERVICE_TOKEN_SIGNING_KEY")
        return missing


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
