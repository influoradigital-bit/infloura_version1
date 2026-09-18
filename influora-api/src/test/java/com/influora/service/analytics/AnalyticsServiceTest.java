package com.influora.service.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.Workspace;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.MetricsAuthorizationService;
import com.influora.web.dto.analytics.AnalyticsDtos.ContentPerformanceResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link AnalyticsService} — the first real caller of {@link
 * MetricsAuthorizationService} (wiki/decisions/2026-07-06-phase3-analytics-api-before-
 * brandsafety.md). Priority: proving the isolation gate actually fires — an unauthorized
 * workspace/creator pair must be rejected with FORBIDDEN and no repository data must leak, and an
 * authorized pair must succeed. Mirrors {@code WalletServiceTest} conventions.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE1234567";
    private static final String CREATOR_ID = "01HWXYZCREATOR12345678";
    private static final String OTHER_WORKSPACES_CREATOR_ID = "01HWXYZOTHERCREATOR1234";

    @Mock private BrandContextService brandContext;
    @Mock private MetricsAuthorizationService metricsAuthorizationService;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private CreatorScoreRepository creatorScoreRepository;
    @Mock private AudienceDemographicsRepository audienceDemographicsRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private Workspace workspace;
    @Mock private AuthPrincipal principal;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService =
                new AnalyticsService(
                        brandContext,
                        metricsAuthorizationService,
                        creatorMetricsRepository,
                        creatorScoreRepository,
                        audienceDemographicsRepository,
                        mediaMetricsRepository);
    }

    // ------------------------------------------------------------------------------------------
    // getCreatorMetrics — authorization gate
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "getCreatorMetrics: unauthorized workspace/creator pair is rejected with FORBIDDEN before"
                    + " any metric row is read")
    void testGetCreatorMetricsRejectsUnauthorizedCreator() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(
                        WORKSPACE_ID, OTHER_WORKSPACES_CREATOR_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "This workspace is not authorized to view metrics for that creator",
                                HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                analyticsService.getCreatorMetrics(
                                        principal, OTHER_WORKSPACES_CREATOR_ID, null, null));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // The whole point of the gate: no data read happens once it throws.
        verifyNoInteractions(creatorMetricsRepository);
    }

    @Test
    @DisplayName("getCreatorMetrics: authorized pair succeeds and returns latest metrics")
    void testGetCreatorMetricsSucceedsForAuthorizedCreator() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890123")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(10000)
                        .avgEngagementRate(new BigDecimal("4.50"))
                        .avgReachPerPost(2000L)
                        .avgImpressionsPerPost(3000L)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();

        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(metric));

        CreatorMetricsResponse result =
                analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        assertNotNull(result);
        assertEquals(2000L, result.totalReach());
        assertEquals(3000L, result.totalImpressions());
        assertEquals(new BigDecimal("4.50"), result.engagementRate());
        // Authorization must have been consulted before the repository was ever queried.
        verify(metricsAuthorizationService).resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID);
    }

    @Test
    @DisplayName("F-0951 getCreatorMetrics: returns the NEWEST row's follower count, not an older one")
    void testGetCreatorMetricsReturnsNewestFollowerCount() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);
        CreatorMetric newest =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890F51")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(12500)
                        .time(Instant.parse("2026-07-02T00:00:00Z"))
                        .build();
        CreatorMetric older =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890F50")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(12000)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        // The repository returns newest first.
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(newest, older));

        CreatorMetricsResponse result =
                analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        assertEquals(12500L, result.followers());
        assertEquals(500L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0953 followerGrowth: with a date window, growth is measured inside that window")
    void testFollowerGrowthUsesTheRequestedWindow() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-31T23:59:59Z");
        CreatorMetric windowStart =
                CreatorMetric.builder().id("01HMETRICGROWTH0000001").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9000).time(Instant.parse("2026-08-01T06:00:00Z")).build();
        CreatorMetric windowEnd =
                CreatorMetric.builder().id("01HMETRICGROWTH0000002").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(10000).time(Instant.parse("2026-08-31T18:00:00Z")).build();
        CreatorMetric recentOlder =
                CreatorMetric.builder().id("01HMETRICGROWTH0000003").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9950).time(Instant.parse("2026-08-30T00:00:00Z")).build();
        // The 20-row lookback only reaches back ~5 days: it would report 10000 - 9950 = 50.
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(windowEnd, recentOlder));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(windowStart, recentOlder, windowEnd));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        assertEquals(1000L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0953 followerGrowth: one snapshot in the window is no measured change, not an older figure")
    void testFollowerGrowthIsZeroWithOneSnapshotInWindow() {
        Instant start = Instant.parse("2026-08-25T00:00:00Z");
        Instant end = Instant.parse("2026-08-31T23:59:59Z");
        CreatorMetric only =
                CreatorMetric.builder().id("01HMETRICGROWTH0000004").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(10000).time(Instant.parse("2026-08-31T18:00:00Z")).build();
        CreatorMetric beforeWindow =
                CreatorMetric.builder().id("01HMETRICGROWTH0000005").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(8000).time(Instant.parse("2026-08-10T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(only, beforeWindow));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(only));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        assertEquals(0L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0953 followerGrowth: other platforms' rows in the window never mix into the delta")
    void testFollowerGrowthIgnoresOtherPlatformsInWindow() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-31T23:59:59Z");
        CreatorMetric igFirst =
                CreatorMetric.builder().id("01HMETRICGROWTH0000006").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9000).time(Instant.parse("2026-08-02T00:00:00Z")).build();
        // A creator-declared YouTube row (PortfolioService writes these) inside the same window.
        CreatorMetric ytDeclared =
                CreatorMetric.builder().id("01HMETRICGROWTH0000007").creatorProfileId(CREATOR_ID)
                        .platform("YOUTUBE").followers(250000).time(Instant.parse("2026-08-01T01:00:00Z")).build();
        CreatorMetric igLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000008").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9400).time(Instant.parse("2026-08-31T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(igLast, igFirst, ytDeclared));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(ytDeclared, igFirst, igLast));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        // Instagram only: 9400 - 9000. Mixing platforms would give 9400 - 250000.
        assertEquals(400L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0953 followerGrowth: a window ENDING on another platform's row still measures one platform")
    void testFollowerGrowthIgnoresOtherPlatformAsLastPointInWindow() {
        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-31T23:59:59Z");
        CreatorMetric newestOverall =
                CreatorMetric.builder().id("01HMETRICGROWTH0000009").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9800).time(Instant.parse("2026-09-01T00:00:00Z")).build();
        CreatorMetric igFirst =
                CreatorMetric.builder().id("01HMETRICGROWTH0000010").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9000).time(Instant.parse("2026-07-02T00:00:00Z")).build();
        CreatorMetric igLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000011").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").followers(9300).time(Instant.parse("2026-07-20T00:00:00Z")).build();
        // The window's LAST row is a creator-declared YouTube row.
        CreatorMetric ytLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000012").creatorProfileId(CREATOR_ID)
                        .platform("YOUTUBE").followers(250000).time(Instant.parse("2026-07-30T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(newestOverall));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(igFirst, igLast, ytLast));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        // Instagram only: 9300 - 9000. Using the unfiltered last row would give 250000 - 9000.
        assertEquals(300L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0951 getCreatorMetricsForProfile: followers is 0 before the first sync")
    void testFollowersIsZeroBeforeFirstSync() {
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of());

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, null, null);

        assertEquals(0L, result.followers());
    }

    @Test
    @DisplayName("getCreatorMetrics: totalEngagements scales the rate by followers, not by reach")
    void testGetCreatorMetricsTotalEngagementsUsesFollowersNotReach() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);
        // 10,000 followers averaging 600 likes+comments per post -> stored rate 6.0. Reach 30,000:
        // reach * rate would report 1,800.
        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890124")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(10000)
                        .avgEngagementRate(new BigDecimal("6.0000"))
                        .avgReachPerPost(30000L)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(metric));

        CreatorMetricsResponse result =
                analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        assertEquals(600L, result.totalEngagements());
    }

    @Test
    @DisplayName("getCreatorMetrics: totalEngagements rounds half up and needs no reach")
    void testGetCreatorMetricsTotalEngagementsRoundsWithoutReach() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);
        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890125")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(1000)
                        .avgEngagementRate(new BigDecimal("12.3556"))
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(metric));

        CreatorMetricsResponse result =
                analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        assertEquals(124L, result.totalEngagements());
    }

    @Test
    @DisplayName("getCreatorMetrics: never calls repository with the raw caller-supplied creatorId")
    void testGetCreatorMetricsNeverPassesRawCreatorIdWhenAuthorizationRemapsIt() {
        // MetricsAuthorizationService is the ONLY source of the id passed to the repository — even
        // if it returns a different (but equal-in-this-case) id, the service must use exactly what
        // authorization returned, not the path-variable value directly.
        String resolvedId = "01HRESOLVEDID123456789";
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(resolvedId);
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        verify(creatorMetricsRepository)
                .findByCreatorProfileIdOrderByTimeDesc(eq(resolvedId), any(Pageable.class));
        verify(creatorMetricsRepository, never())
                .findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class));
    }

    // ------------------------------------------------------------------------------------------
    // getCreatorScores — authorization gate
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "getCreatorScores: unauthorized workspace/creator pair is rejected with FORBIDDEN before"
                    + " any score row is read")
    void testGetCreatorScoresRejectsUnauthorizedCreator() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(
                        WORKSPACE_ID, OTHER_WORKSPACES_CREATOR_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "This workspace is not authorized to view metrics for that creator",
                                HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> analyticsService.getCreatorScores(principal, OTHER_WORKSPACES_CREATOR_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(creatorScoreRepository);
    }

    @Test
    @DisplayName(
            "getCreatorScores: authorized pair succeeds; brand-safety fields are null, never fabricated")
    void testGetCreatorScoresSucceedsForAuthorizedCreatorWithNullBrandSafety() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);

        CreatorScore score =
                CreatorScore.builder()
                        .id("01HSCORE12345678901234")
                        .creatorProfileId(CREATOR_ID)
                        .fakeFollowerScore(new BigDecimal("92.50"))
                        .qualityScore(new BigDecimal("81.00"))
                        .engagementConsistency(new BigDecimal("75.00"))
                        .postingFrequency(new BigDecimal("60.00"))
                        .audienceMatchScore(new BigDecimal("70.00"))
                        .estimatedRateMin(new BigDecimal("5000.00"))
                        .estimatedRateMax(new BigDecimal("8000.00"))
                        .rateConfidence(new BigDecimal("65.00"))
                        .algorithmVersion("v1")
                        .build();

        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID))
                .thenReturn(Optional.of(score));

        CreatorScoresResponse result = analyticsService.getCreatorScores(principal, CREATOR_ID);

        assertNotNull(result);
        // BR-18 fix (Priya ruling, 2026-07-30): authenticityScore = 100 - fakeFollowerScore, not
        // the raw suspicion score. fakeFollowerScore is stubbed 92.50 above, so authenticity is
        // 7.50 (see CreatorScoreMath#toAuthenticity). This assertion previously expected 92.50
        // straight through, asserting the pre-fix inverted-semantics bug.
        assertEquals(new BigDecimal("7.50"), result.authenticityScore());
        assertEquals(new BigDecimal("81.00"), result.qualityScore());
        // BrandSafetyScoreService not built yet — must be null, never a fake/synthetic value.
        assertEquals(null, result.brandSafetyScore());
        assertEquals(null, result.garmFlags());
        assertEquals(null, result.contentSentiment());
        verify(metricsAuthorizationService).resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID);
    }

    @Test
    @DisplayName("getCreatorScores: no computed score yet returns SCORE_NOT_FOUND, not empty/fabricated data")
    void testGetCreatorScoresThrowsWhenNoScoreComputedYet() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> analyticsService.getCreatorScores(principal, CREATOR_ID));

        assertEquals("SCORE_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // ------------------------------------------------------------------------------------------
    // getContentPerformance — brand-feature-audit.md fix #4 (new brand-facing /media route)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "getContentPerformance: unauthorized workspace/creator pair (foreign creator) is rejected"
                    + " with FORBIDDEN before any MediaMetric row is read")
    void testGetContentPerformanceRejectsUnauthorizedCreator() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(
                        WORKSPACE_ID, OTHER_WORKSPACES_CREATOR_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "This workspace is not authorized to view metrics for that creator",
                                HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                analyticsService.getContentPerformance(
                                        principal, OTHER_WORKSPACES_CREATOR_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(mediaMetricsRepository);
    }

    @Test
    @DisplayName(
            "getContentPerformance: authorized creator returns per-post rows with a derived"
                    + " engagementRate")
    void testGetContentPerformanceSucceedsForAuthorizedCreator() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);

        MediaMetric media =
                MediaMetric.builder()
                        .id("01HMEDIA123456789012345")
                        .creatorProfileId(CREATOR_ID)
                        .mediaId("ig-media-1")
                        .mediaType("REEL")
                        .permalink("https://instagram.com/p/abc123")
                        .impressions(5000L)
                        .reach(4000L)
                        .engagement(200L)
                        .postedAt(Instant.parse("2026-07-10T00:00:00Z"))
                        .time(Instant.parse("2026-07-11T00:00:00Z"))
                        .build();

        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(media));

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformance(principal, CREATOR_ID);

        assertEquals(1, result.size());
        ContentPerformanceResponse row = result.get(0);
        assertEquals("ig-media-1", row.mediaId());
        assertEquals("REEL", row.mediaType());
        assertEquals(4000L, row.reach());
        assertEquals(5000L, row.impressions());
        // 200 / 4000 * 100 = 5.00 — derived, never fabricated.
        assertEquals(new BigDecimal("5.00"), row.engagementRate());
        verify(metricsAuthorizationService).resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID);
        verify(mediaMetricsRepository)
                .findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("getContentPerformance: engagementRate is null (not zero/guessed) when reach is missing")
    void testGetContentPerformanceEngagementRateNullWhenReachMissing() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);

        MediaMetric media =
                MediaMetric.builder()
                        .id("01HMEDIA223456789012345")
                        .creatorProfileId(CREATOR_ID)
                        .mediaId("ig-media-2")
                        .mediaType("IMAGE")
                        .engagement(50L)
                        .reach(null)
                        .time(Instant.parse("2026-07-11T00:00:00Z"))
                        .build();

        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(media));

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformance(principal, CREATOR_ID);

        assertEquals(1, result.size());
        assertEquals(null, result.get(0).engagementRate());
    }
}
