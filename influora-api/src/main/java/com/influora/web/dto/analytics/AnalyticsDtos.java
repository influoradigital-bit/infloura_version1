package com.influora.web.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    /**
     * Creator-facing audience demographics (P2-14, {@code GET /creator/analytics/me/demographics})
     * and, since Wave B task B4, the brand-facing mirror ({@code GET
     * /analytics/creators/{creatorId}/demographics}), mapped straight from the latest {@code
     * AudienceDemographics} snapshot (V25) — see that entity's javadoc for the raw {@code {bucket:
     * count}} JSON breakdown convention this mirrors field-for-field. Every breakdown may
     * legitimately be {@code null}/empty when Meta didn't return that dimension (e.g. accounts
     * under the 100+ follower threshold) — never back-filled with a fabricated value.
     *
     * <p>{@code hasData} distinguishes "a snapshot exists, dimensions may just be sparse" from "no
     * demographics snapshot has ever been computed for this creator yet" (e.g. Meta not connected,
     * or the polling job hasn't run) — the latter is a graceful, typed empty state ({@link
     * #empty()}), never a thrown 404. Never fabricate a fake breakdown to avoid returning {@code
     * hasData=false}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorDemographicsResponse(
            boolean hasData,
            Map<String, Long> ageGenderBreakdown,
            Map<String, Long> countryBreakdown,
            Map<String, Long> cityBreakdown,
            Map<String, Long> localeBreakdown,
            Instant fetchedAt) {

        public static CreatorDemographicsResponse empty() {
            return new CreatorDemographicsResponse(false, Map.of(), Map.of(), Map.of(), Map.of(), null);
        }
    }

    /**
     * Per-post performance — the latest {@code MediaMetric} poll snapshot for each distinct post,
     * newest posts first. Mirrors {@code MediaMetric}'s fields directly; the only derived value is
     * {@code engagementRate}. Shared verbatim by both the creator-self route ({@code GET
     * /creator/analytics/me/media}, P2-14) and the brand-facing mirror ({@code GET
     * /analytics/creators/{creatorId}/media}, brand-feature-audit.md fix #4) — same authorization
     * shape as {@link CreatorMetricsResponse}/{@link CreatorScoresResponse}/{@link
     * CreatorDemographicsResponse}.
     *
     * <p><b>{@code engagementRate}</b> [brand-feature-audit.md fix #4]: added for the brand route's
     * FE consumer ({@code ContentPerformanceItem} in {@code src/lib/api.ts}, rendered by
     * {@code ContentPerformancePanel} — see {@code useContentPerformance.ts}), which expects a rate,
     * not a raw engagement count. Computed as {@code engagement / reach * 100} when {@code reach} is
     * present and positive, else {@code null} — never fabricated, same "real value or null" contract
     * as every other derived field in this service. Purely additive: the creator-self route gains
     * this field too, but nothing there previously read a rate off this DTO, so no existing consumer
     * breaks.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentPerformanceResponse(
            String mediaId,
            String mediaType,
            String permalink,
            Long impressions,
            Long reach,
            Long engagement,
            Long likes,
            Long comments,
            Long saves,
            Long shares,
            Long videoViews,
            BigDecimal avgWatchTimeSeconds,
            Instant postedAt,
            BigDecimal engagementRate) {}
}
