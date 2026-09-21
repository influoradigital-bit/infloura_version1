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
            DeliverableActions actions,
            /** Verified-analytics cached state (verified-analytics-0804). */
            String metricSource,
            Instant lastVerifiedAt,
            boolean metaConnected,
            /**
             * F-0418 (CEO ruling) — {@code CreatorDeliverableService.isOverdue(deliverable,
             * today)}, computed fresh on every read. Never persisted, never a status value: purely
             * a visible flag alongside the existing {@code deadline}/{@code submittedAt} fields.
             * True only past-deadline with nothing submitted yet; never true once submitted, even
             * late. Carries no auto-fail/penalty semantics — see the predicate's own javadoc.
             */
            boolean overdue,
            /**
             * The brand's review clock, from the creator's side (owner's ruling, 2026-09-21) -
             * the same {@code ReviewSlaService#clockFor} values the brand sees, so neither party
             * is told a different deadline. Null/zero when no clock is running.
             *
             * <p>The creator's question is not "how long do I have" but "when does someone do
             * something about this": {@code submittedAt} above says when they handed it over,
             * {@code reviewDueAt} says the last moment the brand can act, and {@code
             * reviewEscalatedAt} says when Influora was asked to step in. {@code
             * reviewWorkingDaysLeft} is the brand's remaining working days, not counting today.
             *
             * <p>None of these promise the creator an outcome. Escalation means a person at
             * Influora is chasing the brand - it is not an approval, and it does not pay anyone.
             */
            Instant reviewDueAt,
            Integer reviewWorkingDaysLeft,
            boolean reviewOverdue,
            Instant reviewEscalatedAt) {

        /**
         * Pre-review-clock arity, kept so call sites that predate the {@code review*} fields keep
         * compiling unchanged; defaults them to "no clock running".
         */
        public DeliverableStatusResponse(
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
                DeliverableActions actions,
                String metricSource,
                Instant lastVerifiedAt,
                boolean metaConnected,
                boolean overdue) {
            this(
                    id,
                    collaborationId,
                    type,
                    title,
                    status,
                    deadline,
                    versionNumber,
                    revisionCount,
                    files,
                    caption,
                    hashtags,
                    creatorNotes,
                    reviewNotes,
                    submittedAt,
                    reviewedAt,
                    actions,
                    metricSource,
                    lastVerifiedAt,
                    metaConnected,
                    overdue,
                    null,
                    null,
                    false,
                    null);
        }

        /**
         * Pre-F-0418 arity, kept so existing call sites that predate the {@code overdue} field
         * keep compiling unchanged; defaults it to {@code false}.
         */
        public DeliverableStatusResponse(
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
                DeliverableActions actions,
                String metricSource,
                Instant lastVerifiedAt,
                boolean metaConnected) {
            this(
                    id,
                    collaborationId,
                    type,
                    title,
                    status,
                    deadline,
                    versionNumber,
                    revisionCount,
                    files,
                    caption,
                    hashtags,
                    creatorNotes,
                    reviewNotes,
                    submittedAt,
                    reviewedAt,
                    actions,
                    metricSource,
                    lastVerifiedAt,
                    metaConnected,
                    false);
        }
    }

    public record DeliverableActions(boolean canUploadNewVersion, boolean canSubmit, boolean canReportMetrics) {}

    /**
     * {@code POST /creator/deliverables/{id}/verify} — live verification attempt state
     * (verified-analytics-0804). {@code outcome} is a {@code DeliverableVerificationService.Outcome}
     * name; {@code manualFallbackAllowed} is true ONLY when Meta genuinely failed for a connected
     * account (never for VERIFIED or not-connected). Verified numbers, when present, are the real
     * aggregates persisted by the verification service.
     */
    public record VerificationStateResponse(
            String deliverableId,
            String outcome,
            String metricSource,
            Long reach,
            Long impressions,
            Long engagements,
            Instant lastVerifiedAt,
            boolean metaConnected,
            boolean manualFallbackAllowed) {}

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
