package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.entity.SupportTicketMessage;
import com.influora.domain.entity.SupportTicketMessage.SenderType;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminUserRepository;
import com.influora.repository.SupportTicketMessageRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.SupportTicketSpecs;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminSupportDtos.PagedTicketsDto;
import com.influora.web.dto.admin.AdminSupportDtos.TicketDetailDto;
import com.influora.web.dto.admin.AdminSupportDtos.TicketMessageDto;
import com.influora.web.dto.admin.AdminSupportDtos.TicketSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support ticket CRUD/triage API (src/admin/TASK_ASSIGNMENTS.md P2: "Support ticket API" —
 * Vikram, cycle 6). Backs {@code AdminSupportController} — the eventual swap-in target for
 * {@code supportApi.list}/{@code .getById}/{@code .reply}/{@code .update}/{@code .assign} in
 * {@code src/admin/services/api-contracts.ts} (Priya). {@code src/admin/components/support/}
 * has no {@code TicketList.tsx} yet as of this cycle (checked before writing this — the directory
 * exists but is empty), so this service was built directly off {@code SupportTicket}/{@code
 * TicketDetail}/{@code TicketMessage}/{@code TicketFilters} in {@code admin.types.ts} rather than
 * a specific component's stubbed handlers. {@code supportApi.escalate}/{@code .getStats} are NOT
 * built this cycle — no caller needs them yet, same "flagged as follow-up, not silently forgotten"
 * pattern used by every other {@code Admin*Service}.
 *
 * <p><b>Entity reuse:</b> {@link SupportTicket} (read-only until this cycle, see its class
 * javadoc) and the new {@link SupportTicketMessage} map directly onto {@code support_tickets}/
 * {@code support_ticket_messages} (V34__admin_tables.sql) — no duplicate tables added.
 * {@code support_tickets.user_id} is a plain {@code users.id} FK regardless of {@code userType}
 * (BRAND or CREATOR), so ticket-requester display name is resolved via {@link UserRepository}
 * directly — no need to join through {@code Workspace}/{@code CreatorProfile} the way {@code
 * AdminBrandService}/{@code AdminCreatorService} do for their own detail views.
 *
 * <p><b>RBAC:</b> role allow-lists come straight from {@code
 * src/admin/__tests__/role-permission-matrix.md}'s Support section — list/detail/reply/status
 * update are SUPER_ADMIN/ADMIN/SUPPORT ("Support can triage" / "Support can respond"); {@link
 * #assign} is SUPER_ADMIN/ADMIN only ("ADMIN+ assigns to team", SUPPORT excluded).
 *
 * <p><b>Audit trail — PII guardrail (this cycle's explicit scope note):</b> {@link #updateStatus}
 * and {@link #assign} call {@link AdminAuditLogService#record} using the pre-reserved {@code
 * SUPPORT_TICKET} entity type, whose field allow-list ({@code AdminAuditLogService.FIELD_ALLOWLIST})
 * is {@code id, status, priority, assignedTo, category} — metadata only. {@link #reply}
 * deliberately does NOT call the audit writer at all: message bodies can contain user PII
 * (account details, complaint specifics) and there is no allow-listed field for message content
 * to safely log even if a call were added, per {@code AUDIT-LOG-WRITE-SPEC.md} Rule 2 ("never a
 * raw entity dump" / never log free-text user content). If a "ticket replied to" audit trail is
 * ever required, it must log ticket id + message id only, never {@code content}.
 */
@Service
public class AdminSupportService {

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final SupportTicketRepository supportTicketRepository;
    private final SupportTicketMessageRepository supportTicketMessageRepository;
    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;

    public AdminSupportService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            SupportTicketRepository supportTicketRepository,
            SupportTicketMessageRepository supportTicketMessageRepository,
            UserRepository userRepository,
            AdminUserRepository adminUserRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.supportTicketRepository = supportTicketRepository;
        this.supportTicketMessageRepository = supportTicketMessageRepository;
        this.userRepository = userRepository;
        this.adminUserRepository = adminUserRepository;
    }

    @Transactional(readOnly = true)
    public PagedTicketsDto list(
            AuthPrincipal principal,
            String status,
            String priority,
            String userType,
            String assignedTo,
            String category,
            String search,
            int page,
            int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        TicketStatus statusEnum = parseEnum(TicketStatus.class, status, "status");
        TicketPriority priorityEnum = parseEnum(TicketPriority.class, priority, "priority");
        UserType userTypeEnum = parseEnum(UserType.class, userType, "userType");

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        Specification<SupportTicket> spec =
                SupportTicketSpecs.withFilters(
                        statusEnum, priorityEnum, userTypeEnum, assignedTo, category, search);
        Page<SupportTicket> result =
                supportTicketRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<TicketSummaryDto> data = result.getContent().stream().map(this::toSummaryDto).toList();
        return new PagedTicketsDto(
                data, result.getTotalElements(), safePage, safePageSize, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TicketDetailDto getById(AuthPrincipal principal, String ticketId) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        SupportTicket ticket = requireTicket(ticketId);
        return toDetailDto(ticket);
    }

    @Transactional
    public TicketDetailDto reply(
            AuthPrincipal principal, HttpServletRequest request, String ticketId, String content) {
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        SupportTicket ticket = requireTicket(ticketId);

        SupportTicketMessage message =
                SupportTicketMessage.create(
                        Ulids.newUlid(), ticket.getId(), admin.getId(), SenderType.ADMIN, content);
        supportTicketMessageRepository.save(message);

        // Deliberately NOT calling adminAuditLogService.record here — see class javadoc's "Audit
        // trail — PII guardrail" note. Message content must never reach the audit trail.

        return toDetailDto(ticket);
    }

    @Transactional
    public TicketDetailDto updateStatus(
            AuthPrincipal principal, HttpServletRequest request, String ticketId, String status, String reason) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        SupportTicket ticket = requireTicket(ticketId);

        TicketStatus toStatus = parseEnum(TicketStatus.class, status, "status");
        if (toStatus == null) {
            throw new ApiException(
                    "INVALID_TICKET_STATUS",
                    "status must be one of OPEN, IN_PROGRESS, WAITING_USER, RESOLVED, CLOSED",
                    HttpStatus.BAD_REQUEST);
        }
        TicketStatus fromStatus = ticket.getStatus();

        ticket.updateStatus(toStatus);
        supportTicketRepository.save(ticket);

        // Metadata only, per AUDIT-LOG-WRITE-SPEC.md Rule 2 — never the ticket's message thread.
        adminAuditLogService.record(
                principal,
                request,
                "UPDATE",
                "SUPPORT_TICKET",
                ticket.getId(),
                Map.of("id", ticket.getId(), "status", fromStatus.name()),
                Map.of("id", ticket.getId(), "status", toStatus.name()),
                reason);

        return toDetailDto(ticket);
    }

    @Transactional
    public TicketDetailDto assign(
            AuthPrincipal principal, HttpServletRequest request, String ticketId, String assignedTo) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        SupportTicket ticket = requireTicket(ticketId);

        String normalizedAssignee = assignedTo != null && !assignedTo.isBlank() ? assignedTo : null;
        if (normalizedAssignee != null
                && adminUserRepository.findById(normalizedAssignee).filter(AdminUser::isActive).isEmpty()) {
            throw new ApiException(
                    "ASSIGNEE_NOT_FOUND", "Target admin not found or inactive", HttpStatus.BAD_REQUEST);
        }

        String fromAssignee = ticket.getAssignedTo();
        ticket.assignTo(normalizedAssignee);
        supportTicketRepository.save(ticket);

        adminAuditLogService.record(
                principal,
                request,
                "UPDATE",
                "SUPPORT_TICKET",
                ticket.getId(),
                Map.of("id", ticket.getId(), "assignedTo", fromAssignee != null ? fromAssignee : ""),
                Map.of("id", ticket.getId(), "assignedTo", normalizedAssignee != null ? normalizedAssignee : ""),
                null);

        return toDetailDto(ticket);
    }

    private SupportTicket requireTicket(String ticketId) {
        return supportTicketRepository
                .findById(ticketId)
                .orElseThrow(
                        () -> new ApiException("TICKET_NOT_FOUND", "Ticket not found", HttpStatus.NOT_FOUND));
    }

    // ============================================
    // DTO ASSEMBLY
    // ============================================

    private TicketSummaryDto toSummaryDto(SupportTicket ticket) {
        return new TicketSummaryDto(
                ticket.getId(),
                ticket.getUserId(),
                ticket.getUserType().name(),
                resolveUserName(ticket.getUserId()),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getPriority().name(),
                ticket.getAssignedTo(),
                resolveAdminName(ticket.getAssignedTo()),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getResolvedAt());
    }

    private TicketDetailDto toDetailDto(SupportTicket ticket) {
        List<TicketMessageDto> messages =
                supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                        .map(this::toMessageDto)
                        .toList();

        return new TicketDetailDto(
                ticket.getId(),
                ticket.getUserId(),
                ticket.getUserType().name(),
                resolveUserName(ticket.getUserId()),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getStatus().name(),
                ticket.getPriority().name(),
                ticket.getAssignedTo(),
                resolveAdminName(ticket.getAssignedTo()),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getResolvedAt(),
                messages,
                // Honest empty list — no deliverable/payment/contract cross-reference table exists
                // for support tickets yet, see class javadoc.
                List.of());
    }

    private TicketMessageDto toMessageDto(SupportTicketMessage message) {
        String senderName =
                message.getSenderType() == SenderType.ADMIN
                        ? resolveAdminName(message.getSenderId())
                        : resolveUserName(message.getSenderId());
        return new TicketMessageDto(
                message.getId(),
                message.getSenderId(),
                senderName,
                message.getSenderType().name(),
                message.getContent(),
                message.getCreatedAt());
    }

    private String resolveUserName(String userId) {
        if (userId == null) {
            return null;
        }
        return userRepository
                .findById(userId)
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                .orElse(null);
    }

    private String resolveAdminName(String adminId) {
        if (adminId == null) {
            return null;
        }
        return adminUserRepository.findById(adminId).map(AdminUser::getEmail).orElse(null);
    }

    /**
     * Returns {@code null} if {@code raw} is null/blank (meaning "no filter"), or throws 400 if
     * {@code raw} is non-blank but doesn't match any enum constant — never silently ignores an
     * unrecognized filter value, same discipline {@code AdminAuditLogService} uses for its own
     * action/entity_type allow-lists.
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_" + fieldName.toUpperCase(),
                    "Invalid " + fieldName + ": " + raw,
                    HttpStatus.BAD_REQUEST);
        }
    }
}
