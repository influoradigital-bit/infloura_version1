package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /**
     * F-0551 — was this token issued under a "remember me" login (long refresh lifetime) or not
     * (short lifetime)? Recorded at issuance so {@code AuthService#refresh} can carry the original
     * choice forward onto the rotated replacement instead of silently upgrading a not-remembered
     * session to a remembered one (or the reverse) on every refresh.
     */
    @Column(nullable = false)
    private boolean remembered;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public static RefreshToken create(
            String id, String userId, String tokenHash, Instant expiresAt, boolean remembered) {
        RefreshToken t = new RefreshToken();
        t.id = id;
        t.userId = userId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        t.revoked = false;
        t.remembered = remembered;
        t.createdAt = Instant.now();
        return t;
    }

    public String getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isRemembered() {
        return remembered;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void revoke() {
        this.revoked = true;
    }
}
