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
import com.influora.repository.CreatorAccountInsightRepository;
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
    @Mock private CreatorAccountInsightRepository accountInsightRepository;
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
                        mediaMetricsRepository,
                        accountInsightRepository);
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(10000)
                        .avgEngagementRate(new BigDecimal("4.50"))
                        .avgReachPerPost(2000L)
                        .avgImpressionsPerPost(3000L)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();

        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(12500)
                        .time(Instant.parse("2026-07-02T00:00:00Z"))
                        .build();
        CreatorMetric older =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890F50")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(12000)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        // The repository returns newest first.
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9000).time(Instant.parse("2026-08-01T06:00:00Z")).build();
        CreatorMetric windowEnd =
                CreatorMetric.builder().id("01HMETRICGROWTH0000002").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(10000).time(Instant.parse("2026-08-31T18:00:00Z")).build();
        CreatorMetric recentOlder =
                CreatorMetric.builder().id("01HMETRICGROWTH0000003").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9950).time(Instant.parse("2026-08-30T00:00:00Z")).build();
        // The 20-row lookback only reaches back ~5 days: it would report 10000 - 9950 = 50.
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(10000).time(Instant.parse("2026-08-31T18:00:00Z")).build();
        CreatorMetric beforeWindow =
                CreatorMetric.builder().id("01HMETRICGROWTH0000005").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(8000).time(Instant.parse("2026-08-10T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9000).time(Instant.parse("2026-08-02T00:00:00Z")).build();
        // A Meta-synced row of ANOTHER platform inside the same window (tests the platform filter;
        // the source filter is covered by the F-0961 tests below).
        CreatorMetric ytDeclared =
                CreatorMetric.builder().id("01HMETRICGROWTH0000007").creatorProfileId(CREATOR_ID)
                        .platform("YOUTUBE").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(250000).time(Instant.parse("2026-08-01T01:00:00Z")).build();
        CreatorMetric igLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000008").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9400).time(Instant.parse("2026-08-31T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9800).time(Instant.parse("2026-09-01T00:00:00Z")).build();
        CreatorMetric igFirst =
                CreatorMetric.builder().id("01HMETRICGROWTH0000010").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9000).time(Instant.parse("2026-07-02T00:00:00Z")).build();
        CreatorMetric igLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000011").creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(9300).time(Instant.parse("2026-07-20T00:00:00Z")).build();
        // The window's LAST row belongs to ANOTHER platform (tests the platform filter).
        CreatorMetric ytLast =
                CreatorMetric.builder().id("01HMETRICGROWTH0000012").creatorProfileId(CREATOR_ID)
                        .platform("YOUTUBE").dataSource(CreatorMetric.DATA_SOURCE_META_API).followers(250000).time(Instant.parse("2026-07-30T00:00:00Z")).build();
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(newestOverall));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(igFirst, igLast, ytLast));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        // Instagram only: 9300 - 9000. Using the unfiltered last row would give 250000 - 9000.
        assertEquals(300L, result.followerGrowth());
    }

    // ---- F-0961: creator-reported rows never become analytics figures ----

    private static CreatorMetric row(String id, String source, long followers, String at) {
        return CreatorMetric.builder()
                .id(id)
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .dataSource(source)
                .followers(followers)
                .avgReachPerPost(followers / 10)
                .avgImpressionsPerPost(followers / 5)
                .avgEngagementRate(BigDecimal.valueOf(followers % 97, 2))
                .time(Instant.parse(at))
                .build();
    }

    @Test
    @DisplayName("F-0961 a NEWER creator-reported row never becomes the headline follower count or reach")
    void testNewerCreatorReportedRowIsNotTheHeadline() {
        CreatorMetric declared =
                row("01HMETRICF0961000001", CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 500000,
                        "2026-09-10T00:00:00Z");
        CreatorMetric synced =
                row("01HMETRICF0961000002", CreatorMetric.DATA_SOURCE_META_API, 12000, "2026-09-09T00:00:00Z");
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(declared, synced));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, null, null);

        assertEquals(12000L, result.followers());
        assertEquals(1200L, result.totalReach());
        // Every headline field, not just followers, must come from the Meta row.
        assertEquals(2400L, result.totalImpressions());
        assertEquals(BigDecimal.valueOf(12000 % 97, 2), result.engagementRate());
        assertEquals(new BigDecimal(2400), result.avgViewsPerPost());
        // totalEngagements = followers x rate / 100, from the SAME Meta row (12000 x 0.69% = 83).
        assertEquals(83L, result.totalEngagements());
    }

    @Test
    @DisplayName("F-0961 creator-reported rows in the window are not growth or trend points")
    void testCreatorReportedRowsAreNotGrowthOrTrendPoints() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-31T23:59:59Z");
        CreatorMetric first =
                row("01HMETRICF0961000003", CreatorMetric.DATA_SOURCE_META_API, 9000, "2026-08-02T00:00:00Z");
        CreatorMetric declaredLast =
                row("01HMETRICF0961000004", CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 50000,
                        "2026-08-30T00:00:00Z");
        CreatorMetric last =
                row("01HMETRICF0961000005", CreatorMetric.DATA_SOURCE_META_API, 9500, "2026-08-20T00:00:00Z");
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(declaredLast, last, first));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(first, last, declaredLast));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        assertEquals(500L, result.followerGrowth());
        assertEquals(2, result.trendData().size());
        assertEquals(9500L, result.trendData().get(1).followers());
    }

    @Test
    @DisplayName("F-0961 growth without a window ignores a newer declared row")
    void testNoWindowGrowthIgnoresDeclaredRows() {
        CreatorMetric declared =
                row("01HMETRICF0961000007", CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 90000,
                        "2026-09-12T00:00:00Z");
        CreatorMetric newer =
                row("01HMETRICF0961000008", CreatorMetric.DATA_SOURCE_META_API, 12500, "2026-09-11T00:00:00Z");
        CreatorMetric older =
                row("01HMETRICF0961000009", CreatorMetric.DATA_SOURCE_META_API, 12000, "2026-09-01T00:00:00Z");
        // An OLDER declared row too: it must not become the baseline growth subtracts from.
        CreatorMetric declaredOld =
                row("01HMETRICF0961000013", CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 1000,
                        "2026-08-01T00:00:00Z");
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(declared, newer, older, declaredOld));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, null, null);

        assertEquals(500L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0961 the window's platform comes from the newest META row, not a newer declared row")
    void testWindowPlatformIsNotPickedFromADeclaredRow() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-31T23:59:59Z");
        CreatorMetric declaredYoutube =
                CreatorMetric.builder()
                        .id("01HMETRICF0961000010")
                        .creatorProfileId(CREATOR_ID)
                        .platform("YOUTUBE")
                        .dataSource(CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)
                        .followers(300000)
                        .time(Instant.parse("2026-09-15T00:00:00Z"))
                        .build();
        CreatorMetric igFirst =
                row("01HMETRICF0961000011", CreatorMetric.DATA_SOURCE_META_API, 9000, "2026-08-02T00:00:00Z");
        CreatorMetric igLast =
                row("01HMETRICF0961000012", CreatorMetric.DATA_SOURCE_META_API, 9600, "2026-08-25T00:00:00Z");
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(declaredYoutube, igLast, igFirst));
        when(creatorMetricsRepository.findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(CREATOR_ID, start, end))
                .thenReturn(List.of(igFirst, igLast));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, start, end);

        // Picking YOUTUBE from the declared row would leave no in-window rows and report 0.
        assertEquals(600L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0961 a creator with ONLY creator-reported rows gets the empty response")
    void testOnlyCreatorReportedRowsGiveTheEmptyResponse() {
        CreatorMetric declared =
                row("01HMETRICF0961000006", CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 80000,
                        "2026-09-10T00:00:00Z");
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
                .thenReturn(List.of(declared));

        CreatorMetricsResponse result = analyticsService.getCreatorMetricsForProfile(CREATOR_ID, null, null);

        assertEquals(0L, result.followers());
        assertEquals(0L, result.totalReach());
        assertEquals(0L, result.followerGrowth());
    }

    @Test
    @DisplayName("F-0951 getCreatorMetricsForProfile: followers is 0 before the first sync")
    void testFollowersIsZeroBeforeFirstSync() {
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(10000)
                        .avgEngagementRate(new BigDecimal("6.0000"))
                        .avgReachPerPost(30000L)
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(1000)
                        .avgEngagementRate(new BigDecimal("12.3556"))
                        .time(Instant.parse("2026-07-01T00:00:00Z"))
                        .build();
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(CREATOR_ID), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class)))
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
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        analyticsService.getCreatorMetrics(principal, CREATOR_ID, null, null);

        verify(creatorMetricsRepository)
                .findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq(resolvedId), eq(CreatorMetric.DATA_SOURCE_META_API), any(Pageable.class));
        verify(creatorMetricsRepository, never())
                .findByCreatorProfileIdAndDataSourceOrderByTimeDesc(eq(CREATOR_ID), any(), any(Pageable.class));
        // The unfiltered finder must not be used for the headline at all any more.
        verify(creatorMetricsRepository, never()).findByCreatorProfileIdOrderByTimeDesc(any(), any());
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

    // ------------------------------------------------------------------------------------------
    // Content performance — row order (F-1786). No caption on either route (ADR 2026-07-06).
    // ------------------------------------------------------------------------------------------

    private static MediaMetric post(
            String mediaId, Instant postedAt, Instant pollTime, String caption, Long reach) {
        return MediaMetric.builder()
                .id("01HMEDIA-" + mediaId + "-" + pollTime.getEpochSecond())
                .creatorProfileId(CREATOR_ID)
                .mediaId(mediaId)
                .mediaType("IMAGE")
                .caption(caption)
                .reach(reach)
                .engagement(10L)
                .postedAt(postedAt)
                .time(pollTime)
                .build();
    }

    @Test
    @DisplayName(
            "getContentPerformanceForProfile: posts from the SAME poll come out newest postedAt"
                    + " first regardless of repository order, null postedAt last, dedup still keeps"
                    + " each post's latest snapshot")
    void testContentPerformanceSortedByPostedAtDescNullsLast() {
        Instant samePoll = Instant.parse("2026-09-20T06:00:00Z");
        Instant olderPoll = Instant.parse("2026-09-19T06:00:00Z");

        // Repository order (poll time desc; ties in arbitrary DB order): OLDER post, then the
        // post with no postedAt, then the NEWEST post — deliberately not post order.
        MediaMetric olderPost =
                post("ig-old", Instant.parse("2026-09-01T10:00:00Z"), samePoll, "old caption", 100L);
        MediaMetric undatedPost = post("ig-undated", null, samePoll, "undated caption", 100L);
        MediaMetric newestPost =
                post("ig-new", Instant.parse("2026-09-15T10:00:00Z"), samePoll, "new caption", 100L);
        // A stale, earlier-poll snapshot of the newest post — the dedup must drop it (reach 999
        // would change engagementRate if it leaked through).
        MediaMetric staleNewestSnapshot =
                post("ig-new", Instant.parse("2026-09-15T10:00:00Z"), olderPoll, "stale", 999L);

        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(olderPost, undatedPost, newestPost, staleNewestSnapshot));

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformanceForProfile(CREATOR_ID);

        assertEquals(3, result.size());
        assertEquals("ig-new", result.get(0).mediaId());
        assertEquals("ig-old", result.get(1).mediaId());
        assertEquals("ig-undated", result.get(2).mediaId());
        assertEquals(null, result.get(2).postedAt());

        // Latest snapshot (reach 100) kept, not the stale one (reach 999): 10 / 100 * 100 = 10.00.
        assertEquals(new BigDecimal("10.00"), result.get(0).engagementRate());
    }

    @Test
    @DisplayName(
            "getContentPerformance (brand route): same postedAt-desc order. (No caption: the"
                    + " response type has no such field - NoBrandFacingCaptionExposureTest pins it.)")
    void testBrandContentPerformanceSortedByPostedAtDesc() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);

        Instant samePoll = Instant.parse("2026-09-20T06:00:00Z");
        MediaMetric undatedPost = post("ig-undated", null, samePoll, "undated caption", 100L);
        MediaMetric olderPost =
                post("ig-old", Instant.parse("2026-09-01T10:00:00Z"), samePoll, "old caption", 100L);
        MediaMetric newestPost =
                post("ig-new", Instant.parse("2026-09-15T10:00:00Z"), samePoll, "new caption", 100L);

        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(List.of(undatedPost, olderPost, newestPost));

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformance(principal, CREATOR_ID);

        assertEquals(3, result.size());
        assertEquals("ig-new", result.get(0).mediaId());
        assertEquals("ig-old", result.get(1).mediaId());
        assertEquals("ig-undated", result.get(2).mediaId());
    }

    // ------------------------------------------------------------------------------------------
    // Content performance — preview image (post thumbnails, 2026-09-22)
    // ------------------------------------------------------------------------------------------

    private static final String FRESH_PREVIEW =
            "https://scontent.cdninstagram.com/v/fresh.jpg?oe=6A1B2C3D&oh=00_fresh";
    private static final String STALE_PREVIEW =
            "https://scontent.cdninstagram.com/v/stale.jpg?oe=5A1B2C3D&oh=00_stale";

    /** The same post polled twice: newest-first, as the repository returns it. */
    private static List<MediaMetric> twoSnapshotsWithPreview() {
        MediaMetric latest =
                MediaMetric.builder()
                        .id("01HMEDIA-PREVIEW-LATEST00")
                        .creatorProfileId(CREATOR_ID)
                        .mediaId("ig-preview")
                        .mediaType("VIDEO")
                        .previewImageUrl(FRESH_PREVIEW)
                        .postedAt(Instant.parse("2026-09-15T10:00:00Z"))
                        .time(Instant.parse("2026-09-22T06:00:00Z"))
                        .build();
        MediaMetric older =
                MediaMetric.builder()
                        .id("01HMEDIA-PREVIEW-OLDER000")
                        .creatorProfileId(CREATOR_ID)
                        .mediaId("ig-preview")
                        .mediaType("VIDEO")
                        .previewImageUrl(STALE_PREVIEW)
                        .postedAt(Instant.parse("2026-09-15T10:00:00Z"))
                        .time(Instant.parse("2026-09-22T00:00:00Z"))
                        .build();
        return List.of(latest, older);
    }

    @Test
    @DisplayName(
            "getContentPerformanceForProfile (creator route): carries the LATEST poll's"
                    + " previewImageUrl, not an older (expired) one")
    void testCreatorContentPerformanceCarriesFreshPreviewImage() {
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(twoSnapshotsWithPreview());

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformanceForProfile(CREATOR_ID);

        assertEquals(1, result.size());
        assertEquals(FRESH_PREVIEW, result.get(0).previewImageUrl());
    }

    @Test
    @DisplayName(
            "getContentPerformance (brand route): previewImageUrl is null, pending the owner's"
                    + " ruling on brand visibility")
    void testBrandContentPerformanceNeverCarriesPreviewImage() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(metricsAuthorizationService.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_ID))
                .thenReturn(CREATOR_ID);
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(twoSnapshotsWithPreview());

        List<ContentPerformanceResponse> result =
                analyticsService.getContentPerformance(principal, CREATOR_ID);

        assertEquals(1, result.size());
        assertEquals("ig-preview", result.get(0).mediaId());
        assertEquals(null, result.get(0).previewImageUrl());
    }

    @Test
    @DisplayName("account insights: the newest snapshot, number for number; none -> hasData=false")
    void accountInsightsMapsNewestSnapshot() {
        com.influora.domain.entity.CreatorAccountInsight snapshot =
                new com.influora.domain.entity.CreatorAccountInsight(
                        "01HWACCOUNTINSIGHT000001", "creator-1", "INSTAGRAM",
                        java.time.LocalDate.of(2026, 8, 27), java.time.LocalDate.of(2026, 9, 23),
                        12400L, 48210L, null, 822L, 64L, "META_API", Instant.parse("2026-09-24T00:00:00Z"));
        when(accountInsightRepository.findFirstByCreatorProfileIdOrderByFetchedAtDesc("creator-1"))
                .thenReturn(Optional.of(snapshot));
        when(accountInsightRepository.findFirstByCreatorProfileIdOrderByFetchedAtDesc("creator-2"))
                .thenReturn(Optional.empty());

        var r = analyticsService.getCreatorAccountInsightsForProfile("creator-1");
        assertEquals(true, r.hasData());
        assertEquals(java.time.LocalDate.of(2026, 8, 27), r.periodStart());
        assertEquals(12400L, r.reach());
        assertEquals(48210L, r.views());
        assertEquals(null, r.totalInteractions());
        assertEquals(64L, r.profileLinksTaps());

        assertEquals(false, analyticsService.getCreatorAccountInsightsForProfile("creator-2").hasData());
    }
}
