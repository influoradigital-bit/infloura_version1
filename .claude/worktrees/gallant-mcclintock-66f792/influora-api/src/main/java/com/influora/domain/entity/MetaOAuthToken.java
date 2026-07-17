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
 */
@Entity
@Table(name = "meta_oauth_tokens")
public class MetaOAuthToken {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

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

        public Builder id(String id) {
            t.id = id;
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
            t.createdAt = now;
            t.updatedAt = now;
            return t;
        }
    }
}
