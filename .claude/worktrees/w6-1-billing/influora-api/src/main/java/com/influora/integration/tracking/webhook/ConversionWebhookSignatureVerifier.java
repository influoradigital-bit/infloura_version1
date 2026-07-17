package com.influora.integration.tracking.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] HMAC-SHA256 verification for
 * {@code ConversionWebhookController}'s {@code /webhooks/conversion} and {@code
 * /webhooks/redemption} endpoints. Mirrors {@code ShopifyWebhookSignatureVerifier}/{@code
 * WooCommerceWebhookSignatureVerifier}/{@code
 * com.influora.integration.razorpay.WebhookSignatureVerifier} discipline EXACTLY: verify the RAW
 * body BEFORE any parsing/dispatch, fail closed if unconfigured/blank, constant-time comparison to
 * avoid a timing side-channel on the signature check.
 *
 * <p>Encoding choice: base64, matching Shopify/WooCommerce's {@code X-*-Hmac}/{@code
 * X-*-Signature} header shape (not Razorpay's lowercase-hex) -- this is an arbitrary but
 * consistent choice for a header this codebase itself defines ({@code X-Influora-Signature}),
 * since there is no third-party contract to match here, unlike the Shopify/WooCommerce verifiers
 * which must match an external party's exact byte-for-byte header format.
 *
 * <p>Unlike {@code ShopifyWebhookSignatureVerifier} (one app-level secret) or {@code
 * WooCommerceWebhookSignatureVerifier} (secret always required, no app-level fallback), this
 * verifier's secret is ALWAYS a caller-resolved, per-workspace decrypted secret ({@code
 * ConversionWebhookSecretService#decryptSecret}) -- there is no app-level default because there is
 * no single third party signing every request. Fails closed (returns {@code false}) if either the
 * signature header or the resolved secret is null/blank.
 */
@Component
public class ConversionWebhookSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Verifies {@code rawPayload} against {@code signatureHeader} using the caller-resolved,
     * decrypted per-workspace {@code secret}. Fails closed if either is null/blank -- there is no
     * "unconfigured app-level secret" fallback to worry about, since every call site is required to
     * resolve a real per-workspace secret first (see {@code ConversionWebhookController}).
     */
    public boolean verify(String rawPayload, String signatureHeader, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String computed = computeHmacBase64(rawPayload, secret);
        return constantTimeEquals(computed, signatureHeader);
    }

    private static String computeHmacBase64(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute conversion webhook HMAC", e);
        }
    }

    /** Constant-time comparison to avoid timing side-channels on signature checks -- same as every other webhook signature verifier in this codebase. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
