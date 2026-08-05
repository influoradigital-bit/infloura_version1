package com.influora.web.dto.admin;

/**
 * DTOs for {@link com.influora.web.AdminRevenueController} — the admin revenue report ({@code
 * GET /admin/finance/revenue}). Separate class (not {@code AdminFinanceDtos}) purely to keep the
 * revenue feature self-contained.
 */
public final class AdminRevenueDtos {

    private AdminRevenueDtos() {}

    /**
     * One revenue bucket. Mirrors {@code RevenueSnapshot} in {@code src/admin/types/admin.types.ts}
     * ({@code financeApi.getRevenue}). All money figures are live aggregates, no fabrication:
     *
     * <ul>
     *   <li>{@code period} — the bucket label at the requested granularity ({@code yyyy-MM-dd} /
     *       {@code yyyy-'W'ww} / {@code yyyy-MM}).
     *   <li>{@code gmv} — sum of {@code EscrowHold.amount} for holds RELEASED within the bucket
     *       (bucketed by {@code released_at}). Gross value actually paid out to creators.
     *   <li>{@code platformFees} — sum of {@code PlatformCommissionInvoice.commissionAmount} issued
     *       within the bucket (bucketed by {@code created_at}). The platform's real take.
     *   <li>{@code setupFees} — always {@code 0.0}: Influora charges no setup fee (no such product or
     *       column exists). This is an honest structural zero, NOT a fabricated placeholder — there
     *       is genuinely nothing to sum. Kept in the shape only to match the frontend contract.
     *   <li>{@code totalRevenue} — {@code platformFees + setupFees} (i.e. {@code platformFees}).
     * </ul>
     */
    public record RevenueSnapshotDto(
            String period,
            double gmv,
            double platformFees,
            double setupFees,
            double totalRevenue) {}
}
