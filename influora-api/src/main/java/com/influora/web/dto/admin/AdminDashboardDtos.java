package com.influora.web.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard/KPI response records. Field names match {@code CeoPulseData} / {@code RedFlag} in
 * {@code src/admin/types/admin.types.ts} — except the three {@code *Change} fields below are
 * boxed {@code Double} here (not the {@code number} FE currently declares): see {@code
 * AdminDashboardService} class javadoc for why they're a genuine {@code null} (no historical
 * snapshot table exists yet to diff against) rather than a fabricated {@code 0}, and flag the FE
 * type/rendering ripple to Ananya rather than editing {@code src/} directly.
 */
public final class AdminDashboardDtos {

    private AdminDashboardDtos() {}

    public record CeoPulseDataDto(
            BigDecimal gmv,
            Double gmvChange,
            BigDecimal revenue,
            Double revenueChange,
            long activeCampaigns,
            Double activeCampaignsChange,
            BigDecimal escrowFloat,
            long supportQueueDepth,
            long mauBrands,
            long mauCreators,
            List<RedFlagDto> redFlags) {}

    /** {@code type} is one of ESCROW_LOW|SLA_BREACH|PAYOUT_DELAY|REVIEW_BACKLOG|SUPPORT_AGING. */
    public record RedFlagDto(
            String id,
            String type,
            String message,
            String severity,
            Instant createdAt,
            String entityId,
            String entityType) {}

    /** Mirrors {@code dashboardApi.getOperationsSummary()}'s inline response shape. */
    public record OperationsSummaryDto(
            long activeCampaigns,
            long campaignsAtRisk,
            long reviewBacklog,
            long supportQueueDepth,
            double avgReviewTime) {}
}
