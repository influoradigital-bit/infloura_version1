package com.influora.integration.shopify.webhook;

import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.exception.ShopifyApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * [SEC: Kabir load-bearing] HMAC-SHA256 verification of Shopify webhook signatures, per Shopify's
 * {@code X-Shopify-Hmac-Sha256} header contract. Mirrors {@code
 * com.influora.integration.razorpay.WebhookSignatureVerifier}'s discipline EXACTLY: verify BEFORE
 * any parsing/dispatch, fail closed if unconfigured, constant-time comparison to avoid a
 * timing side-channel on the signature check.
 *
 * <p><b>The one real difference from Razorpay's verifier</b> is encoding, not discipline: Shopify's
 * header is the BASE64 encoding of the raw HMAC-SHA256 digest bytes (not lowercase hex like
 * Razorpay's {@code X-Razorpay-Signature}). Getting this encoding wrong is the single most common
 * way to implement this check "successfully" (code compiles, tests pass with a fabricated fixture)
 * while it silently never matches Shopify's real header in production -- verified byte-for-byte
 * shape against Shopify's public webhook documentation before writing this, not assumed by analogy
 * to Razorpay's hex encoding.
 */
@Component
public class ShopifyWebhookSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final ShopifyProperties props;

    public ShopifyWebhookSignatureVerifier(ShopifyProperties props) {
        this.props = props;
    }

    /**
     * Verifies {@code rawPayload} against Shopify's {@code X-Shopify-Hmac-Sha256} header value.
     * {@code secretOverride}, if non-null/non-blank, is used instead of the app-level {@code
     * influora.shopify.webhook-signing-secret} -- supports a future per-shop {@code
     * shopify_integrations.webhook_secret} override without changing this method's signature
     * again; {@code null} means "use the app-level secret," which is the only path exercised today
     * since no per-shop secret is issued yet.
     */
    public boolean verify(String rawPayload, String signatureHeader, String secretOverride) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String secret =
                (secretOverride == null || secretOverride.isBlank())
                        ? props.getWebhookSigningSecret()
                        : secretOverride;
        if (secret == null || secret.isBlank() || secret.startsWith("REPLACE_WITH_")) {
            // No secret configured (local/dev without a real Shopify app) — fail closed, never accept.
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
            throw new ShopifyApiException("Failed to compute webhook HMAC", e);
        }
    }

    /** Constant-time comparison to avoid timing side-channels on signature checks — same as {@code WebhookSignatureVerifier}. */
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
