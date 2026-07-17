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
    public record ReviewResponse(DeliverableStatus status) {}

    /** File in a deliverable detail response — presigned URLs already resolved. */
    public record DeliverableFileDetail(
            String id,
            String fileType,
            String fileName,
            String url,
            String thumbnailUrl,
            Long fileSize) {}

    /** {@code GET /deliverables/{id}} — full deliverable detail for brand review (DPF-1). */
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
            boolean canRequestRevision) {}
}
