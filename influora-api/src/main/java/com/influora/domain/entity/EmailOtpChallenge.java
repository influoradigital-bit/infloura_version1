package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "email_otp_challenges")
public class EmailOtpChallenge {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false)
    private String email;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean verified;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailOtpChallenge() {}

    public static EmailOtpChallenge create(String id, String email, String otpHash, Instant expiresAt) {
        EmailOtpChallenge c = new EmailOtpChallenge();
        c.id = id;
        c.email = email.toLowerCase().trim();
        c.otpHash = otpHash;
        c.expiresAt = expiresAt;
        c.verified = false;
        c.attempts = 0;
        c.createdAt = Instant.now();
        return c;
    }

    public String getEmail() {
        return email;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }
}
