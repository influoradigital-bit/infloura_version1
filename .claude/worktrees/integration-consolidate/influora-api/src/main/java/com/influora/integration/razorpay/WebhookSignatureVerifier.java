package com.influora.integration.razorpay;

import com.influora.config.RazorpayProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * [SEC] HMAC-SHA256 verification of Razorpay webhook signatures, per Razorpay's
 * {@code X-Razorpay-Signature} header contract. A webhook is trusted only after this passes —
 * escrow only transitions PENDING -> FUNDED on a verified webhook, never on the client's
 * order-creation response alone (API contract §1.4).
 */
@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final RazorpayProperties props;

    public WebhookSignatureVerifier(RazorpayProperties props) {
        this.props = props;
    }

    public boolean verify(String rawPayload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // No secret configured (local/dev without Razorpay) — fail closed, never accept.
            return false;
        }
        String computed = computeHmac(rawPayload, secret);
        return constantTimeEquals(computed, signatureHeader);
    }

    private static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RazorpayIntegrationException("Failed to compute webhook HMAC", e);
        }
    }

    /** Constant-time comparison to avoid timing side-channels on signature checks. */
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
