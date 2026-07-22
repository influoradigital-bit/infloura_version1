package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.analytics.AnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.ContentPerformanceResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
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

/**
 * Wave B task B4: unit test for {@link AnalyticsController#getDemographics} — plain Java call
 * against a mocked {@link AnalyticsService}, same convention as {@code
 * ConversionWebhookControllerTest} (no MockMvc/spring-security-test harness in this codebase yet,
 * see {@code wiki/tech/REMAINING_WORK_PLAN.md} E3). Focus: the controller passes the principal and
 * path-variable creatorId straight through to the service (all authorization logic lives in {@code
 * AnalyticsService}/{@code MetricsAuthorizationService} — see their tests for the isolation
 * coverage) and wraps whatever the service returns — including the graceful empty shape — in a
 * 200 {@code ApiResponse}, never synthesizing its own error/placeholder response.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR000000001";

    @Mock private AnalyticsService analyticsService;
    @Mock private AuthPrincipal principal;

    private AnalyticsController controller;

    @BeforeEach
    void setUp() {
        controller = new AnalyticsController(analyticsService);
    }

    @Test
    @DisplayName("getDemographics: delegates to AnalyticsService with the principal + path creatorId, returns 200 + DTO as-is")
    void testGetDemographicsReturnsServiceResponseWrappedInApiResponse() {
        Instant fetchedAt = Instant.parse("2026-07-05T00:00:00Z");
        CreatorDemographicsResponse serviceResponse =
                new CreatorDemographicsResponse(
                        true,
                        Map.of("F.25-34", 400L),
                        Map.of("US", 1200L),
                        Map.of("New York, NY", 210L),
                        Map.of("en_US", 900L),
                        fetchedAt);

        when(analyticsService.getCreatorDemographics(principal, CREATOR_ID)).thenReturn(serviceResponse);

        ResponseEntity<ApiResponse<CreatorDemographicsResponse>> response =
                controller.getDemographics(principal, CREATOR_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().data().hasData());
        assertEquals(400L, response.getBody().data().ageGenderBreakdown().get("F.25-34"));
        assertEquals(fetchedAt, response.getBody().data().fetchedAt());
        verify(analyticsService).getCreatorDemographics(principal, CREATOR_ID);
    }

    @Test
    @DisplayName("getDemographics: graceful empty response (no snapshot yet) is passed through as-is, not altered into an error")
    void testGetDemographicsPassesThroughGracefulEmptyResponse() {
        when(analyticsService.getCreatorDemographics(principal, CREATOR_ID))
                .thenReturn(CreatorDemographicsResponse.empty());

        ResponseEntity<ApiResponse<CreatorDemographicsResponse>> response =
                controller.getDemographics(principal, CREATOR_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().data().hasData());
    }

    // ------------------------------------------------------------------------------------------
    // getContentPerformance — brand-feature-audit.md fix #4 (new /{creatorId}/media route)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "getContentPerformance: authorized creator — delegates to AnalyticsService, returns 200"
                    + " + rows as-is")
    void testGetContentPerformanceReturnsServiceResponseWrappedInApiResponse() {
        ContentPerformanceResponse row =
                new ContentPerformanceResponse(
                        "ig-media-1", "REEL", "https://instagram.com/p/abc123",
                        5000L, 4000L, 200L, 150L, 20L, 10L, 5L, null, null,
                        Instant.parse("2026-07-10T00:00:00Z"), new BigDecimal("5.00"));
        when(analyticsService.getContentPerformance(principal, CREATOR_ID)).thenReturn(List.of(row));

        ResponseEntity<ApiResponse<List<ContentPerformanceResponse>>> response =
                controller.getContentPerformance(principal, CREATOR_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().data().size());
        assertEquals("ig-media-1", response.getBody().data().get(0).mediaId());
        assertEquals(new BigDecimal("5.00"), response.getBody().data().get(0).engagementRate());
        verify(analyticsService).getContentPerformance(principal, CREATOR_ID);
    }

    @Test
    @DisplayName(
            "getContentPerformance: foreign creator — the FORBIDDEN thrown by AnalyticsService's"
                    + " authorization gate propagates untouched, controller adds no fallback data")
    void testGetContentPerformancePropagatesForbiddenForForeignCreator() {
        when(analyticsService.getContentPerformance(principal, CREATOR_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "This workspace is not authorized to view metrics for that creator",
                                HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.getContentPerformance(principal, CREATOR_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
