package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.ContentPerformanceResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task #35 (P2-V6) — {@code GET /creator/analytics/me/*}. Principal-scoped mirror of {@link
 * AnalyticsController}'s metrics/scores/demographics reads; no {@code creatorId} path param.
 * Response DTOs match {@code ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md} §4 so existing brand hooks
 * ({@code useCreatorMetrics}, {@code useCreatorScores}) can be pointed here with a base-path swap.
 */
@RestController
@RequestMapping("/creator/analytics/me")
public class CreatorAnalyticsController {

    private final CreatorAnalyticsService creatorAnalyticsService;

    public CreatorAnalyticsController(CreatorAnalyticsService creatorAnalyticsService) {
        this.creatorAnalyticsService = creatorAnalyticsService;
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<CreatorMetricsResponse>> getMyMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Instant start = parseInstant(startDate, "startDate");
        Instant end = parseInstant(endDate, "endDate");
        return ResponseEntity.ok(
                ApiResponse.ok(creatorAnalyticsService.getMyMetrics(principal, start, end)));
    }

    @GetMapping("/scores")
    public ResponseEntity<ApiResponse<CreatorScoresResponse>> getMyScores(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(creatorAnalyticsService.getMyScores(principal)));
    }

    @GetMapping("/demographics")
    public ResponseEntity<ApiResponse<CreatorDemographicsResponse>> getMyDemographics(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorAnalyticsService.getMyDemographics(principal)));
    }

    /**
     * P2-14 — per-post content performance for the authenticated creator (self-service).
     * Replaces api.ts stub that expected {@code GET /analytics/creators/:id/media}.
     */
    @GetMapping("/media")
    public ResponseEntity<ApiResponse<List<ContentPerformanceResponse>>> getMyContentPerformance(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorAnalyticsService.getMyContentPerformance(principal)));
    }

    private static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_DATE_RANGE",
                    "Invalid " + paramName + " — must be an ISO-8601 instant",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
