package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config for the Spring -&gt; influora-ai {@code POST /internal/trendspark/nudge} phrasing call
 * (T4/T8 contract). Mirrors {@link BrandSafetyAiProperties} exactly — same outbound-call shape,
 * separate config class per this codebase's "connection config vs signing secret" split (see
 * {@link BrandSafetyAiProperties} javadoc for the rationale). Reuses {@link
 * com.influora.service.integration.BrandSafetyServiceTokenService} for the signed service token
 * (same Spring -&gt; influora-ai direction, same JWKS-verified mechanism — no new signing secret
 * needed for this second endpoint). */
@ConfigurationProperties(prefix = "influora.trendspark-ai")
public class TrendSparkAiProperties {

    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 15;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8000" : baseUrl;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 5 : connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 15 : requestTimeoutSeconds;
    }
}
