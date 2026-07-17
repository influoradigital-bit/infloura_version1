package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing/verification config for the Python→Spring internal mesh call
 * ({@code /internal/meera/*}). Deliberately a DISTINCT secret from every other key in the system
 * (user JWT, refresh, Meera stream token, R2, Razorpay) — Domain E blast-radius separation
 * ([SEC: 1.2/Layer 6, MF-3, LB-1]). A compromise of this key alone must not be usable against
 * any other credential surface, and vice versa.
 *
 * <p>{@code aud} is fixed to {@code influora-internal} and {@code iss} to {@code meera-python}
 * per 09-ADVANCED-SECURITY-MEASURES.md §2.2 — not configurable, so a misconfiguration cannot
 * silently widen acceptance.
 */
@ConfigurationProperties(prefix = "influora.internal-service-token")
public class InternalServiceTokenProperties {

    private String signingSecret = "";

    /** Hard ceiling regardless of config — service tokens must be short-lived (≤60s). */
    public static final long MAX_TTL_SECONDS = 60;

    /** HMAC signing key shared out-of-band with the Python service for the request-signature header. */
    private String hmacSigningSecret = "";

    /** Allowed clock skew (seconds) for both token exp and the request-signature timestamp. */
    private long clockSkewSeconds = 30;

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public String getHmacSigningSecret() {
        return hmacSigningSecret;
    }

    public void setHmacSigningSecret(String hmacSigningSecret) {
        this.hmacSigningSecret = hmacSigningSecret;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public boolean isConfigured() {
        return signingSecret != null && !signingSecret.isBlank();
    }
}
