package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Wire types for the admin console's Festival Box enquiry inbox (T-FESTIVALBOX-0905), backing
 * {@code festivalEnquiryApi} in {@code src/admin/services/api-contracts.ts}.
 */
public final class AdminFestivalEnquiryDtos {

    private AdminFestivalEnquiryDtos() {}

    /**
     * One enquiry as admin sees it.
     *
     * <p>Carries the submitter's email and phone — that is the point of the inbox, and the route is
     * already behind the SUPER_ADMIN/ADMIN + MFA gate. It deliberately does NOT carry
     * {@code sourceIpHash}: nothing in the console does anything with it, and shipping it to the
     * browser would widen where a visitor-linkable value travels for no gain. It stays in the row
     * for the throttle and for an abuse investigation done against the database.
     *
     * <p>Type-specific fields are null on the other half of the table (a BRAND row has no
     * {@code instagramHandle}); the console renders those as an em dash rather than an empty cell,
     * so "not asked" stays visibly different from "left blank".
     */
    public record AdminFestivalEnquiryDto(
            String id,
            String type,
            String status,
            String edition,
            String name,
            String email,
            String phone,
            String company,
            String website,
            String tier,
            String productCategory,
            String instagramHandle,
            Long followers,
            String city,
            String message,
            String adminNotes,
            String handledBy,
            String handledAt,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String createdAt,
            // T-FESTIVALBOX-0905 phase 2 — the provisioning link-back.
            //
            // These four were persisted on the entity by FestivalSponsorProvisioningService but
            // were NOT exposed here, while src/admin/services/api-contracts.ts already declared
            // them on AdminFestivalEnquiry. That combination is silent: TypeScript is perfectly
            // happy declaring a field the Java record never sends, so the FE compiled, the tests
            // on both sides passed, and every provisioned row still rendered as un-provisioned
            // after a page reload — the admin's only signal was the transient success panel held
            // in the open drawer's local state. Re-clicking "Provision" then hit the backend's
            // ALREADY_PROVISIONED guard, so nothing was ever corrupted; it just looked broken.
            //
            // Same class of defect as the phone-field gap logged under PHONE-0829 P3. The lesson
            // that keeps applying: a new column is not shipped until the READ path serves it.
            String provisionedUserId,
            String provisionedWorkspaceId,
            String provisionedCampaignId,
            String provisionedAt) {}

    /**
     * One page of enquiries plus the counts the inbox header shows.
     *
     * @param statusCounts total per status ACROSS the whole table, not just this page — the header
     *     reads "12 new" and that must not change when the admin pages forward. Keyed by
     *     {@code FestivalEnquiryStatus} name.
     */
    public record PagedFestivalEnquiriesDto(
            List<AdminFestivalEnquiryDto> items,
            int page,
            int pageSize,
            long total,
            boolean hasMore,
            java.util.Map<String, Long> statusCounts) {}

    /**
     * Move one enquiry along the pipeline.
     *
     * @param status target status. {@code NEW} is refused by the service — see
     *     {@code FestivalEnquiryStatus}.
     * @param notes optional. Omitting it (null) LEAVES the existing note intact; sending an empty
     *     string is how the console clears one. Those are different requests on purpose, so a
     *     status change made without touching the notes field cannot silently erase a colleague's
     *     note.
     */
    public record UpdateEnquiryStatusRequest(
            @NotBlank(message = "status is required") @Size(max = 16) String status,
            @Size(max = 2000) String notes) {}
}
