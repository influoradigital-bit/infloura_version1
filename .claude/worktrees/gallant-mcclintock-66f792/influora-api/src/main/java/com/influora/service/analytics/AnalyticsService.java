package com.influora.service.analytics;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorScore;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.MetricsAuthorizationService;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.MetricDataPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand-facing Analytics Read API service (wiki/decisions/2026-07-06-phase3-analytics-api-before-
 * brandsafety.md, LOCKED). This is the FIRST real caller of {@link MetricsAuthorizationService} —
 * every method here resolves the caller's workspace, then routes the caller-supplied {@code
 * creatorId} through {@link MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String,
 * String)} BEFORE any repository read. No finder in {@link CreatorMetricsRepository} / {@link
 * CreatorScoreRepository} may be reached with a bare, unauthorized {@code creatorProfileId}.
 *
 * <p>Mirrors the "resolve-then-scope" shape {@code DeliverableMetricService.getCampaignAnalytics}
 * uses for campaigns: {@link BrandContextService#requireBrandWorkspace(AuthPrincipal)} resolves a
 * trustworthy {@code workspaceId} from the authenticated principal (never trusting a client-
 * supplied workspace id), and only then is the per-creator authorization gate consulted.
 */
@Service
public class AnalyticsService {

    /** Most recent N per-platform metric rows considered "current" across platforms. */
    private static final int LATEST_METRICS_LOOKBACK = 20;

    private final BrandContextService brandContext;
    private final MetricsAuthorizationService metricsAuthorizationService;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final CreatorScoreRepository creatorScoreRepository;

    public AnalyticsService(
            BrandContextService brandContext,
            MetricsAuthorizationService metricsAuthorizationService,
            CreatorMetricsRepository creatorMetricsRepository,
            CreatorScoreRepository creatorScoreRepository) {
        this.brandContext = brandContext;
        this.metricsAuthorizationService = metricsAuthorizationService;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.creatorScoreRepository = creatorScoreRepository;
    }

    /**
     * Brand-facing creator metrics: the latest snapshot (across whichever platforms have been
     * polled) plus, when {@code startDate}/{@code endDate} are both supplied, a trend series built
     * from {@code CreatorMetric} rows in that window. Authorization is enforced before any metric
     * row is read.
     */
    @Transactional(readOnly = true)
    public CreatorMetricsResponse getCreatorMetrics(
            AuthPrincipal principal, String creatorId, Instant startDate, Instant endDate) {
        String workspaceId = brandContext.requireBrandWorkspace(principal).getId();
        String authorizedCreatorId =
                metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);

        List<CreatorMetric> latest =
                creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        authorizedCreatorId, PageRequest.of(0, LATEST_METRICS_LOOKBACK));

        long totalReach = 0;
        long totalImpressions = 0;
        long totalEngagements = 0;
        long totalFollowers = 0;
        BigDecimal engagementRate = null;
        BigDecimal avgViewsPerPost = null;
        long followerGrowth = 0;

        if (!latest.isEmpty()) {
            // "Latest" tile = most recent row overall (across platforms); see class/DTO javadoc —
            // this codebase has no defined multi-platform aggregation rule yet.
            CreatorMetric mostRecent = latest.get(0);
            totalFollowers = mostRecent.getFollowers();
            totalReach = nz(mostRecent.getAvgReachPerPost());
            totalImpressions = nz(mostRecent.getAvgImpressionsPerPost());
            engagementRate = mostRecent.getAvgEngagementRate();
            avgViewsPerPost =
                    mostRecent.getAvgImpressionsPerPost() != null
                            ? BigDecimal.valueOf(mostRecent.getAvgImpressionsPerPost())
                            : null;
            // totalEngagements is derived (never fabricated): reach * engagementRate% when both
            // are present, else left at 0 rather than guessing.
            if (mostRecent.getAvgReachPerPost() != null && engagementRate != null) {
                totalEngagements =
                        BigDecimal.valueOf(mostRecent.getAvgReachPerPost())
                                .multiply(engagementRate)
                                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                                .longValue();
            }

            // followerGrowth: delta between the most recent row and the oldest row still within the
            // lookback window (same platform), when at least two snapshots exist for that platform.
            List<CreatorMetric> samePlatformOldestFirst =
                    latest.stream()
                            .filter(m -> m.getPlatform().equals(mostRecent.getPlatform()))
                            .sorted(Comparator.comparing(CreatorMetric::getTime))
                            .toList();
            if (samePlatformOldestFirst.size() > 1) {
                followerGrowth =
                        mostRecent.getFollowers() - samePlatformOldestFirst.get(0).getFollowers();
            }
        }

        List<MetricDataPoint> trendData = List.of();
        if (startDate != null && endDate != null) {
            List<CreatorMetric> range =
                    creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(
                            authorizedCreatorId, startDate, endDate);
            trendData =
                    range.stream()
                            .map(
                                    m ->
                                            new MetricDataPoint(
                                                    DateTimeFormatter.ISO_INSTANT.format(m.getTime()),
                                                    m.getFollowers(),
                                                    nz(m.getAvgImpressionsPerPost()),
                                                    nz(m.getAvgReachPerPost()),
                                                    m.getAvgEngagementRate()))
                            .toList();
        }

        return new CreatorMetricsResponse(
                totalReach,
                totalImpressions,
                totalEngagements,
                engagementRate,
                followerGrowth,
                avgViewsPerPost,
                trendData);
    }

    /**
     * Brand-facing latest creator score. {@code brandSafetyScore}/{@code garmFlags}/{@code
     * contentSentiment} are null (BrandSafetyScoreService not built yet — see {@code CreatorScore}
     * javadoc). Authorization is enforced before any score row is read.
     */
    @Transactional(readOnly = true)
    public CreatorScoresResponse getCreatorScores(AuthPrincipal principal, String creatorId) {
        String workspaceId = brandContext.requireBrandWorkspace(principal).getId();
        String authorizedCreatorId =
                metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);

        CreatorScore score =
                creatorScoreRepository
                        .findFirstByCreatorProfileIdOrderByTimeDesc(authorizedCreatorId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SCORE_NOT_FOUND",
                                                "No computed score yet for this creator",
                                                HttpStatus.NOT_FOUND));

        return new CreatorScoresResponse(
                score.getFakeFollowerScore(),
                JsonLists.stringListFromJson(score.getFakeFollowerReasonsJson()).stream()
                        .collect(Collectors.toList()),
                score.getQualityScore(),
                score.getEngagementConsistency(),
                score.getPostingFrequency(),
                score.getAudienceMatchScore(),
                score.getBrandSafetyScore(),
                score.getGarmFlagsJson() == null
                        ? null
                        : JsonLists.stringListFromJson(score.getGarmFlagsJson()),
                score.getContentSentiment(),
                score.getEstimatedRateMin(),
                score.getEstimatedRateMax(),
                score.getRateCurrency(),
                score.getRateConfidence(),
                score.getAlgorithmVersion(),
                score.getComputedAt());
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
