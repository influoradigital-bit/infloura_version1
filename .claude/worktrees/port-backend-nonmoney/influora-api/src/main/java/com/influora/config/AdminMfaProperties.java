package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Encryption-key configuration for {@code AdminMfaSecretCipher} ({@code admin_users
 * .encrypted_mfa_secret}, V35__encrypt_mfa_secret.sql) — Swapnil CEO ship-blocking security fix,
 * {@code wiki/decisions/admin-panel-security-priority.md}: Kabir's cycle 2 audit found admin TOTP
 * MFA secrets stored in PLAINTEXT.
 *
 * <p>Same shape as {@code ConversionWebhookProperties} / {@code WooCommerceProperties}: a single
 * app-wide AES-256-GCM key, not a per-row secret.
 *
 * <p>[SEC: Kabir sign-off gate] {@code mfaSecretEncryptionKey} is a DISTINCT secret from every
 * other key (JWT/stream/R2/Razorpay/internal-service-token/Meta/Shopify/WooCommerce/
 * conversion-webhook) — never shared. Must decode to exactly 32 bytes (AES-256); enforced in
 * {@code AdminMfaSecretCipher}, not here.
 */
@ConfigurationProperties(prefix = "influora.admin")
public class AdminMfaProperties {

    private String mfaSecretEncryptionKey = "";

    public String getMfaSecretEncryptionKey() {
        return mfaSecretEncryptionKey;
    }

    public void setMfaSecretEncryptionKey(String mfaSecretEncryptionKey) {
        this.mfaSecretEncryptionKey = mfaSecretEncryptionKey;
    }
}
