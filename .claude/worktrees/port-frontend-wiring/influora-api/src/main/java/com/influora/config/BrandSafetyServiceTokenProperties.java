package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing config for the short-lived service token Spring mints when calling influora-ai's
 * {@code POST /internal/brand-safety} (Wave C task C3) — the Spring -&gt; Python direction.
 *
 * <p><b>Do not confuse with {@link InternalServiceTokenProperties}</b>, which is the OPPOSITE
 * direction (Python -&gt; Spring {@code /internal/meera/*}, {@code X-Meera-Service-Token} minted by
 * {@code influora-ai/app/auth/service_token.py}, verified here by {@code InternalRequestVerifier}
 * / {@code InternalServiceTokenFilter}). This class is for a token Spring mints and Python
 * verifies — see {@code influora-ai/app/auth/service_token.py::verify_token}.
 *
 * <p>Mirrors the existing {@code MeeraStreamProperties} pattern exactly: a signing secret
 * DISTINCT from every other credential surface in this codebase (JWT access/refresh, Meera
 * stream, internal-service-token/HMAC, R2, Razorpay, Meta) — a compromise of one must not be
 * usable against another (Kabir guardrail 6 / blast-radius separation).
 *
 * <p><b>Local-dev wiring:</b> influora-ai's {@code service_token.py} verifies via Spring's JWKS in
 * prod (asymmetric RS256/ES256 — Spring does not expose a JWKS endpoint yet, see {@code
 * BrandSafetyServiceTokenService} javadoc for the full reasoning) but falls back to a single
 * shared HS256 secret ({@code DEV_SHARED_JWT_SECRET}) when {@code SPRING_JWKS_URL} is unset. For
 * this token to verify in local dev, {@code influora.brand-safety-service-token.signing-secret}
 * (env {@code BRAND_SAFETY_SERVICE_TOKEN_SECRET}) MUST be set to the exact same value — BYTE FOR
 * BYTE — as influora-ai's {@code DEV_SHARED_JWT_SECRET}, or every incoming
 * {@code /internal/brand-safety} call fails closed with 401 invalid-signature. Same discipline as
 * the existing {@code INTERNAL_HMAC_KEY} (Python env) / {@code
 * influora.internal-service-token.hmac-signing-secret} ({@link InternalServiceTokenProperties})
 * pairing and the {@code MEERA_STREAM_SIGNING_SECRET} / {@code DEV_SHARED_JWT_SECRET} pairing
 * already documented for the existing Meera stream token — see both {@code .env.example} files
 * ({@code influora-api/.env.example} and {@code influora-ai/.env.example}) for the mirrored
 * comments.
 */
@ConfigurationProperties(prefix = "influora.brand-safety-service-token")
public class BrandSafetyServiceTokenProperties {

    /** Hard ceiling regardless of config — mirrors {@link InternalServiceTokenProperties#MAX_TTL_SECONDS}
     * as the ceiling convention for internal service tokens in this codebase. */
    public static final long MAX_TTL_SECONDS = 60;

    private String signingSecret = "";
    private long ttlSeconds = 60;

    /** Must equal influora-ai's {@code SERVICE_TOKEN_AUD} (env, default {@code influora-internal}). */
    private String audience = "influora-internal";

    /** Must equal influora-ai's {@code SPRING_JWT_ISSUER} (env, default {@code influora-api}). */
    private String issuer = "influora-api";

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds <= 0 ? 60 : ttlSeconds;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = (audience == null || audience.isBlank()) ? "influora-internal" : audience;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = (issuer == null || issuer.isBlank()) ? "influora-api" : issuer;
    }

    public boolean isConfigured() {
        return signingSecret != null && !signingSecret.isBlank();
    }
}
