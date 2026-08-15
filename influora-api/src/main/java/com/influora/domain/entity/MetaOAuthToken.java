package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Encrypted Meta (Instagram/Facebook) OAuth token for a creator (V20 {@code meta_oauth_tokens}).
 *
 * <p>[SEC: Kabir sign-off gate] {@code encryptedAccessToken} is AES-256-GCM ciphertext produced by
 * {@code MetaTokenStorage} — this entity never sees or exposes plaintext; there is deliberately no
 * plain getter/setter pair that could be mistaken for one.
 *
 * <p><b>{@code workspaceId} is nullable (V20260721150000, Creator AI Co-pilot Tier-1 OAuth flip)</b>
 * — a creator-owned row (this feature) always has {@code workspaceId == null}; a brand-owned row
 * (unchanged, pre-existing) always has a non-null {@code workspaceId}. The two key-spaces are
 * disjoint: creator reads/writes key on {@code
 * findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse}, brand reads/writes key on {@code
 * findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse} (CR-111: that method's JPQL carries an
 * explicit {@code workspaceId IS NOT NULL} predicate so a {@code null} workspaceId argument can
 * never match a null-workspace row — this is enforced by the query itself, not just true of every
 * current caller). See {@code MetaTokenStorage} for the creator-owned method pair and Kabir gate
 * finding F-1 for the revoke-before-insert discipline this nullability requires.
 */
@Entity
@Table(name = "meta_oauth_tokens")
public class MetaOAuthToken {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    /**
     * Instagram's real numeric Business Account id, resolved via {@code
     * FacebookPageClient.resolveConnectedInstagram} at connect time (V65 {@code
     * ig_business_account_id} — H-9 fix). Was added to the schema but never mapped on this entity
     * until the Creator Co-pilot OAuth-flip work; nullable — existing rows connected before either
     * fix shipped won't have it until the creator reconnects.
     */
    @Column(name = "ig_business_account_id", length = 64)
    private String igBusinessAccountId;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_scopes", columnDefinition = "json")
    private String grantedScopesJson;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MetaOAuthToken() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getIgBusinessAccountId() {
        return igBusinessAccountId;
    }

    /** Set once at connect time when a linked IG business account resolves (V65/H-9, now also
     * populated on the creator-owned connect path — see {@code CreatorMetaOAuthService}). */
    public void applyIgBusinessAccountId(String igBusinessAccountId) {
        this.igBusinessAccountId = igBusinessAccountId;
        touch();
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getGrantedScopesJson() {
        return grantedScopesJson;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Replaces the encrypted token + expiry on refresh (does not change id/creator/workspace). */
    public void rotateToken(String encryptedAccessToken, Instant expiresAt, String grantedScopesJson) {
        this.encryptedAccessToken = encryptedAccessToken;
        this.expiresAt = expiresAt;
        this.grantedScopesJson = grantedScopesJson;
        this.lastRefreshedAt = Instant.now();
        touch();
    }

    public void revoke() {
        this.revoked = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final MetaOAuthToken t = new MetaOAuthToken();
        private Instant createdAtOverride;

        public Builder id(String id) {
            t.id = id;
            return this;
        }

        /**
         * F-0173 — overrides the {@code createdAt} that {@link #build()} would otherwise stamp
         * with "now". Exists for exactly one caller: {@code MetaTokenStorage.storeCreatorToken}'s
         * revoke-then-insert rotation, which mints a brand-new row on every refresh (the
         * revoke-before-insert step {@link #build()}'s own javadoc on {@code storeCreatorToken}
         * documents as deliberate self-DoS prevention) — without this, {@code createdAt} silently
         * advanced forward every ~55-day auto-refresh cycle instead of reflecting when the
         * creator actually first connected, since {@code MetaConnectionService.getStatus} reads
         * this column directly as the "connected since" date shown to the creator. Every other
         * caller leaves this unset and gets the original "now" behavior.
         */
        public Builder createdAt(Instant createdAt) {
            this.createdAtOverride = createdAt;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            t.workspaceId = workspaceId;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            t.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder igBusinessAccountId(String igBusinessAccountId) {
            t.igBusinessAccountId = igBusinessAccountId;
            return this;
        }

        public Builder encryptedAccessToken(String encryptedAccessToken) {
            t.encryptedAccessToken = encryptedAccessToken;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            t.expiresAt = expiresAt;
            return this;
        }

        public Builder grantedScopesJson(String grantedScopesJson) {
            t.grantedScopesJson = grantedScopesJson;
            return this;
        }

        public Builder lastRefreshedAt(Instant lastRefreshedAt) {
            t.lastRefreshedAt = lastRefreshedAt;
            return this;
        }

        public MetaOAuthToken build() {
            Instant now = Instant.now();
            t.createdAt = createdAtOverride != null ? createdAtOverride : now;
            t.updatedAt = now;
            return t;
        }
    }
}
