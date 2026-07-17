package com.influora.security;

import com.influora.config.InternalServiceTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies the HMAC request signature carried on every {@code /internal/meera/*} call
 * ([SEC: 2.4]): {@code X-Meera-Signature} = HMAC-SHA256 over
 * {@code method.upper() + path + sha256_hex(body) + timestamp + nonce}, hex-encoded — this MUST
 * match {@code influora-ai/app/clients/spring.py sign_request()} byte-for-byte (plain
 * concatenation, no separators, lowercase hex digest, not Base64) since that is the already-built
 * counterpart computing the same signature. Uses a key distinct from every other secret in the
 * system ({@link InternalServiceTokenProperties}). Constant-time comparison prevents a timing
 * side-channel from leaking the expected signature.
 *
 * <p>Rejects: signature mismatch, {@code |now - timestamp| > clockSkewSeconds}, or a nonce
 * already seen within its TTL window ({@link NonceCache}) — the last of which stops a captured,
 * validly-signed request from being replayed verbatim.
 */
@Component
public class InternalRequestVerifier {

    private final InternalServiceTokenProperties props;
    private final NonceCache nonceCache;

    public InternalRequestVerifier(InternalServiceTokenProperties props, NonceCache nonceCache) {
        this.props = props;
        this.nonceCache = nonceCache;
    }

    public VerificationResult verify(
            String method,
            String path,
            String body,
            String timestampHeader,
            String nonce,
            String signatureHeader) {
        if (signatureHeader == null
                || signatureHeader.isBlank()
                || timestampHeader == null
                || timestampHeader.isBlank()
                || nonce == null
                || nonce.isBlank()) {
            return VerificationResult.reject("MISSING_SIGNATURE_HEADERS");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return VerificationResult.reject("MALFORMED_TIMESTAMP");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > props.getClockSkewSeconds()) {
            return VerificationResult.reject("TIMESTAMP_OUT_OF_SKEW");
        }

        String expected = computeSignature(method, path, body, timestampHeader, nonce);
        if (!constantTimeEquals(expected, signatureHeader)) {
            return VerificationResult.reject("SIGNATURE_MISMATCH");
        }

        if (!nonceCache.tryConsume(nonce)) {
            return VerificationResult.reject("NONCE_REPLAYED");
        }

        return VerificationResult.ok();
    }

    /**
     * Mirrors {@code sign_request()} in {@code app/clients/spring.py} exactly:
     * {@code message = f"{method.upper()}{path}{body_hash}{timestamp}{nonce}"} (plain
     * concatenation, no delimiters), HMAC-SHA256, hex digest (not Base64).
     */
    private String computeSignature(
            String method, String path, String body, String timestamp, String nonce) {
        String bodyHash = sha256Hex(body == null ? "" : body);
        String canonical = method.toUpperCase(java.util.Locale.ROOT) + path + bodyHash + timestamp + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            props.getHmacSigningSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    public record VerificationResult(boolean accepted, String rejectionReason) {
        static VerificationResult ok() {
            return new VerificationResult(true, null);
        }

        static VerificationResult reject(String reason) {
            return new VerificationResult(false, reason);
        }
    }
}
