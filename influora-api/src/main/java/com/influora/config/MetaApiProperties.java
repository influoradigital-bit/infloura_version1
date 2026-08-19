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
    // T-IGLOGIN-0820 — Business Login for Instagram. These are the INSTAGRAM app's credentials
    // from the Instagram product in the App Dashboard, NOT the Facebook app id/secret above; the
    // two are distinct and reusing one for the other fails the token exchange. Blank by default
    // for the same reason as the Facebook pair: isInstagramLoginConfigured() is the gate that
    // decides whether the no-Facebook-Page path is offered at all.
    private String instagramAppId = "";
    private String instagramAppSecret = "";
    private String instagramRedirectUri = "";
    private String graphApiVersion = "v25.0";
    private String tokenEncryptionKey = "";
    private int tokenRefreshDaysBeforeExpiry = 7;
    private int rateLimitAlertThreshold = 80;
    private int rateLimitThrottleThreshold = 90;

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /**
     * Whether the Instagram-Login path (no Facebook Page required) can be offered. Deliberately
     * independent of {@link #isConfigured()} — a deploy may have one path configured and not the
     * other, and the connect UI must show only what will actually work rather than dead-ending a
     * creator on an unconfigured branch.
     */
    public boolean isInstagramLoginConfigured() {
        return instagramAppId != null
                && !instagramAppId.isBlank()
                && instagramAppSecret != null
                && !instagramAppSecret.isBlank();
    }

    public String getInstagramAppId() {
        return instagramAppId;
    }

    public void setInstagramAppId(String instagramAppId) {
        this.instagramAppId = instagramAppId;
    }

    public String getInstagramAppSecret() {
        return instagramAppSecret;
    }

    public void setInstagramAppSecret(String instagramAppSecret) {
        this.instagramAppSecret = instagramAppSecret;
    }

    public String getInstagramRedirectUri() {
        return instagramRedirectUri;
    }

    public void setInstagramRedirectUri(String instagramRedirectUri) {
        this.instagramRedirectUri = instagramRedirectUri;
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
