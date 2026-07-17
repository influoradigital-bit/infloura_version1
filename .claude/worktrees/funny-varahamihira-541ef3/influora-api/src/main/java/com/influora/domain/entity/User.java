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
}
