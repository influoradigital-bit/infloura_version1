package com.influora.domain.entity;

import com.influora.domain.enums.ContentFlagSource;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single content-moderation flag ({@code content_flags}, V34__admin_tables.sql). The table has
 * existed since Phase 1 (Meera) but had no JPA entity/repository until now — confirmed by grep
 * (zero hits for {@code ContentFlag}/{@code content_flags} anywhere under {@code
 * influora-api/src/main/java} before this cycle). Built read-only for cycle 5: {@code
 * AdminCreatorService} needs it for {@code CreatorDetail.flaggedContentCount}, and {@code
 * ApprovalWorkflowService} needs it to surface {@code CONTENT_MODERATION}-type rows in the
 * unified pending-approvals queue. No mutator/builder is added yet — nothing in this codebase
 * writes a {@code content_flags} row (the AI auto-flagging pipeline and {@code
 * AdminSupportController}'s {@code actionFlag} endpoint are both P2, not built this cycle) or
 * actions one (that belongs to a future dedicated moderation controller, not
 * {@code ApprovalWorkflowController} — see that class's javadoc for why {@code CONTENT_MODERATION}
 * items are surfaced but not processable here yet).
 */
@Entity
@Table(name = "content_flags")
public class ContentFlag {

    @Id
    @Column(length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentFlagType contentType;

    @Column(name = "content_id", nullable = false, length = 26)
    private String contentId;

    @Column(name = "content_preview", columnDefinition = "TEXT")
    private String contentPreview;

    @Column(name = "flag_reason", nullable = false, length = 255)
    private String flagReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "flagged_by", nullable = false)
    private ContentFlagSource flaggedBy;

    /** User who filed the flag (null for AI/admin system rows). Unique with content_id (V46). */
    @Column(name = "flagged_by_user_id", length = 26)
    private String flaggedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentFlagStatus status;

    @Column(name = "action_taken", length = 100)
    private String actionTaken;

    @Column(name = "reviewed_by", length = 26)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContentFlag() {}

    /** User-reported flag for admin moderation queue (Task #29 review flagging). */
    public static ContentFlag userFlag(
            String id,
            ContentFlagType contentType,
            String contentId,
            String contentPreview,
            String flagReason,
            String flaggedByUserId) {
        ContentFlag flag = new ContentFlag();
        flag.id = id;
        flag.contentType = contentType;
        flag.contentId = contentId;
        flag.contentPreview = contentPreview;
        flag.flagReason = flagReason;
        flag.flaggedBy = ContentFlagSource.USER;
        flag.flaggedByUserId = flaggedByUserId;
        flag.status = ContentFlagStatus.PENDING;
        flag.createdAt = Instant.now();
        return flag;
    }

    public String getId() {
        return id;
    }

    public ContentFlagType getContentType() {
        return contentType;
    }

    public String getContentId() {
        return contentId;
    }

    public String getContentPreview() {
        return contentPreview;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public ContentFlagSource getFlaggedBy() {
        return flaggedBy;
    }

    public String getFlaggedByUserId() {
        return flaggedByUserId;
    }

    public ContentFlagStatus getStatus() {
        return status;
    }

    public String getActionTaken() {
        return actionTaken;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Mark flag as actioned by an admin with the given action taken. Called by {@code
     * AdminModerationService} when an admin removes/rejects/escalates content.
     */
    public void markActioned(String action, String adminId) {
        this.status = ContentFlagStatus.ACTIONED;
        this.actionTaken = action;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }

    /**
     * Mark flag as reviewed (admin looked at it but didn't action) by an admin. Called by {@code
     * AdminModerationService} when an admin dismisses/marks-as-reviewed without removing content.
     */
    public void markReviewed(String adminId) {
        this.status = ContentFlagStatus.REVIEWED;
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }

    /**
     * Reassign this flag upward to a higher-priority (SUPER_ADMIN-tier) review queue. Deliberately
     * distinct from {@link #markActioned} — escalation is not a resolution. The flag does NOT enter
     * a terminal state; it must keep surfacing in the review queue until a later
     * REMOVE/REJECT/WARN moves it to ACTIONED/REVIEWED (Priya's ruling, 2026-07-15: the previous
     * implementation called {@code markActioned("ESCALATE", ...)}, which silently dropped the flag
     * out of the PENDING-only queue before any human had actually reviewed it).
     *
     * <p>{@code reviewedBy}/{@code reviewedAt} are set to the escalating admin/time here, and will
     * be overwritten again when a senior admin later actions the flag via {@link #markActioned} or
     * {@link #markReviewed} — the final resolver's identity/time is what should win. {@code
     * actionTaken} is set to {@code "ESCALATE"} so the DTO/audit trail show why the flag left
     * PENDING even while unresolved.
     *
     * <p>Called by {@code AdminModerationService} for the ESCALATE action.
     */
    public void markEscalated(String adminId) {
        this.status = ContentFlagStatus.ESCALATED;
        this.actionTaken = "ESCALATE";
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
    }
}
