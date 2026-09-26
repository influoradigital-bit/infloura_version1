package com.influora.integration.razorpay;

import com.influora.config.RazorpayProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.5, K-11) — verifies the {@code razorpay_signature} the
 * BROWSER's Razorpay Checkout handler returns on success, for {@code POST
 * /creator/credits/orders/{id}/verify}. Razorpay's documented formula for this signature is
 * {@code HMAC_SHA256(order_id + "|" + payment_id, key_secret)} — the KEY secret ({@link
 * RazorpayProperties#getKeySecret()}), NOT the webhook secret {@link WebhookSignatureVerifier}
 * uses. A verified signature alone never credits anything — {@code
 * CreatorCreditOrderService#confirmPaid} only ever fires after this AND a server-side gateway
 * fetch both say paid (K-11: never grant from client-supplied data alone).
 */
@Component
public class CheckoutSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final RazorpayProperties props;

    public CheckoutSignatureVerifier(RazorpayProperties props) {
        this.props = props;
    }

    /** Fails closed (false) on a blank/unconfigured key secret — never accepts an unverifiable signature. */
    public boolean verify(String razorpayOrderId, String razorpayPaymentId, String signature) {
        if (signature == null
                || signature.isBlank()
                || razorpayOrderId == null
                || razorpayOrderId.isBlank()
                || razorpayPaymentId == null
                || razorpayPaymentId.isBlank()) {
            return false;
        }
        String secret = props.getKeySecret();
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String payload = razorpayOrderId + "|" + razorpayPaymentId;
        String computed = computeHmac(payload, secret);
        return constantTimeEquals(computed, signature);
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
            throw new RazorpayIntegrationException("Failed to compute checkout signature HMAC", e);
        }
    }

    /** Constant-time comparison — avoids a timing side-channel on the signature check (K-11). */
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
