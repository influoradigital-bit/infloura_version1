package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task #35 (P2-V6) — pure delegation tests for {@code GET /creator/analytics/me/*}. */
@ExtendWith(MockitoExtension.class)
class CreatorAnalyticsControllerTest {

    @Mock private CreatorAnalyticsService creatorAnalyticsService;
    @Mock private AuthPrincipal principal;

    private CreatorAnalyticsController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorAnalyticsController(creatorAnalyticsService);
    }

    @Test
    @DisplayName("GET /creator/analytics/me/metrics delegates to service and returns 200")
    void testGetMyMetrics() {
        CreatorMetricsResponse metrics =
                new CreatorMetricsResponse(
                        2000L, 3000L, 90L, new BigDecimal("4.50"), 150L, new BigDecimal("3000"), List.of());
        when(creatorAnalyticsService.getMyMetrics(principal, null, null)).thenReturn(metrics);

        ResponseEntity<ApiResponse<CreatorMetricsResponse>> response =
                controller.getMyMetrics(principal, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2000L, response.getBody().data().totalReach());
        verify(creatorAnalyticsService).getMyMetrics(principal, null, null);
    }

    @Test
    @DisplayName("GET /creator/analytics/me/scores delegates to service and returns 200")
    void testGetMyScores() {
        Instant computedAt = Instant.parse("2026-07-05T00:00:00Z");
        CreatorScoresResponse scores =
                new CreatorScoresResponse(
                        null,
                        List.of(),
                        new BigDecimal("80"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "INR",
                        null,
                        "v2",
                        computedAt);
        when(creatorAnalyticsService.getMyScores(principal)).thenReturn(scores);

        ResponseEntity<ApiResponse<CreatorScoresResponse>> response = controller.getMyScores(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new BigDecimal("80"), response.getBody().data().qualityScore());
        verify(creatorAnalyticsService).getMyScores(principal);
    }

    @Test
    @DisplayName("GET /creator/analytics/me/demographics delegates to service and returns 200")
    void testGetMyDemographics() {
        Instant fetchedAt = Instant.parse("2026-07-05T00:00:00Z");
        CreatorDemographicsResponse demographics =
                new CreatorDemographicsResponse(
                        true,
                        Map.of("F.25-34", 400L),
                        Map.of("IN", 1200L),
                        null,
                        null,
                        fetchedAt);
        when(creatorAnalyticsService.getMyDemographics(principal)).thenReturn(demographics);

        ResponseEntity<ApiResponse<CreatorDemographicsResponse>> response =
                controller.getMyDemographics(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().data().hasData());
        verify(creatorAnalyticsService).getMyDemographics(principal);
    }
}
