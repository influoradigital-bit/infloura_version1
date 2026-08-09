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

    /**
     * Combined list + SETTLED-vs-pending summary for {@code GET /creator/affiliate-earnings}.
     *
     * <p>CR-83 — {@code earnings} is now one page of the creator's full history rather than
     * every row ever recorded; {@code page}/{@code limit}/{@code totalElements}/{@code hasMore}
     * let the client page through the rest. {@code summary} still reflects the creator's FULL
     * history (all-time unsettled commission, this-month totals) regardless of which page of
     * {@code earnings} is being viewed — pagination narrows the row list, not the summary.
     */
    public record CreatorAffiliateEarningsResponse(
            List<AffiliateEarningRow> earnings,
            AffiliateEarningsSummary summary,
            int page,
            int limit,
            long totalElements,
            boolean hasMore) {}
}
