package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.analytics.AnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task #35 (P2-V6) — creator-self analytics isolation + delegation to {@link AnalyticsService}. */
@ExtendWith(MockitoExtension.class)
class CreatorAnalyticsServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String CREATOR_PROFILE_A = "01HCREATORPROFILEA12";
    private static final String CREATOR_PROFILE_B = "01HCREATORPROFILEB12";

    @Mock private CreatorContextService creatorContext;
    @Mock private AnalyticsService analyticsService;
    @Mock private AuthPrincipal principal;

    private CreatorAnalyticsService service;
    private CreatorProfile creatorA;

    @BeforeEach
    void setUp() {
        service = new CreatorAnalyticsService(creatorContext, analyticsService);
        creatorA = CreatorProfile.newForUser(CREATOR_PROFILE_A, CREATOR_USER_ID, "Creator A");
    }

    @Test
    @DisplayName("getMyMetrics: scopes to authenticated creator profile — cross-creator isolation")
    void testGetMyMetricsCrossCreatorIsolation() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        CreatorMetricsResponse metrics =
                new CreatorMetricsResponse(
                        2000L, 3000L, 90L, new BigDecimal("4.50"), 150L, new BigDecimal("3000"), List.of());
        when(analyticsService.getCreatorMetricsForProfile(eq(CREATOR_PROFILE_A), isNull(), isNull()))
                .thenReturn(metrics);

        CreatorMetricsResponse result = service.getMyMetrics(principal, null, null);

        assertNotNull(result);
        assertEquals(2000L, result.totalReach());
        verify(analyticsService).getCreatorMetricsForProfile(CREATOR_PROFILE_A, null, null);
        verify(analyticsService, never()).getCreatorMetricsForProfile(eq(CREATOR_PROFILE_B), any(), any());
    }

    @Test
    @DisplayName("getMyScores: scopes to authenticated creator profile — cross-creator isolation")
    void testGetMyScoresCrossCreatorIsolation() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        Instant computedAt = Instant.parse("2026-07-05T00:00:00Z");
        CreatorScoresResponse scores =
                new CreatorScoresResponse(
                        new BigDecimal("0.95"),
                        List.of("low_bot_ratio"),
                        new BigDecimal("82"),
                        new BigDecimal("0.88"),
                        new BigDecimal("0.75"),
                        new BigDecimal("0.90"),
                        new BigDecimal("0.92"),
                        List.of("NONE"),
                        new BigDecimal("0.85"),
                        new BigDecimal("15000"),
                        new BigDecimal("25000"),
                        "INR",
                        new BigDecimal("0.85"),
                        "v2.1",
                        computedAt);
        when(analyticsService.getCreatorScoresForProfile(CREATOR_PROFILE_A)).thenReturn(scores);

        CreatorScoresResponse result = service.getMyScores(principal);

        assertEquals(new BigDecimal("82"), result.qualityScore());
        verify(analyticsService).getCreatorScoresForProfile(CREATOR_PROFILE_A);
        verify(analyticsService, never()).getCreatorScoresForProfile(CREATOR_PROFILE_B);
    }

    @Test
    @DisplayName("getMyDemographics: scopes to authenticated creator profile — cross-creator isolation")
    void testGetMyDemographicsCrossCreatorIsolation() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        when(analyticsService.getCreatorDemographicsForProfile(CREATOR_PROFILE_A))
                .thenReturn(CreatorDemographicsResponse.empty());

        CreatorDemographicsResponse result = service.getMyDemographics(principal);

        assertFalse(result.hasData());
        verify(analyticsService).getCreatorDemographicsForProfile(CREATOR_PROFILE_A);
        verify(analyticsService, never()).getCreatorDemographicsForProfile(CREATOR_PROFILE_B);
    }
}
