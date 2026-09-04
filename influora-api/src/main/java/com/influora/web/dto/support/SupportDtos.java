package com.influora.web.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * [F-0535] Requester-facing support DTOs. Deliberately NOT the admin ones in {@code
 * AdminSupportDtos}: those carry {@code assignedTo}, the internal admin id on every message, and
 * the triage fields a requester has no business reading. Sharing one DTO across both surfaces is
 * how internal identifiers leak into a customer response.
 */
public final class SupportDtos {

    private SupportDtos() {}

    /**
     * {@code POST /support/tickets}. {@code priority} is optional — omitted means the entity's
     * default. It is accepted from the requester because the form asks them how urgent it is;
     * support re-triages via the admin routes and {@code escalate}, so nothing here is final.
     */
    public record CreateTicketRequest(
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 5000) String message,
            String priority) {}

    /** {@code POST /support/tickets/{id}/messages}. */
    public record AddMessageRequest(@NotBlank @Size(max = 5000) String content) {}

    /**
     * One message as the REQUESTER sees it. {@code fromSupport} replaces the raw sender id on
     * purpose: a requester needs to know whether support or they themselves wrote a line, and
     * never needs the {@code admin_users.id} that wrote it.
     */
    public record TicketMessageResponse(String id, boolean fromSupport, String content, Instant createdAt) {}

    /** List row. No message bodies — the detail route carries the thread. */
    public record TicketSummaryResponse(
            String id,
            String category,
            String subject,
            String status,
            String priority,
            Instant createdAt,
            Instant updatedAt) {}

    /** Detail, thread included, oldest first. */
    public record TicketDetailResponse(
            String id,
            String category,
            String subject,
            String status,
            String priority,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt,
            List<TicketMessageResponse> messages) {}
}
