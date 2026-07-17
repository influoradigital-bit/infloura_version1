package com.influora.web.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard/KPI response records. Field names match {@code CeoPulseData} / {@code RedFlag} in
 * {@code src/admin/types/admin.types.ts} exactly. Several fields are honest zeros with a TODO
 * rather than fabricated numbers — see {@code AdminDashboardService} class javadoc for exactly
 * which ones and why (no revenue ledger / no historical snapshot table exists yet).
 */
public final class AdminDashboardDtos {

    private AdminDashboardDtos() {}

    public record CeoPulseDataDto(
            BigDecimal gmv,
            double gmvChange,
            BigDecimal revenue,
            double revenueChange,
            long activeCampaigns,
            double activeCampaignsChange,
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
