package com.influora.domain.entity;

import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Read/write mapping of {@code support_tickets} (V34__admin_tables.sql). Originally added
 * read-only for {@code AdminDashboardController}'s pulse/operations endpoints (queue depth +
 * aging); {@link #updateStatus}/{@link #assignTo} mutators added cycle 6 for the P2
 * {@code AdminSupportController} task (src/admin/TASK_ASSIGNMENTS.md) — list/filter, detail,
 * reply, status change, assignment. {@code userType} is {@code BRAND}/{@code CREATOR} only per
 * the migration's column ENUM, even though the shared {@link UserType} enum also has an
 * {@code ADMIN} value.
 */
@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private UserType userType;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(nullable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketPriority priority;

    @Column(name = "assigned_to", length = 26)
    private String assignedTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SupportTicket() {}

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public UserType getUserType() {
        return userType;
    }

    public String getCategory() {
        return category;
    }

    public String getSubject() {
        return subject;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    /**
     * Admin-panel status transition (AdminSupportController). Stamps {@code resolvedAt} the
     * moment the ticket first reaches {@link TicketStatus#RESOLVED}; deliberately does NOT clear
     * it on a later re-open (e.g. RESOLVED -> IN_PROGRESS) — that timestamp remains the historical
     * record of when it was last resolved, same "don't erase history" discipline {@code
     * Workspace#reinstate} documents for {@code suspendedReason}/{@code suspendedBy}. Re-resolving
     * a reopened ticket naturally overwrites it with the newer time.
     */
    public void updateStatus(TicketStatus newStatus) {
        this.status = newStatus;
        if (newStatus == TicketStatus.RESOLVED) {
            this.resolvedAt = Instant.now();
        }
        touch();
    }

    /** Admin-panel assignment (AdminSupportController.assign). {@code adminId} may be null to unassign. */
    public void assignTo(String adminId) {
        this.assignedTo = adminId;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
