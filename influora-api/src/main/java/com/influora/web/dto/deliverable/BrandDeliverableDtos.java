package com.influora.web.dto.deliverable;

import com.influora.domain.enums.DeliverableStatus;
import java.time.Instant;
import java.util.List;

/** Brand deliverable review (09_CREATOR_DELIVERABLES_SPEC.md §11.4–11.5, api.ts deliverables.*). */
public final class BrandDeliverableDtos {

    private BrandDeliverableDtos() {}

    /** {@code POST /deliverables/{id}/revise} — required brand feedback. */
    public record ReviseRequest(String feedback) {}

    /** Approve / revise response — mirrors {@code api.ts} {@code { status }} contract. */
    /**
     * F-0223 — {@code paymentReleased} and {@code paymentHeldReason} exist because approving a
     * deliverable is the act that pays the creator, and this response used to carry only the
     * deliverable's new status. Approval succeeded identically whether escrow released or was
     * silently skipped (unfunded milestone, unmet release condition, dispute freeze — eight
     * conditions in all), so the brand was told "Approved" over a payment that never happened
     * and the creator was left waiting with nothing to look at.
     *
     * <p>{@code paymentHeldReason} is null when {@code paymentReleased} is true, and otherwise
     * carries the server-side reason code so the UI can say WHICH held it. Only meaningful on
     * the approve path — {@code revise} and {@code reject} never release, and report false with
     * a {@code NOT_APPLICABLE} reason rather than pretending the question does not apply.
     */
    public record ReviewResponse(
            DeliverableStatus status, boolean paymentReleased, String paymentHeldReason) {

        /** Revise/reject: no release is attempted, so neither field carries a claim about money. */
        public static ReviewResponse withoutRelease(DeliverableStatus status) {
            return new ReviewResponse(status, false, "NOT_APPLICABLE");
        }
    }

    /** File in a deliverable detail response — presigned URLs already resolved. */
    public record DeliverableFileDetail(
            String id,
            String fileType,
            String fileName,
            String url,
            String thumbnailUrl,
            Long fileSize) {}

    /**
     * {@code GET /deliverables/{id}} — full deliverable detail for brand review (DPF-1).
     *
     * <p>D-9 (BrandF.md §25): {@code canReject} added alongside {@code canApprove}/{@code
     * canRequestRevision} — same {@code canReview} gate (status is SUBMITTED/RESUBMITTED), since
     * the brand-safety-relevant "final decision" action was implemented server-side ({@code
     * BrandDeliverableService#reject}, routed at {@code BrandDeliverableController#reject}) but had
     * no DTO flag for the frontend to gate a Reject button on, so it never got a button.
     */
    public record DeliverableDetailResponse(
            String id,
            String title,
            DeliverableStatus status,
            int versionNumber,
            List<DeliverableFileDetail> files,
            String caption,
            List<String> hashtags,
            String creatorNotes,
            String reviewNotes,
            Instant submittedAt,
            boolean canApprove,
            boolean canRequestRevision,
            boolean canReject) {}
}
