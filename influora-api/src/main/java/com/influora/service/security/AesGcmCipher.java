package com.influora.service.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared AES-256-GCM helper (IV prepended to ciphertext, Base64 wire format) — same shape as {@code
 * AdminMfaSecretCipher} / Meta token storage.
 */
public final class AesGcmCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    public static final int KEY_LENGTH_BYTES = 32;

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String failureLabel;

    public AesGcmCipher(byte[] encryptionKey, String failureLabel) {
        if (encryptionKey == null || encryptionKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(failureLabel + " must be 256 bits (32 bytes)");
        }
        this.encryptionKey = encryptionKey;
        this.failureLabel = failureLabel;
    }

    public static byte[] decodeKey(String base64Key, String configName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    configName + " is not configured — refusing to start PII encryption");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(configName + " must be 256 bits (32 bytes)");
        }
        return key;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException(failureLabel + " encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES);
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), spec);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(failureLabel + " decryption failed", e);
        }
    }
}
