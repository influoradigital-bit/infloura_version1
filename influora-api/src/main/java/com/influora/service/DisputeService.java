package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Dispute;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DisputeOpenerType;
import com.influora.domain.enums.DisputeStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuditLogService;
import com.influora.service.admin.AdminContextService;
import com.influora.web.dto.dispute.DisputeDtos.DisputeListItemResponse;
import com.influora.web.dto.dispute.DisputeDtos.DisputeResponse;
import com.influora.web.dto.dispute.DisputeDtos.DisputeSummaryDto;
import com.influora.web.dto.dispute.DisputeDtos.OpenDisputeRequest;
import com.influora.web.dto.dispute.DisputeDtos.PagedDisputeSummaryDto;
import com.influora.web.dto.dispute.DisputeDtos.ResolveDisputeRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collaboration dispute case management (Task #34 / CEO §1.3, extended by P2-14's display-row
 * list endpoints). Opening a dispute freezes unreleased escrow via {@link
 * EscrowService#freezeUnreleasedForDispute} and moves the {@link Collaboration} to {@link
 * CollaborationStatus#DISPUTED}; resolution is admin-mediated only — no automatic refund or
 * clawback happens here (that stays a manual {@code EscrowService} release/refund action once the
 * admin has decided the outcome).
 *
 * <p>Reconstructed 2026-07-12 after an uncommitted copy of this file was lost mid-edit (confirmed
 * absent from both disk and {@code git log --all}). Rebuilt from the four controllers that still
 * reference it ({@code AdminDisputeController}, {@code BrandDisputeController}, {@code
 * CreatorDisputeController}, {@code DealController}) plus the P2-14 completion log in {@code
 * wiki/reports/2026-07-12/tasks/P2-14-content-review-disputes.md}, which documented the exact
 * method names/line ranges of the original.
 */
@Service
public class DisputeService {

    private static final Set<DisputeStatus> ACTIVE_DISPUTE_STATUSES =
            EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final DisputeRepository disputeRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EscrowService escrowService;
    private final BrandContextService brandContext;
    private final CreatorContextService creatorContext;
    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;

    public DisputeService(
            DisputeRepository disputeRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            CreatorProfileRepository creatorProfileRepository,
            WorkspaceRepository workspaceRepository,
            EscrowService escrowService,
            BrandContextService brandContext,
            CreatorContextService creatorContext,
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService) {
        this.disputeRepository = disputeRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.workspaceRepository = workspaceRepository;
        this.escrowService = escrowService;
        this.brandContext = brandContext;
        this.creatorContext = creatorContext;
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
    }

    /**
     * Task #34 — either party (brand or creator) on the deal may open a dispute. Identity/ownership
     * is resolved from {@link AuthPrincipal} — the {@code dealId} path param is never trusted on
     * its own, mirroring {@code DealService#requireOwnedCollaboration}.
     */
    @Transactional
    public DisputeResponse openDispute(
            AuthPrincipal principal, String dealId, OpenDisputeRequest body) {
        UserType role = requireBrandOrCreator(principal);
        Collaboration collaboration = requireOwnedCollaboration(principal, role, dealId);

        // [SEC/H-T34] a dispute is only meaningful against real money on hold — reject before any
        // side effect if there is no FUNDED, unreleased escrow to freeze.
        if (!escrowService.hasFundedUnreleasedEscrow(collaboration.getId())) {
            throw new ApiException(
                    "NO_FUNDED_ESCROW",
                    "This deal has no funded, unreleased escrow to dispute",
                    HttpStatus.CONFLICT);
        }

        if (disputeRepository.existsByCollaborationIdAndStatusIn(
                collaboration.getId(), ACTIVE_DISPUTE_STATUSES)) {
            throw new ApiException(
                    "DISPUTE_ALREADY_OPEN",
                    "This deal already has an active dispute",
                    HttpStatus.CONFLICT);
        }

        DisputeOpenerType openerType =
                role == UserType.CREATOR ? DisputeOpenerType.CREATOR : DisputeOpenerType.BRAND;
        Dispute dispute =
                Dispute.open(
                        Ulids.newUlid(),
                        collaboration.getId(),
                        openerType,
                        principal.getUserId(),
                        body.reason());

        // [H-T34-1] freeze escrow BEFORE the dispute row is persisted so a crash/rollback between
        // the two never leaves a persisted OPEN dispute with unfrozen (releasable) escrow.
        escrowService.freezeUnreleasedForDispute(collaboration.getId());
        disputeRepository.save(dispute);

        collaboration.transitionTo(CollaborationStatus.DISPUTED);
        collaborationRepository.save(collaboration);

        return toDisputeResponse(dispute);
    }

    /**
     * Admin-mediated status transition AND escrow settlement (Task #5, extending Task #34 / CEO
     * §1.3). An admin decides the outcome; this method moves the frozen escrow accordingly by
     * calling straight into {@link EscrowService}'s release/refund/split primitives — it never
     * re-implements a money-moving path of its own (Priya's directive: reuse {@code
     * EscrowService}/{@code EscrowController}'s existing escrow primitives). MFA-gated like the
     * rest of the admin surface ({@code AdminModerationService}, {@code ApprovalWorkflowService}).
     *
     * <p>Escrow settlement runs BEFORE the dispute row is flipped to a terminal status — same
     * ordering discipline as {@link #openDispute}'s freeze-before-save (H-T34-1): if the ledger
     * movement throws, the dispute is never persisted as resolved, so a dispute can never end up
     * "resolved" with its money left unmoved. Each settled {@link EscrowStatusResponse} is recorded
     * to {@code admin_audit_log} via {@link AdminAuditLogService#record} — the same audit primitive
     * every other {@code Admin*Service} money/moderation action uses — after both the escrow
     * movement and the dispute save have completed.
     *
     * <p><b>Kabir security review (phase1-admin-panel-escrow-security.md), both High findings
     * fixed here:</b>
     *
     * <ul>
     *   <li><b>Finding #1 (lost-update race):</b> {@link Dispute#getVersion()} is a JPA {@code
     *       @Version} column now. Two concurrent resolve calls that both load the dispute while
     *       still active will both pass the early {@code isActive()} check below and both settle
     *       escrow (the second's hold loop finds nothing left to move — that part was already
     *       race-safe), but only the first's {@link DisputeRepository#saveAndFlush} wins; the
     *       second's stale version fails Hibernate's {@code WHERE version = ?} check and throws
     *       {@link ObjectOptimisticLockingFailureException} — caught below and translated to a
     *       clean 409 ({@code DISPUTE_RESOLVE_CONFLICT}) instead of silently overwriting the first
     *       call's committed status. {@code saveAndFlush} (not plain {@code save}) is deliberate:
     *       it forces the version check to run synchronously inside this method, inside the try
     *       block, rather than deferring to transaction-commit time where this catch could never
     *       see it.
     *   <li><b>Finding #2 (audit trail can be silently skipped):</b> the unconditional {@code
     *       adminAuditLogService.record(..., "DISPUTE", ...)} call below always fires once the
     *       dispute itself is durably resolved — regardless of how many escrow holds settled (even
     *       zero) — so a resolution can never complete with zero audit trace. The existing
     *       per-settlement {@code ESCROW} entries remain as supplementary detail.
     * </ul>
     *
     * <p>Low finding also fixed: an already-terminal dispute (double-submit) now short-circuits to
     * a clean 409 {@code DISPUTE_ALREADY_RESOLVED} before any escrow call, instead of falling
     * through to {@link Dispute#resolve}'s bare {@code IllegalStateException} (which used to bubble
     * up as a generic 500 via {@code GlobalExceptionHandler}'s catch-all).
     */
    @Transactional
    public DisputeResponse resolveDispute(
            AuthPrincipal principal,
            HttpServletRequest request,
            String disputeId,
            ResolveDisputeRequest body) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        if (body.resolution() == null || !body.resolution().isResolved()) {
            throw new ApiException(
                    "INVALID_RESOLUTION",
                    "resolution must be a terminal RESOLVED_* status",
                    HttpStatus.BAD_REQUEST);
        }

        Dispute dispute =
                disputeRepository
                        .findById(disputeId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "DISPUTE_NOT_FOUND",
                                                "Dispute not found",
                                                HttpStatus.NOT_FOUND));

        // [Low finding] Fast-path rejection of an already-terminal dispute (double-submit is the
        // common case) BEFORE touching escrow at all — cheaper and cleaner than letting it run the
        // full settlement path only to fail later. The @Version check below still covers the true
        // concurrent race this can't see (two loads racing before either commits).
        if (!dispute.getStatus().isActive()) {
            throw new ApiException(
                    "DISPUTE_ALREADY_RESOLVED",
                    "This dispute has already been resolved",
                    HttpStatus.CONFLICT);
        }

        // [FIX: escrow-frozen-hold-fix-spec, Fix 3] Recorded BEFORE the settlement call runs, not
        // after — once adminReleaseForDispute/adminRefundForDispute/adminSplitForDispute actually
        // settle a hold it leaves FROZEN (moves to RELEASED/REFUNDED), so checking afterward would
        // always read "no frozen escrow" and defeat the point of this check.
        // [SEC: Kabir gate on CR-35, HIGH-2] The COUNT, not a boolean. See countFrozenHolds' javadoc:
        // a boolean is satisfied by one hold moving out of many, and reads false for a collaboration
        // a first dispute already settled — letting a second dispute resolve and audit-log a
        // movement that never happened, with no race required.
        int frozenHoldsBefore = escrowService.countFrozenHolds(dispute.getCollaborationId());

        // [Task #5] Move the frozen escrow BEFORE persisting the resolved status (freeze-before-save
        // discipline, mirrored from openDispute/H-T34-1) — a thrown ApiException here (e.g. no
        // wallet, missing fee config) rolls the whole transaction back, so the dispute never ends up
        // marked resolved without the money having actually moved.
        String auditAction;
        List<EscrowStatusResponse> settlements;
        switch (body.resolution()) {
            case RESOLVED_CREATOR -> {
                settlements = escrowService.adminReleaseForDispute(dispute.getCollaborationId());
                auditAction = "ESCROW_RELEASE";
            }
            case RESOLVED_BRAND -> {
                settlements = escrowService.adminRefundForDispute(dispute.getCollaborationId());
                auditAction = "ESCROW_REFUND";
            }
            case RESOLVED_SPLIT -> {
                settlements =
                        escrowService.adminSplitForDispute(
                                dispute.getCollaborationId(), body.creatorSplitPercent());
                auditAction = "ESCROW_RELEASE";
            }
            default ->
                    throw new ApiException(
                            "INVALID_RESOLUTION",
                            "resolution must be a terminal RESOLVED_* status",
                            HttpStatus.BAD_REQUEST);
        }

        // [FIX: escrow-frozen-hold-fix-spec, Fix 3 — "make the invariant real"] The class/method
        // javadoc above has always CLAIMED that a thrown exception here rolls the whole transaction
        // back, so "the dispute never ends up marked resolved without the money having actually
        // moved." That claim held for a thrown exception. It did NOT hold for an empty settlement
        // list: if this collaboration had frozen escrow (checked above, before the settlement ran)
        // and the settlement call still moved zero holds, the loop above simply completed with
        // `settlements = []` and execution fell straight through to `dispute.resolve(...)` below —
        // marking the dispute resolved, and `adminAuditLogService` logging ESCROW_RELEASE/
        // ESCROW_REFUND, for a movement that never happened. That was the actual bug (CR-22a finding
        // #2): the null-`collaboration_id` hold was just what triggered it; Fix 1 + Fix 2 above close
        // that specific trigger, but without this check a *future* regression in the lookup would
        // reopen the same silent-mis-statement failure mode. Refuse loudly instead.
        //
        // A collaboration with NO escrow at all (hadFrozenEscrow == false) is a legitimate case — a
        // dispute opened on a deal whose escrow was never funded — and must still resolve cleanly;
        // this branch is never taken for it, regardless of settlements being empty.
        // [SEC: Kabir gate on CR-35, HIGH-2] Compare COUNTS, not emptiness. `settlements.isEmpty()`
        // passes as soon as a single hold moves, so a partial settlement — some holds moved, others
        // silently skipped because they slipped out of FROZEN between the read and the lock — would
        // have sailed through and been audit-logged as a completed movement.
        //
        // Deliberately `<` and not `!=`: moving FEWER holds than were frozen is the invariant
        // violation this guard exists for. Moving MORE can only mean a hold was frozen concurrently
        // after the count was taken, which is not a mis-statement and must not spuriously 409 a
        // legitimate resolution.
        if (settlements.size() < frozenHoldsBefore) {
            throw new ApiException(
                    "DISPUTE_SETTLEMENT_EMPTY",
                    "This collaboration had "
                            + frozenHoldsBefore
                            + " frozen escrow hold(s) but the settlement moved only "
                            + settlements.size()
                            + " — refusing to mark the dispute resolved without the money actually moving",
                    HttpStatus.CONFLICT);
        }

        DisputeStatus previousStatus = dispute.getStatus();
        try {
            dispute.resolve(body.resolution(), admin.getId(), body.notes());
        } catch (IllegalStateException e) {
            // Defense in depth: the isActive() fast-path above already covers the common
            // double-submit case, but this catches the same condition if it's ever reached a
            // different way — never let it fall through to a generic 500.
            throw new ApiException(
                    "DISPUTE_ALREADY_RESOLVED",
                    "This dispute has already been resolved",
                    HttpStatus.CONFLICT);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_RESOLUTION",
                    "resolution must be a terminal RESOLVED_* status",
                    HttpStatus.BAD_REQUEST);
        }

        try {
            // saveAndFlush (not save) so the @Version WHERE-clause check runs synchronously here,
            // inside this try block — see Finding #1 in the class/method javadoc above.
            disputeRepository.saveAndFlush(dispute);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ApiException(
                    "DISPUTE_RESOLVE_CONFLICT",
                    "This dispute was already resolved by someone else, please refresh.",
                    HttpStatus.CONFLICT);
        }

        // [Finding #2] Unconditional top-level audit entry — fires regardless of settlement count
        // (including zero), so the resolution decision itself can never go unrecorded.
        Map<String, Object> before = Map.of("id", dispute.getId(), "status", previousStatus.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", dispute.getId());
        after.put("status", dispute.getStatus().name());
        after.put("resolvedByAdminId", dispute.getResolvedByAdminId());
        adminAuditLogService.record(
                principal,
                request,
                "UPDATE",
                "DISPUTE",
                dispute.getId(),
                before,
                after,
                "Dispute " + disputeId + " resolved " + body.resolution() + " by admin " + admin.getId());

        for (EscrowStatusResponse settlement : settlements) {
            adminAuditLogService.record(
                    principal,
                    request,
                    auditAction,
                    "ESCROW",
                    settlement.escrowHoldId(),
                    null,
                    null,
                    "Dispute "
                            + disputeId
                            + " resolved "
                            + body.resolution()
                            + " — escrow hold "
                            + settlement.escrowHoldId()
                            + " settled ("
                            + settlement.amount()
                            + " "
                            + settlement.currency()
                            + (body.notes() != null && !body.notes().isBlank() ? "): " + body.notes() : ")"));
        }

        return toDisputeResponse(dispute);
    }

    /**
     * Task #4 — admin disputes list with pagination and filtering. Returns all disputes with
     * campaign ID, brand name, creator name for the admin disputes table view. MFA-gated.
     */
    @Transactional(readOnly = true)
    public PagedDisputeSummaryDto list(
            AuthPrincipal principal,
            int page,
            int pageSize,
            DisputeStatus status,
            String campaignId,
            String brandId,
            String creatorId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(Math.min(pageSize, 100), 1);
        PageRequest pageRequest =
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Dispute> result;
        if (status != null || campaignId != null || brandId != null || creatorId != null) {
            result = disputeRepository.findFiltered(status, campaignId, brandId, creatorId, pageRequest);
        } else {
            result = disputeRepository.findAll(pageRequest);
        }

        Map<String, Campaign> campaignCache = new HashMap<>();
        Map<String, String> workspaceNameCache = new HashMap<>();
        Map<String, String> creatorNameCache = new HashMap<>();

        List<DisputeSummaryDto> items =
                result.getContent().stream()
                        .map(dispute -> {
                            Collaboration collaboration =
                                    collaborationRepository
                                            .findById(dispute.getCollaborationId())
                                            .orElse(null);

                            if (collaboration == null) {
                                return new DisputeSummaryDto(
                                        dispute.getId(),
                                        null,
                                        "Unknown",
                                        "Unknown",
                                        dispute.getStatus().name(),
                                        dispute.getCreatedAt(),
                                        dispute.getUpdatedAt());
                            }

                            Campaign campaign =
                                    campaignCache.computeIfAbsent(
                                            collaboration.getCampaignId(),
                                            id -> campaignRepository.findById(id).orElse(null));

                            String workspaceName =
                                    workspaceNameCache.computeIfAbsent(
                                            campaign != null ? campaign.getWorkspaceId() : "unknown",
                                            workspaceId -> {
                                                if (workspaceId.equals("unknown")) return "Unknown";
                                                Workspace workspace =
                                                        workspaceRepository.findById(workspaceId).orElse(null);
                                                return workspace != null ? workspace.getName() : "Unknown";
                                            });

                            String creatorName =
                                    creatorNameCache.computeIfAbsent(
                                            collaboration.getCreatorId(),
                                            userId -> {
                                                CreatorProfile creator =
                                                        creatorProfileRepository
                                                                .findByUserId(userId)
                                                                .orElse(null);
                                                return creator != null
                                                        ? creator.getDisplayName()
                                                        : "Unknown";
                                            });

                            return new DisputeSummaryDto(
                                    dispute.getId(),
                                    collaboration.getCampaignId(),
                                    workspaceName,
                                    creatorName,
                                    dispute.getStatus().name(),
                                    dispute.getCreatedAt(),
                                    dispute.getUpdatedAt());
                        })
                        .toList();

        int totalPages = result.getTotalPages();
        return new PagedDisputeSummaryDto(
                items, result.getTotalElements(), safePage, safePageSize, totalPages);
    }

    /** B7 — original paginated brand dispute list (no display fields). */
    @Transactional(readOnly = true)
    public PagedDisputes listForBrand(AuthPrincipal principal, int page, int limit) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        PageRequest pageRequest =
                PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Dispute> result = disputeRepository.findByWorkspaceId(workspace.getId(), pageRequest);
        List<DisputeResponse> items = result.getContent().stream().map(this::toDisputeResponse).toList();
        PageMeta meta =
                new PageMeta(safePage, safeLimit, result.getTotalElements(), result.hasNext());
        return new PagedDisputes(items, meta);
    }

    /**
     * P2-14 — brand-scoped dispute list with display fields (campaign name, creator name, deal
     * value). Replaces {@code api.ts}'s client-side derivation from {@code /deals}.
     */
    @Transactional(readOnly = true)
    public List<DisputeListItemResponse> listDisplayForBrand(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        List<Object[]> rows = disputeRepository.findWithCollaborationByWorkspaceId(workspace.getId());
        return buildDisputeDisplayRows(rows, UserType.BRAND);
    }

    /**
     * P2-14 — creator-scoped dispute list with display fields (campaign name, brand name, deal
     * value). Replaces {@code api.ts}'s client-side derivation from {@code /deals}.
     */
    @Transactional(readOnly = true)
    public List<DisputeListItemResponse> listDisplayForCreator(AuthPrincipal principal) {
        creatorContext.requireCreator(principal);
        List<Object[]> rows =
                disputeRepository.findWithCollaborationByCreatorUserId(principal.getUserId());
        return buildDisputeDisplayRows(rows, UserType.CREATOR);
    }

    /**
     * Joins each {@code [Dispute, Collaboration]} row with its {@link Campaign} (for name/currency)
     * and the counterparty display name (workspace name for a creator viewer, creator display name
     * for a brand viewer) — memoized per request so repeated campaigns/counterparties across
     * multiple disputes are only fetched once.
     */
    private List<DisputeListItemResponse> buildDisputeDisplayRows(
            List<Object[]> rows, UserType viewerRole) {
        Map<String, Campaign> campaignCache = new HashMap<>();
        Map<String, String> counterpartyNameCache = new HashMap<>();
        List<DisputeListItemResponse> out = new ArrayList<>(rows.size());

        for (Object[] row : rows) {
            Dispute dispute = (Dispute) row[0];
            Collaboration collaboration = (Collaboration) row[1];

            Campaign campaign =
                    campaignCache.computeIfAbsent(
                            collaboration.getCampaignId(),
                            id -> campaignRepository.findById(id).orElse(null));
            String campaignName = campaign != null ? campaign.getTitle() : "Campaign";

            String counterpartyKey =
                    viewerRole == UserType.BRAND
                            ? "creator:" + collaboration.getCreatorId()
                            : "workspace:" + (campaign != null ? campaign.getWorkspaceId() : "unknown");
            String counterpartyName =
                    counterpartyNameCache.computeIfAbsent(
                            counterpartyKey,
                            key -> resolveCounterpartyName(viewerRole, collaboration, campaign));

            out.add(
                    new DisputeListItemResponse(
                            collaboration.getId(),
                            campaignName,
                            counterpartyName,
                            collaboration.getAgreedRate(),
                            collaboration.getCurrency(),
                            dispute.getStatus().name(),
                            dispute.getCreatedAt(),
                            dispute.getReason()));
        }
        return out;
    }

    private String resolveCounterpartyName(
            UserType viewerRole, Collaboration collaboration, Campaign campaign) {
        if (viewerRole == UserType.BRAND) {
            CreatorProfile creator =
                    creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
            return creator != null ? creator.getDisplayName() : "Creator";
        }
        String workspaceId = campaign != null ? campaign.getWorkspaceId() : null;
        if (workspaceId == null) {
            return "Brand";
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        return workspace != null ? workspace.getName() : "Brand";
    }

    private Collaboration requireOwnedCollaboration(
            AuthPrincipal principal, UserType role, String dealId) {
        if (role == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return collaborationRepository
                    .findByIdAndCreatorId(dealId, principal.getUserId())
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(dealId, workspace.getId())
                .orElseThrow(
                        () -> new ApiException("DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private UserType requireBrandOrCreator(AuthPrincipal principal) {
        if (principal == null
                || (principal.getUserType() != UserType.CREATOR
                        && principal.getUserType() != UserType.BRAND)) {
            throw new ApiException(
                    "WRONG_USER_TYPE",
                    "This endpoint is for brand or creator accounts only",
                    HttpStatus.FORBIDDEN);
        }
        return principal.getUserType();
    }

    private DisputeResponse toDisputeResponse(Dispute dispute) {
        return new DisputeResponse(
                dispute.getId(),
                dispute.getCollaborationId(),
                dispute.getOpenedByType().name(),
                dispute.getOpenedByUserId(),
                dispute.getReason(),
                dispute.getStatus().name(),
                dispute.getCreatedAt(),
                dispute.getResolvedByAdminId(),
                dispute.getResolutionNotes(),
                dispute.getResolvedAt());
    }

    public record PagedDisputes(List<DisputeResponse> items, PageMeta meta) {}
}
