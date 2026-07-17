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
import com.influora.domain.entity.Workspace;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.MetricsAuthorizationService;
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
        assertEquals(new BigDecimal("92.50"), result.authenticityScore());
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
}
