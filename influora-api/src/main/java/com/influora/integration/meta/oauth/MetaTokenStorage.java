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

    // -------------------------------------------------------------------------------------------
    // Creator-owned overloads (Creator AI Co-pilot Tier-1 OAuth flip, be-services-plan.md §3).
    // These never touch the brand-scoped methods above: creator rows always have workspaceId=NULL,
    // brand rows never do, so the two key-spaces are disjoint by construction (Kabir gate, IDOR
    // threat-1: PASS).
    // -------------------------------------------------------------------------------------------

    /**
     * Stores (or replaces) the encrypted token for a creator with NO owning workspace.
     *
     * <p>[SEC: Kabir gate finding F-1, Low — hardening, not a live vuln] making {@code
     * workspace_id} nullable (V20260721150000) silently drops the DB uniqueness the brand path
     * relied on: {@code UNIQUE(workspace_id, creator_profile_id)} does NOT constrain these rows,
     * because MySQL treats multiple NULLs as distinct, not equal. Without this revoke-before-insert
     * step, two concurrent first-time creator connects (double-submit, or a reconnect race) could
     * each insert a non-revoked NULL-workspace row for the same creator; {@link
     * #getValidCreatorToken} would then throw {@code IncorrectResultSizeDataAccessException} on
     * every subsequent read (self-DoS for that one creator, no cross-tenant impact). Revoking any
     * existing non-revoked creator-owned row FIRST — inside this same {@code @Transactional}
     * method, before the new row is even built — guarantees at most one non-revoked row per
     * creator can exist at any point in time, closing the race at the source rather than papering
     * over it with a second unique-key backstop.
     */
    @Transactional
    public void storeCreatorToken(
            String creatorProfileId,
            String accessToken,
            Instant expiresAt,
            List<String> grantedScopes,
            String igBusinessAccountId) {
        repository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .ifPresent(
                        existing -> {
                            existing.revoke();
                            repository.save(existing);
                        });

        String encrypted = encrypt(accessToken);
        String scopesJson = JsonLists.toJson(grantedScopes);

        MetaOAuthToken entity =
                MetaOAuthToken.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(creatorProfileId)
                        // .workspaceId(...) intentionally omitted — stays null (creator-owned row).
                        .igBusinessAccountId(igBusinessAccountId)
                        .encryptedAccessToken(encrypted)
                        .expiresAt(expiresAt)
                        .grantedScopesJson(scopesJson)
                        .lastRefreshedAt(Instant.now())
                        .build();
        repository.save(entity);

        auditLog.recordToolCall(
                null,
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

    /** Returns the decrypted token for a creator-owned row. Empty if none exists, it has been
     * revoked, or it is expired — same three-way-collapse convention as {@link #getValidToken}. */
    @Transactional(readOnly = true)
    public Optional<String> getValidCreatorToken(String creatorProfileId) {
        return repository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .map(t -> decrypt(t.getEncryptedAccessToken()));
    }

    /** Marks a creator-owned token revoked (does not delete the row). */
    @Transactional
    public void revokeCreatorToken(String creatorProfileId) {
        repository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .ifPresent(
                        t -> {
                            t.revoke();
                            repository.save(t);
                            auditLog.recordToolCall(
                                    null,
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
