package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config for the Spring -&gt; influora-ai {@code POST /internal/creator-suggestion} phrasing call
 * (be-services-plan.md §4). Mirrors {@link TrendSparkAiProperties} exactly — same outbound-call
 * shape, same "connection config vs signing secret" split. Reuses {@link
 * com.influora.service.integration.CreatorSuggestionServiceTokenService} for the signed
 * creator-scoped service token (same Spring -&gt; influora-ai direction/JWKS mechanism as
 * Trend-Spark's brand-scoped call, different scope/claim — see that class's javadoc). */
@ConfigurationProperties(prefix = "influora.creator-copilot-ai")
public class CreatorSuggestionAiProperties {

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
