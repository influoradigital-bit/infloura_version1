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

    @Column(length = 20)
    private String gstin;

    @Column(length = 20)
    private String pan;

    @Column(name = "kyc_gstin_doc_url", length = 500)
    private String kycGstinDocUrl;

    @Column(name = "kyc_pan_doc_url", length = 500)
    private String kycPanDocUrl;

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

    public String getIndustry() {
        return industry;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
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

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
        touch();
    }
}
