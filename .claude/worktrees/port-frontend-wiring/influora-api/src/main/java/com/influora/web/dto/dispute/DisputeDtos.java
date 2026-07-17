package com.influora.web.dto.dispute;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.domain.enums.DisputeStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class DisputeDtos {

    private DisputeDtos() {}

    public record OpenDisputeRequest(@NotBlank @Size(max = 2000) String reason) {}

    public record DisputeResponse(
            String id,
            String collaborationId,
            String openedByType,
            String openedByUserId,
            String reason,
            String status,
            Instant createdAt,
            String resolvedByAdminId,
            String resolutionNotes,
            Instant resolvedAt) {}

    /**
     * Task #5 — {@code creatorSplitPercent} is only consulted when {@code resolution ==
     * RESOLVED_SPLIT}; it is the admin's split decision, not a money amount — the actual escrow
     * amount being split is always server-derived from the frozen {@code EscrowHold.amount}
     * (Guardrail 4), never accepted from this request.
     *
     * <p><b>[SEC: Vikram, P4 fix — corrected claim]</b> {@code @DecimalMin}/{@code @DecimalMax}
     * below range-check this field ONLY when it is present — bean validation treats {@code null} as
     * vacuously valid for both annotations (neither is paired with {@code @NotNull}), and there is
     * no cross-field constraint here tying "required" to {@code resolution == RESOLVED_SPLIT}. A
     * {@code RESOLVED_SPLIT} request that omits this field passes DTO validation with {@code
     * creatorSplitPercent == null}. The actual, complete 0-100-and-non-null enforcement lives in
     * {@code EscrowService.adminSplitForDispute} itself (the money-moving call), which re-validates
     * defensively and rejects a {@code null} with a clean {@code ApiException} rather than letting
     * it reach a raw arithmetic NPE — see that method's javadoc.
     */
    public record ResolveDisputeRequest(
            @NotNull DisputeStatus resolution,
            @Size(max = 2000) String notes,
            @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "100", inclusive = true)
                    BigDecimal creatorSplitPercent) {}

    /**
     * P2-14 — dispute list with display fields (replaces api.ts client-side derivation).
     * Matches {@code DisputeRow}/{@code CreatorDisputeRow}/{@code BrandDisputeRow} in api.ts.
     * Populated by joining {@code Dispute} with {@code Collaboration} and {@code Campaign}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisputeListItemResponse(
            String collaborationId,
            String campaignName,
            String counterpartyName,
            BigDecimal dealValue,
            String currency,
            String disputeStatus,
            Instant openedAt,
            String reason) {}

    /**
     * Admin disputes list summary (Task #4). Includes dispute ID, campaign ID, brand/creator names,
     * status, and timestamps for the admin disputes table view.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisputeSummaryDto(
            String disputeId,
            String campaignId,
            String brandName,
            String creatorName,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * Paginated response for admin disputes list. Matches {@code PaginatedResponse<T>} pattern.
     */
    public record PagedDisputeSummaryDto(
            List<DisputeSummaryDto> data, long total, int page, int pageSize, int totalPages) {}
}
