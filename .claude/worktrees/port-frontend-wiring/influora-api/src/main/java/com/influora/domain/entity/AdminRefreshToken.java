package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Refresh-token rotation record for admin sessions (V34__admin_tables.sql,
 * {@code admin_refresh_tokens}). Mirrors {@link RefreshToken} (the brand/creator equivalent)
 * field-for-field but keyed by {@code adminId} against {@code admin_users} instead of
 * {@code users} — kept as a distinct entity/table rather than a shared one so admin session
 * revocation can never be confused with, or accidentally cascade into, brand/creator sessions.
 */
@Entity
@Table(name = "admin_refresh_tokens")
public class AdminRefreshToken {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "admin_id", nullable = false, length = 26)
    private String adminId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminRefreshToken() {}

    public static AdminRefreshToken create(
            String id, String adminId, String tokenHash, Instant expiresAt) {
        AdminRefreshToken t = new AdminRefreshToken();
        t.id = id;
        t.adminId = adminId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        t.revoked = false;
        t.createdAt = Instant.now();
        return t;
    }

    public String getAdminId() {
        return adminId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void revoke() {
        this.revoked = true;
    }
}
