package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P1 security hardening (Kabir §8 HIGH, {@code AdminAuthService.java:101-109}) — admin login had
 * (1) no failed-attempt lockout and (2) MFA that was fully opt-in even for the highest-privilege
 * roles. Distinct class from {@link AdminMfaProperties} (that one is scoped tightly to the TOTP
 * secret's own AES-256-GCM encryption key per its javadoc); this one is general admin-login
 * security policy, same {@code influora.admin} prefix.
 *
 * <p><b>{@code mfaEnforceOnLogin} — [DECISION FLAGGED, not guessed]</b> when {@code true}
 * (the default, per CTO instruction), {@code AdminAuthService#login} REJECTS with {@code
 * MFA_ENROLLMENT_REQUIRED} (403) any {@code SUPER_ADMIN}/{@code ADMIN}-tier login where the
 * account has never enabled MFA (mirrors {@code AdminContextService#requireMfaSatisfied}'s
 * identical SUPPORT-is-exempt role split, applied one step earlier — at login, not just at
 * privileged-route access). This closes the real gap: previously an admin who never opted into
 * MFA could still obtain a full session (blocked only from *privileged routes*, per {@code
 * AdminContextService}, but still able to reach {@code /me}/{@code /logout}/{@code /mfa/*} on
 * password alone).
 *
 * <p><b>Real, unmitigated risk of the {@code true} default:</b> this codebase has NO admin
 * self-registration/seeding endpoint and NO "reset another admin's MFA" endpoint (confirmed by
 * grep — {@code AdminAuthService}'s own class javadoc flags both as "Known gaps... out of scope").
 * If any existing {@code SUPER_ADMIN}/{@code ADMIN} row in {@code admin_users} has {@code
 * mfa_enabled = false} today, this change locks that account out of login entirely with no
 * in-app recovery path — only a direct DB update (clear the row's role to SUPPORT temporarily, or
 * flip this flag to {@code false} via {@code ADMIN_MFA_ENFORCE_ON_LOGIN=false}) gets them back in.
 * <b>Before deploying with this flag left at its default {@code true}, confirm no such row exists
 * (or enroll MFA for it out-of-band first).</b> This is flagged per the CTO's explicit instruction
 * to implement the safe default and surface the decision rather than silently picking a different
 * one.
 */
@ConfigurationProperties(prefix = "influora.admin")
public class AdminSecurityProperties {

    private boolean mfaEnforceOnLogin = true;
    private int lockoutMaxAttempts = 5;
    private long lockoutCooldownSeconds = 900; // 15 minutes

    // Kabir P1-2 Gap 2 (HIGH) — MFA-specific rate limit (distributed TOTP brute-force defense)
    private int mfaLockoutMaxAttempts = 3;
    private long mfaLockoutCooldownSeconds = 3600; // 1 hour

    public boolean isMfaEnforceOnLogin() {
        return mfaEnforceOnLogin;
    }

    public void setMfaEnforceOnLogin(boolean mfaEnforceOnLogin) {
        this.mfaEnforceOnLogin = mfaEnforceOnLogin;
    }

    public int getLockoutMaxAttempts() {
        return lockoutMaxAttempts;
    }

    public void setLockoutMaxAttempts(int lockoutMaxAttempts) {
        this.lockoutMaxAttempts = lockoutMaxAttempts;
    }

    public long getLockoutCooldownSeconds() {
        return lockoutCooldownSeconds;
    }

    public void setLockoutCooldownSeconds(long lockoutCooldownSeconds) {
        this.lockoutCooldownSeconds = lockoutCooldownSeconds;
    }

    public int getMfaLockoutMaxAttempts() {
        return mfaLockoutMaxAttempts;
    }

    public void setMfaLockoutMaxAttempts(int mfaLockoutMaxAttempts) {
        this.mfaLockoutMaxAttempts = mfaLockoutMaxAttempts;
    }

    public long getMfaLockoutCooldownSeconds() {
        return mfaLockoutCooldownSeconds;
    }

    public void setMfaLockoutCooldownSeconds(long mfaLockoutCooldownSeconds) {
        this.mfaLockoutCooldownSeconds = mfaLockoutCooldownSeconds;
    }
}
