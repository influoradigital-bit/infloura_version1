package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorDeliverableService;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableStatusResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MarkPostedResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.MetricsReportResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.ProofUploadResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.SubmitResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.UploadResponse;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.VerificationStateResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Week 3 P0 — creator deliverable upload + status (09_CREATOR_DELIVERABLES_SPEC.md §4.3). Creator
 * identity always resolved inside {@link CreatorDeliverableService} via {@link
 * com.influora.service.CreatorContextService} — no creator-id path param is trusted.
 */
@RestController
@RequestMapping("/creator/deliverables")
public class CreatorDeliverableController {

    private final CreatorDeliverableService creatorDeliverableService;

    public CreatorDeliverableController(CreatorDeliverableService creatorDeliverableService) {
        this.creatorDeliverableService = creatorDeliverableService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeliverableListItem>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("collaboration_id") String collaborationId) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        creatorDeliverableService.listForCollaboration(principal, collaborationId)));
    }

    /**
     * CR-51 — batched counterpart to {@link #list}. Powers the creator dashboard's
     * pending-deliverable rollup with one request instead of one {@code GET /creator/deliverables}
     * call per active deal. See {@link CreatorDeliverableService#listForCollaborations} for the
     * query-count reasoning.
     */
    @GetMapping("/bulk")
    public ResponseEntity<ApiResponse<Map<String, List<DeliverableListItem>>>> listBulk(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("collaboration_ids") List<String> collaborationIds) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        creatorDeliverableService.listForCollaborations(principal, collaborationIds)));
    }

    @PostMapping(value = "/{deliverableId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadResponse>> upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) List<String> hashtags,
            @RequestParam(value = "creatorNotes", required = false) String creatorNotes) {
        UploadResponse response =
                creatorDeliverableService.uploadContent(
                        principal, deliverableId, files, thumbnail, caption, hashtags, creatorNotes);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/{deliverableId}/status")
    public ResponseEntity<ApiResponse<DeliverableStatusResponse>> status(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.getStatus(principal, deliverableId)));
    }

    @PostMapping("/{deliverableId}/submit")
    public ResponseEntity<ApiResponse<SubmitResponse>> submit(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody(required = false) SubmitRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.submitForReview(principal, deliverableId, body)));
    }

    @PostMapping("/{deliverableId}/metrics")
    public ResponseEntity<ApiResponse<MetricsReportResponse>> reportMetrics(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody MetricsReportRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.reportMetrics(principal, deliverableId, body)));
    }

    @PostMapping(value = "/{deliverableId}/proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProofUploadResponse>> uploadProof(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestPart("screenshot") MultipartFile screenshot) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(creatorDeliverableService.uploadProof(principal, deliverableId, screenshot)));
    }

    @PostMapping("/{deliverableId}/mark-posted")
    public ResponseEntity<ApiResponse<MarkPostedResponse>> markPosted(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody MarkPostedRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.markPosted(principal, deliverableId, body)));
    }

    /**
     * verified-analytics-0804 — on-demand Meta verification. Runs the same verification the 6h batch
     * job runs, on request, and returns the live outcome + verified numbers + whether a manual
     * fallback is permitted (only when Meta genuinely failed for a connected account).
     */
    @PostMapping("/{deliverableId}/verify")
    public ResponseEntity<ApiResponse<VerificationStateResponse>> verify(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String deliverableId) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.verifyNow(principal, deliverableId)));
    }
}
