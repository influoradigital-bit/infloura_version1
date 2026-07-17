package com.influora.service.admin;

import com.influora.config.AdminMfaProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * [SEC: Kabir cycle 2 audit finding, ship-blocking per {@code
 * wiki/decisions/admin-panel-security-priority.md}] AES-256-GCM encryption for {@code
 * admin_users.encrypted_mfa_secret} (TOTP shared secret). Mirrors {@code MetaTokenStorage} /
 * {@code ConversionWebhookSecretService} exactly — same algorithm, same IV-prepended-to-ciphertext
 * wire format, same key-management convention (single app-wide key sourced from {@code
 * application.yml}'s {@code influora.admin.mfa-secret-encryption-key}, decoded + length-checked at
 * construction time). No plaintext secret is ever written to the database or logged: {@link
 * AdminAuthService#setupMfa} generates the secret, builds the {@code otpauth://} URI from the
 * plaintext in memory, then hands it to {@link #encrypt} before persisting — the plaintext is
 * returned to the caller once (same-request response body) and never recoverable from storage
 * again except via {@link #decrypt} at MFA-verification time.
 *
 * <p>{@code mfaSecretEncryptionKey} is a distinct secret from every other key (JWT/stream/R2/
 * Razorpay/internal-service-token/Meta/Shopify/WooCommerce/conversion-webhook) per {@code
 * application.yml}'s {@code influora.admin} block.
 */
@Service
public class AdminMfaSecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminMfaSecretCipher(AdminMfaProperties props) {
        this.encryptionKey = decodeKey(props.getMfaSecretEncryptionKey());
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "influora.admin.mfa-secret-encryption-key is not configured — refusing to start"
                            + " admin MFA secret encryption");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Admin MFA secret encryption key must be 256 bits (32 bytes)");
        }
        return key;
    }

    /** Encrypts a plaintext Base32 TOTP secret for storage in {@code encrypted_mfa_secret}. */
    public String encrypt(String plaintext) {
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
            throw new IllegalStateException("Admin MFA secret encryption failed", e);
        }
    }

    /** Decrypts a stored {@code encrypted_mfa_secret} value back to the plaintext Base32 TOTP secret. */
    public String decrypt(String encrypted) {
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
            throw new IllegalStateException("Admin MFA secret decryption failed", e);
        }
    }
}
