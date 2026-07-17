package com.influora.domain.entity;

import com.influora.domain.enums.AdminRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Admin-panel operator account (V34__admin_tables.sql, {@code admin_users}). Deliberately a
 * SEPARATE table from {@code users} (V2__core_auth.sql) — see the migration's header comment for
 * the reasoning (role granularity + MFA columns without touching the brand/creator auth path).
 * {@code users.user_type} already has an unused {@code ADMIN} value; this entity does NOT use it.
 *
 * <p>[SEC: Kabir cycle 2 audit finding, ship-blocking per {@code
 * wiki/decisions/admin-panel-security-priority.md}] {@code encrypted_mfa_secret} (renamed from
 * plaintext {@code mfa_secret} by V35__encrypt_mfa_secret.sql) holds AES-256-GCM ciphertext only —
 * see {@code AdminMfaSecretCipher}. This entity never sees or handles the plaintext TOTP secret;
 * {@code AdminAuthService} encrypts before calling {@link #stageMfaSecret} and decrypts after
 * calling {@link #getEncryptedMfaSecret}.
 */
@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    @Column(length = 26)
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminRole role;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "encrypted_mfa_secret", columnDefinition = "TEXT")
    private String encryptedMfaSecret;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** P1 security hardening (Kabir §8, HIGH) — consecutive failed login/MFA attempts since the last success or lockout. */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    /** Set when {@link #failedLoginAttempts} crosses the configured threshold; login is rejected until this instant passes. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Kabir P1-2 Gap 2 (HIGH) — MFA-specific failed attempts counter for distributed TOTP brute-force defense. Separate from {@link #failedLoginAttempts} (password failures). */
    @Column(name = "failed_mfa_attempts", nullable = false)
    private int failedMfaAttempts;

    /** Kabir P1-2 Gap 2 — MFA-specific lockout expiry. When set, MFA code submission is rejected until this instant passes (even if password is correct). */
    @Column(name = "mfa_locked_until")
    private Instant mfaLockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminUser() {}

    /**
     * Seeds a new admin operator. There is no self-registration endpoint (Phase 1 scope is
     * login/session only, per src/admin/TASK_ASSIGNMENTS.md) — rows are provisioned out-of-band
     * (ops script / future AdminUserController) until that lands.
     */
    public static AdminUser create(String id, String email, String passwordHash, AdminRole role) {
        AdminUser u = new AdminUser();
        u.id = id;
        u.email = email.toLowerCase().trim();
        u.passwordHash = passwordHash;
        u.role = role;
        u.mfaEnabled = false;
        u.active = true;
        u.failedLoginAttempts = 0;
        u.failedMfaAttempts = 0;
        Instant now = Instant.now();
        u.createdAt = now;
        u.updatedAt = now;
        return u;
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AdminRole getRole() {
        return role;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    /** @return AES-256-GCM ciphertext ({@code AdminMfaSecretCipher}), never plaintext. */
    public String getEncryptedMfaSecret() {
        return encryptedMfaSecret;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /** {@code true} if a prior lockout is still in effect at {@code now}. */
    public boolean isLockedOut(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public int getFailedMfaAttempts() {
        return failedMfaAttempts;
    }

    public Instant getMfaLockedUntil() {
        return mfaLockedUntil;
    }

    /** {@code true} if a prior MFA-specific lockout is still in effect at {@code now}. Kabir P1-2 Gap 2. */
    public boolean isMfaLockedOut(Instant now) {
        return mfaLockedUntil != null && mfaLockedUntil.isAfter(now);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markLogin() {
        this.lastLoginAt = Instant.now();
        touch();
    }

    /**
     * Records one failed login/MFA attempt (P1 security hardening, Kabir §8 HIGH). If this
     * pushes {@link #failedLoginAttempts} to {@code maxAttempts} or beyond, locks the account
     * until {@code now + cooldown} and resets the counter — the next attempt after the cooldown
     * starts a fresh count, mirroring a standard exponential-free fixed-window lockout.
     *
     * @return {@code true} if this call just triggered the lockout (caller uses this to return a
     *     "you are now locked out" response instead of a bare "invalid credentials").
     */
    public boolean recordFailedLogin(int maxAttempts, java.time.Duration cooldown, Instant now) {
        this.failedLoginAttempts++;
        touch();
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = now.plus(cooldown);
            this.failedLoginAttempts = 0;
            return true;
        }
        return false;
    }

    /** Clears the failed-attempt counter on a successful, fully-authenticated login. */
    public void resetFailedLoginAttempts() {
        if (this.failedLoginAttempts != 0) {
            this.failedLoginAttempts = 0;
            touch();
        }
    }

    /**
     * Kabir P1-2 Gap 2 (HIGH) — Records one failed MFA code attempt. Separate from password failures
     * to defend against distributed TOTP brute-force (attacker with valid password rotating IPs to
     * try many MFA codes before account locks). If this pushes {@link #failedMfaAttempts} to {@code
     * maxAttempts}, locks MFA submission for {@code cooldown} duration.
     *
     * @return {@code true} if this call just triggered the MFA lockout.
     */
    public boolean recordFailedMfaAttempt(int maxAttempts, java.time.Duration cooldown, Instant now) {
        this.failedMfaAttempts++;
        touch();
        if (this.failedMfaAttempts >= maxAttempts) {
            this.mfaLockedUntil = now.plus(cooldown);
            this.failedMfaAttempts = 0;
            return true;
        }
        return false;
    }

    /** Clears the MFA-specific failed-attempt counter on a successful MFA code verification. */
    public void resetFailedMfaAttempts() {
        if (this.failedMfaAttempts != 0 || this.mfaLockedUntil != null) {
            this.failedMfaAttempts = 0;
            this.mfaLockedUntil = null;
            touch();
        }
    }

    /**
     * Stages an ALREADY-ENCRYPTED TOTP secret pending confirmation — MFA is NOT enabled until
     * {@link #confirmMfa} runs. Caller ({@code AdminAuthService}) must pass {@code
     * AdminMfaSecretCipher.encrypt(...)} output, never the plaintext secret.
     */
    public void stageMfaSecret(String encryptedSecret) {
        this.encryptedMfaSecret = encryptedSecret;
        touch();
    }

    /** Flips {@code mfaEnabled} true after the caller has verified a code against the staged secret. */
    public void confirmMfa() {
        this.mfaEnabled = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
