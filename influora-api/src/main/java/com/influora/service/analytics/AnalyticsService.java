package com.influora.service.analytics;

import com.influora.common.ApiException;
import com.influora.common.CreatorScoreMath;
import com.influora.common.JsonLists;
import com.influora.domain.entity.AudienceDemographics;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.MetricsAuthorizationService;
import com.influora.web.dto.analytics.AnalyticsDtos.ContentPerformanceResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.MetricDataPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Recent per-post metric rows considered when building the content-performance list. */
    private static final int CONTENT_PERFORMANCE_LOOKBACK = 100;

    private final BrandContextService brandContext;
    private final MetricsAuthorizationService metricsAuthorizationService;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final CreatorScoreRepository creatorScoreRepository;
    private final AudienceDemographicsRepository audienceDemographicsRepository;
    private final MediaMetricsRepository mediaMetricsRepository;

    public AnalyticsService(
            BrandContextService brandContext,
            MetricsAuthorizationService metricsAuthorizationService,
            CreatorMetricsRepository creatorMetricsRepository,
            CreatorScoreRepository creatorScoreRepository,
            AudienceDemographicsRepository audienceDemographicsRepository,
            MediaMetricsRepository mediaMetricsRepository) {
        this.brandContext = brandContext;
        this.metricsAuthorizationService = metricsAuthorizationService;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.creatorScoreRepository = creatorScoreRepository;
        this.audienceDemographicsRepository = audienceDemographicsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
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
        return buildMetricsResponse(authorizedCreatorId, startDate, endDate);
    }

    /**
     * Creator-self mirror of {@link #getCreatorMetrics}. The caller ({@code
     * CreatorAnalyticsService}) has already resolved {@code creatorProfileId} to the authenticated
     * creator's own profile via {@code CreatorContextService.requireCreatorProfile}, so no brand
     * workspace/{@code MetricsAuthorizationService} gate applies here — a creator always owns their
     * own metrics.
     */
    @Transactional(readOnly = true)
    public CreatorMetricsResponse getCreatorMetricsForProfile(
            String creatorProfileId, Instant startDate, Instant endDate) {
        return buildMetricsResponse(creatorProfileId, startDate, endDate);
    }

    private CreatorMetricsResponse buildMetricsResponse(
            String authorizedCreatorId, Instant startDate, Instant endDate) {
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
     * Brand-facing latest creator score. The {@code brandSafetyScore}/{@code garmFlags}/{@code
     * contentSentiment} fields surface whatever {@code ScoreCalculationJob} persisted on the latest
     * score row: real values once brand-safety scoring has run for the creator (the flag-gated
     * {@code BrandSafetyScoreService} leg — see {@code BrandSafetyScoringProperties}), or {@code
     * null} when it has not been computed yet (frontend {@code BrandSafetyBadge} renders that as its
     * "not computed" state — a null is never a "scored safe" signal). Authorization is enforced
     * before any score row is read.
     */
    @Transactional(readOnly = true)
    public CreatorScoresResponse getCreatorScores(AuthPrincipal principal, String creatorId) {
        String workspaceId = brandContext.requireBrandWorkspace(principal).getId();
        String authorizedCreatorId =
                metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);
        return buildScoresResponse(authorizedCreatorId);
    }

    /**
     * Creator-self mirror of {@link #getCreatorScores} — see {@link #getCreatorMetricsForProfile}
     * javadoc for why no brand-authorization gate applies here.
     */
    @Transactional(readOnly = true)
    public CreatorScoresResponse getCreatorScoresForProfile(String creatorProfileId) {
        return buildScoresResponse(creatorProfileId);
    }

    private CreatorScoresResponse buildScoresResponse(String authorizedCreatorId) {
        CreatorScore score =
                creatorScoreRepository
                        .findFirstByCreatorProfileIdOrderByTimeDesc(authorizedCreatorId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SCORE_NOT_FOUND",
                                                "No computed score yet for this creator",
                                                HttpStatus.NOT_FOUND));

        // Priya ruling (BR-18, 2026-07-30): authenticityScore is the inverse of the raw
        // fake-follower suspicion score — see CreatorScoreMath#toAuthenticity javadoc. Same
        // derivation CreatorDiscoveryService#buildScores uses for discovery's CreatorScores, so
        // this brand-facing analytics endpoint doesn't serve an inverted value under a field name
        // that means the opposite. Null in (never scored) stays null out.
        return new CreatorScoresResponse(
                CreatorScoreMath.toAuthenticity(score.getFakeFollowerScore()),
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

    /**
     * Brand-facing audience demographics (Wave B task B4) — the brand-facing mirror of {@link
     * #getCreatorDemographicsForProfile}, same authorization shape as {@link #getCreatorMetrics}/
     * {@link #getCreatorScores} (resolve+authorize {@code creatorId} against the caller's brand
     * workspace before reading anything).
     */
    @Transactional(readOnly = true)
    public CreatorDemographicsResponse getCreatorDemographics(AuthPrincipal principal, String creatorId) {
        String workspaceId = brandContext.requireBrandWorkspace(principal).getId();
        String authorizedCreatorId =
                metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);
        return buildDemographicsResponse(authorizedCreatorId);
    }

    /**
     * Creator-self audience demographics — see {@link #getCreatorMetricsForProfile} javadoc for why
     * no brand-authorization gate applies here (the caller already resolved {@code
     * creatorProfileId} to the authenticated creator's own profile). Mirrors the {@code
     * PortfolioService#loadTopAudienceCities} pattern for decoding the raw JSON breakdown columns.
     */
    @Transactional(readOnly = true)
    public CreatorDemographicsResponse getCreatorDemographicsForProfile(String creatorProfileId) {
        return buildDemographicsResponse(creatorProfileId);
    }

    /**
     * [SEC: Vikram, P5 fix] Shared by both the brand-facing and creator-self demographics reads.
     * Previously threw {@code DEMOGRAPHICS_NOT_FOUND} (404) when no snapshot had been computed yet
     * (e.g. Meta not connected, polling job hasn't run) — inconsistent with {@link
     * CreatorDemographicsResponse}'s own designed graceful-empty ({@code hasData=false}) contract,
     * and a poor UX for a routine, expected "nothing yet" state on a read endpoint. Returns {@link
     * CreatorDemographicsResponse#empty()} instead — never a fabricated breakdown, just an honest
     * "no data yet" shape the caller can render as an empty state.
     */
    private CreatorDemographicsResponse buildDemographicsResponse(String authorizedCreatorId) {
        AudienceDemographics snapshot =
                audienceDemographicsRepository
                        .findFirstByCreatorProfileIdOrderByTimeDesc(authorizedCreatorId)
                        .orElse(null);
        if (snapshot == null) {
            return CreatorDemographicsResponse.empty();
        }

        return new CreatorDemographicsResponse(
                true,
                breakdownFromJson(snapshot.getAgeGenderBreakdownJson()),
                breakdownFromJson(snapshot.getCountryBreakdownJson()),
                breakdownFromJson(snapshot.getCityBreakdownJson()),
                breakdownFromJson(snapshot.getLocaleBreakdownJson()),
                snapshot.getFetchedAt());
    }

    /**
     * Brand-facing per-post content performance (brand-feature-audit.md fix #4 — {@code
     * AnalyticsController} had no {@code /{creatorId}/media} route at all; the FE's
     * {@code contentPerformance.list} call 404'd on every brand). Same authorization shape as
     * {@link #getCreatorMetrics}/{@link #getCreatorScores}/{@link #getCreatorDemographics}: resolve
     * the caller's brand workspace, route {@code creatorId} through {@link
     * MetricsAuthorizationService#resolveAuthorizedCreatorProfileId} BEFORE any {@link
     * MediaMetricsRepository} read, then delegate to the same builder the creator-self route uses.
     */
    @Transactional(readOnly = true)
    public List<ContentPerformanceResponse> getContentPerformance(
            AuthPrincipal principal, String creatorId) {
        String workspaceId = brandContext.requireBrandWorkspace(principal).getId();
        String authorizedCreatorId =
                metricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorId);
        return buildContentPerformanceResponse(authorizedCreatorId);
    }

    /**
     * Creator-self per-post performance — see {@link #getCreatorMetricsForProfile} javadoc for why
     * no brand-authorization gate applies here.
     */
    @Transactional(readOnly = true)
    public List<ContentPerformanceResponse> getContentPerformanceForProfile(String creatorProfileId) {
        return buildContentPerformanceResponse(creatorProfileId);
    }

    /**
     * Shared by both the brand-facing and creator-self content-performance reads. Pulls the most
     * recent {@link #CONTENT_PERFORMANCE_LOOKBACK} poll rows for the creator (across all posts) and
     * keeps only the latest poll per distinct {@code mediaId} (rows arrive newest-first, so the
     * first occurrence per media id wins) — never a fabricated aggregate, just the latest real
     * snapshot per post. {@code engagementRate} is derived per-row (see {@link
     * ContentPerformanceResponse} javadoc); left {@code null} rather than guessed when {@code reach}
     * is missing or zero.
     */
    private List<ContentPerformanceResponse> buildContentPerformanceResponse(String creatorProfileId) {
        List<MediaMetric> recent =
                mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        creatorProfileId, PageRequest.of(0, CONTENT_PERFORMANCE_LOOKBACK));

        Map<String, MediaMetric> latestByMediaId = new LinkedHashMap<>();
        for (MediaMetric metric : recent) {
            latestByMediaId.putIfAbsent(metric.getMediaId(), metric);
        }

        return latestByMediaId.values().stream()
                .map(
                        m ->
                                new ContentPerformanceResponse(
                                        m.getMediaId(),
                                        m.getMediaType(),
                                        m.getPermalink(),
                                        m.getImpressions(),
                                        m.getReach(),
                                        m.getEngagement(),
                                        m.getLikes(),
                                        m.getComments(),
                                        m.getSaves(),
                                        m.getShares(),
                                        m.getVideoViews(),
                                        m.getAvgWatchTimeSeconds(),
                                        m.getPostedAt(),
                                        engagementRate(m.getEngagement(), m.getReach())))
                .toList();
    }

    /** {@code engagement / reach * 100}, rounded to 2dp; {@code null} when reach is absent/zero. */
    private static BigDecimal engagementRate(Long engagement, Long reach) {
        if (engagement == null || reach == null || reach <= 0) {
            return null;
        }
        return BigDecimal.valueOf(engagement)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(reach), 2, RoundingMode.HALF_UP);
    }

    /** Raw {@code {bucket: count}} JSON -> Map, same unchecked-raw-type convention as {@code
     * PortfolioService#loadTopAudienceCities}. Never null — empty map when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, Long> breakdownFromJson(String json) {
        Map<String, Long> map = JsonLists.objectFromJson(json, Map.class);
        return map == null ? Map.of() : map;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
