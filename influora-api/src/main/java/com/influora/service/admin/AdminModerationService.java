package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminModerationDtos.AccountSuspensionDto;
import com.influora.web.dto.admin.AdminModerationDtos.ActionFlagRequest;
import com.influora.web.dto.admin.AdminModerationDtos.FlagDto;
import com.influora.web.dto.admin.AdminModerationDtos.PagedFlagsDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Content moderation service for admin panel (P2-6). Backs {@link
 * com.influora.web.AdminModerationController} — manages the content flag queue and actions on
 * flagged content.
 *
 * <p><b>Entity reuse:</b> {@link ContentFlag} (created in V34__admin_tables.sql, JPA entity added
 * in cycle 5) is the single source of truth for all flagged content. This service provides write
 * operations (action/reject/escalate) — prior cycles only read flags for display counts.
 *
 * <p><b>RBAC:</b> role allow-lists come from {@code
 * src/admin/__tests__/role-permission-matrix.md}'s Content Moderation section — list/action are
 * {@code SUPER_ADMIN/ADMIN/SUPPORT} (all admin tiers can moderate content, matching the "SUPPORT
 * can triage" precedent from support tickets).
 *
 * <p><b>Action semantics:</b> "remove" = content violation, content should be removed, flag status
 * → ACTIONED. "reject" = false positive, no violation, flag status → REVIEWED. "escalate" = needs
 * higher-tier ({@code SUPER_ADMIN}) review — flag status → {@code ESCALATED}, a non-terminal state
 * (Priya's ruling, 2026-07-15: an earlier version marked escalated flags ACTIONED, which silently
 * dropped them out of the PENDING-only queue before any human had reviewed them — a regression
 * worse than the 501 stub it replaced). {@link #listFlags} surfaces ESCALATED flags alongside
 * PENDING ones (sorted first) via {@link ContentFlagRepository#findPendingReviewQueue}, and {@link
 * #actionFlag} still accepts ESCALATED flags for a later REMOVE/REJECT/WARN so a senior admin can
 * resolve them. Reason is mandatory for "escalate" (enforced service-side, {@code
 * REASON_REQUIRED}/400) since it is what the next reviewer acts on.
 *
 * <p><b>Audit trail:</b> flag actions are logged via {@link AdminAuditLogService} using entity type
 * {@code CONTENT_FLAG} (field allow-list: {@code id, status, actionTaken, reviewedBy} — no {@code
 * contentPreview} logged to avoid capturing user PII verbatim).
 */
@Service
public class AdminModerationService {

    private static final String APPEAL_STATUS_NONE = "NONE";

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final ContentFlagRepository contentFlagRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    public AdminModerationService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            ContentFlagRepository contentFlagRepository,
            WorkspaceRepository workspaceRepository,
            CreatorProfileRepository creatorProfileRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.contentFlagRepository = contentFlagRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * {@code GET /admin/moderation/suspensions} — every currently-suspended account (brands/agencies
     * from {@code Workspace}, creators from {@code CreatorProfile}), newest {@code Workspace} rows
     * then creator rows. Read-only. See {@link AccountSuspensionDto} for the exact per-field mapping
     * and the two declared limitations ({@code appealStatus} always {@code NONE} — no appeal schema
     * yet; {@code reinstatedAt}/{@code reinstatedBy} always {@code null} — these accounts are still
     * suspended). Gated SUPER_ADMIN + ADMIN (account-management data, tighter than content triage).
     */
    @Transactional(readOnly = true)
    public List<AccountSuspensionDto> getSuspensions(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<AccountSuspensionDto> suspensions = new ArrayList<>();
        for (Workspace w : workspaceRepository.findBySuspendedTrue()) {
            suspensions.add(
                    new AccountSuspensionDto(
                            w.getId(),
                            w.getId(),
                            "BRAND",
                            w.getName(),
                            w.getSuspendedReason(),
                            w.getSuspendedBy(),
                            w.getSuspendedAt() == null ? null : w.getSuspendedAt().toString(),
                            APPEAL_STATUS_NONE,
                            null,
                            null,
                            null));
        }
        for (CreatorProfile c : creatorProfileRepository.findBySuspendedTrue()) {
            suspensions.add(
                    new AccountSuspensionDto(
                            c.getUserId(),
                            c.getUserId(),
                            "CREATOR",
                            c.getDisplayName(),
                            c.getSuspendedReason(),
                            c.getSuspendedBy(),
                            c.getSuspendedAt() == null ? null : c.getSuspendedAt().toString(),
                            APPEAL_STATUS_NONE,
                            null,
                            null,
                            null));
        }
        return suspensions;
    }

    /**
     * List the review queue: PENDING flags plus any ESCALATED flags (escalated sorted first, then
     * oldest-first within each group). Backs {@code moderationApi.listFlags}.
     *
     * <p>Widened from PENDING-only (Priya's ruling, 2026-07-15) so an escalated flag never
     * disappears from the queue a human actually looks at — see {@link
     * ContentFlagRepository#findPendingReviewQueue} for why this is a new repository method rather
     * than a change to the shared {@code findByStatusOrderByCreatedAtAsc}. No {@code Sort} is
     * attached to the {@link PageRequest} here — the ordering is fully specified in that query.
     */
    public PagedFlagsDto listFlags(AuthPrincipal principal, int page, int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);

        Page<ContentFlag> flagPage = contentFlagRepository.findPendingReviewQueue(pageRequest);

        List<FlagDto> items = flagPage.getContent().stream().map(this::toDto).toList();

        return new PagedFlagsDto(items, (int) flagPage.getTotalElements(), page, pageSize);
    }

    /**
     * Take action on a content flag. Backs {@code moderationApi.actionFlag}. Supported actions:
     * "REMOVE" (content removed, flag → ACTIONED), "REJECT" (false positive, flag → REVIEWED),
     * "WARN" (warning issued, flag → REVIEWED), "ESCALATE" (raised for SUPER_ADMIN-tier review,
     * flag → ESCALATED — non-terminal, reason mandatory). Action values match {@code
     * ModerationAction} in {@code admin.types.ts}.
     *
     * <p>Actionable starting states are PENDING and ESCALATED — REVIEWED/ACTIONED are terminal.
     * This lets a flag already escalated by one admin still be resolved (REMOVE/REJECT/WARN) by
     * whichever admin picks it up next, same role allow-list as before (no new RBAC tier was
     * added — "senior admin" here just means whoever from SUPER_ADMIN/ADMIN/SUPPORT reviews the
     * escalated queue, matching the existing gate).
     */
    @Transactional
    public FlagDto actionFlag(
            AuthPrincipal principal,
            jakarta.servlet.http.HttpServletRequest request,
            String flagId,
            ActionFlagRequest body) {
        String action = body.action();
        String reason = body.reason();
        com.influora.domain.entity.AdminUser admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        String adminId = admin.getId();

        ContentFlag flag =
                contentFlagRepository
                        .findById(flagId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "FLAG_NOT_FOUND",
                                                "Content flag not found",
                                                HttpStatus.NOT_FOUND));

        // Validate flag is actionable: PENDING (initial) or ESCALATED (reassigned upward, still
        // awaiting a resolving decision) may be acted on. REVIEWED/ACTIONED are terminal.
        if (flag.getStatus() != ContentFlagStatus.PENDING
                && flag.getStatus() != ContentFlagStatus.ESCALATED) {
            throw new ApiException(
                    "FLAG_ALREADY_ACTIONED",
                    "Flag has already been reviewed or actioned",
                    HttpStatus.BAD_REQUEST);
        }

        // Apply action (values match ModerationAction['action'] in admin.types.ts)
        switch (action) {
            case "REMOVE":
                flag.markActioned("REMOVE", adminId);
                adminAuditLogService.record(
                        principal,
                        request,
                        "ACTION",
                        "CONTENT_FLAG",
                        flag.getId(),
                        java.util.Map.of("id", flag.getId(), "status", flag.getStatus().name()),
                        java.util.Map.of("id", flag.getId(), "status", "ACTIONED", "actionTaken", "REMOVE"),
                        reasonOrDefault(reason, "Removed flagged content"));
                break;

            case "REJECT":
                flag.markReviewed(adminId);
                adminAuditLogService.record(
                        principal,
                        request,
                        "REVIEW",
                        "CONTENT_FLAG",
                        flag.getId(),
                        java.util.Map.of("id", flag.getId(), "status", flag.getStatus().name()),
                        java.util.Map.of("id", flag.getId(), "status", "REVIEWED"),
                        reasonOrDefault(reason, "Rejected flag as false positive"));
                break;

            case "WARN":
                // WARN action: flag → REVIEWED, admin noted concern but didn't remove
                flag.markReviewed(adminId);
                adminAuditLogService.record(
                        principal,
                        request,
                        "WARN",
                        "CONTENT_FLAG",
                        flag.getId(),
                        java.util.Map.of("id", flag.getId(), "status", flag.getStatus().name()),
                        java.util.Map.of("id", flag.getId(), "status", "REVIEWED"),
                        reasonOrDefault(reason, "Issued warning for flagged content"));
                break;

            case "ESCALATE":
                // Reason is mandatory for escalation (unlike REMOVE/REJECT/WARN, which fall back
                // to a default) — it's the only signal the next reviewer gets.
                if (reason == null || reason.isBlank()) {
                    throw new ApiException(
                            "REASON_REQUIRED",
                            "A reason is required to escalate a content flag",
                            HttpStatus.BAD_REQUEST);
                }
                // Non-terminal: status -> ESCALATED, NOT ACTIONED. An escalated flag must keep
                // surfacing in listFlags()'s review queue until a later REMOVE/REJECT/WARN
                // resolves it (Priya's ruling, 2026-07-15 — mirrors class javadoc's "Action
                // semantics" note). actionTaken="ESCALATE" + the audit log row record why/when it
                // was escalated; there is still no dedicated escalation-queue entity.
                flag.markEscalated(adminId);
                adminAuditLogService.record(
                        principal,
                        request,
                        "ESCALATE",
                        "CONTENT_FLAG",
                        flag.getId(),
                        java.util.Map.of("id", flag.getId(), "status", flag.getStatus().name()),
                        java.util.Map.of(
                                "id", flag.getId(), "status", "ESCALATED", "actionTaken", "ESCALATE"),
                        reason);
                break;

            default:
                throw new ApiException(
                        "INVALID_ACTION",
                        "Action must be REMOVE, REJECT, WARN, or ESCALATE",
                        HttpStatus.BAD_REQUEST);
        }

        ContentFlag saved = contentFlagRepository.save(flag);
        return toDto(saved);
    }

    /**
     * Returns the admin-typed reason (from {@link ActionFlagRequest#reason()}) when present, else a
     * sensible default description of the action taken. Ensures the admin's explanation actually
     * reaches the audit trail (Kavya QA fix — see {@code wiki/errors/P2-6-qa-review.md} issue #1).
     */
    private static String reasonOrDefault(String reason, String fallback) {
        return (reason != null && !reason.isBlank()) ? reason : fallback;
    }

    private FlagDto toDto(ContentFlag flag) {
        return new FlagDto(
                flag.getId(),
                flag.getContentType(),
                flag.getContentId(),
                flag.getContentPreview(),
                flag.getFlagReason(),
                flag.getFlaggedBy(),
                flag.getFlaggedByUserId(),
                flag.getStatus(),
                flag.getActionTaken(),
                flag.getReviewedBy(),
                flag.getReviewedAt(),
                flag.getCreatedAt());
    }
}
