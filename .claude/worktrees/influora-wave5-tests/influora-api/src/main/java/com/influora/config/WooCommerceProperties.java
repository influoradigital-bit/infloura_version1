package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WooCommerce webhook-integration credentials, Wave D task D2 (wiki/tech/REMAINING_WORK_PLAN.md).
 *
 * <p>Unlike {@code ShopifyProperties}, there is deliberately NO {@code apiKey}/{@code apiSecret}/
 * {@code webhookSigningSecret} here: WooCommerce has no OAuth app-install flow and no single
 * app-level webhook-signing secret shared across every connected store. Each brand generates their
 * OWN webhook secret directly in their WooCommerce admin (Settings -> Advanced -> Webhooks) and
 * submits it to us via {@code WooCommerceConnectController}; that PER-INTEGRATION secret is what
 * {@code WooCommerceWebhookSignatureVerifier} checks each delivery against (see {@code
 * WooCommerceIntegration#getEncryptedWebhookSecret}), never a value read from this properties
 * class.
 *
 * <p>[SEC: Kabir sign-off gate] {@code tokenEncryptionKey} is a DISTINCT secret from every other
 * key (JWT/stream/R2/Razorpay/internal-service-token/Meta/Shopify) -- never shared. Must decode to
 * exactly 32 bytes (AES-256); enforced in {@code WooCommerceIntegrationService}, not here. Same
 * dev-placeholder convention as {@code ShopifyProperties}/{@code MetaApiProperties}.
 */
@ConfigurationProperties(prefix = "influora.woocommerce")
public class WooCommerceProperties {

    private String tokenEncryptionKey = "";

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey;
    }
}
