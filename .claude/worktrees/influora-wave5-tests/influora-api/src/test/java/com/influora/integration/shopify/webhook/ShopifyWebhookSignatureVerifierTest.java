package com.influora.integration.shopify.webhook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.config.ShopifyProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [SEC: Kabir load-bearing] Unit tests for {@link ShopifyWebhookSignatureVerifier} — the task
 * brief's required "valid signature accepted, invalid/missing rejected" webhook signature test,
 * mirroring {@code RazorpayWebhookController}'s verification discipline. Computes the EXACT same
 * HMAC-SHA256-then-base64 algorithm Shopify's real webhook signer uses (verified independently in
 * this test via the raw {@code javax.crypto.Mac} APIs, not by calling back into the verifier's own
 * private helper) so a passing test actually proves interoperability with Shopify's real header
 * shape, not just internal self-consistency.
 */
class ShopifyWebhookSignatureVerifierTest {

    private static final String SECRET = "test-shopify-webhook-secret";
    private static final String PAYLOAD = "{\"id\":123456789,\"total_price\":\"49.99\"}";

    private ShopifyWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        ShopifyProperties props = new ShopifyProperties();
        props.setWebhookSigningSecret(SECRET);
        verifier = new ShopifyWebhookSignatureVerifier(props);
    }

    /** Independently computes Shopify's real signature shape: base64(HMAC-SHA256(payload, secret)). */
    private static String independentlyComputeShopifySignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("verify: ACCEPTS a signature computed with the correct secret over the exact raw payload")
    void verify_acceptsValidSignature() {
        String validSignature = independentlyComputeShopifySignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, null));
    }

    @Test
    @DisplayName("verify: REJECTS a signature computed with the wrong secret")
    void verify_rejectsWrongSecret() {
        String wrongSecretSignature = independentlyComputeShopifySignature(PAYLOAD, "wrong-secret");
        assertFalse(verifier.verify(PAYLOAD, wrongSecretSignature, null));
    }

    @Test
    @DisplayName("verify: REJECTS a valid-looking signature computed over a tampered payload")
    void verify_rejectsTamperedPayload() {
        String validSignatureForOriginal = independentlyComputeShopifySignature(PAYLOAD, SECRET);
        String tamperedPayload = "{\"id\":123456789,\"total_price\":\"999999.99\"}";
        assertFalse(verifier.verify(tamperedPayload, validSignatureForOriginal, null));
    }

    @Test
    @DisplayName("verify: REJECTS a missing (null) signature header")
    void verify_rejectsNullSignature() {
        assertFalse(verifier.verify(PAYLOAD, null, null));
    }

    @Test
    @DisplayName("verify: REJECTS a blank signature header")
    void verify_rejectsBlankSignature() {
        assertFalse(verifier.verify(PAYLOAD, "   ", null));
    }

    @Test
    @DisplayName("verify: REJECTS garbage/malformed (non-base64-shaped) signature values")
    void verify_rejectsGarbageSignature() {
        assertFalse(verifier.verify(PAYLOAD, "not-a-real-signature", null));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when no webhook secret is configured (blank)")
    void verify_failsClosedWhenSecretBlank() {
        ShopifyProperties noSecretProps = new ShopifyProperties();
        noSecretProps.setWebhookSigningSecret("");
        ShopifyWebhookSignatureVerifier noSecretVerifier = new ShopifyWebhookSignatureVerifier(noSecretProps);

        // Even a signature that WOULD be valid against some secret must be rejected when no real
        // secret is configured -- fail closed, never silently accept because there's nothing to
        // check against.
        String someSignature = independentlyComputeShopifySignature(PAYLOAD, "irrelevant");
        assertFalse(noSecretVerifier.verify(PAYLOAD, someSignature, null));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when the configured secret is still the dev placeholder")
    void verify_failsClosedForPlaceholderSecret() {
        ShopifyProperties placeholderProps = new ShopifyProperties();
        placeholderProps.setWebhookSigningSecret("REPLACE_WITH_YOUR_SHOPIFY_WEBHOOK_SECRET");
        ShopifyWebhookSignatureVerifier placeholderVerifier = new ShopifyWebhookSignatureVerifier(placeholderProps);

        String signatureForPlaceholder =
                independentlyComputeShopifySignature(PAYLOAD, "REPLACE_WITH_YOUR_SHOPIFY_WEBHOOK_SECRET");
        assertFalse(placeholderVerifier.verify(PAYLOAD, signatureForPlaceholder, null));
    }

    @Test
    @DisplayName("verify: honors a per-shop secretOverride when supplied, ignoring the app-level secret")
    void verify_honorsSecretOverride() {
        String perShopSecret = "per-shop-secret-distinct-from-app-level";
        String signatureForPerShopSecret = independentlyComputeShopifySignature(PAYLOAD, perShopSecret);

        // Would fail against the app-level SECRET, but should pass when overridden.
        assertFalse(verifier.verify(PAYLOAD, signatureForPerShopSecret, null));
        assertTrue(verifier.verify(PAYLOAD, signatureForPerShopSecret, perShopSecret));
    }

    @Test
    @DisplayName("verify: replaying the SAME valid signature twice both times returns true (verification itself is not one-shot; dedup is the webhook controller's/IdempotencyService's job)")
    void verify_sameValidSignatureVerifiesRepeatedly() {
        String validSignature = independentlyComputeShopifySignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, null));
        assertTrue(verifier.verify(PAYLOAD, validSignature, null));
    }
}
