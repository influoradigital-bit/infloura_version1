package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing config for the short-lived Meera SSE stream token (Guardrail 2 / Guardrail 6).
 *
 * <p>Deliberately a DISTINCT signing key from {@link JwtProperties} (user access/refresh) — a
 * compromise of one must not compromise the other. TTL is capped at 60s per the API contract
 * (§4 streaming design); {@code streamTokenTtlSeconds} exists only so ops can shorten it further,
 * never lengthen it past the contract ceiling (enforced in {@code StreamTokenService}).
 */
@ConfigurationProperties(prefix = "influora.meera.stream")
public class MeeraStreamProperties {

    private String signingSecret;
    private long streamTokenTtlSeconds = 60;

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public long getStreamTokenTtlSeconds() {
        return streamTokenTtlSeconds;
    }

    public void setStreamTokenTtlSeconds(long streamTokenTtlSeconds) {
        this.streamTokenTtlSeconds = streamTokenTtlSeconds;
    }
}
