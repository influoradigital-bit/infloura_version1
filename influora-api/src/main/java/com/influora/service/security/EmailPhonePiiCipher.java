package com.influora.service.security;

import com.influora.config.PiiEncryptionProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Spec 12 §3.1 email/phone AES-256-GCM + blind-index hash (Kabir M-K6-C3-1).
 *
 * <p>Scaffold: cipher is live and fail-closed on missing key. Column migration + dual-write of
 * {@code users.email}/{@code phone_number} is tracked in {@code
 * wiki/decisions/2026-07-10-pii-email-phone-encryption.md} — do not persist plaintext via a new
 * path that bypasses this cipher once columns flip.
 */
@Service
public class EmailPhonePiiCipher {

    private final AesGcmCipher cipher;
    private final byte[] blindIndexKey;

    public EmailPhonePiiCipher(PiiEncryptionProperties props) {
        byte[] key =
                AesGcmCipher.decodeKey(
                        props.getEmailPhoneEncryptionKey(),
                        "influora.pii.email-phone-encryption-key");
        this.cipher = new AesGcmCipher(key, "Email/phone PII");
        // Blind-index HMAC material: same key bytes (documented rotation plan in ADR).
        this.blindIndexKey = key;
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(normalize(plaintext));
    }

    public String decrypt(String encrypted) {
        return cipher.decrypt(encrypted);
    }

    /**
     * Deterministic lookup token for login / uniqueness (SHA-256 of normalized value keyed with
     * encryption key material). Not reversible; safe to index.
     */
    public String blindIndex(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(blindIndexKey);
            digest.update(normalize(plaintext).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Email/phone blind-index failed", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
