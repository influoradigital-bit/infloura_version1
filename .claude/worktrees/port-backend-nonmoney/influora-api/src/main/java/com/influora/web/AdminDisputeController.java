package com.influora.web;

import com.influora.common.ApiException;
import com.influora.domain.enums.DisputeStatus;
import com.influora.security.AuthPrincipal;
import com.influora.service.DisputeService;
import com.influora.web.dto.dispute.DisputeDtos.DisputeResponse;
import com.influora.web.dto.dispute.DisputeDtos.PagedDisputeSummaryDto;
import com.influora.web.dto.dispute.DisputeDtos.ResolveDisputeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dispute resolution (Task #34 / CEO §1.3, extended by Task #5). {@code resolve()} both
 * flips the dispute's terminal status AND settles the frozen escrow (release/refund/split) via
 * {@code DisputeService.resolveDispute} -> {@code EscrowService}. Mounted at {@code
 * /admin/disputes} (full path {@code /api/v1/admin/disputes/...}).
 */
@RestController
@RequestMapping("/admin/disputes")
public class AdminDisputeController {

    private final DisputeService disputeService;

    public AdminDisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    /**
     * Task #4 — GET /admin/disputes list with pagination and filtering. Returns all disputes with
     * campaign ID, brand name, creator name for the admin disputes table view. MFA-gated.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public PagedDisputeSummaryDto list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String brandId,
            @RequestParam(required = false) String creatorId) {
        return disputeService.list(principal, page, pageSize, status, campaignId, brandId, creatorId);
    }

    /**
     * Task #5 — resolving a dispute now also settles the frozen escrow (release to creator /
     * refund to brand / custom split) via {@code EscrowService}, then records the fund movement to
     * {@code admin_audit_log} — both done inside {@code DisputeService.resolveDispute}'s single
     * transaction. {@code request} is passed through untouched to {@code AdminAuditLogService},
     * same convention as every other {@code Admin*Controller} money/moderation endpoint (see {@code
     * AdminBrandController}).
     *
     * <p><b>Kabir security review (phase1-admin-panel-escrow-security.md High finding #1):</b>
     * {@code DisputeService.resolveDispute} already translates a lost-update race
     * ({@link ObjectOptimisticLockingFailureException} from the {@code Dispute} entity's new
     * {@code @Version} column) into a clean {@code ApiException} 409 itself via {@code
     * saveAndFlush}. This catch is a second, defense-in-depth layer — same convention as {@code
     * PlatformFeeAdminController#update} — in case the exception is ever thrown at the
     * transaction-commit boundary (outside the service method body) instead.
     */
    @PostMapping("/{disputeId}/resolve")
    @ResponseStatus(HttpStatus.OK)
    public DisputeResponse resolve(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @PathVariable String disputeId,
            @Valid @RequestBody ResolveDisputeRequest body) {
        try {
            return disputeService.resolveDispute(principal, request, disputeId, body);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ApiException(
                    "DISPUTE_RESOLVE_CONFLICT",
                    "This dispute was already resolved by someone else, please refresh.",
                    HttpStatus.CONFLICT);
        }
    }
}
