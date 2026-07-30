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
