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

    /**
     * F-0479 — whether {@code MetricsPollingJob} also fetches recent media + per-post insights and
     * writes {@code media_metrics}. On by default: with it off, that table has no writer at all and
     * every creator stays permanently UNSCORED (see F-0478), which is the state this flag exists to
     * end rather than preserve.
     *
     * <p>It exists because turning this on is the one change here with a real per-account cost —
     * roughly {@code 1 + mediaLimit} extra Graph calls per creator per 6-hour cycle, which is
     * exactly the rate-limit spend the original TODO cited as its reason to defer. This is the kill
     * switch for that, not a rollout gate: flip it off if Meta usage spikes, and accept that scores
     * go absent again while it is off.
     */
    private boolean mediaMetricsEnabled = true;

    /**
     * T-CREATORCONNECT-0902 — {@code influora.meta.creator-marketplace.enabled}. Gates {@code
     * CreatorMarketplaceClient}: OFF by default, same "blank/false stays off" convention as every
     * other Meta flag in this file. wiki/decisions/2026-09-02-what-we-need.md §4.2 — the
     * {@code instagram_creator_marketplace_discovery} scope needed to make real Marketplace calls
     * cannot even be App-Reviewed yet, so this ships dark; {@code ExternalCreatorService} falls
     * back to the {@code external_creators} table (Business Discovery + admin import) regardless
     * of this flag.
     */
    private CreatorMarketplace creatorMarketplace = new CreatorMarketplace();

    /**
     * Q1.3 (T-CREATORCONNECT-0902) — an Influora-OWNED IG Business system caller for Business
     * Discovery lookups, preferred ahead of borrowing an arbitrary connected creator's
     * FACEBOOK_LOGIN token. Without this, {@code ExternalCreatorService.resolveBusinessDiscoveryCaller}
     * falls back to a live creator's own token, and {@code MetaGraphApiClient} rate-limits on that
     * creator's {@code igBusinessAccountId} — the SAME key {@code MetricsPollingJob} throttles on
     * — so brand Discover volume can silently stall an unrelated creator's own metrics polling.
     * Blank by default, same "off means degrade, never fake" convention as every other Meta
     * property here. Cross-user token reuse (the creator-fallback path this exists to reduce
     * reliance on) still needs a platform-terms ruling from Swapnil before it is relied on in
     * production — this property does not resolve that, only gives the system caller priority
     * once one is provisioned.
     */
    private String systemIgUserId = "";

    private String systemIgAccessToken = "";

    public static class CreatorMarketplace {
        private Boolean enabled = Boolean.FALSE;

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * Q7.4 (T-CREATORCONNECT-0902, Critical) — {@code Boolean} (not primitive {@code boolean})
         * so a resolved-but-EMPTY env var (docker-compose passing {@code META_CREATOR_MARKETPLACE_ENABLED=}
         * with no shell-side value set, distinct from the var being absent) converts to {@code null}
         * here instead of throwing a {@code ConversionFailedException} out of Spring's relaxed
         * binder — which primitive {@code boolean} cannot survive, and which crashed API boot on
         * the live Hostinger VPS. Coalesces {@code null} to {@code false}, matching the documented
         * off-by-default convention; never lets a blank env var reach this field as anything but
         * "off".
         */
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled == null ? Boolean.FALSE : enabled;
        }
    }

    public CreatorMarketplace getCreatorMarketplace() {
        return creatorMarketplace;
    }

    public void setCreatorMarketplace(CreatorMarketplace creatorMarketplace) {
        this.creatorMarketplace = creatorMarketplace == null ? new CreatorMarketplace() : creatorMarketplace;
    }

    public String getSystemIgUserId() {
        return systemIgUserId;
    }

    public void setSystemIgUserId(String systemIgUserId) {
        this.systemIgUserId = systemIgUserId == null ? "" : systemIgUserId;
    }

    public String getSystemIgAccessToken() {
        return systemIgAccessToken;
    }

    public void setSystemIgAccessToken(String systemIgAccessToken) {
        this.systemIgAccessToken = systemIgAccessToken == null ? "" : systemIgAccessToken;
    }

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

    public boolean isMediaMetricsEnabled() {
        return mediaMetricsEnabled;
    }

    public void setMediaMetricsEnabled(boolean mediaMetricsEnabled) {
        this.mediaMetricsEnabled = mediaMetricsEnabled;
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
