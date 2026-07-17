package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta Graph API (Instagram/Facebook) OAuth + insights credentials (Week 1-2 backend spec,
 * VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §1). No real Meta app exists yet — placeholders only,
 * matching how MSG91/Razorpay keys are stubbed for local dev.
 *
 * <p>[SEC: Kabir sign-off gate] {@code tokenEncryptionKey} is a DISTINCT secret from every other
 * key (JWT/stream/R2/Razorpay/internal-service-token) — never shared. Must decode to exactly 32
 * bytes (AES-256); enforced in {@code MetaTokenStorage}, not here.
 */
@ConfigurationProperties(prefix = "influora.meta")
public class MetaApiProperties {

    private String appId = "";
    private String appSecret = "";
    private String redirectUri = "";
    private String graphApiVersion = "v25.0";
    private String tokenEncryptionKey = "";
    private int tokenRefreshDaysBeforeExpiry = 7;
    private int rateLimitAlertThreshold = 80;
    private int rateLimitThrottleThreshold = 90;

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getGraphApiVersion() {
        return graphApiVersion;
    }

    public void setGraphApiVersion(String graphApiVersion) {
        this.graphApiVersion = (graphApiVersion == null || graphApiVersion.isBlank()) ? "v25.0" : graphApiVersion;
    }

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey;
    }

    public int getTokenRefreshDaysBeforeExpiry() {
        return tokenRefreshDaysBeforeExpiry;
    }

    public void setTokenRefreshDaysBeforeExpiry(int tokenRefreshDaysBeforeExpiry) {
        this.tokenRefreshDaysBeforeExpiry =
                tokenRefreshDaysBeforeExpiry <= 0 ? 7 : tokenRefreshDaysBeforeExpiry;
    }

    public int getRateLimitAlertThreshold() {
        return rateLimitAlertThreshold;
    }

    public void setRateLimitAlertThreshold(int rateLimitAlertThreshold) {
        this.rateLimitAlertThreshold = rateLimitAlertThreshold <= 0 ? 80 : rateLimitAlertThreshold;
    }

    public int getRateLimitThrottleThreshold() {
        return rateLimitThrottleThreshold;
    }

    public void setRateLimitThrottleThreshold(int rateLimitThrottleThreshold) {
        this.rateLimitThrottleThreshold = rateLimitThrottleThreshold <= 0 ? 90 : rateLimitThrottleThreshold;
    }
}
