package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Response/request records for {@code AdminSupportController} (P2, cycle 6). Field names match
 * {@code SupportTicket}/{@code TicketDetail}/{@code TicketMessage}/{@code PaginatedResponse} in
 * {@code src/admin/types/admin.types.ts} exactly, same convention as {@code AdminBrandDtos}/{@code
 * AdminCreatorDtos}. {@code TicketDetailDto.relatedEntities} is an honest empty list — no
 * deliverable/payment/contract cross-reference table exists for support tickets in this schema
 * yet, same "not implemented this cycle" pattern documented on {@code AdminBrandDtos.CampaignSummaryDto}.
 */
public final class AdminSupportDtos {

    private AdminSupportDtos() {}

    public record TicketSummaryDto(
            String id,
            String userId,
            String userType,
            String userName,
            String category,
            String subject,
            String status,
            String priority,
            String assignedTo,
            String assignedToName,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt) {}

    public record TicketDetailDto(
            String id,
            String userId,
            String userType,
            String userName,
            String category,
            String subject,
            String status,
            String priority,
            String assignedTo,
            String assignedToName,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt,
            List<TicketMessageDto> messages,
            List<Object> relatedEntities) {}

    public record TicketMessageDto(
            String id, String senderId, String senderName, String senderType, String content, Instant createdAt) {}

    /** Matches {@code PaginatedResponse<T>} in admin.types.ts exactly. */
    public record PagedTicketsDto(
            List<TicketSummaryDto> data, long total, int page, int pageSize, int totalPages) {}

    public record ReplyRequest(@NotBlank @Size(max = 5000) String content) {}

    /**
     * {@code status} is validated against {@link com.influora.domain.enums.TicketStatus} in the
     * service layer (rejects unknown values with 400, same discipline as {@code
     * AdminBrandDtos.VerifyKycRequest}'s {@code action} field). {@code reason} is optional —
     * status changes are lower-stakes than KYC/suspend decisions and Kabir's audit spec only
     * requires {@code reason} to be free text, not mandatory, for non-destructive triage actions.
     */
    public record UpdateStatusRequest(@NotBlank String status, @Size(max = 2000) String reason) {}

    /** {@code assignedTo} may be blank/null to unassign the ticket. */
    public record AssignRequest(String assignedTo) {}

    /**
     * {@code reason} is mandatory (unlike {@link UpdateStatusRequest#reason}) — escalation is a
     * priority-raising action that needs a documented justification in the audit trail. Enforced
     * again in {@code AdminSupportService.escalate} via {@code ApiException} so the rule holds
     * even for direct service callers/tests, not just controller-bound requests.
     */
    public record EscalateRequest(@NotBlank @Size(max = 2000) String reason) {}
}
