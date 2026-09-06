package com.influora.web.dto.admin;

import java.time.LocalDate;
import java.util.List;

/**
 * Response records for {@code AdminFestivalMetricsController} (T-FESTIVALBOX-0905 phase 6). Raw
 * DTOs, unwrapped — same convention as every other {@code Admin*Controller} (see {@code
 * AdminFestivalEnquiryController} class javadoc).
 */
public final class AdminFestivalMetricsDtos {

    private AdminFestivalMetricsDtos() {}

    /**
     * GET /admin/festival-metrics/copies?edition= response. {@code sponsorTotals} is the
     * highest-copy-count-first leaderboard for the edition; {@code dailySeries} is every
     * (sponsor, day) point that has a row, in day order, for a time-series chart.
     *
     * <p>Both are read from {@code festival_coupon_copies} — an INTENT signal, never a sale. Kept
     * out of any DTO that also carries redemption/sales numbers, per the coupon-copy tracker's
     * design (see the V20260905170000 migration header): a copy count must never be presented next
     * to a revenue figure in a way that implies it is one.
     *
     * <p><b>[Kabir M-1/M-2] {@code measurementCaveat} travels with the numbers on purpose.</b>
     * These counts come from an unauthenticated public endpoint: a browser reports a tap and the
     * server increments. There is no visitor identity, no deduplication per person, and no way to
     * verify any individual tap — the client is not a trustworthy reporter and cannot be made one.
     * {@code FestivalCouponCopyService} caps how much any single origin can add per sponsor per
     * day, which makes casual forgery expensive, but a distributed caller still moves the number.
     *
     * <p>The risk this field exists to prevent is not technical. It is that a figure like "2,431
     * copies" reaches a sponsorship deck or an invoice as if it were audited, because nothing
     * between this table and that slide ever said otherwise. Shipping the caveat inside the
     * response means a client cannot render the number without having been handed the sentence
     * that qualifies it. If you are adding a UI for this: show it. Do not drop it because it makes
     * the number look weaker — that is precisely what it is for.
     */
    public record CouponCopyMetricsResponse(
            String edition,
            List<SponsorCopyTotal> sponsorTotals,
            List<DailyCopyPoint> dailySeries,
            String measurementCaveat) {

        /**
         * The one sentence that must accompany any presentation of these counts. A constant rather
         * than a caller-supplied string so it cannot be softened per-surface.
         */
        public static final String MEASUREMENT_CAVEAT =
                "Copy taps self-reported by the public page, not verified unique shoppers:"
                        + " not deduplicated per person, and capped per origin rather than"
                        + " authenticated. Treat as an intent trend, not an audited count.";

        public CouponCopyMetricsResponse(
                String edition, List<SponsorCopyTotal> sponsorTotals, List<DailyCopyPoint> dailySeries) {
            this(edition, sponsorTotals, dailySeries, MEASUREMENT_CAVEAT);
        }
    }

    public record SponsorCopyTotal(String sponsorSlug, long totalCopies) {}

    public record DailyCopyPoint(String sponsorSlug, LocalDate day, long copyCount) {}
}
