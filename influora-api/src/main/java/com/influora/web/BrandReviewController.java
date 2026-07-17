package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.ReviewService;
import com.influora.web.dto.review.ReviewDtos.CreateReviewRequest;
import com.influora.web.dto.review.ReviewDtos.FlagReviewRequest;
import com.influora.web.dto.review.ReviewDtos.FlagReviewResponse;
import com.influora.web.dto.review.ReviewDtos.ReviewResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task #29 — brand rates creator post-{@code COMPLETED} (CEO §1.2). Identity resolved inside
 * {@link ReviewService} via {@link com.influora.service.BrandContextService}.
 */
@RestController
@RequestMapping("/brand/reviews")
public class BrandReviewController {

    private final ReviewService reviewService;

    public BrandReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReviewResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateReviewRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(reviewService.createBrandReview(principal, body)));
    }

    /** P2-14 — reviews creators left about this brand workspace (matches api.ts:1630). */
    @GetMapping("/received")
    public ResponseEntity<ApiResponse<List<ReviewResponse>>> listReceived(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.listReceivedByBrand(principal)));
    }

    @PostMapping("/{reviewId}/flag")
    public ResponseEntity<ApiResponse<FlagReviewResponse>> flag(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String reviewId,
            @Valid @RequestBody FlagReviewRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(reviewService.flagBrandReview(principal, reviewId, body)));
    }
}
