package com.influora.web.dto.deliverable;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /deliverables/{id}/safety-review} — brand-feature-audit.md fix #3 ("Deliverable-level
 * brand-safety review"). Advisory-only DTO shape for a single deliverable's caption/text, scored on
 * demand by {@link com.influora.service.DeliverableSafetyReviewService} via the SAME forced-tool
 * GARM classifier already used for creators' published posts ({@code
 * com.influora.integration.ai.BrandSafetyAiClient#classify} -> influora-ai {@code POST
 * /internal/brand-safety} -> {@code app/routes/brand_safety.py}, {@code
 * ANALYZE_CREATOR_CONTENT_SCHEMA}). Field names/casing match {@code DeliverableSafetyReview} in
 * {@code src/lib/api.ts} exactly (the FE was built against this documented contract in parallel —
 * see {@code useDeliverableSafetyReview.ts}).
 *
 * <p><b>Server-interpreted, advisory only, NEVER blocks submit/approve</b> — this endpoint is a
 * pure read; nothing in {@code CreatorDeliverableController#submit} / {@link
 * com.influora.service.BrandDeliverableService#approve} calls it or waits on it. A missing/failed
 * review degrades to a 4xx/5xx the FE hook already treats as "review not available yet", never a
 * blocker on the real approve/reject flow.
 */
public final class DeliverableSafetyDtos {

    private DeliverableSafetyDtos() {}

    /** Mirrors {@code DeliverableSafetyVerdict} ({@code src/lib/api.ts}). Server-computed only —
     * the model never returns a verdict directly, only the per-category GARM classification this
     * enum is derived FROM (see {@code DeliverableSafetyReviewService#deriveVerdict}). */
    public enum SafetyVerdict {
        PASS,
        REVIEW,
        FAIL
    }

    /** Mirrors {@code DeliverableSafetyCheckStatus} ({@code src/lib/api.ts}). */
    public enum SafetyCheckStatus {
        PASS,
        WARNING,
        FAIL
    }

    /**
     * One GARM category's result for this deliverable — always all 10 fixed categories from
     * {@code app/tools/schemas.py::GARM_CATEGORIES}, "floor" (no concern) included, same
     * never-omit-a-category discipline the Python classifier already enforces server-side.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SafetyCheck(String id, String label, SafetyCheckStatus status, String detail) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliverableSafetyReviewResponse(
            SafetyVerdict overallVerdict, List<SafetyCheck> checks, BigDecimal score, Instant computedAt) {}
}
