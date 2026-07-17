package com.influora.web.dto.deliverable;

import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Creator deliverable upload + status (09_CREATOR_DELIVERABLES_SPEC.md §4.3–4.4). */
public final class CreatorDeliverableDtos {

    private CreatorDeliverableDtos() {}

    public record DeliverableFileResponse(
            String id,
            String fileType,
            String fileName,
            String url,
            String thumbnailUrl,
            Long fileSize,
            Integer durationSeconds) {}

    public record UploadResponse(
            String versionId,
            int versionNumber,
            List<DeliverableFileResponse> files,
            DeliverableStatus status) {}

    public record DeliverableStatusResponse(
            String id,
            String collaborationId,
            DeliverableType type,
            String title,
            DeliverableStatus status,
            LocalDate deadline,
            int versionNumber,
            int revisionCount,
            List<DeliverableFileResponse> files,
            String caption,
            List<String> hashtags,
            String creatorNotes,
            String reviewNotes,
            Instant submittedAt,
            Instant reviewedAt,
            DeliverableActions actions) {}

    public record DeliverableActions(boolean canUploadNewVersion, boolean canSubmit, boolean canReportMetrics) {}

    /** {@code POST /creator/deliverables/{id}/submit} — optional caption/hashtags/notes on lean row. */
    public record SubmitRequest(String finalCaption, List<String> hashtags, String notes) {}

    public record SubmitResponse(String deliverableId, DeliverableStatus status, String message) {}

    /** Deal-room picker row — {@code GET /creator/deliverables?collaboration_id=}. */
    public record DeliverableListItem(
            String id,
            String title,
            String description,
            DeliverableStatus status,
            boolean completed,
            Integer currentRevision,
            Integer maxRevisions) {}

    /** Self-reported performance numbers (09_CREATOR_DELIVERABLES_SPEC.md §4.6). */
    public record MetricsPayload(
            Integer likes,
            Integer comments,
            Integer shares,
            Integer views,
            Integer reach,
            Integer impressions,
            Integer saves) {}

    /** {@code POST /creator/deliverables/{id}/metrics} request body. */
    public record MetricsReportRequest(
            MetricsPayload metrics,
            List<String> proofScreenshots,
            Integer reportedDaysAfterPosting) {}

  /** {@code POST /creator/deliverables/{id}/metrics} response. */
    public record MetricsReportResponse(
            String deliverableId,
            DeliverableStatus status,
            MetricsPayload metrics,
            Double engagementRate,
            String verificationStatus,
            String message) {}

    /** {@code POST /creator/deliverables/{id}/proof} — §4.7 ownership-bound proof screenshot. */
    public record ProofUploadResponse(
            String id, String key, String url, Instant uploadedAt, Instant urlExpiresAt) {}

    /** {@code POST /creator/deliverables/{id}/mark-posted} — DPF-3 live post URL submission. */
    public record MarkPostedRequest(String livePostUrl) {}

    public record MarkPostedResponse(String id, DeliverableStatus status, String postUrl, Instant postedAt) {}
}
