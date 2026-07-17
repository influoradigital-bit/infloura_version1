package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shopify OAuth ("custom app" / public-app install flow) + webhook credentials, Wave D task D1
 * (wiki/tech/REMAINING_WORK_PLAN.md). This is the FREE OAuth path every Shopify app (free or paid)
 * uses to obtain a per-store access token -- no $99/mo Shopify Plus or paid-app-listing fee is
 * required for a brand to connect their own store via this flow, same "install and authorize"
 * handshake Shopify's own free apps use.
 *
 * <p>No real Shopify Partner app exists yet -- placeholders only, matching how Meta/Razorpay/MSG91
 * keys are stubbed for local dev (see {@code MetaApiProperties} javadoc).
 *
 * <p>[SEC: Kabir sign-off gate] {@code tokenEncryptionKey} is a DISTINCT secret from every other key
 * (JWT/stream/R2/Razorpay/internal-service-token/Meta) -- never shared. Must decode to exactly 32
 * bytes (AES-256); enforced in {@code ShopifyTokenStorage}, not here. {@code webhookSigningSecret}
 * is Shopify's per-app "Client secret" (also used to HMAC-sign webhook deliveries) -- distinct
 * concern from the encryption key even though Shopify happens to reuse the app secret for both
 * OAuth code exchange and webhook signing.
 */
@ConfigurationProperties(prefix = "influora.shopify")
public class ShopifyProperties {

    private String apiKey = "";
    private String apiSecret = "";
    private String redirectUri = "";
    private String webhookSigningSecret = "";
    private String tokenEncryptionKey = "";
    private String apiVersion = "2025-01";
    private String scopes = "read_orders,read_products";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getWebhookSigningSecret() {
        return webhookSigningSecret;
    }

    public void setWebhookSigningSecret(String webhookSigningSecret) {
        this.webhookSigningSecret = webhookSigningSecret;
    }

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey;
    }

    public String getApiVersion() {
        return (apiVersion == null || apiVersion.isBlank()) ? "2025-01" : apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getScopes() {
        return (scopes == null || scopes.isBlank()) ? "read_orders,read_products" : scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }
}
