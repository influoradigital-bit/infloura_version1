package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandDeliverableService;
import com.influora.service.DeliverableSafetyReviewService;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableDetailResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableFileDetail;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviewResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviseRequest;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.DeliverableSafetyReviewResponse;
import com.influora.web.dto.deliverable.DeliverableSafetyDtos.SafetyVerdict;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/** Week 3 P0 Task #21 — controller delegation tests for brand deliverable review. */
@ExtendWith(MockitoExtension.class)
class BrandDeliverableControllerTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";

    @Mock private BrandDeliverableService brandDeliverableService;
    @Mock private DeliverableSafetyReviewService deliverableSafetyReviewService;
    @Mock private AuthPrincipal principal;

    private BrandDeliverableController controller;

    @BeforeEach
    void setUp() {
        controller =
                new BrandDeliverableController(brandDeliverableService, deliverableSafetyReviewService);
    }

    @Test
    @DisplayName("POST /deliverables/{id}/approve delegates to service")
    void testApprove() {
        when(brandDeliverableService.approve(eq(principal), eq(DELIVERABLE_ID)))
                .thenReturn(new ReviewResponse(DeliverableStatus.APPROVED));

        ResponseEntity<ApiResponse<ReviewResponse>> response =
                controller.approve(principal, DELIVERABLE_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DeliverableStatus.APPROVED, response.getBody().data().status());
        verify(brandDeliverableService).approve(principal, DELIVERABLE_ID);
    }

    @Test
    @DisplayName("POST /deliverables/{id}/revise delegates to service")
    void testRevise() {
        ReviseRequest body = new ReviseRequest("Please fix the intro");
        when(brandDeliverableService.requestRevision(eq(principal), eq(DELIVERABLE_ID), eq(body)))
                .thenReturn(new ReviewResponse(DeliverableStatus.REVISION_REQUESTED));

        ResponseEntity<ApiResponse<ReviewResponse>> response =
                controller.revise(principal, DELIVERABLE_ID, body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DeliverableStatus.REVISION_REQUESTED, response.getBody().data().status());
        verify(brandDeliverableService).requestRevision(principal, DELIVERABLE_ID, body);
    }

    @Test
    @DisplayName("GET /deliverables/{id} delegates to service and returns file URLs (DPF-1)")
    void testGetDetail() {
        DeliverableDetailResponse detail =
                new DeliverableDetailResponse(
                        DELIVERABLE_ID,
                        "Workout Reel 1",
                        DeliverableStatus.SUBMITTED,
                        1,
                        List.of(
                                new DeliverableFileDetail(
                                        "f1",
                                        "VIDEO",
                                        "reel.mp4",
                                        "https://r2.example.com/signed?sig=abc123",
                                        null,
                                        123L)),
                        "Caption",
                        List.of(),
                        null,
                        null,
                        null,
                        true,
                        true,
                        true);
        when(brandDeliverableService.getDetail(eq(principal), eq(DELIVERABLE_ID)))
                .thenReturn(detail);

        ResponseEntity<ApiResponse<DeliverableDetailResponse>> response =
                controller.getDetail(principal, DELIVERABLE_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().data().files().size());
        assertEquals(
                "https://r2.example.com/signed?sig=abc123",
                response.getBody().data().files().get(0).url());
        verify(brandDeliverableService).getDetail(principal, DELIVERABLE_ID);
    }

    @Test
    @DisplayName("GET /deliverables/{id}/safety-review delegates to DeliverableSafetyReviewService (brand-feature-audit.md fix #3)")
    void testGetSafetyReview() {
        DeliverableSafetyReviewResponse review =
                new DeliverableSafetyReviewResponse(
                        SafetyVerdict.PASS, List.of(), new java.math.BigDecimal("98.00"), Instant.now());
        when(deliverableSafetyReviewService.getReview(eq(principal), eq(DELIVERABLE_ID)))
                .thenReturn(review);

        ResponseEntity<ApiResponse<DeliverableSafetyReviewResponse>> response =
                controller.getSafetyReview(principal, DELIVERABLE_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(SafetyVerdict.PASS, response.getBody().data().overallVerdict());
        verify(deliverableSafetyReviewService).getReview(principal, DELIVERABLE_ID);
    }
}
