package com.influora.domain.entity;

import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(length = 26)
    private String id;

    @Column(unique = true)
    private String email;

    @Column(name = "phone_number", unique = true)
    private String phoneNumber;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    // [OB-1, BrandF.md §105/§91] Server-side home for "brand skipped the KYC prompt" so the
    // dismissal survives across devices/browsers instead of living only in the client's
    // localStorage flag. See V20260809120000__brand_kyc_prompt_dismissed.sql.
    @Column(name = "kyc_prompt_dismissed", nullable = false)
    private boolean kycPromptDismissed;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "first_name", length = 50)
    private String firstName;

    @Column(name = "last_name", length = 50)
    private String lastName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // [SEC: Vikram, P2 fix] V61's DDL is `deleted_at DATETIME(6)`, unlike the base V2 temporal
    // columns above (last_login_at/created_at/updated_at), which are plain `TIMESTAMP` (no explicit
    // fractional-seconds precision -- implicitly TIMESTAMP(0)). Left as a bare @Column, Hibernate's
    // MySQLDialect infers the DDL type for an Instant field itself -- its default column type for
    // the JDBC TIMESTAMP SQL code is `datetime($p)` with $p defaulting to 6 (microseconds), i.e.
    // Hibernate's own "expected" type for a plain Instant column is DATETIME(6). That default
    // already happens to line up with what V61 actually created, so pinning it explicitly here
    // (matching the established DATETIME(6) columnDefinition pattern already used elsewhere in this
    // codebase -- e.g. AffiliateEarning#createdAt/settledAt, AudienceDemographics#fetchedAt) makes
    // the match correct BY CONSTRUCTION rather than by relying on inferred-default behavior staying
    // stable across a Hibernate/driver upgrade, and removes any ambiguity for
    // spring.jpa.hibernate.ddl-auto=validate (application.yml) at boot.
    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private Instant deletedAt;

    protected User() {}

    public static User newBrand(
            String id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String displayName) {
        User u = new User();
        u.id = id;
        u.email = email.toLowerCase().trim();
        u.passwordHash = passwordHash;
        u.userType = UserType.BRAND;
        u.status = UserStatus.PENDING_VERIFICATION;
        u.emailVerified = false;
        u.phoneVerified = false;
        u.onboardingCompleted = false;
        u.firstName = firstName;
        u.lastName = lastName;
        u.displayName = displayName;
        u.timezone = "Asia/Kolkata";
        Instant now = Instant.now();
        u.createdAt = now;
        u.updatedAt = now;
        return u;
    }

    public static User newCreator(
            String id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String displayName) {
        User u = new User();
        u.id = id;
        u.email = email.toLowerCase().trim();
        u.passwordHash = passwordHash;
        u.userType = UserType.CREATOR;
        u.status = UserStatus.PENDING_VERIFICATION;
        u.emailVerified = false;
        u.phoneVerified = false;
        u.onboardingCompleted = false;
        u.firstName = firstName;
        u.lastName = lastName;
        u.displayName = displayName;
        u.timezone = "Asia/Kolkata";
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

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public UserType getUserType() {
        return userType;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
        this.updatedAt = Instant.now();
    }

    public boolean isKycPromptDismissed() {
        return kycPromptDismissed;
    }

    /** OB-1: marks the brand KYC prompt as explicitly skipped, server-side, for this user. */
    public void dismissKycPrompt() {
        this.kycPromptDismissed = true;
        this.updatedAt = Instant.now();
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
        this.updatedAt = Instant.now();
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
        this.updatedAt = Instant.now();
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
        this.updatedAt = Instant.now();
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void markLogin() {
        this.lastLoginAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
        if (emailVerified && status == UserStatus.PENDING_VERIFICATION) {
            this.status = UserStatus.ACTIVE;
        }
        this.updatedAt = Instant.now();
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * V61: blanks PII and stamps {@link #deletedAt}.
     *
     * <p><b>[SEC: Vikram, P4 defensive fix]</b> Early-returns if already soft-deleted ({@code
     * deletedAt != null}) — a second call (double-submit of the delete-account action, a retried
     * request) previously re-stamped {@link #deletedAt}/{@link #updatedAt} with a fresh {@code
     * Instant.now()} every time, silently overwriting the ORIGINAL deletion timestamp with a later
     * one on every redundant call. {@code deletedAt} is meant to be the one true record of when the
     * account was actually deleted (compliance/retention-relevant); preserving the original value
     * is correct, re-computing it on every retry is not. PII fields are already {@code null} from
     * the first call, so there is nothing further to blank either.
     */
    public void softDelete() {
        if (this.deletedAt != null) {
            return;
        }
        this.email = null;
        this.phoneNumber = null;
        this.passwordHash = null;
        this.displayName = null;
        this.firstName = null;
        this.lastName = null;
        this.avatarUrl = null;
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
