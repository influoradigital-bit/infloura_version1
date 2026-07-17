package com.influora.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Minimal RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s step) implemented in plain Java.
 *
 * <p><b>Blocker note (Vikram, cycle 1):</b> no TOTP/QR library (e.g. {@code samstevens.totp},
 * {@code zxing}) is on the classpath — pom.xml has none, and adding one needs Priya's sign-off
 * (TASK_ASSIGNMENTS.md: "Cannot install new npm packages without Priya approval" — same rule
 * applies to a new Maven dependency). This class covers secret generation + code verification
 * without a dependency. It deliberately does NOT render a QR *image*: {@link
 * #buildOtpAuthUri(String, String)} returns the {@code otpauth://} URI only; rendering that into
 * a scannable PNG needs a QR-encoding library. Until one is approved, the admin SPA must either
 * render the QR client-side (e.g. a JS QR lib) from this URI, or MFA setup shows the URI/secret
 * as text for manual entry.
 */
@Service
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20; // 160-bit secret
    private static final int STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    /** Accept the previous/current/next 30s step to tolerate clock drift. */
    private static final int WINDOW_STEPS = 1;

    private final SecureRandom random = new SecureRandom();

    /** Generates a new random Base32-encoded shared secret. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** {@code otpauth://totp/...} URI an authenticator app (or a client-side QR renderer) can consume. */
    public String buildOtpAuthUri(String accountEmail, String base32Secret) {
        String issuer = "Influora Admin";
        return String.format(
                Locale.ROOT,
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d",
                urlEncode(issuer),
                urlEncode(accountEmail),
                base32Secret,
                urlEncode(issuer),
                CODE_DIGITS,
                STEP_SECONDS);
    }

    /** Verifies a user-entered 6-digit code against the secret, within the drift window. */
    public boolean verifyCode(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long currentStep = Instant.now().getEpochSecond() / STEP_SECONDS;
        byte[] keyBytes = base32Decode(base32Secret);
        for (long step = currentStep - WINDOW_STEPS; step <= currentStep + WINDOW_STEPS; step++) {
            if (generateCode(keyBytes, step).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(byte[] key, long step) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary =
                    ((hash[offset] & 0x7f) << 24)
                            | ((hash[offset + 1] & 0xff) << 16)
                            | ((hash[offset + 2] & 0xff) << 8)
                            | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().toUpperCase(Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (char c : clean.toCharArray()) {
            int idx = BASE32_ALPHABET.indexOf(c);
            if (idx < 0) {
                continue;
            }
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
