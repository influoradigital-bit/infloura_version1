package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
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
            List<PaymentRecordDto> paymentHistory) {}

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
}
