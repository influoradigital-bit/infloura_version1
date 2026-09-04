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
     * [F-0535] Opens a new ticket. This factory is the whole reason the defect existed: the class
     * had only a protected no-arg constructor and admin-side mutators, so a ticket could be
     * listed, replied to, assigned and escalated but never CREATED by anyone — no support
     * controller outside the admin one had any way to make the row.
     *
     * <p>A new ticket always starts {@link TicketStatus#OPEN}. Priority is the REQUESTER'S stated
     * urgency and is deliberately accepted from them rather than forced to {@code MEDIUM}: the
     * admin side can already re-triage it, and {@link #escalate()} exists precisely so support
     * owns the final word. Refusing the requester any say would make the field a lie on the form.
     * {@code assignedTo} stays null — assignment is a support decision, not a requester one.
     */
    public static SupportTicket open(
            String id, String userId, UserType userType, String category, String subject, TicketPriority priority) {
        SupportTicket t = new SupportTicket();
        t.id = id;
        t.userId = userId;
        t.userType = userType;
        t.category = category;
        t.subject = subject;
        t.status = TicketStatus.OPEN;
        t.priority = priority == null ? TicketPriority.MEDIUM : priority;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    /**
     * [F-0535] The requester has replied. Clears {@link TicketStatus#WAITING_USER}, which was
     * previously a dead end for the person it names: only an admin could move a ticket out of it,
     * so a user answering the question they were asked left the ticket sitting in "waiting on
     * user" forever with no signal that they had in fact responded.
     *
     * <p>Only WAITING_USER is affected. A reply on an OPEN or IN_PROGRESS ticket must not reorder
     * the triage queue, and a reply on a RESOLVED or CLOSED ticket must not silently reopen it —
     * reopening is a support decision and there is no requester-side route for it by design.
     */
    public void noteUserReplied() {
        if (this.status == TicketStatus.WAITING_USER) {
            this.status = TicketStatus.OPEN;
        }
        touch();
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

    /**
     * Admin-panel escalation (AdminSupportController.escalate). {@link TicketStatus} has no
     * ESCALATED value and {@link TicketPriority} tops out at {@code URGENT}, so escalation is
     * modeled as raising priority to {@code URGENT} — status is deliberately left untouched,
     * since escalating changes how urgently a ticket should be handled, not where it sits in the
     * triage lifecycle. Idempotent: escalating an already-{@code URGENT} ticket just re-stamps
     * {@code updatedAt}, same as the reason getting re-recorded in the audit trail.
     */
    public void escalate() {
        this.priority = TicketPriority.URGENT;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
