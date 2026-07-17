package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the Spring -&gt; influora-ai (Python) {@code POST /internal/brand-safety} call (Wave C
 * task C3). This is the ONLY outbound HTTP integration Spring makes into influora-ai today —
 * every other cross-service call in this codebase runs the opposite direction ({@code
 * /internal/meera/*}, verified via {@link InternalServiceTokenProperties} /
 * {@code InternalRequestVerifier}).
 *
 * <p><b>Signing key is deliberately its OWN {@link BrandSafetyServiceTokenProperties}, not this
 * class</b> — base URL/timeout config and signing-secret config are kept in separate
 * {@code @ConfigurationProperties} classes throughout this codebase (see {@code
 * RazorpayProperties} vs {@code InternalServiceTokenProperties}) so a credential rotation never
 * touches unrelated connection settings.
 */
@ConfigurationProperties(prefix = "influora.brand-safety-ai")
public class BrandSafetyAiProperties {

    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 30;
    private int maxItemsPerCall = 25;

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
        this.requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 30 : requestTimeoutSeconds;
    }

    /** Mirrors influora-ai's {@code brand_safety_max_items_per_call} (default 25) so the Java
     * side never sends a batch the Python route would reject with a typed 400. */
    public int getMaxItemsPerCall() {
        return maxItemsPerCall;
    }

    public void setMaxItemsPerCall(int maxItemsPerCall) {
        this.maxItemsPerCall = maxItemsPerCall <= 0 ? 25 : maxItemsPerCall;
    }
}
