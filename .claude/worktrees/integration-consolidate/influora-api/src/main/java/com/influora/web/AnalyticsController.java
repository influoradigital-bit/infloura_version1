package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.analytics.AnalyticsService;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorMetricsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorScoresResponse;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing Analytics Read API (wiki/decisions/2026-07-06-phase3-analytics-api-before-
 * brandsafety.md, LOCKED). Exposes Phase 2 creator/media metrics and Phase 3 creator scores over
 * REST for the first time — endpoint contract matches
 * {@code ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md} §4 exactly so Ananya's existing hooks
 * ({@code useCreatorMetrics}, {@code useCreatorScores}) work against this with no changes.
 *
 * <p><b>Security (load-bearing — Kabir review):</b> every read here goes through {@link
 * AnalyticsService}, which calls {@code MetricsAuthorizationService.resolveAuthorizedCreatorProfileId}
 * before touching any repository. This controller never passes {@code creatorId} directly to a
 * repository — see {@link AnalyticsService} javadoc.
 *
 * <p><b>[SEC: Vikram, P5 fix, Wave B task B4] Demographics endpoint — now implemented.</b> This
 * class javadoc previously documented the spec's {@code GET
 * /analytics/creators/{creatorId}/demographics} as deliberately omitted because "no corresponding
 * persisted entity/table" existed for {@code AudienceDemographics}. That claim went stale: {@code
 * AudienceDemographics} (V25) and its repository were built for the creator-self path ({@code
 * CreatorAnalyticsController#getMyDemographics}) but this brand-facing mirror was never added to
 * match — {@link #getDemographics} below closes that gap, reusing the exact same {@link
 * AnalyticsService}/{@code MetricsAuthorizationService} authorization gate as {@link #getMetrics}/
 * {@link #getScores}. Still never fabricates a value: when no snapshot has been computed yet, the
 * service returns a typed, graceful empty shape ({@code CreatorDemographicsResponse#empty()},
 * {@code hasData=false}) — never a synthetic breakdown, never a 404 for a routine "nothing yet"
 * state.
 */
@RestController
@RequestMapping("/analytics/creators")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Latest creator metrics, plus a trend series when both {@code startDate} and {@code endDate}
     * are supplied (ISO-8601 instants, e.g. {@code 2026-06-01T00:00:00Z}).
     */
    @GetMapping("/{creatorId}/metrics")
    public ResponseEntity<ApiResponse<CreatorMetricsResponse>> getMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String creatorId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Instant start = parseInstant(startDate, "startDate");
        Instant end = parseInstant(endDate, "endDate");
        return ResponseEntity.ok(
                ApiResponse.ok(analyticsService.getCreatorMetrics(principal, creatorId, start, end)));
    }

    /** Latest computed score row for the creator. See class javadoc for brand-safety null fields. */
    @GetMapping("/{creatorId}/scores")
    public ResponseEntity<ApiResponse<CreatorScoresResponse>> getScores(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String creatorId) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getCreatorScores(principal, creatorId)));
    }

    /**
     * [SEC: Vikram, P5 fix, Wave B task B4] Brand-facing audience demographics — see class javadoc.
     * Returns a graceful, typed empty shape (never a 404) when no snapshot has been computed yet.
     */
    @GetMapping("/{creatorId}/demographics")
    public ResponseEntity<ApiResponse<CreatorDemographicsResponse>> getDemographics(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String creatorId) {
        return ResponseEntity.ok(
                ApiResponse.ok(analyticsService.getCreatorDemographics(principal, creatorId)));
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
