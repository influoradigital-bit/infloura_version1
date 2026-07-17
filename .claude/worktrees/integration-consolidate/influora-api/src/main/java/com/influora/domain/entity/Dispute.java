package com.influora.domain.entity;

import com.influora.common.TextSanitizer;
import com.influora.domain.enums.DisputeOpenerType;
import com.influora.domain.enums.DisputeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Collaboration dispute case for admin arbitration (Task #34 / CEO §1.3). Opening a dispute
 * freezes unreleased escrow via {@link com.influora.service.EscrowService}; resolution is
 * admin-mediated — no automatic refund or clawback in v1.
 */
@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "opened_by_type", nullable = false)
    private DisputeOpenerType openedByType;

    @Column(name = "opened_by_user_id", nullable = false, length = 26)
    private String openedByUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeStatus status;

    @Column(name = "resolved_by_admin_id", length = 26)
    private String resolvedByAdminId;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-locking column (V53__disputes_version.sql, Vikram, per Kabir's security review —
     * phase1-admin-panel-escrow-security.md High finding #1): without this, two concurrent {@code
     * POST /admin/disputes/:id/resolve} calls (double-click, two admin tabs, or a race) can both
     * read this row while still {@code OPEN}, both pass the in-memory {@link
     * DisputeStatus#isActive()} check, and the second call's stale object would blind-overwrite the
     * first's committed {@code status}/{@code resolvedByAdminId}/{@code resolutionNotes} on save —
     * even though the escrow money movement itself is already race-safe (per-hold pessimistic lock
     * in {@code EscrowService}). Hibernate increments this on every {@code UPDATE} and checks it in
     * the {@code WHERE} clause; a stale read now throws {@code ObjectOptimisticLockingFailureException},
     * which {@code DisputeService#resolveDispute} translates to a clean HTTP 409 instead of a silent
     * overwrite. Same pattern as {@link PlatformFeeConfig#getVersion()}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Dispute() {}

    public static Dispute open(
            String id,
            String collaborationId,
            DisputeOpenerType openedByType,
            String openedByUserId,
            String reason) {
        Dispute dispute = new Dispute();
        dispute.id = id;
        dispute.collaborationId = collaborationId;
        dispute.openedByType = openedByType;
        dispute.openedByUserId = openedByUserId;
        String sanitized = TextSanitizer.sanitizePlainText(reason);
        if (sanitized == null || sanitized.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        dispute.reason = sanitized;
        dispute.status = DisputeStatus.OPEN;
        Instant now = Instant.now();
        dispute.createdAt = now;
        dispute.updatedAt = now;
        return dispute;
    }

    public void markUnderReview() {
        if (status != DisputeStatus.OPEN) {
            throw new IllegalStateException("Only OPEN disputes can move to UNDER_REVIEW");
        }
        this.status = DisputeStatus.UNDER_REVIEW;
        touch();
    }

    public void resolve(DisputeStatus resolution, String adminId, String notes) {
        if (!resolution.isResolved()) {
            throw new IllegalArgumentException("resolution must be a RESOLVED_* status");
        }
        if (!status.isActive()) {
            throw new IllegalStateException("Dispute is not open for resolution");
        }
        this.status = resolution;
        this.resolvedByAdminId = adminId;
        String sanitized = TextSanitizer.sanitizePlainText(notes);
        this.resolutionNotes =
                sanitized != null && !sanitized.isBlank() ? sanitized : null;
        this.resolvedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public DisputeOpenerType getOpenedByType() {
        return openedByType;
    }

    public String getOpenedByUserId() {
        return openedByUserId;
    }

    public String getReason() {
        return reason;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public String getResolvedByAdminId() {
        return resolvedByAdminId;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
