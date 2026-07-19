package com.influora.service.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the one-click unsubscribe link embedded in outbound emails
 * ({@code EmailTemplateRegistry}'s footer). A recipient clicking the link from their inbox has no
 * session/JWT to authenticate with — same trust problem {@link
 * com.influora.security.InternalRequestVerifier} solves for service-to-service calls, HMAC-SHA256
 * over a canonical payload, hex digest, constant-time comparison, same shape here.
 *
 * <p>Token format: base64url({@code userId:eventType:hex(hmac)}). {@code userId} (a ULID) and
 * {@code eventType} (dotted/underscored keys like {@code "creator.campaign_match"}) never contain
 * a colon, so splitting on the last colon for the signature and the first colon for the payload
 * is unambiguous.
 */
@Component
public class UnsubscribeTokenService {

    private final String secret;

    public UnsubscribeTokenService(
            @Value("${influora.notification.unsubscribe-signing-secret}") String secret) {
        this.secret = secret;
    }

    public String generateToken(String userId, String eventType) {
        String payload = userId + ":" + eventType;
        String raw = payload + ":" + hmacHex(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Parsed> verify(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sigSep = raw.lastIndexOf(':');
            String payload = raw.substring(0, sigSep);
            String signature = raw.substring(sigSep + 1);
            if (!constantTimeEquals(hmacHex(payload), signature)) {
                return Optional.empty();
            }
            int payloadSep = payload.indexOf(':');
            return Optional.of(new Parsed(payload.substring(0, payloadSep), payload.substring(payloadSep + 1)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record Parsed(String userId, String eventType) {}
}
