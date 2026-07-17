package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Spring -&gt; influora-ai (Python) {@code POST /chat} call (Wave 3 task A4 — Meera's
 * real model turn). Same split as {@link BrandSafetyAiProperties}/{@link TrendSparkAiProperties}/
 * {@link AnalyzeSiteAiProperties} — base URL/timeout config lives here, the signing secret stays in
 * {@code BrandSafetyServiceTokenService} (this client reuses that service to mint its {@code
 * scope=service} token rather than minting a parallel signing secret for the same
 * Spring-&gt;influora-ai leg, exactly like {@code AnalyzeSiteAiClient} already does).
 *
 * <p>Timeout is deliberately generous: {@code /chat} is a Server-Sent-Events response covering a
 * full Claude turn, possibly spanning multiple tool-loop iterations (cap 6, {@code
 * app/tools/loop.py::DEFAULT_MAX_ITERATIONS}) with a ~15s heartbeat keeping the connection warm —
 * the request timeout must comfortably exceed the worst-case multi-iteration turn, not just a
 * single model round-trip.
 */
@ConfigurationProperties(prefix = "influora.meera-chat-ai")
public class MeeraChatAiProperties {

    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 120;

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

    /** Mirrors the multi-iteration tool-loop worst case (see class javadoc) with headroom. */
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 120 : requestTimeoutSeconds;
    }
}
