package com.influora.integration.meta.oauth;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
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
 * [SEC: Kabir sign-off gate] AES-256-GCM encrypted persistence for Meta OAuth tokens. No code path
 * in this class (or anywhere else) may write a plaintext access token to the database — the only
 * write method takes a plaintext token in memory, encrypts it immediately, and the plaintext is
 * never logged (see {@code AuditLogService} detail maps below — creator/scope counts only, never
 * the token itself).
 *
 * <p>{@code tokenEncryptionKey} is a distinct secret from every other key (JWT/stream/R2/Razorpay/
 * internal-service-token) per {@code application.yml}'s {@code influora.meta} block.
 */
@Service
public class MetaTokenStorage {

    /** [SEC] Matches whatever tool-tier taxonomy the codebase's AuditLogService callers use
     * (see ToolCallValidator: e.g. "R" for read tools). No "SENSITIVE" tier constant exists yet
     * anywhere in the codebase — ASSUMPTION documented in the handoff: introducing a dedicated
     * literal here rather than inventing a shared constant, since AuditLogService.toolTier is a
     * free-text column (VARCHAR(16), no enum/CHECK constraint). Flagging for Kabir/Priya to fold
     * into a shared tier constant if/when one is formalized. */
    private static final String TOOL_TIER_SENSITIVE = "SENSITIVE";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32;

    private final MetaOAuthTokenRepository repository;
    private final AuditLogService auditLog;
    private final byte[] encryptionKey;

    public MetaTokenStorage(
            MetaOAuthTokenRepository repository, AuditLogService auditLog, MetaApiProperties props) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.encryptionKey = decodeKey(props.getTokenEncryptionKey());
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "influora.meta.token-encryption-key is not configured — refusing to start token storage");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Meta token encryption key must be 256 bits (32 bytes)");
        }
        return key;
    }

    /**
     * Stores (or rotates) the encrypted token for a creator, scoped to the owning workspace.
     * Emits a {@code META_OAUTH_TOKEN_STORED} audit record — detail carries only ids/counts, never
     * the token or its ciphertext.
     */
    @Transactional
    public void storeToken(
            String creatorProfileId,
            String workspaceId,
            String accessToken,
            Instant expiresAt,
            List<String> grantedScopes) {
        String encrypted = encrypt(accessToken);
        String scopesJson = JsonLists.toJson(grantedScopes);

        Optional<MetaOAuthToken> existing =
                repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId);

        if (existing.isPresent()) {
            existing.get().rotateToken(encrypted, expiresAt, scopesJson);
            repository.save(existing.get());
        } else {
            MetaOAuthToken entity =
                    MetaOAuthToken.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .creatorProfileId(creatorProfileId)
                            .encryptedAccessToken(encrypted)
                            .expiresAt(expiresAt)
                            .grantedScopesJson(scopesJson)
                            .lastRefreshedAt(Instant.now())
                            .build();
            repository.save(entity);
        }

        auditLog.recordToolCall(
                workspaceId,
                "META_OAUTH_TOKEN_STORED",
                TOOL_TIER_SENSITIVE,
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "creatorProfileId", creatorProfileId,
                        "scopeCount", grantedScopes == null ? 0 : grantedScopes.size()));
    }

    /**
     * Returns the decrypted token for API calls, scoped to the caller's workspace. Empty if no
     * token exists for that (workspace, creator) pair, it has been revoked, or it is expired —
     * callers should treat all three the same way (trigger re-auth / refresh flow).
     */
    @Transactional(readOnly = true)
    public Optional<String> getValidToken(String workspaceId, String creatorProfileId) {
        return repository
                .findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .map(t -> decrypt(t.getEncryptedAccessToken()));
    }

    /** Finds tokens (system-wide) expiring within {@code days} — for the proactive refresh sweep. */
    @Transactional(readOnly = true)
    public List<MetaOAuthToken> findTokensExpiringSoon(int days) {
        Instant threshold = Instant.now().plus(Duration.ofDays(days));
        return repository.findByExpiresAtBeforeAndRevokedFalse(threshold);
    }

    /** Marks a token revoked (does not delete the row — audit trail is append-only by convention). */
    @Transactional
    public void revoke(String workspaceId, String creatorProfileId) {
        repository
                .findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId)
                .ifPresent(
                        t -> {
                            t.revoke();
                            repository.save(t);
                            auditLog.recordToolCall(
                                    workspaceId,
                                    "META_OAUTH_TOKEN_REVOKED",
                                    TOOL_TIER_SENSITIVE,
                                    AuditLogService.OUTCOME_ALLOWED,
                                    null,
                                    null,
                                    null,
                                    Map.of("creatorProfileId", creatorProfileId));
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
            throw new IllegalStateException("Meta token encryption failed", e);
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
            throw new IllegalStateException("Meta token decryption failed", e);
        }
    }
}
