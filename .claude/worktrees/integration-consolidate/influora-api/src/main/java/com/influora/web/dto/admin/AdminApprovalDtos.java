package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Response/request records for {@code ApprovalWorkflowController}. Field names match {@code
 * ApprovalWorkflow} in {@code src/admin/types/admin.types.ts} exactly.
 */
public final class AdminApprovalDtos {

    private AdminApprovalDtos() {}

    /**
     * {@code id} is a synthetic composite key ({@code "<WorkflowType>:<entityId>"}, e.g. {@code
     * "BRAND_KYC:01H..."}) — there is no dedicated {@code approval_workflows} table backing this
     * queue (see {@code ApprovalWorkflowService} class javadoc for why: it deliberately reuses the
     * existing {@code Workspace}/{@code CreatorProfile}/{@code ContentFlag} rows rather than
     * duplicating a parallel workflow-tracking table). {@code POST /admin/moderation/approvals/{id}}
     * parses this id back apart to route the decision to the correct underlying entity/service.
     */
    public record ApprovalWorkflowDto(
            String id,
            String type,
            String entityId,
            String entityName,
            String status,
            Instant submittedAt,
            String reviewedBy,
            Instant reviewedAt,
            String notes) {}

    /**
     * {@code notes} is required, not optional — this endpoint delegates straight into {@code
     * AdminBrandService#verifyKyc}/{@code AdminCreatorService#reviewApplication}, both of which
     * treat their {@code reason} parameter as the mandatory audit-trail justification (matches
     * {@code AdminBrandDtos.VerifyKycRequest}/{@code AdminCreatorDtos.ReviewApplicationRequest}'s
     * own {@code @NotBlank reason} — same compliance-action discipline, reached through a different
     * door).
     */
    public record ProcessApprovalRequest(
            @NotBlank String action, @NotBlank @Size(max = 2000) String notes) {}
}
