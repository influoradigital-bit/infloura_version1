package com.influora.integration.woocommerce.webhook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [SEC: Kabir load-bearing] Unit tests for {@link WooCommerceWebhookSignatureVerifier} — the task
 * brief's required "valid signature accepted, invalid/missing/tampered rejected" webhook signature
 * test coverage. Computes the EXACT same HMAC-SHA256-then-base64 algorithm WooCommerce's real
 * webhook signer uses (confirmed against WooCommerce's own REST API webhook documentation before
 * writing the verifier -- see that class's javadoc), verified independently in this test via the
 * raw {@code javax.crypto.Mac} APIs, not by calling back into the verifier's own private helper, so
 * a passing test actually proves interoperability with WooCommerce's real header shape, not just
 * internal self-consistency. Mirrors {@code ShopifyWebhookSignatureVerifierTest}'s structure.
 *
 * <p>Unlike {@code ShopifyWebhookSignatureVerifierTest}, there is no {@code ShopifyProperties}-style
 * app-level-secret fixture here -- {@link WooCommerceWebhookSignatureVerifier#verify} takes the
 * secret directly as a caller-resolved parameter (see that class's javadoc for why: WooCommerce has
 * no app-level secret, only per-integration ones), so this test constructs the verifier with no
 * dependencies at all.
 */
class WooCommerceWebhookSignatureVerifierTest {

    private static final String SECRET = "test-woocommerce-webhook-secret";
    private static final String PAYLOAD = "{\"id\":123456789,\"total\":\"49.99\"}";

    private WooCommerceWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WooCommerceWebhookSignatureVerifier();
    }

    /** Independently computes WooCommerce's real signature shape: base64(HMAC-SHA256(payload, secret)). */
    private static String independentlyComputeWooCommerceSignature(String payload, String secret) {
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
        String validSignature = independentlyComputeWooCommerceSignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a signature computed with the wrong secret")
    void verify_rejectsWrongSecret() {
        String wrongSecretSignature = independentlyComputeWooCommerceSignature(PAYLOAD, "wrong-secret");
        assertFalse(verifier.verify(PAYLOAD, wrongSecretSignature, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a valid-looking signature computed over a tampered payload")
    void verify_rejectsTamperedPayload() {
        String validSignatureForOriginal = independentlyComputeWooCommerceSignature(PAYLOAD, SECRET);
        String tamperedPayload = "{\"id\":123456789,\"total\":\"999999.99\"}";
        assertFalse(verifier.verify(tamperedPayload, validSignatureForOriginal, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a missing (null) signature header")
    void verify_rejectsNullSignature() {
        assertFalse(verifier.verify(PAYLOAD, null, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a blank signature header")
    void verify_rejectsBlankSignature() {
        assertFalse(verifier.verify(PAYLOAD, "   ", SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS garbage/malformed (non-base64-shaped) signature values")
    void verify_rejectsGarbageSignature() {
        assertFalse(verifier.verify(PAYLOAD, "not-a-real-signature", SECRET));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when secret is null")
    void verify_failsClosedWhenSecretNull() {
        String someSignature = independentlyComputeWooCommerceSignature(PAYLOAD, "irrelevant");
        assertFalse(verifier.verify(PAYLOAD, someSignature, null));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when secret is blank")
    void verify_failsClosedWhenSecretBlank() {
        String someSignature = independentlyComputeWooCommerceSignature(PAYLOAD, "irrelevant");
        assertFalse(verifier.verify(PAYLOAD, someSignature, "   "));
    }

    @Test
    @DisplayName("verify: two different integrations' secrets never cross-validate each other's signatures")
    void verify_secretsDoNotCrossValidate() {
        String secretA = "site-a-secret";
        String secretB = "site-b-secret";
        String signatureForA = independentlyComputeWooCommerceSignature(PAYLOAD, secretA);

        assertTrue(verifier.verify(PAYLOAD, signatureForA, secretA));
        assertFalse(verifier.verify(PAYLOAD, signatureForA, secretB));
    }

    @Test
    @DisplayName("verify: replaying the SAME valid signature twice both times returns true (verification itself is not one-shot; dedup is the webhook controller's/IdempotencyService's job)")
    void verify_sameValidSignatureVerifiesRepeatedly() {
        String validSignature = independentlyComputeWooCommerceSignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
    }
}
