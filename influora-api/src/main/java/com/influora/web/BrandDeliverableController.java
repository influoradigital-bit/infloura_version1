package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandDeliverableService;
import com.influora.service.DeliverableSafetyReviewService;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableDetailResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviewResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviseRequest;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.DeliverableSafetyReviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand deliverable review (Week 3 P0 Task #21 — {@code api.ts} {@code deliverables.approve} /
 * {@code deliverables.requestRevision}). Shares {@code /deliverables} base path with {@link
 * DeliverableMetricController}; routes are disjoint ({@code /{id}/approve}, {@code /{id}/revise}).
 */
@RestController
@RequestMapping("/deliverables")
public class BrandDeliverableController {

    private final BrandDeliverableService brandDeliverableService;
    private final DeliverableSafetyReviewService deliverableSafetyReviewService;

    public BrandDeliverableController(
            BrandDeliverableService brandDeliverableService,
            DeliverableSafetyReviewService deliverableSafetyReviewService) {
        this.brandDeliverableService = brandDeliverableService;
        this.deliverableSafetyReviewService = deliverableSafetyReviewService;
    }

    @GetMapping("/{deliverableId}")
    public ResponseEntity<ApiResponse<DeliverableDetailResponse>> getDetail(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(brandDeliverableService.getDetail(principal, deliverableId)));
    }

    @PostMapping("/{deliverableId}/approve")
    public ResponseEntity<ApiResponse<ReviewResponse>> approve(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(brandDeliverableService.approve(principal, deliverableId)));
    }

    @PostMapping("/{deliverableId}/revise")
    public ResponseEntity<ApiResponse<ReviewResponse>> revise(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody ReviseRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        brandDeliverableService.requestRevision(principal, deliverableId, body)));
    }

    /** B6 — the missing route for {@link BrandDeliverableService#reject}. */
    @PostMapping("/{deliverableId}/reject")
    public ResponseEntity<ApiResponse<ReviewResponse>> reject(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody ReviseRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(brandDeliverableService.reject(principal, deliverableId, body)));
    }

    /**
     * [Fix: brand-feature-audit.md #3] Advisory-only brand-safety review of this deliverable's
     * caption — see {@link DeliverableSafetyReviewService} class javadoc for the full contract
     * (server-interpreted verdict, never blocks {@link #approve}/{@link #reject}/{@link #revise}).
     * Matches the FE's documented expectation ({@code api.contentPerformance}'s sibling in {@code
     * src/lib/api.ts}: {@code deliverables.getSafetyReview}, {@code GET
     * /deliverables/:id/safety-review}).
     */
    @GetMapping("/{deliverableId}/safety-review")
    public ResponseEntity<ApiResponse<DeliverableSafetyReviewResponse>> getSafetyReview(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(deliverableSafetyReviewService.getReview(principal, deliverableId)));
    }
}
