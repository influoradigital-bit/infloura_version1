package com.influora.integration.tracking.webhook;

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
 * [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] Unit tests for {@link
 * ConversionWebhookSignatureVerifier} -- the task brief's required "valid signature accepted,
 * invalid/missing/tampered rejected" webhook signature test coverage. Mirrors {@code
 * WooCommerceWebhookSignatureVerifierTest}'s structure exactly (same caller-resolved-secret shape,
 * no app-level-secret fixture needed).
 */
class ConversionWebhookSignatureVerifierTest {

    private static final String SECRET = "test-conversion-webhook-secret";
    private static final String PAYLOAD = "{\"utmCampaignId\":\"01HUTM1234567890ABCDE\",\"orderAmount\":49.99}";

    private ConversionWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ConversionWebhookSignatureVerifier();
    }

    /** Independently computes this codebase's chosen signature shape: base64(HMAC-SHA256(payload, secret)). */
    private static String independentlyComputeSignature(String payload, String secret) {
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
        String validSignature = independentlyComputeSignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a signature computed with the wrong secret")
    void verify_rejectsWrongSecret() {
        String wrongSecretSignature = independentlyComputeSignature(PAYLOAD, "wrong-secret");
        assertFalse(verifier.verify(PAYLOAD, wrongSecretSignature, SECRET));
    }

    @Test
    @DisplayName("verify: REJECTS a valid-looking signature computed over a tampered payload")
    void verify_rejectsTamperedPayload() {
        String validSignatureForOriginal = independentlyComputeSignature(PAYLOAD, SECRET);
        String tamperedPayload = "{\"utmCampaignId\":\"01HUTM1234567890ABCDE\",\"orderAmount\":999999.99}";
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
    @DisplayName("verify: REJECTS garbage/malformed signature values")
    void verify_rejectsGarbageSignature() {
        assertFalse(verifier.verify(PAYLOAD, "not-a-real-signature", SECRET));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when secret is null (e.g. workspace never generated one)")
    void verify_failsClosedWhenSecretNull() {
        String someSignature = independentlyComputeSignature(PAYLOAD, "irrelevant");
        assertFalse(verifier.verify(PAYLOAD, someSignature, null));
    }

    @Test
    @DisplayName("verify: FAILS CLOSED when secret is blank")
    void verify_failsClosedWhenSecretBlank() {
        String someSignature = independentlyComputeSignature(PAYLOAD, "irrelevant");
        assertFalse(verifier.verify(PAYLOAD, someSignature, "   "));
    }

    @Test
    @DisplayName("verify: two different workspaces' secrets never cross-validate each other's signatures")
    void verify_secretsDoNotCrossValidate() {
        String secretA = "workspace-a-secret";
        String secretB = "workspace-b-secret";
        String signatureForA = independentlyComputeSignature(PAYLOAD, secretA);

        assertTrue(verifier.verify(PAYLOAD, signatureForA, secretA));
        assertFalse(verifier.verify(PAYLOAD, signatureForA, secretB));
    }

    @Test
    @DisplayName("verify: replaying the SAME valid signature twice both times returns true (verification itself is not one-shot; dedup is IdempotencyService's job)")
    void verify_sameValidSignatureVerifiesRepeatedly() {
        String validSignature = independentlyComputeSignature(PAYLOAD, SECRET);
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
        assertTrue(verifier.verify(PAYLOAD, validSignature, SECRET));
    }
}
