package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorDeliverableService;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableActions;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableFileResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableStatusResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsPayload;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.UploadResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** Week 3 P0 — controller delegation tests for creator deliverable upload + status. */
@ExtendWith(MockitoExtension.class)
class CreatorDeliverableControllerTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";

    @Mock private CreatorDeliverableService creatorDeliverableService;
    @Mock private AuthPrincipal principal;

    private CreatorDeliverableController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorDeliverableController(creatorDeliverableService);
    }

    @Test
    @DisplayName("POST /creator/deliverables/{id}/upload delegates to service and returns 201")
    void testUpload() {
        MultipartFile file =
                new MockMultipartFile("files", "reel.mp4", "video/mp4", new byte[] {1, 2, 3});
        UploadResponse uploadResponse =
                new UploadResponse(
                        "ver1",
                        1,
                        List.of(
                                new DeliverableFileResponse(
                                        "f1", "VIDEO", "reel.mp4", "https://x", null, 3L, null)),
                        DeliverableStatus.DRAFT);
        when(creatorDeliverableService.uploadContent(
                        eq(principal),
                        eq(DELIVERABLE_ID),
                        eq(List.of(file)),
                        isNull(),
                        eq("Caption"),
                        eq(List.of("#fit")),
                        eq("Notes")))
                .thenReturn(uploadResponse);

        ResponseEntity<ApiResponse<UploadResponse>> response =
                controller.upload(
                        principal,
                        DELIVERABLE_ID,
                        List.of(file),
                        null,
                        "Caption",
                        List.of("#fit"),
                        "Notes");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().data().versionNumber());
        assertEquals(DeliverableStatus.DRAFT, response.getBody().data().status());
        verify(creatorDeliverableService)
                .uploadContent(
                        principal, DELIVERABLE_ID, List.of(file), null, "Caption", List.of("#fit"), "Notes");
    }

    @Test
    @DisplayName("GET /creator/deliverables/{id}/status delegates to service")
    void testStatus() {
        DeliverableStatusResponse statusResponse =
                new DeliverableStatusResponse(
                        DELIVERABLE_ID,
                        "collab1",
                        DeliverableType.INSTAGRAM_REEL,
                        "Workout Reel 1",
                        DeliverableStatus.DRAFT,
                        null,
                        1,
                        0,
                        List.of(),
                        "Caption",
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        new DeliverableActions(true, true, false));
        when(creatorDeliverableService.getStatus(principal, DELIVERABLE_ID)).thenReturn(statusResponse);

        ResponseEntity<ApiResponse<DeliverableStatusResponse>> response =
                controller.status(principal, DELIVERABLE_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(DELIVERABLE_ID, response.getBody().data().id());
        verify(creatorDeliverableService).getStatus(principal, DELIVERABLE_ID);
    }

    @Test
    @DisplayName("GET /creator/deliverables?collaboration_id= delegates to service")
    void testList() {
        List<DeliverableListItem> listResponse =
                List.of(
                        new DeliverableListItem(
                                DELIVERABLE_ID,
                                "Workout Reel 1",
                                "Version 0",
                                DeliverableStatus.PENDING,
                                false,
                                null,
                                null));
        when(creatorDeliverableService.listForCollaboration(principal, COLLAB_ID))
                .thenReturn(listResponse);

        ResponseEntity<ApiResponse<List<DeliverableListItem>>> response =
                controller.list(principal, COLLAB_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().data().size());
        assertEquals(DELIVERABLE_ID, response.getBody().data().get(0).id());
        verify(creatorDeliverableService).listForCollaboration(principal, COLLAB_ID);
    }

    @Test
    @DisplayName("POST /creator/deliverables/{id}/submit delegates to service")
    void testSubmit() {
        SubmitResponse submitResponse =
                new SubmitResponse(
                        DELIVERABLE_ID, DeliverableStatus.SUBMITTED, "Submitted for brand review");
        SubmitRequest body = new SubmitRequest("Final caption", List.of("#fit"), "Notes");
        when(creatorDeliverableService.submitForReview(principal, DELIVERABLE_ID, body))
                .thenReturn(submitResponse);

        ResponseEntity<ApiResponse<SubmitResponse>> response =
                controller.submit(principal, DELIVERABLE_ID, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(DeliverableStatus.SUBMITTED, response.getBody().data().status());
        assertEquals("Submitted for brand review", response.getBody().data().message());
        verify(creatorDeliverableService).submitForReview(principal, DELIVERABLE_ID, body);
    }

    @Test
    @DisplayName("POST /creator/deliverables/{id}/metrics delegates to service")
    void testReportMetrics() {
        MetricsReportRequest body =
                new MetricsReportRequest(
                        new MetricsPayload(100, 10, 5, 1000, 500, 800, 20),
                        List.of("scr_1"),
                        3);
        MetricsReportResponse metricsResponse =
                new MetricsReportResponse(
                        DELIVERABLE_ID,
                        DeliverableStatus.METRICS_REPORTED,
                        body.metrics(),
                        22.0,
                        "PENDING",
                        "Metrics submitted. They will be verified.");
        when(creatorDeliverableService.reportMetrics(principal, DELIVERABLE_ID, body))
                .thenReturn(metricsResponse);

        ResponseEntity<ApiResponse<MetricsReportResponse>> response =
                controller.reportMetrics(principal, DELIVERABLE_ID, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(DeliverableStatus.METRICS_REPORTED, response.getBody().data().status());
        verify(creatorDeliverableService).reportMetrics(principal, DELIVERABLE_ID, body);
    }
}
