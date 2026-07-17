package com.influora.web.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Creator-reported deliverable/campaign analytics (P0 #3, brand-audit backend build task — Q9).
 *
 * <p><b>Honesty rule (non-negotiable):</b> every response record here carries
 * {@code source = "CREATOR_REPORTED"}. These numbers are self-declared by creators, never pulled
 * from a platform API. No frontend surface may present them as verified. Verified platform-API
 * integration is a separate, later effort.
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {}

    /** The one and only source value in this slice — never silently swapped for anything else. */
    public static final String SOURCE_CREATOR_REPORTED = "CREATOR_REPORTED";

    /** All fields optional — a creator may report a subset (e.g. reach only) at first. */
    public record DeliverableMetricSubmitRequest(
            Long reach, Long impressions, Long engagements, String link, String proofScreenshotR2Key) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliverableMetricResponse(
            String id,
            String milestoneId,
            String collaborationId,
            Long reach,
            Long impressions,
            Long engagements,
            String link,
            String proofScreenshotR2Key,
            String reportedByCreatorId,
            Instant reportedAt,
            String source) {}

    /**
     * Brand-facing aggregated campaign analytics. {@code derivedEngagementRate} is
     * {@code engagements / impressions} (as a percentage) when impressions are reported and
     * non-zero; {@code null} otherwise — never a divide-by-zero fabrication.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignAnalyticsResponse(
            String campaignId,
            long totalReach,
            long totalImpressions,
            long totalEngagements,
            BigDecimal derivedEngagementRate,
            int deliverablesReported,
            int deliverablesTotal,
            String source,
            List<DeliverableMetricResponse> deliverables) {}

    // ------------------------------------------------------------------------------------------
    // Brand-facing Analytics Read API (wiki/decisions/2026-07-06-phase3-analytics-api-before-
    // brandsafety.md, LOCKED). Every field here is sourced from Phase 2 CreatorMetric/MediaMetric
    // rows or the Phase 3 CreatorScore row, gated by MetricsAuthorizationService — see
    // AnalyticsService. Matches ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §4/§7 shapes exactly
    // (CreatorMetrics / CreatorScores / MetricDataPoint), field-for-field, so Ananya's existing
    // hooks (useCreatorMetrics.ts, useCreatorScores.ts) work against this with no changes.
    // ------------------------------------------------------------------------------------------

    /** One point in a metrics trend series (spec's {@code MetricDataPoint}). */
    public record MetricDataPoint(
            String date,
            long followers,
            long impressions,
            long reach,
            BigDecimal engagementRate) {}

    /**
     * Brand-facing creator metrics (spec's {@code CreatorMetrics}). {@code trendData} is populated
     * only when {@code startDate}/{@code endDate} were supplied to the endpoint; otherwise empty
     * (never fabricated). All aggregate fields reflect the single latest {@code CreatorMetric} row
     * across platforms (dashboard "current" tile) — this codebase does not yet aggregate multiple
     * platforms into one number, so "latest across platforms" is the closest available meaning of
     * "total" until a defined multi-platform aggregation rule exists.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorMetricsResponse(
            long totalReach,
            long totalImpressions,
            long totalEngagements,
            BigDecimal engagementRate,
            long followerGrowth,
            BigDecimal avgViewsPerPost,
            List<MetricDataPoint> trendData) {}

    /**
     * Brand-facing creator scores (spec's {@code CreatorScores}), mapped from the latest {@code
     * CreatorScore} row. {@code brandSafetyScore}/{@code garmFlags}/{@code contentSentiment} are
     * {@code null} — {@code BrandSafetyScoreService} is not built yet (see {@code CreatorScore}
     * class javadoc "Scope cut" note and the CTO ruling ADR this task implements). Frontend must
     * treat null as "not yet computed", never substitute a fake value.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorScoresResponse(
            BigDecimal authenticityScore,
            List<String> fakeFollowerReasons,
            BigDecimal qualityScore,
            BigDecimal engagementConsistency,
            BigDecimal postingFrequency,
            BigDecimal audienceMatchScore,
            BigDecimal brandSafetyScore,
            List<String> garmFlags,
            BigDecimal contentSentiment,
            BigDecimal estimatedRateMin,
            BigDecimal estimatedRateMax,
            String rateCurrency,
            BigDecimal rateConfidence,
            String algorithmVersion,
            Instant computedAt) {}
}
