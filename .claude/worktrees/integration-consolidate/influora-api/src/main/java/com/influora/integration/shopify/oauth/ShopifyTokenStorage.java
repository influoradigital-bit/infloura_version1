package com.influora.integration.shopify.oauth;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.config.ShopifyProperties;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [SEC: Kabir sign-off gate] AES-256-GCM encrypted persistence for Shopify OAuth access tokens.
 * Mirrors {@code MetaTokenStorage} exactly -- no code path in this class (or anywhere else) may
 * write a plaintext access token to the database: the only write method takes a plaintext token in
 * memory, encrypts it immediately, and the plaintext is never logged (audit-log detail maps below
 * carry only ids/counts, never the token itself).
 *
 * <p>{@code tokenEncryptionKey} is a distinct secret from every other key (JWT/stream/R2/Razorpay/
 * internal-service-token/Meta) per {@code application.yml}'s {@code influora.shopify} block.
 *
 * <p>Unlike {@code MetaOAuthToken}, a {@link ShopifyIntegration} row has no {@code expiresAt} --
 * Shopify's free OAuth token does not expire on its own schedule (see {@code ShopifyOAuthService}
 * javadoc); {@link #getValidToken} therefore only checks {@code revoked}, not an expiry timestamp.
 */
@Service
public class ShopifyTokenStorage {

    /** [SEC] Same free-text tool-tier literal used by {@code MetaTokenStorage} -- see that class's javadoc for why this isn't a shared enum/constant yet. */
    private static final String TOOL_TIER_SENSITIVE = "SENSITIVE";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    private final ShopifyIntegrationRepository repository;
    private final AuditLogService auditLog;
    private final byte[] encryptionKey;

    public ShopifyTokenStorage(
            ShopifyIntegrationRepository repository, AuditLogService auditLog, ShopifyProperties props) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.encryptionKey = decodeKey(props.getTokenEncryptionKey());
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "influora.shopify.token-encryption-key is not configured — refusing to start token storage");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Shopify token encryption key must be 256 bits (32 bytes)");
        }
        return key;
    }

    /**
     * Stores (or rotates/reconnects) the encrypted token for a workspace's store connection, keyed
     * by {@code shopDomain}. Emits a {@code SHOPIFY_OAUTH_TOKEN_STORED} audit record -- detail
     * carries only ids/counts, never the token or its ciphertext.
     *
     * <p>Workspace-scoped like {@code MetaTokenStorage#storeToken}: looks up any existing
     * connection for this workspace first (a workspace should have at most one active store at a
     * time in this slice) and rotates it in place rather than creating a duplicate row, mirroring
     * the {@code rotateToken} vs. fresh-insert branch there.
     */
    @Transactional
    public void storeToken(
            String workspaceId, String shopDomain, String accessToken, List<String> grantedScopes) {
        String encrypted = encrypt(accessToken);
        String scopesJson = JsonLists.toJson(grantedScopes);

        Optional<ShopifyIntegration> existing = repository.findByWorkspaceIdAndRevokedFalse(workspaceId);

        if (existing.isPresent()) {
            existing.get().rotateToken(encrypted, scopesJson);
            repository.save(existing.get());
        } else {
            ShopifyIntegration entity =
                    ShopifyIntegration.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .shopDomain(shopDomain)
                            .encryptedAccessToken(encrypted)
                            .grantedScopesJson(scopesJson)
                            .build();
            repository.save(entity);
        }

        auditLog.recordToolCall(
                workspaceId,
                "SHOPIFY_OAUTH_TOKEN_STORED",
                TOOL_TIER_SENSITIVE,
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "shopDomain", shopDomain,
                        "scopeCount", grantedScopes == null ? 0 : grantedScopes.size()));
    }

    /**
     * Returns the decrypted token for API calls, scoped to the caller's workspace. Empty if no
     * connection exists for that workspace or it has been revoked.
     */
    @Transactional(readOnly = true)
    public Optional<String> getValidToken(String workspaceId) {
        return repository
                .findByWorkspaceIdAndRevokedFalse(workspaceId)
                .map(t -> decrypt(t.getEncryptedAccessToken()));
    }

    /** Marks a workspace's store connection revoked (does not delete the row — audit trail is append-only by convention). */
    @Transactional
    public void revoke(String workspaceId) {
        repository
                .findByWorkspaceIdAndRevokedFalse(workspaceId)
                .ifPresent(
                        t -> {
                            t.revoke();
                            repository.save(t);
                            auditLog.recordToolCall(
                                    workspaceId,
                                    "SHOPIFY_OAUTH_TOKEN_REVOKED",
                                    TOOL_TIER_SENSITIVE,
                                    AuditLogService.OUTCOME_ALLOWED,
                                    null,
                                    null,
                                    null,
                                    Map.of("shopDomain", t.getShopDomain()));
                        });
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
            throw new IllegalStateException("Shopify token encryption failed", e);
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
            throw new IllegalStateException("Shopify token decryption failed", e);
        }
    }
}
