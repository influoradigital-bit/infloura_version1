package com.influora.integration.woocommerce.webhook;

import com.influora.integration.woocommerce.exception.WooCommerceApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * [SEC: Kabir load-bearing] HMAC-SHA256 verification of WooCommerce webhook signatures, per
 * WooCommerce's {@code X-WC-Webhook-Signature} header contract -- confirmed against WooCommerce's
 * own REST API webhook documentation before writing this (not assumed by analogy to Shopify): "a
 * base64 encoded HMAC-SHA256 hash of the payload," computed over the raw JSON request body using
 * the webhook's own per-site "Secret" as the HMAC key. Byte-for-byte the SAME shape as Shopify's
 * {@code X-Shopify-Hmac-Sha256} header (base64-encoded HMAC-SHA256 digest), so this class mirrors
 * {@code ShopifyWebhookSignatureVerifier} almost exactly.
 *
 * <p><b>The one structural difference from Shopify's verifier</b> is where the secret comes from:
 * Shopify signs every shop's webhooks with ONE app-level secret ({@code
 * ShopifyProperties#getWebhookSigningSecret}), so that verifier reads it from a Spring {@code
 * @ConfigurationProperties} bean. WooCommerce has no OAuth app-install flow and no app-level
 * secret at all -- each brand generates their OWN webhook secret in their WooCommerce admin and
 * submits it to us via {@code WooCommerceConnectController}, stored per-integration ({@code
 * WooCommerceIntegration#getEncryptedWebhookSecret}, decrypted by {@code
 * WooCommerceIntegrationService} before being passed in here). This verifier is therefore stateless
 * with respect to configuration -- {@code secret} is a required, caller-supplied parameter, never
 * an internal default -- so {@code WooCommerceWebhookController} MUST resolve the claimed site's
 * integration (and therefore its secret) BEFORE calling {@link #verify}, unlike Shopify's
 * controller which verifies against the app-level secret first and resolves the shop afterward.
 * This does not weaken the "verify before parse" discipline: resolving which integration's secret
 * to check is a header/DB lookup, not payload parsing -- the raw JSON body is still never touched
 * by a JSON parser until AFTER a successful {@link #verify} call. See {@code
 * WooCommerceWebhookController} class javadoc for the full ordering rationale.
 *
 * <p>Same discipline as {@code ShopifyWebhookSignatureVerifier}/{@code
 * com.influora.integration.razorpay.WebhookSignatureVerifier} otherwise: fail closed on a
 * null/blank signature or secret, constant-time comparison to avoid a timing side-channel on the
 * signature check.
 */
@Component
public class WooCommerceWebhookSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Verifies {@code rawPayload} against WooCommerce's {@code X-WC-Webhook-Signature} header
     * value, using {@code secret} -- the CALLER-RESOLVED, decrypted per-integration webhook secret
     * (see class javadoc for why this verifier never reads a secret from its own configuration).
     * Fails closed (returns {@code false}) if {@code signatureHeader} or {@code secret} is
     * null/blank -- there is no "unconfigured app-level secret" fallback to worry about here since
     * every call site is required to resolve a real per-integration secret first.
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
            throw new WooCommerceApiException("Failed to compute webhook HMAC", e);
        }
    }

    /** Constant-time comparison to avoid timing side-channels on signature checks -- same as {@code ShopifyWebhookSignatureVerifier}. */
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
