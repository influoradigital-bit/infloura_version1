package com.influora.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ShopifyProperties.class);

    private String apiKey = "";
    private String apiSecret = "";
    private String redirectUri = "";
    private String webhookSigningSecret = "";
    private String tokenEncryptionKey = "";
    private String apiVersion = "2025-01";
    private String scopes = "read_orders,read_products";

    /**
     * Whether this deployment can actually RUN the Shopify integration end to end.
     *
     * <p>All three credentials are required, not just the OAuth pair. With a blank (or
     * placeholder) webhook signing secret, {@code ShopifyWebhookSignatureVerifier#verify} fails
     * closed on every delivery, so a store would connect successfully and then silently receive no
     * order or redemption events at all — the integration exists only to receive those. Reporting
     * "configured" on the OAuth pair alone is what made that half-working state reachable, so the
     * connect button (gated on this via {@code GET /integrations/store/status}) is offered only
     * when the whole path works.
     */
    public boolean isConfigured() {
        return isPresent(apiKey) && isPresent(apiSecret) && isPresent(webhookSigningSecret);
    }

    /**
     * Some credentials set but not all — a half-configured deploy. The feature stays OFF (see
     * {@link #isConfigured()}); this exists so that is visible in the boot log instead of looking
     * like the operator's values were ignored.
     */
    public boolean isPartiallyConfigured() {
        boolean any = isPresent(apiKey) || isPresent(apiSecret) || isPresent(webhookSigningSecret);
        return any && !isConfigured();
    }

    /**
     * Blank, unset, or still the placeholder the deploy templates ship. The {@code REPLACE_WITH_}
     * prefix is the same sentinel {@code ShopifyWebhookSignatureVerifier} refuses to sign with, so
     * a placeholder must not count as configured here either.
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank() && !value.startsWith("REPLACE_WITH_");
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

    /**
     * A deploy that set SOME Shopify credentials but not all of them keeps the feature off, which
     * from the outside looks exactly like the values having been ignored. Say so once at boot.
     */
    @PostConstruct
    void warnIfPartiallyConfigured() {
        if (isPartiallyConfigured()) {
            log.warn(
                    "Shopify integration is DISABLED: set all three of INFLUORA_SHOPIFY_APIKEY,"
                            + " INFLUORA_SHOPIFY_APISECRET and INFLUORA_SHOPIFY_WEBHOOKSIGNINGSECRET."
                            + " Without the webhook signing secret every Shopify delivery fails"
                            + " signature verification, so a connected store would receive no order"
                            + " events at all.");
        }
    }
}
