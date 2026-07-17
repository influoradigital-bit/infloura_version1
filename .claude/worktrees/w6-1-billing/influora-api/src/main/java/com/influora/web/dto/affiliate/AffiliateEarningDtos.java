package com.influora.web.dto.affiliate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Creator-facing affiliate earnings read DTOs — V-GA-7. Mirrors {@code AffiliateEarningRow} /
 * {@code AffiliateEarningsSummary} in {@code src/lib/api.ts}.
 */
public final class AffiliateEarningDtos {

    private AffiliateEarningDtos() {}

    public record AffiliateEarningRow(
            String id,
            String campaignId,
            String campaignName,
            String brandName,
            String redemptionId,
            String orderId,
            BigDecimal orderTotal,
            BigDecimal commissionAmount,
            String currency,
            String status,
            String settlementBatchId,
            Instant createdAt,
            Instant settledAt) {}

    public record AffiliateEarningsSummary(
            long thisMonthSales,
            BigDecimal thisMonthRevenue,
            BigDecimal thisMonthCommission,
            BigDecimal unsettledCommission,
            String currency) {}

    /** Combined list + SETTLED-vs-pending summary for {@code GET /creator/affiliate-earnings}. */
    public record CreatorAffiliateEarningsResponse(
            List<AffiliateEarningRow> earnings, AffiliateEarningsSummary summary) {}
}
