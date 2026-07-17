package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps {@code support_ticket_messages} (V34__admin_tables.sql). One row per message in a ticket's
 * thread — {@code senderType} disambiguates whether {@code senderId} is a {@code users.id} (the
 * brand/creator who opened the ticket) or an {@code admin_users.id} (a support admin reply).
 *
 * <p><b>PII note (AdminSupportController, cycle 6):</b> {@code content} is free-text and may
 * contain user PII (account details, complaint specifics, etc.) — never pass this field into
 * {@code AdminAuditLogService#record}'s snapshot maps. The audit trail only ever records ticket
 * status/priority/assignment metadata, never message bodies. See
 * {@code wiki/admin-progress/AUDIT-LOG-WRITE-SPEC.md} Rule 2.
 */
@Entity
@Table(name = "support_ticket_messages")
public class SupportTicketMessage {

    /** Mirrors {@code TicketMessage.senderType} in {@code src/admin/types/admin.types.ts}. */
    public enum SenderType {
        USER,
        ADMIN
    }

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "ticket_id", nullable = false, length = 26)
    private String ticketId;

    @Column(name = "sender_id", nullable = false, length = 26)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SupportTicketMessage() {}

    public static SupportTicketMessage create(
            String id, String ticketId, String senderId, SenderType senderType, String content) {
        SupportTicketMessage m = new SupportTicketMessage();
        m.id = id;
        m.ticketId = ticketId;
        m.senderId = senderId;
        m.senderType = senderType;
        m.content = content;
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getSenderId() {
        return senderId;
    }

    public SenderType getSenderType() {
        return senderType;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
