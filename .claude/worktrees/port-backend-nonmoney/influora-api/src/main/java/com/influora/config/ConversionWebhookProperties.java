package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Encryption-key configuration for {@code ConversionWebhookSecretService} (V31 {@code
 * conversion_webhook_secrets}) -- Wave E4 capstone red-team fix, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1.
 *
 * <p>Unlike {@code ShopifyProperties}, there is no {@code webhook-signing-secret} here: this is a
 * per-workspace secret model (see {@code ConversionWebhookSecret} javadoc for why), so the only
 * thing this properties class needs to hold is the AES-256-GCM key used to encrypt every
 * workspace's own secret at rest -- same shape as {@code WooCommerceProperties}.
 *
 * <p>[SEC: Kabir sign-off gate] {@code tokenEncryptionKey} is a DISTINCT secret from every other
 * key (JWT/stream/R2/Razorpay/internal-service-token/Meta/Shopify/WooCommerce) -- never shared.
 * Must decode to exactly 32 bytes (AES-256); enforced in {@code ConversionWebhookSecretService},
 * not here. Same dev-placeholder convention as {@code WooCommerceProperties}.
 */
@ConfigurationProperties(prefix = "influora.conversion-webhook")
public class ConversionWebhookProperties {

    private String tokenEncryptionKey = "";

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey;
    }
}
