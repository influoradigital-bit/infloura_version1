package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.DeliverableMetricService;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricSubmitRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator-reported deliverable metrics (P0 #3, brand-audit backend build task — Q9). Every
 * number here is self-declared by the creator; the response always carries
 * {@code source: "CREATOR_REPORTED"} (see {@code AnalyticsDtos}). Verified platform-API
 * integration is a separate, later effort.
 */
@RestController
@RequestMapping("/deliverables")
public class DeliverableMetricController {

    private final DeliverableMetricService deliverableMetricService;

    public DeliverableMetricController(DeliverableMetricService deliverableMetricService) {
        this.deliverableMetricService = deliverableMetricService;
    }

    /**
     * Submits or updates the reporting creator's own metrics for a deliverable (payment
     * milestone). Idempotent-by-overwrite: calling again replaces the prior report and refreshes
     * {@code reportedAt}.
     */
    @PutMapping("/{milestoneId}/metrics")
    public ResponseEntity<ApiResponse<DeliverableMetricResponse>> submit(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String milestoneId,
            @Valid @RequestBody DeliverableMetricSubmitRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(deliverableMetricService.submit(principal, milestoneId, body)));
    }
}
