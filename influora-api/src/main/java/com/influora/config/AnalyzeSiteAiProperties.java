package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Spring -&gt; influora-ai (Python) {@code POST /analyze-site} call (H-23). Same
 * split as {@link BrandSafetyAiProperties}/{@link TrendSparkAiProperties} — base URL/timeout
 * config lives here, the signing secret stays in {@code BrandSafetyServiceTokenProperties} (this
 * client reuses {@code BrandSafetyServiceTokenService} rather than minting a parallel signing
 * secret for the same Spring-&gt;influora-ai leg, exactly like {@code TrendSparkAiClient} already
 * does).
 *
 * <p>Timeout is deliberately longer than {@link BrandSafetyAiProperties}'s default: {@code
 * analyze_site.py}'s own docstring states an end-to-end target of &lt;= 45s (page fetch + Gemini
 * classification), so the HTTP client's read timeout must comfortably exceed that.
 */
@ConfigurationProperties(prefix = "influora.analyze-site-ai")
public class AnalyzeSiteAiProperties {

    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 60;

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

    /** Mirrors {@code analyze_site.py}'s &lt;= 45s end-to-end target with headroom. */
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 60 : requestTimeoutSeconds;
    }
}
