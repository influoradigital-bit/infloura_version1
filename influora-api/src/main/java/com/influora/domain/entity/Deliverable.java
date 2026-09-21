package com.influora.domain.entity;

import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Lean per-slot deliverable row (Priya CREATOR_EXEC_PLAN §1.3). Current draft files live in
 * {@link #filesJson}; {@link #versionNumber} increments on each creator upload.
 */
@Entity
@Table(name = "deliverables")
public class Deliverable {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "milestone_id", length = 26)
    private String milestoneId;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DeliverableType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliverableStatus status;

    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "revision_count", nullable = false)
    private int revisionCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "files_json", columnDefinition = "json")
    private String filesJson;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hashtags_json", columnDefinition = "json")
    private String hashtagsJson;

    @Column(name = "creator_notes", columnDefinition = "TEXT")
    private String creatorNotes;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "post_url", length = 500)
    private String postUrl;

    @Column(name = "post_id", length = 100)
    private String postId;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * When the brand's review clock ran out and the Influora team was told (owner's ruling,
     * 2026-09-21). Null while the brand is still inside its window, or has already acted.
     *
     * <p>This column is the once-and-only-once guard, not the record — the record is the support
     * ticket {@code ReviewSlaService} opens. {@link #applySubmit} clears it, so each submission
     * round (the first submission, then each resubmission after a revision) can escalate exactly
     * once.
     *
     * <p>It carries no automatic consequence of any kind: it never approves, never rejects and
     * never releases money. It means only that a person at Influora has been asked to look.
     */
    @Column(name = "review_escalated_at")
    private Instant reviewEscalatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Deliverable() {}

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public DeliverableType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public DeliverableStatus getStatus() {
        return status;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public int getRevisionCount() {
        return revisionCount;
    }

    public String getFilesJson() {
        return filesJson;
    }

    public String getCaption() {
        return caption;
    }

    public String getHashtagsJson() {
        return hashtagsJson;
    }

    public String getCreatorNotes() {
        return creatorNotes;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public String getPostUrl() {
        return postUrl;
    }

    public String getPostId() {
        return postId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getReviewEscalatedAt() {
        return reviewEscalatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void applyUpload(
            int nextVersion,
            String filesJson,
            String caption,
            String hashtagsJson,
            String creatorNotes) {
        this.versionNumber = nextVersion;
        this.filesJson = filesJson;
        this.caption = caption;
        this.hashtagsJson = hashtagsJson;
        this.creatorNotes = creatorNotes;
        this.status = DeliverableStatus.DRAFT;
        touch();
    }

    /**
     * Creator submits current draft for brand review (lean row — no version table).
     *
     * <p>{@link #submittedAt} is the start of the brand's review clock, so a resubmission after a
     * revision genuinely restarts it rather than leaving the creator waiting against the original
     * submission time. {@link #reviewEscalatedAt} is cleared for the same reason: the new round is
     * a new promise to the creator, and it is entitled to its own single escalation if the brand
     * lets this one run out too.
     */
    public void applySubmit(
            String finalCaption,
            String hashtagsJson,
            String notes,
            DeliverableStatus newStatus) {
        if (finalCaption != null) {
            this.caption = finalCaption;
        }
        if (hashtagsJson != null) {
            this.hashtagsJson = hashtagsJson;
        }
        if (notes != null) {
            this.creatorNotes = notes;
        }
        this.status = newStatus;
        this.submittedAt = Instant.now();
        this.reviewEscalatedAt = null;
        touch();
    }

    /**
     * Records that the brand's review window elapsed and the Influora team was told (owner's
     * ruling, 2026-09-21). Status is deliberately untouched — an escalation is a request for a
     * human to look, not a lifecycle transition, and the deliverable stays exactly where it was:
     * still awaiting the brand's decision, still unpaid.
     *
     * <p>Not idempotent by design. The caller ({@code ReviewSlaService}) must have established
     * under a row lock that {@link #getReviewEscalatedAt()} was null before calling, so a second
     * call would mean a second escalation was about to happen and the guard had failed — better
     * that it fails loudly in the caller's test than that it silently re-stamps.
     */
    public void markReviewEscalated(Instant at) {
        this.reviewEscalatedAt = at;
        touch();
    }

    /** Brand approves a submitted deliverable (lean row — sets {@link #approvedAt}). */
    public void applyApprove() {
        Instant now = Instant.now();
        this.status = DeliverableStatus.APPROVED;
        this.approvedAt = now;
        this.reviewedAt = now;
        touch();
    }

    /** Brand requests revision — increments {@link #revisionCount} and stores feedback. */
    public void applyRevision(String feedback) {
        this.status = DeliverableStatus.REVISION_REQUESTED;
        this.reviewNotes = feedback;
        this.revisionCount = this.revisionCount + 1;
        this.reviewedAt = Instant.now();
        touch();
    }

    /**
     * B6 — terminal reject: {@code DeliverableStatus.REJECTED} was declared in the enum but no code
     * path ever wrote it. Reserved for the brand's final decision when a submitted deliverable
     * still isn't acceptable — distinct from {@link #applyRevision}, which keeps the deliverable
     * alive for another creator attempt. Terminal: {@code BrandDeliverableService#canReview} must
     * never treat REJECTED as reviewable again.
     */
    public void applyReject(String feedback) {
        this.status = DeliverableStatus.REJECTED;
        this.reviewNotes = feedback;
        this.reviewedAt = Instant.now();
        touch();
    }

    /** Creator reports self-declared performance metrics (lean row — status only). */
    public void applyMetricsReport() {
        this.status = DeliverableStatus.METRICS_REPORTED;
        touch();
    }

    /** DPF-3 — creator marks deliverable as posted with live URL (lean row). */
    public void applyMarkPosted(String postUrl) {
        Instant now = Instant.now();
        this.postUrl = postUrl;
        this.status = DeliverableStatus.POSTED;
        this.postedAt = now;
        touch();
    }

    /**
     * DPF-6 — {@code DeliverableVerificationService} calls this after real platform metrics have
     * been fetched and persisted to the linked {@link DeliverableMetric} row. Idempotent: calling
     * it again on an already-{@code VERIFIED} deliverable (re-verification refresh) is a no-op
     * status-wise, it just re-touches {@link #updatedAt}.
     */
    public void applyVerify() {
        this.status = DeliverableStatus.VERIFIED;
        touch();
    }

    /**
     * DPF-8 — R2 lifecycle cleanup clears stored media references after the underlying R2 objects
     * have actually been deleted. Deliberately does NOT touch {@link #status} or {@link
     * #versionNumber} — unlike {@link #applyUpload}, which sets status back to {@code DRAFT}
     * (Kabir H-DPF8-3: a cleanup job must never move the deliverable lifecycle backward, e.g.
     * resurrecting a {@code REJECTED} deliverable into an editable state).
     */
    public void applyMediaCleanup() {
        this.filesJson = null;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Deliverable d = new Deliverable();

        public Builder id(String id) {
            d.id = id;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            d.collaborationId = collaborationId;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            d.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder milestoneId(String milestoneId) {
            d.milestoneId = milestoneId;
            return this;
        }

        public Builder slotIndex(int slotIndex) {
            d.slotIndex = slotIndex;
            return this;
        }

        public Builder type(DeliverableType type) {
            d.type = type;
            return this;
        }

        public Builder title(String title) {
            d.title = title;
            return this;
        }

        public Builder description(String description) {
            d.description = description;
            return this;
        }

        public Builder status(DeliverableStatus status) {
            d.status = status;
            return this;
        }

        public Builder deadline(LocalDate deadline) {
            d.deadline = deadline;
            return this;
        }

        public Builder revisionCount(int revisionCount) {
            d.revisionCount = revisionCount;
            return this;
        }

        public Builder filesJson(String filesJson) {
            d.filesJson = filesJson;
            return this;
        }

        public Deliverable build() {
            if (d.type == null) {
                d.type = DeliverableType.INSTAGRAM_REEL;
            }
            if (d.status == null) {
                d.status = DeliverableStatus.PENDING;
            }
            Instant now = Instant.now();
            d.createdAt = now;
            d.updatedAt = now;
            return d;
        }
    }
}
