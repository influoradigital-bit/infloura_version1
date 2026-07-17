package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminApprovalDtos.ApprovalWorkflowDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified pending-approvals queue (src/admin/TASK_ASSIGNMENTS.md P1: "Approval workflow API" —
 * Vikram, cycle 5). Backs {@code ApprovalWorkflowController}, the swap-in target for {@code
 * moderationApi.getPendingApprovals}/{@code .processApproval} (api-contracts.ts).
 *
 * <p><b>Why this is NOT a duplicate of {@code AdminBrandController}'s {@code verify-kyc} or {@code
 * AdminCreatorController}'s {@code review-application}:</b> those two endpoints are the
 * per-entity-type mutation surface (a brand-detail page's "Approve KYC" button, a creator-detail
 * page's "Approve Application" button) — each already existed before this class and each is left
 * completely untouched. This class does not re-implement KYC/application review logic; {@link
 * #processApproval} DELEGATES to {@link AdminBrandService#verifyKyc} / {@link
 * AdminCreatorService#reviewApplication} for those two types, reusing their existing role-gating,
 * mutation, and audit-log-writing wholesale. What this class adds that neither of those two
 * endpoints can provide on its own is the thing the frontend's moderation-queue view actually
 * needs and the per-entity pages don't: a single, cross-entity-type, "what needs my review right
 * now" list — {@code WorkflowType.BRAND_KYC} + {@code CREATOR_APPLICATION} + {@code
 * CONTENT_MODERATION} rows interleaved and sortable by submission time, matching {@code
 * moderationApi.getPendingApprovals()}'s contract exactly. A brand-ops admin working the queue
 * never has to know in advance whether the next item to review is a brand or a creator.
 *
 * <p><b>No new {@code approval_workflows} table:</b> confirmed by grep before writing this
 * (zero hits under {@code db/migration} or {@code domain/entity}) — a workflow-tracking table
 * would just be a redundant shadow of state that already lives on {@code
 * workspaces.verification_status} / {@code creator_profiles.application_status} / {@code
 * content_flags.status}. Instead, each pending row is read live from its owning entity and given a
 * synthetic composite id ({@code "<type>:<entityId>"}) — see {@code ApprovalWorkflowDto}'s javadoc.
 * This mirrors {@code AdminBrandService}/{@code AdminCreatorService}'s own "reuse the existing
 * entity, don't invent a parallel one" discipline, one level up.
 *
 * <p><b>Scope, cycle 5 — 3 of 6 {@code WorkflowType} values implemented:</b> {@code BRAND_KYC}
 * (workspaces pending KYC review), {@code CREATOR_APPLICATION} (creator profiles pending
 * application review), {@code CONTENT_MODERATION} (open content flags, surfaced read-only — see
 * {@link #processApproval}'s javadoc for why this type cannot be actioned here yet). {@code
 * DELIVERABLE_DISPUTE}/{@code ESCROW_RELEASE}/{@code ACCOUNT_SUSPENSION} return an empty slice
 * for those type filters rather than fabricated rows: {@code DELIVERABLE_DISPUTE} could plausibly
 * map onto {@code CollaborationStatus.DISPUTED} but nothing in this codebase yet distinguishes "an
 * admin has been notified and needs to act" from "a dispute exists" for that status value;
 * {@code ESCROW_RELEASE}/{@code ACCOUNT_SUSPENSION} are the role-permission-matrix's own separate
 * "Escrow Operations"/"Account Suspensions" sections with distinct endpoints not built this cycle —
 * folding them into this generic queue without a real backing "needs review" signal would be
 * exactly the fabricated-contract failure mode TECH-STACK rule 7 exists to prevent.
 */
@Service
public class ApprovalWorkflowService {

    private static final int QUEUE_PAGE_SIZE = 50;

    private static final String TYPE_BRAND_KYC = "BRAND_KYC";
    private static final String TYPE_CREATOR_APPLICATION = "CREATOR_APPLICATION";
    private static final String TYPE_CONTENT_MODERATION = "CONTENT_MODERATION";

    private final AdminContextService adminContext;
    private final AdminBrandService adminBrandService;
    private final AdminCreatorService adminCreatorService;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ContentFlagRepository contentFlagRepository;

    public ApprovalWorkflowService(
            AdminContextService adminContext,
            AdminBrandService adminBrandService,
            AdminCreatorService adminCreatorService,
            WorkspaceRepository workspaceRepository,
            CreatorProfileRepository creatorProfileRepository,
            ContentFlagRepository contentFlagRepository) {
        this.adminContext = adminContext;
        this.adminBrandService = adminBrandService;
        this.adminCreatorService = adminCreatorService;
        this.workspaceRepository = workspaceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.contentFlagRepository = contentFlagRepository;
    }

    /**
     * @param type optional {@code WorkflowType} filter (matches {@code
     *     moderationApi.getPendingApprovals(type?)}'s query param). Unrecognized/unimplemented
     *     types return an empty list rather than a 400 — same "unknown filter = no matches" leniency
     *     the frontend's own optional-type contract implies, not an error condition.
     */
    @Transactional(readOnly = true)
    public List<ApprovalWorkflowDto> getPendingApprovals(AuthPrincipal principal, String type) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<ApprovalWorkflowDto> results = new ArrayList<>();
        if (type == null || type.equalsIgnoreCase(TYPE_BRAND_KYC)) {
            results.addAll(brandKycQueue());
        }
        if (type == null || type.equalsIgnoreCase(TYPE_CREATOR_APPLICATION)) {
            results.addAll(creatorApplicationQueue());
        }
        if (type == null || type.equalsIgnoreCase(TYPE_CONTENT_MODERATION)) {
            results.addAll(contentModerationQueue());
        }
        return results;
    }

    /**
     * Routes a decision to the correct underlying service based on the composite id's type prefix.
     * {@code CONTENT_MODERATION} rows are surfaced by {@link #getPendingApprovals} but cannot be
     * processed here — actioning a {@code ContentFlag} (set {@code status}/{@code action_taken}/
     * {@code reviewed_by}) is a distinct write path this cycle doesn't build (see class javadoc);
     * returning a fabricated "processed" response would violate TECH-STACK rule 7, so this throws a
     * clear, honest {@code APPROVAL_ACTION_NOT_IMPLEMENTED} instead of silently no-op'ing.
     */
    @Transactional
    public ApprovalWorkflowDto processApproval(
            AuthPrincipal principal, HttpServletRequest request, String compositeId, String action, String notes) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        String[] parts = compositeId.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ApiException(
                    "INVALID_APPROVAL_ID",
                    "Approval id must be in \"<WorkflowType>:<entityId>\" form",
                    HttpStatus.BAD_REQUEST);
        }
        String workflowType = parts[0];
        String entityId = parts[1];
        String resolvedStatus = "APPROVE".equalsIgnoreCase(action) ? "APPROVED" : "REJECTED";

        return switch (workflowType) {
            case TYPE_BRAND_KYC -> {
                var brand = adminBrandService.verifyKyc(principal, request, entityId, action, notes);
                yield new ApprovalWorkflowDto(
                        compositeId,
                        TYPE_BRAND_KYC,
                        brand.id(),
                        brand.name(),
                        resolvedStatus,
                        brand.createdAt(),
                        principal.getUserId(),
                        brand.kycReviewedAt(),
                        notes);
            }
            case TYPE_CREATOR_APPLICATION -> {
                var creator =
                        adminCreatorService.reviewApplication(principal, request, entityId, action, notes);
                yield new ApprovalWorkflowDto(
                        compositeId,
                        TYPE_CREATOR_APPLICATION,
                        creator.id(),
                        creator.name(),
                        resolvedStatus,
                        creator.createdAt(),
                        principal.getUserId(),
                        creator.applicationReviewedAt(),
                        notes);
            }
            case TYPE_CONTENT_MODERATION ->
                    throw new ApiException(
                            "APPROVAL_ACTION_NOT_IMPLEMENTED",
                            "Content flag actioning is not implemented yet — see ApprovalWorkflowService"
                                    + " class javadoc. This queue currently surfaces CONTENT_MODERATION items"
                                    + " read-only.",
                            HttpStatus.NOT_IMPLEMENTED);
            default ->
                    throw new ApiException(
                            "UNKNOWN_APPROVAL_TYPE",
                            "Unrecognized or unsupported workflow type: " + workflowType,
                            HttpStatus.BAD_REQUEST);
        };
    }

    // ============================================
    // QUEUE ASSEMBLY
    // ============================================

    private List<ApprovalWorkflowDto> brandKycQueue() {
        List<Workspace> pending =
                workspaceRepository.findByTypeAndVerificationStatusOrderByCreatedAtAsc(
                        WorkspaceType.BRAND, VerificationStatus.PENDING, PageRequest.of(0, QUEUE_PAGE_SIZE));
        List<ApprovalWorkflowDto> dtos = new ArrayList<>();
        for (Workspace w : pending) {
            dtos.add(
                    new ApprovalWorkflowDto(
                            TYPE_BRAND_KYC + ":" + w.getId(),
                            TYPE_BRAND_KYC,
                            w.getId(),
                            w.getName(),
                            "PENDING",
                            // No dedicated "kyc submitted at" column — workspace creation time is the
                            // closest honest proxy, same gap AdminBrandService's kycReviewedAt/By
                            // already documents for the review side of this trail.
                            w.getCreatedAt(),
                            null,
                            null,
                            null));
        }
        return dtos;
    }

    private List<ApprovalWorkflowDto> creatorApplicationQueue() {
        List<CreatorProfile> pending =
                creatorProfileRepository.findByApplicationStatusOrderByCreatedAtAsc(
                        CreatorApplicationStatus.PENDING, PageRequest.of(0, QUEUE_PAGE_SIZE));
        List<ApprovalWorkflowDto> dtos = new ArrayList<>();
        for (CreatorProfile p : pending) {
            dtos.add(
                    new ApprovalWorkflowDto(
                            TYPE_CREATOR_APPLICATION + ":" + p.getId(),
                            TYPE_CREATOR_APPLICATION,
                            p.getId(),
                            p.getDisplayName(),
                            "PENDING",
                            p.getCreatedAt(),
                            null,
                            null,
                            null));
        }
        return dtos;
    }

    /**
     * Priya's ruling, 2026-07-15: switched from the PENDING-only {@code
     * findByStatusOrderByCreatedAtAsc} to {@link ContentFlagRepository#findPendingReviewQueue} so
     * ESCALATED flags surface here too — ESCALATED is unresolved work (still awaiting a human
     * decision, see {@code ContentFlagStatus} javadoc), so it belongs in this "needs my review"
     * queue exactly like PENDING does. Ordering differs from the old PENDING-only read (ESCALATED
     * rows sort first, ahead of plain FIFO) but that's a deliberate improvement, not a side effect
     * to work around: a reassigned-up flag SHOULD surface ahead of the ordinary backlog for an
     * admin working this queue. The {@code status} field below now reports each row's real status
     * (PENDING or ESCALATED) instead of the old hardcoded {@code "PENDING"} literal, which would
     * otherwise misreport an escalated flag as merely pending.
     */
    private List<ApprovalWorkflowDto> contentModerationQueue() {
        List<ContentFlag> pending =
                contentFlagRepository.findPendingReviewQueue(PageRequest.of(0, QUEUE_PAGE_SIZE)).getContent();
        List<ApprovalWorkflowDto> dtos = new ArrayList<>();
        for (ContentFlag f : pending) {
            dtos.add(
                    new ApprovalWorkflowDto(
                            TYPE_CONTENT_MODERATION + ":" + f.getId(),
                            TYPE_CONTENT_MODERATION,
                            f.getId(),
                            // No standalone "name" concept for a content flag — synthesized from real
                            // fields (content type + reason), never fabricated, same spirit as
                            // AdminDashboardService's honest-zero convention for missing data.
                            f.getContentType().name() + ": " + f.getFlagReason(),
                            f.getStatus().name(),
                            f.getCreatedAt(),
                            f.getReviewedBy(),
                            f.getReviewedAt(),
                            null));
        }
        return dtos;
    }
}
