package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.ApprovalWorkflowService;
import com.influora.web.dto.admin.AdminApprovalDtos.ApprovalWorkflowDto;
import com.influora.web.dto.admin.AdminApprovalDtos.ProcessApprovalRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified pending-approvals queue API (src/admin/TASK_ASSIGNMENTS.md P1: "Approval workflow API"
 * — Vikram, cycle 5). Mounted at {@code /admin/moderation} (full path {@code
 * /api/v1/admin/moderation/...}) to match {@code moderationApi.getPendingApprovals}/{@code
 * .processApproval}'s {@code /moderation/approvals/...} paths in {@code
 * src/admin/services/api-contracts.ts} exactly.
 *
 * <p><b>Relationship to {@code AdminBrandController}/{@code AdminCreatorController} — read this
 * before assuming duplication:</b> those two controllers already own per-entity-type KYC/
 * application review (each shipped in an earlier cycle, each left untouched by this class). This
 * controller does not re-implement that logic — see {@link ApprovalWorkflowService}'s class
 * javadoc for the full non-duplication rationale and delegation design. What this controller
 * uniquely provides is the cross-entity-type "everything needing my review right now" list the
 * moderation-queue UI needs, which neither single-entity-type controller can answer on its own.
 *
 * <p>Only {@code moderationApi}'s two {@code approvals/*} methods are implemented this cycle —
 * {@code getContentFlags}/{@code actionFlag}/{@code getSuspensions}/{@code reviewAppeal} (also
 * declared in api-contracts.ts, backing Ananya's P2 {@code FlagQueue.tsx}) are NOT built here;
 * flagged as follow-up scope for a future dedicated moderation controller, not silently forgotten
 * — same "only build what the assigned frontend caller needs this cycle" discipline {@code
 * AdminBrandController}/{@code AdminCreatorController} both document.
 */
@RestController
@RequestMapping("/admin/moderation")
public class ApprovalWorkflowController {

    private final ApprovalWorkflowService approvalWorkflowService;

    public ApprovalWorkflowController(ApprovalWorkflowService approvalWorkflowService) {
        this.approvalWorkflowService = approvalWorkflowService;
    }

    @GetMapping("/approvals/pending")
    public List<ApprovalWorkflowDto> getPendingApprovals(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String type) {
        return approvalWorkflowService.getPendingApprovals(principal, type);
    }

    @PostMapping("/approvals/{id}")
    public ApprovalWorkflowDto processApproval(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ProcessApprovalRequest body) {
        return approvalWorkflowService.processApproval(principal, request, id, body.action(), body.notes());
    }
}
