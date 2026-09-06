package com.influora.web.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response/request records for {@code AdminBrandController}. Field names match {@code
 * BrandDetail}/{@code TeamMember}/{@code CampaignSummary}/{@code PaymentRecord} in {@code
 * src/admin/types/admin.types.ts} exactly, mirroring {@code AdminDashboardDtos}'s convention. Some
 * {@code CampaignSummaryDto} fields are honest zeros + a note rather than fabricated numbers —
 * this codebase has no deliverable-review-pipeline table yet (same "not implemented this cycle"
 * pattern as {@code AdminDashboardService}'s {@code campaignsAtRisk}/{@code reviewBacklog}).
 *
 * <p><b>F7 — two distinct brand phone values (do not conflate):</b> {@code BrandSummaryDto}/{@code
 * BrandDetailDto} carry BOTH {@code workspacePhone} ({@code workspaces.phone} — the optional,
 * loosely-validated business contact line) AND {@code ownerPhone} ({@code users.phone_number} of
 * the workspace OWNER — the strictly-validated, now-required-at-registration personal mobile).
 * They are named unambiguously on purpose: a single "phone" field already caused real confusion on
 * the brand settings page. An admin trying to actually reach a brand needs {@code ownerPhone}; the
 * business line is {@code workspacePhone}. Both are admin-only (PII scope, sign-off Q10) — never
 * add either to a public/counterparty-facing DTO or the Meera context payload.
 */
public final class AdminBrandDtos {

    private AdminBrandDtos() {}

    /**
     * Summary record for brand list endpoint — matches {@code Brand} interface in
     * src/admin/types/admin.types.ts. Subset of {@code BrandDetailDto} fields (no
     * gstNumber/panNumber/incorporationDoc/billingAddress/teamMembers/campaigns/paymentHistory
     * nested objects).
     */
    public record BrandSummaryDto(
            String id,
            String name,
            String email,
            /**
             * Workspace's business contact number — {@code workspaces.phone} (V20260718180000).
             * Optional and clearable; validated with a LOOSE international rule ({@code
             * WorkspaceService#isValidPhone}: 7-15 digits, {@code [+()\-\s0-9]} chars only). {@code
             * null} for any workspace that has never set one (most workspaces) — never a fabricated
             * placeholder. NOT the same value as {@code ownerPhone} below — see class javadoc.
             */
            String workspacePhone,
            /**
             * Workspace OWNER's personal mobile — {@code users.phone_number} of the OWNER member.
             * REQUIRED at brand registration since PHONE-0904 (strict Indian-mobile format via {@link
             * com.influora.common.IndianPhoneUtils}), UNIQUE across every user type. {@code null} for
             * any brand that registered BEFORE PHONE-0904 made this required — an honest gap, never a
             * fabricated placeholder.
             */
            String ownerPhone,
            String industry,
            String size,
            String kycStatus,
            String kycReviewedBy,
            Instant kycReviewedAt,
            String kycRejectionReason,
            int campaignCount,
            BigDecimal totalSpend,
            boolean isSuspended,
            Instant createdAt) {}

    /**
     * Paginated response wrapper for brand list — matches {@code PaginatedResponse<Brand>} in
     * src/admin/types/admin.types.ts (data, total, page, pageSize, totalPages).
     */
    public record PaginatedBrandResponse(
            List<BrandSummaryDto> data, long total, int page, int pageSize, int totalPages) {}

    public record BrandDetailDto(
            String id,
            String name,
            String email,
            /**
             * Workspace's business contact number — {@code workspaces.phone} (V20260718180000).
             * Optional and clearable; validated with a LOOSE international rule ({@code
             * WorkspaceService#isValidPhone}: 7-15 digits, {@code [+()\-\s0-9]} chars only). {@code
             * null} for any workspace that has never set one (most workspaces) — never a fabricated
             * placeholder. NOT the same value as {@code ownerPhone} below — see class javadoc.
             */
            String workspacePhone,
            /**
             * Workspace OWNER's personal mobile — {@code users.phone_number} of the OWNER member.
             * REQUIRED at brand registration since PHONE-0904 (strict Indian-mobile format via {@link
             * com.influora.common.IndianPhoneUtils}), UNIQUE across every user type. {@code null} for
             * any brand that registered BEFORE PHONE-0904 made this required — an honest gap, never a
             * fabricated placeholder.
             */
            String ownerPhone,
            String industry,
            String size,
            String kycStatus,
            String kycReviewedBy,
            Instant kycReviewedAt,
            String kycRejectionReason,
            int campaignCount,
            BigDecimal totalSpend,
            boolean isSuspended,
            Instant createdAt,
            String gstNumber,
            String panNumber,
            String incorporationDoc,
            String billingAddress,
            List<TeamMemberDto> teamMembers,
            List<CampaignSummaryDto> campaigns,
            List<PaymentRecordDto> paymentHistory,
            /**
             * T-FESTIVALBOX-0905 phase 6 — {@code workspaces.meta_pixel_id}, read-back for {@code
             * PATCH /admin/brands/{id}/meta-pixel} ({@code AdminBrandService#updateMetaPixel}).
             * {@code null} for the overwhelming majority of brands that have never set one — never
             * a fabricated placeholder. Additive field, appended last: not yet in {@code
             * src/admin/types/admin.types.ts}'s {@code BrandDetail} (frontend is out of scope for
             * this task), but an extra JSON field a TS consumer does not declare is harmless.
             */
            String metaPixelId) {}

    public record TeamMemberDto(String id, String name, String email, String role) {}

    /**
     * {@code deliverablesPending}/{@code deliverablesApproved}/{@code slaBreachRate} are honest
     * zeros — no deliverable-review-pipeline table exists in this schema yet (same documented gap
     * as {@code AdminDashboardService}'s {@code reviewBacklog}). {@code creatorCount}/{@code
     * budget}/{@code spent}/{@code status}/dates are real, computed from live rows.
     */
    public record CampaignSummaryDto(
            String id,
            String name,
            String brandName,
            String type,
            String status,
            BigDecimal budget,
            BigDecimal spent,
            int creatorCount,
            int deliverablesPending,
            int deliverablesApproved,
            double slaBreachRate,
            Instant createdAt,
            Instant endsAt) {}

    public record PaymentRecordDto(
            String id, String type, BigDecimal amount, String description, String status, Instant createdAt) {}

    /**
     * {@code action} must be APPROVE or REJECT; {@code reason} is mandatory per admin.types.ts.
     * {@code brandId} mirrors {@code BrandKycAction}'s shape (api-contracts.ts's {@code
     * brandApi.verifyKyc} serializes the whole action object into the body) but is deliberately
     * IGNORED server-side — the path variable is the only trusted source for which brand is being
     * reviewed, same "never trust a body-supplied id over the path" discipline used everywhere
     * else in this codebase (e.g. {@code CampaignController}).
     */
    public record VerifyKycRequest(
            String brandId,
            @NotBlank String action,
            @NotBlank @Size(max = 2000) String reason) {}

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReinstateRequest(@NotBlank @Size(max = 500) String reason) {}

    /**
     * PUT /admin/brands/{id} ({@code brandApi.update} — api-contracts.ts:185-189). SAFE, non-money
     * profile fields ONLY. All fields optional ({@code Partial<Brand>} semantics — an absent/null
     * field means "leave unchanged", enforced by the null-guarded {@link
     * com.influora.domain.entity.Workspace#applyAdminProfileEdit}). Deliberately carries NO
     * kycStatus/isSuspended/gstNumber/panNumber/totalSpend/campaignCount field — those are outside
     * the admin-editable allow-list and have dedicated flows (verify-kyc/suspend/reinstate). {@code
     * email} maps to {@code workspaces.billing_email} server-side; {@code size} is validated against
     * the closed STARTUP/SMB/ENTERPRISE union in {@code AdminBrandService.update} before it is
     * written (the DB column is free-text, but {@code Brand.size} in admin.types.ts is a closed
     * union). Any field the client sends outside this record (e.g. isSuspended, totalSpend) is
     * simply never bound — Jackson drops unknown JSON properties, so the allow-list is the DTO shape
     * itself, not a blind copy of the request onto the entity.
     */
    public record UpdateBrandRequest(
            @Size(max = 200) String name,
            @Size(max = 100) String industry,
            @Size(max = 50) String size,
            @Email @Size(max = 255) String email) {}

    /**
     * POST /admin/brands/{id}/campaigns/{campaignId}/budget-override ({@code
     * brandApi.overrideBudget} — api-contracts.ts:209-213). <b>MONEY PATH.</b> {@code newBudget}
     * must be present and strictly positive ({@link Positive} excludes zero and negatives); the
     * sane upper bound and the committed-spend floor are enforced in {@code
     * AdminBrandService.overrideCampaignBudget}. {@code reason} is mandatory, min 10 chars —
     * matching the billing money-path {@code @Size(min = 10)} convention (CompSubscriptionRequest/
     * OverrideSubscriptionRequest) so an auditable justification is always captured.
     */
    public record BudgetOverrideRequest(
            @NotNull @Positive BigDecimal newBudget,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    /**
     * PATCH /admin/brands/{id}/meta-pixel (T-FESTIVALBOX-0905 phase 6). Writes {@code
     * Workspace.metaPixelId} — see that field's javadoc and {@code
     * V20260905150000__workspace_meta_pixel_id.sql} for why it lives on the workspace.
     *
     * <p>Unlike {@link UpdateBrandRequest} (a {@code Partial} where an absent/null field means
     * "leave unchanged"), {@code metaPixelId} here has exactly two valid states and both are
     * meaningful: a digits-only string SETS the pixel, and an explicit {@code null} CLEARS it — a
     * sponsor withdrawing consent must be able to return this to null, not be stuck unable to send
     * a "no value" the field-omission convention would silently ignore. {@code @Pattern} treats
     * {@code null} as valid per Bean Validation's null-is-valid rule, so only a non-null value has
     * to match the shape; nothing here rejects {@code null} itself.
     */
    public record UpdateMetaPixelRequest(
            @Pattern(
                            regexp = "^[0-9]{8,20}$",
                            message = "metaPixelId must be 8-20 digits, or null to clear")
                    String metaPixelId) {}
}
