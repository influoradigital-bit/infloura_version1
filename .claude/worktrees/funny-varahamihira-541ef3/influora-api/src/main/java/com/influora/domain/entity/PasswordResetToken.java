package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

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
    private boolean used;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetToken() {}

    public static PasswordResetToken create(
            String id, String userId, String tokenHash, Instant expiresAt) {
        PasswordResetToken t = new PasswordResetToken();
        t.id = id;
        t.userId = userId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        t.used = false;
        t.createdAt = Instant.now();
        return t;
    }

    public String getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isUsed() {
        return used;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void markUsed() {
        this.used = true;
    }
}
