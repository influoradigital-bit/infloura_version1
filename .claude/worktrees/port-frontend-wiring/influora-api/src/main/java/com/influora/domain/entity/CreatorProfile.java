package com.influora.domain.entity;

import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.CreatorTaxRegistrationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "creator_profiles")
public class CreatorProfile {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, unique = true, length = 26)
    private String userId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "username", length = 80)
    private String username;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "json")
    private String categoriesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages", columnDefinition = "json")
    private String languagesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_styles", columnDefinition = "json")
    private String contentStylesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "portfolio_settings_json", columnDefinition = "json")
    private String portfolioSettingsJson;

    @Column(name = "rate_min", precision = 12, scale = 2)
    private BigDecimal rateMin;

    @Column(name = "rate_max", precision = 12, scale = 2)
    private BigDecimal rateMax;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    @Column(name = "is_discoverable", nullable = false)
    private boolean discoverable;

    @Column(name = "engagement_rate", precision = 5, scale = 2)
    private BigDecimal engagementRate;

    @Column(name = "total_followers", nullable = false)
    private long totalFollowers;

    @Column(name = "username_changed_at")
    private Instant usernameChangedAt;

    /**
     * Admin-panel creator suspension/application review (V38). {@code suspended_by}/
     * {@code reinstated_by}/{@code application_reviewed_by} are {@code admin_users.id} FKs, never
     * {@code users.id} — mirrors {@link Workspace}'s suspension fields.
     */
    @Column(name = "is_suspended", nullable = false)
    private boolean suspended;

    @Column(name = "suspended_reason", length = 500)
    private String suspendedReason;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_by", length = 26)
    private String suspendedBy;

    @Column(name = "reinstated_at")
    private Instant reinstatedAt;

    @Column(name = "reinstated_by", length = 26)
    private String reinstatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", nullable = false)
    private CreatorApplicationStatus applicationStatus;

    @Column(name = "application_reviewed_by", length = 26)
    private String applicationReviewedBy;

    @Column(name = "application_reviewed_at")
    private Instant applicationReviewedAt;

    @Column(name = "application_rejection_reason", length = 1000)
    private String applicationRejectionReason;

    /**
     * D14 (2026-07-15) creator tax identity — V20260715120000__creator_tax_identity.sql.
     * Nullable per D14-B: schema captures GSTIN/PAN, but enforcement (mandatory GST registration
     * before a creator can transact) is a runtime toggle pending Legal, not a schema constraint.
     */
    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "pan", length = 10)
    private String pan;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_registration_status", length = 20)
    private CreatorTaxRegistrationStatus taxRegistrationStatus;

    /**
     * Stable per-creator statutory invoice-number series code (e.g. {@code CRT001}), assigned at
     * GST onboarding. Per D14-C, Doc#2 (creator service invoice) uses a PER-CREATOR numbering
     * series (each creator is their own supplier of record => own unbroken series), keyed on this
     * code — never a global counter.
     */
    @Column(name = "creator_invoice_code", length = 12)
    private String creatorInvoiceCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorProfile() {}

    /**
     * V38 default preserves live no-pre-approval behavior: creators are auto-APPROVED at signup
     * unless a future application-review flow explicitly gates them.
     */
    public static CreatorProfile newForUser(String id, String userId, String displayName) {
        CreatorProfile p = new CreatorProfile();
        p.id = id;
        p.userId = userId;
        p.displayName = displayName;
        p.currency = "INR";
        p.discoverable = true;
        p.verified = false;
        p.totalFollowers = 0;
        p.suspended = false;
        p.applicationStatus = CreatorApplicationStatus.APPROVED;
        p.taxRegistrationStatus = CreatorTaxRegistrationStatus.UNREGISTERED;
        Instant now = Instant.now();
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public String getCity() {
        return city;
    }

    public String getCategoriesJson() {
        return categoriesJson;
    }

    public String getLanguagesJson() {
        return languagesJson;
    }

    public String getContentStylesJson() {
        return contentStylesJson;
    }

    public String getPortfolioSettingsJson() {
        return portfolioSettingsJson;
    }

    public BigDecimal getRateMin() {
        return rateMin;
    }

    public BigDecimal getRateMax() {
        return rateMax;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isDiscoverable() {
        return discoverable;
    }

    public BigDecimal getEngagementRate() {
        return engagementRate;
    }

    public long getTotalFollowers() {
        return totalFollowers;
    }

    public Instant getUsernameChangedAt() {
        return usernameChangedAt;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public String getSuspendedReason() {
        return suspendedReason;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public String getSuspendedBy() {
        return suspendedBy;
    }

    public Instant getReinstatedAt() {
        return reinstatedAt;
    }

    public String getReinstatedBy() {
        return reinstatedBy;
    }

    public CreatorApplicationStatus getApplicationStatus() {
        return applicationStatus;
    }

    public String getApplicationReviewedBy() {
        return applicationReviewedBy;
    }

    public Instant getApplicationReviewedAt() {
        return applicationReviewedAt;
    }

    public String getApplicationRejectionReason() {
        return applicationRejectionReason;
    }

    public String getGstin() {
        return gstin;
    }

    public String getPan() {
        return pan;
    }

    public CreatorTaxRegistrationStatus getTaxRegistrationStatus() {
        return taxRegistrationStatus;
    }

    public String getCreatorInvoiceCode() {
        return creatorInvoiceCode;
    }

    /**
     * D14-B onboarding capture — null-guarded partial update, same discipline as {@link
     * #applySelfEdit}. {@code creatorInvoiceCode} is assigned once at GST onboarding (by an admin
     * or an onboarding service) and is otherwise stable — callers should not attempt to reassign
     * an existing code without a deliberate migration path, since it anchors the creator's
     * statutory invoice-number series.
     */
    public void applyTaxIdentity(
            String gstin, String pan, CreatorTaxRegistrationStatus status, String creatorInvoiceCode) {
        if (gstin != null) this.gstin = gstin;
        if (pan != null) this.pan = pan;
        if (status != null) this.taxRegistrationStatus = status;
        if (creatorInvoiceCode != null) this.creatorInvoiceCode = creatorInvoiceCode;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Null-guarded partial self-edit shared by {@code CreatorProfileService.patchMyProfile} and
     * {@code PortfolioService.updateMine} — only non-null args are written.
     */
    public void applySelfEdit(
            String displayName,
            String bio,
            String avatarUrl,
            String coverImageUrl,
            String city,
            String categoriesJson,
            String languagesJson,
            String contentStylesJson,
            BigDecimal rateMin,
            BigDecimal rateMax,
            Boolean discoverable) {
        if (displayName != null) this.displayName = displayName;
        if (bio != null) this.bio = bio;
        if (avatarUrl != null) this.avatarUrl = avatarUrl;
        if (coverImageUrl != null) this.coverImageUrl = coverImageUrl;
        if (city != null) this.city = city;
        if (categoriesJson != null) this.categoriesJson = categoriesJson;
        if (languagesJson != null) this.languagesJson = languagesJson;
        if (contentStylesJson != null) this.contentStylesJson = contentStylesJson;
        if (rateMin != null) this.rateMin = rateMin;
        if (rateMax != null) this.rateMax = rateMax;
        if (discoverable != null) this.discoverable = discoverable;
        touch();
    }

    /** Uniqueness/format validation happens in the service before this is called. */
    public void applyUsername(String username) {
        this.username = username;
        this.usernameChangedAt = Instant.now();
        touch();
    }

    public void applyPortfolioSettingsJson(String json) {
        this.portfolioSettingsJson = json;
        touch();
    }

    /** Stores the R2 object key, not a URL — resolved to a presigned URL at read time. */
    public void applyCoverImageUrl(String key) {
        this.coverImageUrl = key;
        touch();
    }

    /**
     * Admin-panel creator application decision (mirrors {@link Workspace#applyKycDecision}).
     *
     * <p><b>[SEC: Vikram, P4 defensive fix]</b> {@code reason} is required (non-blank) when {@code
     * toStatus == REJECTED} — {@code AdminCreatorDtos.ReviewApplicationRequest#reason} is already
     * {@code @NotBlank} at the DTO layer, but that alone does not stop a rejection from being
     * persisted with a blank/null reason via any OTHER caller of this entity method (a future
     * internal call, a batch job, a test). Same defense-in-depth discipline as {@link
     * Dispute#resolve} — throws a bare {@link IllegalArgumentException} here (entities in this
     * codebase never depend on the web/{@code ApiException} layer); callers translate it into a
     * clean 400 (see {@code AdminCreatorService#reviewApplication}).
     */
    public void applyApplicationDecision(
            CreatorApplicationStatus toStatus, String adminId, String reason) {
        if (toStatus == CreatorApplicationStatus.REJECTED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A non-blank reason is required to reject an application");
        }
        this.applicationStatus = toStatus;
        this.applicationReviewedBy = adminId;
        this.applicationReviewedAt = Instant.now();
        this.applicationRejectionReason =
                toStatus == CreatorApplicationStatus.REJECTED ? reason : null;
        touch();
    }

    /**
     * Admin-panel creator suspension (mirrors {@link Workspace#suspend}).
     *
     * <p><b>[SEC: Vikram, P4 defensive fix]</b> Rejects a no-op double-suspend with {@link
     * IllegalStateException} — {@code AdminCreatorService#suspend} already pre-checks {@code
     * isSuspended()} before calling this, but that check-then-act has a TOCTOU gap under
     * concurrency (two admin requests both observing "not suspended" before either commits); this
     * guard makes the entity itself the final arbiter rather than relying solely on the caller's
     * pre-check, so a raced double-suspend can never silently overwrite the original {@code
     * suspendedAt}/{@code suspendedBy}/{@code suspendedReason} audit trail with a second admin's
     * values.
     */
    public void suspend(String reason, String adminId) {
        if (this.suspended) {
            throw new IllegalStateException("Creator is already suspended");
        }
        this.suspended = true;
        this.suspendedReason = reason;
        this.suspendedAt = Instant.now();
        this.suspendedBy = adminId;
        touch();
    }

    /**
     * Admin-panel creator reinstatement (mirrors {@link Workspace#reinstate}). Deliberately does
     * NOT clear {@code suspended*} fields — they remain as the historical record.
     *
     * <p><b>[SEC: Vikram, P4 defensive fix]</b> Rejects a no-op reinstate-when-not-suspended with
     * {@link IllegalStateException} — same TOCTOU rationale as {@link #suspend}.
     */
    public void reinstate(String adminId) {
        if (!this.suspended) {
            throw new IllegalStateException("Creator is not currently suspended");
        }
        this.suspended = false;
        this.reinstatedAt = Instant.now();
        this.reinstatedBy = adminId;
        touch();
    }
}
