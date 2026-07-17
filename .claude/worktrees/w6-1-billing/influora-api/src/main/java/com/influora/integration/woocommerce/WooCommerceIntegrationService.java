package com.influora.integration.woocommerce;

import com.influora.common.Ulids;
import com.influora.config.WooCommerceProperties;
import com.influora.domain.entity.WooCommerceIntegration;
import com.influora.repository.WooCommerceIntegrationRepository;
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
 * [SEC: Kabir sign-off gate] AES-256-GCM encrypted persistence for WooCommerce webhook secrets.
 * Mirrors {@code ShopifyTokenStorage} exactly -- no code path in this class (or anywhere else) may
 * write a plaintext secret to the database: the only write method takes a plaintext secret in
 * memory, encrypts it immediately, and the plaintext is never logged (audit-log detail maps below
 * carry only ids/counts, never the secret itself).
 *
 * <p>{@code tokenEncryptionKey} is a distinct secret from every other key (JWT/stream/R2/Razorpay/
 * internal-service-token/Meta/Shopify) per {@code application.yml}'s {@code influora.woocommerce}
 * block.
 *
 * <p><b>Structural difference from {@code ShopifyTokenStorage}</b>: that class stores an OAuth
 * ACCESS TOKEN used to call OUT to Shopify's API. This class stores a WEBHOOK SECRET used only to
 * verify signatures on INBOUND deliveries -- there is no outbound API call anywhere in this
 * integration, so there is no token-expiry/refresh concept at all (not even the "no expiry"
 * simplification {@code ShopifyIntegration} documents -- the concept simply does not apply here).
 */
@Service
public class WooCommerceIntegrationService {

    /** [SEC] Same free-text tool-tier literal used by {@code ShopifyTokenStorage}/{@code MetaTokenStorage}. */
    private static final String TOOL_TIER_SENSITIVE = "SENSITIVE";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    private final WooCommerceIntegrationRepository repository;
    private final AuditLogService auditLog;
    private final byte[] encryptionKey;

    public WooCommerceIntegrationService(
            WooCommerceIntegrationRepository repository, AuditLogService auditLog, WooCommerceProperties props) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.encryptionKey = decodeKey(props.getTokenEncryptionKey());
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "influora.woocommerce.token-encryption-key is not configured — refusing to start WooCommerce integration storage");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("WooCommerce token encryption key must be 256 bits (32 bytes)");
        }
        return key;
    }

    /**
     * Stores (or rotates/reconnects) the encrypted webhook secret for a workspace's store
     * connection, keyed by {@code siteUrl}. Emits a {@code WOOCOMMERCE_WEBHOOK_SECRET_STORED} audit
     * record -- detail carries only ids/counts, never the secret or its ciphertext.
     *
     * <p>Workspace-scoped like {@code ShopifyTokenStorage#storeToken}: looks up any existing
     * connection for this workspace first (a workspace should have at most one active site at a
     * time in this slice) and rotates it in place rather than creating a duplicate row.
     *
     * @param siteUrl MUST already be normalized via {@link WooCommerceSiteUrl#normalize} -- this
     *     method does not re-normalize, callers (only {@code WooCommerceConnectController}) own
     *     that step so the normalization rule lives in exactly one place
     */
    @Transactional
    public WooCommerceIntegration connect(String workspaceId, String siteUrl, String webhookSecret) {
        String encrypted = encrypt(webhookSecret);

        Optional<WooCommerceIntegration> existing = repository.findByWorkspaceIdAndRevokedFalse(workspaceId);

        WooCommerceIntegration saved;
        if (existing.isPresent()) {
            existing.get().rotateSecret(encrypted);
            saved = repository.save(existing.get());
        } else {
            WooCommerceIntegration entity =
                    WooCommerceIntegration.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .siteUrl(siteUrl)
                            .encryptedWebhookSecret(encrypted)
                            .build();
            saved = repository.save(entity);
        }

        auditLog.recordToolCall(
                workspaceId,
                "WOOCOMMERCE_WEBHOOK_SECRET_STORED",
                TOOL_TIER_SENSITIVE,
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("siteUrl", siteUrl));

        return saved;
    }

    /** Marks a workspace's store connection revoked (does not delete the row — audit trail is append-only by convention). */
    @Transactional
    public void revoke(String workspaceId) {
        repository
                .findByWorkspaceIdAndRevokedFalse(workspaceId)
                .ifPresent(
                        w -> {
                            w.revoke();
                            repository.save(w);
                            auditLog.recordToolCall(
                                    workspaceId,
                                    "WOOCOMMERCE_WEBHOOK_SECRET_REVOKED",
                                    TOOL_TIER_SENSITIVE,
                                    AuditLogService.OUTCOME_ALLOWED,
                                    null,
                                    null,
                                    null,
                                    Map.of("siteUrl", w.getSiteUrl()));
                        });
    }

    /**
     * Returns the decrypted webhook secret for {@code integration} -- called by {@code
     * WooCommerceWebhookController} ONLY after resolving the integration row via {@code
     * WooCommerceIntegrationRepository#findBySiteUrlAndRevokedFalse}, so the caller already has the
     * entity in hand; this is a pure decrypt helper, not a repository-backed lookup, to avoid a
     * second redundant query.
     */
    public String decryptSecret(WooCommerceIntegration integration) {
        return decrypt(integration.getEncryptedWebhookSecret());
    }

    private String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("WooCommerce webhook secret encryption failed", e);
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
            throw new IllegalStateException("WooCommerce webhook secret decryption failed", e);
        }
    }
}
