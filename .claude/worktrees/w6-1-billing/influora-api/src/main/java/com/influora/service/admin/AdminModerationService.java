package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.repository.ContentFlagRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminModerationDtos.ActionFlagRequest;
import com.influora.web.dto.admin.AdminModerationDtos.FlagDto;
import com.influora.web.dto.admin.AdminModerationDtos.PagedFlagsDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * higher-tier review (P2+ scope, not implemented this cycle — would integrate with support ticket
 * creation or a dedicated escalation queue).
 *
 * <p><b>Audit trail:</b> flag actions are logged via {@link AdminAuditLogService} using entity type
 * {@code CONTENT_FLAG} (field allow-list: {@code id, status, actionTaken, reviewedBy} — no {@code
 * contentPreview} logged to avoid capturing user PII verbatim).
 */
@Service
public class AdminModerationService {

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final ContentFlagRepository contentFlagRepository;

    public AdminModerationService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            ContentFlagRepository contentFlagRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.contentFlagRepository = contentFlagRepository;
    }

    /**
     * List pending content flags (PENDING status only, oldest first). Backs {@code
     * moderationApi.listFlags}.
     */
    public PagedFlagsDto listFlags(AuthPrincipal principal, int page, int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        PageRequest pageRequest =
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<ContentFlag> flagPage =
                contentFlagRepository.findByStatusOrderByCreatedAtAsc(
                        ContentFlagStatus.PENDING, pageRequest);

        List<FlagDto> items = flagPage.getContent().stream().map(this::toDto).toList();

        return new PagedFlagsDto(items, (int) flagPage.getTotalElements(), page, pageSize);
    }

    /**
     * Take action on a content flag. Backs {@code moderationApi.actionFlag}. Supported actions:
     * "REMOVE" (content removed, flag → ACTIONED), "REJECT" (false positive, flag → REVIEWED),
     * "ESCALATE" (P2+, not implemented this cycle). Action values match {@code ModerationAction} in
     * {@code admin.types.ts}.
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

        // Validate flag is pending
        if (flag.getStatus() != ContentFlagStatus.PENDING) {
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
                // P2+ scope — would create support ticket or move to escalation queue
                throw new ApiException(
                        "NOT_IMPLEMENTED",
                        "Escalation not yet implemented",
                        HttpStatus.NOT_IMPLEMENTED);

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
