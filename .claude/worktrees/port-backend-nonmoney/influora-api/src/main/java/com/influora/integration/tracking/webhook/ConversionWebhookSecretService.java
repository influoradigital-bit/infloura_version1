package com.influora.integration.tracking.webhook;

import com.influora.common.Ulids;
import com.influora.config.ConversionWebhookProperties;
import com.influora.domain.entity.ConversionWebhookSecret;
import com.influora.repository.ConversionWebhookSecretRepository;
import com.influora.service.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] AES-256-GCM encrypted
 * persistence for {@code ConversionWebhookController}'s per-workspace HMAC signing secret. Mirrors
 * {@code WooCommerceIntegrationService} exactly -- no code path in this class (or anywhere else)
 * may write a plaintext secret to the database: the secret is generated in memory, encrypted
 * immediately, and the plaintext is returned to the CALLER exactly once (at generation time, so the
 * brand can copy it into their own backend) and never logged or persisted anywhere in recoverable
 * form. Audit-log detail maps below carry only ids/counts, never the secret itself.
 *
 * <p><b>Structural difference from {@code WooCommerceIntegrationService}</b>: WooCommerce's secret
 * is brand-generated (copied out of a third-party admin panel) and submitted TO us. This secret is
 * SERVER-generated (cryptographically random, {@link #SECRET_BYTES} bytes) and handed FROM us to
 * the brand -- there is no third-party admin UI for a generic checkout integration to copy a
 * secret out of. See {@link ConversionWebhookSecret} class javadoc for the full trust-model
 * rationale.
 *
 * <p>{@code tokenEncryptionKey} is a distinct secret from every other key (JWT/stream/R2/Razorpay/
 * internal-service-token/Meta/Shopify/WooCommerce) per {@code application.yml}'s {@code
 * influora.conversion-webhook} block.
 */
@Service
public class ConversionWebhookSecretService {

    /** [SEC] Same free-text tool-tier literal used by {@code WooCommerceIntegrationService}/{@code ShopifyTokenStorage}/{@code MetaTokenStorage}. */
    private static final String TOOL_TIER_SENSITIVE = "SENSITIVE";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    /** Cryptographically random secret length -- 32 bytes (256 bits) base64url-encoded, comfortably beyond brute-force range for an HMAC key. */
    private static final int SECRET_BYTES = 32;

    private final ConversionWebhookSecretRepository repository;
    private final AuditLogService auditLog;
    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConversionWebhookSecretService(
            ConversionWebhookSecretRepository repository,
            AuditLogService auditLog,
            ConversionWebhookProperties props) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.encryptionKey = decodeKey(props.getTokenEncryptionKey());
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "influora.conversion-webhook.token-encryption-key is not configured — refusing to start"
                            + " conversion webhook secret storage");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Conversion webhook token encryption key must be 256 bits (32 bytes)");
        }
        return key;
    }

    /**
     * Generates a fresh cryptographically random secret for {@code workspaceId}, stores it
     * AES-256-GCM encrypted (rotating any existing secret for that workspace in place, same
     * "rotate, don't duplicate" shape as {@code WooCommerceIntegrationService#connect}), and
     * returns the PLAINTEXT secret to the caller -- the ONLY time this plaintext is ever available
     * after this call returns. The caller (a brand-authenticated controller) is responsible for
     * returning it in the HTTP response body exactly once; nothing else in this codebase can
     * recover it again after this method returns, by design.
     *
     * @return the plaintext secret -- hand it to the caller, never log it
     */
    @Transactional
    public String generate(String workspaceId) {
        String plaintext = generateRandomSecret();
        String encrypted = encrypt(plaintext);

        Optional<ConversionWebhookSecret> existing = repository.findByWorkspaceIdAndRevokedFalse(workspaceId);
        if (existing.isPresent()) {
            existing.get().rotateSecret(encrypted);
            repository.save(existing.get());
        } else {
            ConversionWebhookSecret entity =
                    ConversionWebhookSecret.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .encryptedSecret(encrypted)
                            .build();
            repository.save(entity);
        }

        auditLog.recordToolCall(
                workspaceId,
                "CONVERSION_WEBHOOK_SECRET_GENERATED",
                TOOL_TIER_SENSITIVE,
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of());

        return plaintext;
    }

    /** Marks a workspace's secret revoked (does not delete the row -- audit trail is append-only by convention). */
    @Transactional
    public void revoke(String workspaceId) {
        repository
                .findByWorkspaceIdAndRevokedFalse(workspaceId)
                .ifPresent(
                        s -> {
                            s.revoke();
                            repository.save(s);
                            auditLog.recordToolCall(
                                    workspaceId,
                                    "CONVERSION_WEBHOOK_SECRET_REVOKED",
                                    TOOL_TIER_SENSITIVE,
                                    AuditLogService.OUTCOME_ALLOWED,
                                    null,
                                    null,
                                    null,
                                    Map.of());
                        });
    }

    /**
     * Resolves and decrypts the active secret for {@code workspaceId}, or {@code null} if none is
     * configured -- called by {@code ConversionWebhookController} at webhook-verification time.
     * Returning {@code null} (rather than throwing) lets the controller fail closed with a single,
     * uniform {@code INVALID_WEBHOOK_SIGNATURE} rejection regardless of whether the workspace never
     * configured a secret or configured one and sent a bad signature -- no enumeration signal
     * either way.
     */
    public String decryptSecretForWorkspace(String workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        return repository
                .findByWorkspaceIdAndRevokedFalse(workspaceId)
                .map(s -> decrypt(s.getEncryptedSecret()))
                .orElse(null);
    }

    private String generateRandomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encrypt(String plaintext) {
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
            throw new IllegalStateException("Conversion webhook secret encryption failed", e);
        }
    }

    private String decrypt(String encrypted) {
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
            throw new IllegalStateException("Conversion webhook secret decryption failed", e);
        }
    }
}
