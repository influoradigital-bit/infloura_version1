package com.influora.domain.entity;

import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceType type;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(length = 100)
    private String industry;

    @Column(name = "company_size", length = 50)
    private String companySize;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus;

    @Column(name = "billing_email")
    private String billingEmail;

    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(length = 20)
    private String gstin;

    @Column(length = 20)
    private String pan;

    @Column(name = "kyc_gstin_doc_url", length = 500)
    private String kycGstinDocUrl;

    @Column(name = "kyc_pan_doc_url", length = 500)
    private String kycPanDocUrl;

    /**
     * Admin-panel brand suspension (V36__workspace_suspension_kyc_audit.sql,
     * AdminBrandController). {@code suspended_by}/{@code reinstated_by}/{@code kyc_reviewed_by}
     * are {@code admin_users.id} FKs, never {@code users.id} — these are always admin actions.
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

    @Column(name = "kyc_reviewed_by", length = 26)
    private String kycReviewedBy;

    @Column(name = "kyc_reviewed_at")
    private Instant kycReviewedAt;

    @Column(name = "kyc_rejection_reason", length = 2000)
    private String kycRejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Workspace() {}

    public static Workspace newBrand(
            String id, String name, String slug, String industry, String companySize) {
        Workspace w = new Workspace();
        w.id = id;
        w.name = name;
        w.slug = slug;
        w.type = WorkspaceType.BRAND;
        w.industry = industry;
        w.companySize = companySize;
        w.verificationStatus = VerificationStatus.UNVERIFIED;
        Instant now = Instant.now();
        w.createdAt = now;
        w.updatedAt = now;
        return w;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public WorkspaceType getType() {
        return type;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    /**
     * L-9 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md) — needed by {@code
     * WorkspaceService#getMyWorkspace}/{@code updateMyWorkspace} (workspace read-update endpoint);
     * {@link #applyCompanyDetails} already accepted this as a mutator argument even though there
     * was previously no accessor.
     */
    public String getDescription() {
        return description;
    }

    public String getIndustry() {
        return industry;
    }

    public String getCompanySize() {
        return companySize;
    }

    public String getBillingEmail() {
        return billingEmail;
    }

    public String getBillingAddress() {
        return billingAddress;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
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

    public String getKycReviewedBy() {
        return kycReviewedBy;
    }

    public Instant getKycReviewedAt() {
        return kycReviewedAt;
    }

    public String getKycRejectionReason() {
        return kycRejectionReason;
    }

    public String getGstin() {
        return gstin;
    }

    public String getPan() {
        return pan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void applyCompanyDetails(
            String name,
            String slug,
            WorkspaceType type,
            String industry,
            String companySize,
            String websiteUrl,
            String description,
            String logoUrl) {
        this.name = name;
        this.slug = slug;
        this.type = type != null ? type : WorkspaceType.BRAND;
        this.industry = industry;
        this.companySize = companySize;
        this.websiteUrl = websiteUrl;
        this.description = description;
        this.logoUrl = logoUrl;
        touch();
    }

    public void applyKyc(String gstin, String pan, String gstinDocUrl, String panDocUrl) {
        this.gstin = gstin;
        this.pan = pan;
        this.kycGstinDocUrl = gstinDocUrl;
        this.kycPanDocUrl = panDocUrl;
        this.verificationStatus = VerificationStatus.PENDING;
        touch();
    }

    /**
     * Captures PAN/GSTIN optionally supplied at wallet top-up time (B0 — CFO constraint, LOCKED
     * ruling item 6: "capture PAN/GSTIN at top-up for TDS reconciliation"). Deliberately reuses
     * the existing {@link #pan}/{@link #gstin} columns (the KYC home already established by
     * {@link #applyKyc}) rather than adding a parallel pair of columns.
     *
     * <p>Fill-if-blank only: a value already on file (typically from verified KYC document
     * submission, {@link #applyKyc}) is never overwritten by an unverified value typed into a
     * top-up form, and this method never touches {@code kycGstinDocUrl}/{@code kycPanDocUrl}/
     * {@link #verificationStatus} — unlike {@link #applyKyc}, this is not a KYC submission, just a
     * best-effort tax-id capture for a workspace that has not been through KYC yet. A no-op (no
     * {@link #touch()}) when nothing new was actually written.
     */
    public void applyTopUpTaxIds(String pan, String gstin) {
        boolean changed = false;
        if (this.pan == null && pan != null && !pan.isBlank()) {
            this.pan = pan;
            changed = true;
        }
        if (this.gstin == null && gstin != null && !gstin.isBlank()) {
            this.gstin = gstin;
            changed = true;
        }
        if (changed) {
            touch();
        }
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
        touch();
    }

    /**
     * Admin-panel KYC decision (AdminBrandController.verifyKyc). {@code reviewerAdminId} is
     * always server-derived from the caller's {@code AuthPrincipal}, never client-supplied —
     * mirrors the admin_audit_log writer's "never trust client-sent identity" rule
     * (wiki/admin-progress/AUDIT-LOG-WRITE-SPEC.md Rule 1). {@code rejectionReason} is cleared on
     * approval so a stale rejection note never lingers after a later approval overturns it.
     */
    public void applyKycDecision(
            VerificationStatus newStatus, String reviewerAdminId, String rejectionReason) {
        this.verificationStatus = newStatus;
        this.kycReviewedBy = reviewerAdminId;
        this.kycReviewedAt = Instant.now();
        this.kycRejectionReason = newStatus == VerificationStatus.REJECTED ? rejectionReason : null;
        touch();
    }

    /** Admin-panel brand suspension (AdminBrandController.suspend). */
    public void suspend(String reason, String adminId) {
        this.suspended = true;
        this.suspendedReason = reason;
        this.suspendedAt = Instant.now();
        this.suspendedBy = adminId;
        touch();
    }

    /**
     * Admin-panel brand reinstatement (AdminBrandController.reinstate). Deliberately does NOT
     * clear {@code suspendedReason}/{@code suspendedBy}/{@code suspendedAt} — those remain as the
     * historical record of the suspension that was lifted; only {@link #suspended} flips and the
     * reinstatement is recorded alongside it.
     */
    public void reinstate(String adminId) {
        this.suspended = false;
        this.reinstatedAt = Instant.now();
        this.reinstatedBy = adminId;
        touch();
    }
}
