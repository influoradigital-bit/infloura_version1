package com.influora.domain.entity;

import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalEnquiryType;
import com.influora.domain.enums.FestivalTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One submission from the public Festival Box enquiry form (T-FESTIVALBOX-0905,
 * V20260905130000). Written by {@code FestivalEnquiryService} from an UNAUTHENTICATED request —
 * the submitter has no {@link User}, no {@link Workspace} and no JWT — and read/updated by
 * {@code AdminFestivalEnquiryService}.
 *
 * <p><b>Every {@code @Column} here names its column explicitly</b> rather than relying on the
 * implicit naming strategy. That is deliberate: {@code ddl-auto=validate} is what runs at boot, so
 * an entity field whose derived column name drifts from the migration does not fail a Mockito test
 * — it fails the application's startup, in the environment where it is hardest to diagnose.
 *
 * <p>Half the row is always null. {@link FestivalEnquiryType#BRAND} populates
 * company/website/tier/productCategory; {@link FestivalEnquiryType#CREATOR} populates
 * instagramHandle/followers/city. The unused half stays null rather than empty-string/zero, so
 * admin can distinguish "not asked" from "answered with nothing".
 */
@Entity
@Table(name = "festival_enquiries")
public class FestivalEnquiry {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private FestivalEnquiryType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FestivalEnquiryStatus status;

    @Column(name = "edition", nullable = false, length = 64)
    private String edition;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    // --- BRAND half ---------------------------------------------------------------------------

    @Column(name = "company", length = 160)
    private String company;

    @Column(name = "website", length = 500)
    private String website;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 16)
    private FestivalTier tier;

    @Column(name = "product_category", length = 120)
    private String productCategory;

    // --- CREATOR half -------------------------------------------------------------------------

    @Column(name = "instagram_handle", length = 80)
    private String instagramHandle;

    @Column(name = "followers")
    private Long followers;

    @Column(name = "city", length = 80)
    private String city;

    // --- Shared -------------------------------------------------------------------------------

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "admin_notes", length = 2000)
    private String adminNotes;

    @Column(name = "handled_by", length = 26)
    private String handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "utm_source", length = 120)
    private String utmSource;

    @Column(name = "utm_medium", length = 120)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 120)
    private String utmCampaign;

    @Column(name = "source_ip_hash", length = 64)
    private String sourceIpHash;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- Provisioning link-back (T-FESTIVALBOX-0905 phase 2, V20260905140000) -----------------
    // Plain FK-id columns, deliberately NOT actual foreign keys (same reasoning as handled_by
    // above): deleting the workspace/user/campaign created by provisioning must never cascade
    // away this enquiry's history. Written once, by FestivalSponsorProvisioningService, under a
    // PESSIMISTIC_WRITE lock on this row (see FestivalEnquiryRepository#findByIdForUpdate) —
    // provisionedWorkspaceId being non-null IS the idempotency marker that call checks.

    @Column(name = "provisioned_user_id", length = 26)
    private String provisionedUserId;

    @Column(name = "provisioned_workspace_id", length = 26)
    private String provisionedWorkspaceId;

    @Column(name = "provisioned_campaign_id", length = 26)
    private String provisionedCampaignId;

    @Column(name = "provisioned_at")
    private Instant provisionedAt;

    protected FestivalEnquiry() {}

    /**
     * Factory for a brand-side submission. Status is fixed to {@link FestivalEnquiryStatus#NEW}
     * here and is deliberately not a parameter: the public endpoint must never be able to insert a
     * row that claims to have already been worked.
     */
    public static FestivalEnquiry brand(
            String id,
            String edition,
            String name,
            String email,
            String phone,
            String company,
            String website,
            FestivalTier tier,
            String productCategory,
            String message) {
        FestivalEnquiry e = new FestivalEnquiry();
        e.id = id;
        e.type = FestivalEnquiryType.BRAND;
        e.status = FestivalEnquiryStatus.NEW;
        e.edition = edition;
        e.name = name;
        e.email = email;
        e.phone = phone;
        e.company = company;
        e.website = website;
        e.tier = tier;
        e.productCategory = productCategory;
        e.message = message;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    /** Factory for a creator-side (roster application) submission. See {@link #brand}. */
    public static FestivalEnquiry creator(
            String id,
            String edition,
            String name,
            String email,
            String phone,
            String instagramHandle,
            Long followers,
            String city,
            String message) {
        FestivalEnquiry e = new FestivalEnquiry();
        e.id = id;
        e.type = FestivalEnquiryType.CREATOR;
        e.status = FestivalEnquiryStatus.NEW;
        e.edition = edition;
        e.name = name;
        e.email = email;
        e.phone = phone;
        e.instagramHandle = instagramHandle;
        e.followers = followers;
        e.city = city;
        e.message = message;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    /**
     * Attribution captured from the submitting request. Separate from the factories because it is
     * request metadata, not form input: the caller supplies it from the servlet request, never from
     * the JSON body, so a submitter cannot forge their own sourceIpHash.
     */
    public void applyProvenance(
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String sourceIpHash,
            String userAgent) {
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
        this.utmCampaign = utmCampaign;
        this.sourceIpHash = sourceIpHash;
        this.userAgent = userAgent;
    }

    /**
     * Admin moves the row along the pipeline. {@code NEW} is rejected by the service before it
     * reaches here (see {@link FestivalEnquiryStatus}); handledBy/handledAt record who last touched
     * the row and are overwritten on each transition so they always mean "most recent".
     *
     * <p>A null {@code notes} leaves any existing note intact rather than clearing it — the admin
     * UI sends notes only when the field was edited, so treating null as "erase" would silently
     * discard a previous admin's note on every status change.
     */
    public void applyStatus(FestivalEnquiryStatus next, String adminId, String notes) {
        this.status = next;
        this.handledBy = adminId;
        this.handledAt = Instant.now();
        if (notes != null) {
            this.adminNotes = notes;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Records the outcome of {@code FestivalSponsorProvisioningService#provision} — the User,
     * Workspace and Campaign it created for this WON/BRAND enquiry. Called exactly once per
     * enquiry: the caller holds a {@code PESSIMISTIC_WRITE} lock on this row (see
     * {@code FestivalEnquiryRepository#findByIdForUpdate}) and has already checked {@link
     * #getProvisionedWorkspaceId()} is null inside that same transaction, so a second concurrent
     * call blocks until the first commits and then sees a non-null value instead of overwriting it.
     */
    public void markProvisioned(
            String provisionedUserId,
            String provisionedWorkspaceId,
            String provisionedCampaignId,
            Instant at) {
        this.provisionedUserId = provisionedUserId;
        this.provisionedWorkspaceId = provisionedWorkspaceId;
        this.provisionedCampaignId = provisionedCampaignId;
        this.provisionedAt = at;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public FestivalEnquiryType getType() {
        return type;
    }

    public FestivalEnquiryStatus getStatus() {
        return status;
    }

    public String getEdition() {
        return edition;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getCompany() {
        return company;
    }

    public String getWebsite() {
        return website;
    }

    public FestivalTier getTier() {
        return tier;
    }

    public String getProductCategory() {
        return productCategory;
    }

    public String getInstagramHandle() {
        return instagramHandle;
    }

    public Long getFollowers() {
        return followers;
    }

    public String getCity() {
        return city;
    }

    public String getMessage() {
        return message;
    }

    public String getAdminNotes() {
        return adminNotes;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public String getUtmSource() {
        return utmSource;
    }

    public String getUtmMedium() {
        return utmMedium;
    }

    public String getUtmCampaign() {
        return utmCampaign;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getProvisionedUserId() {
        return provisionedUserId;
    }

    public String getProvisionedWorkspaceId() {
        return provisionedWorkspaceId;
    }

    public String getProvisionedCampaignId() {
        return provisionedCampaignId;
    }

    public Instant getProvisionedAt() {
        return provisionedAt;
    }
}
