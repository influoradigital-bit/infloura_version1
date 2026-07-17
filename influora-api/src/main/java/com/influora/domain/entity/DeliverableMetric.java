package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Deliverable performance numbers for a single deliverable (P0 #3, brand-audit backend build task
 * — Q9). One row per {@link PaymentMilestone}, our existing per-deliverable unit.
 *
 * <p><b>Honesty rule (non-negotiable):</b> every row carries an explicit {@link #source}: {@code
 * CREATOR_REPORTED} (self-declared by the creator, the only kind that existed before DPF-6) or
 * {@code PLATFORM_VERIFIED} (fetched from the real post via the Meta/YouTube platform API by
 * {@code DeliverableVerificationService}, DPF-6). No DTO built from this entity may claim a
 * verified value that was not actually fetched from the platform — {@link #getSource()} is always
 * surfaced alongside the numbers so no frontend surface can silently present a self-report as
 * verified.
 *
 * <p><b>Verified numbers never regress to self-reported (DPF-6 fail-closed rule):</b> once {@link
 * #source} is {@code PLATFORM_VERIFIED}, {@link #applyReport} (creator self-report path) leaves
 * {@link #reach}/{@link #impressions}/{@link #engagements} untouched — a later, less trustworthy
 * self-report must never overwrite a real platform number. Only {@link #applyVerifiedReport} may
 * write while verified, and it may only ever move a row from {@code CREATOR_REPORTED} (or no row
 * at all) to {@code PLATFORM_VERIFIED}, never the reverse.
 */
@Entity
@Table(name = "deliverable_metrics")
public class DeliverableMetric {

    /** Legacy/default value — every row before DPF-6 is exactly this. */
    public static final String SOURCE_CREATOR_REPORTED = "CREATOR_REPORTED";

    /** DPF-6 — numbers fetched from the real platform post, never creator-typed. */
    public static final String SOURCE_PLATFORM_VERIFIED = "PLATFORM_VERIFIED";

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "milestone_id", nullable = false, length = 26, unique = true)
    private String milestoneId;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column
    private Long reach;

    @Column
    private Long impressions;

    @Column
    private Long engagements;

    /** {@code CREATOR_REPORTED} or {@code PLATFORM_VERIFIED} — see class javadoc. */
    @Column(nullable = false, length = 20)
    private String source;

    /** DPF-6 — the Instagram media id / YouTube video id the verified numbers came from, if any. */
    @Column(name = "platform_media_id", length = 100)
    private String platformMediaId;

    /** DPF-6 — when platform verification last succeeded for this row; null until it has. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(length = 1000)
    private String link;

    @Column(name = "proof_screenshot_r2_key", length = 500)
    private String proofScreenshotR2Key;

    @Column(name = "reported_by_creator_id", nullable = false, length = 26)
    private String reportedByCreatorId;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeliverableMetric() {}

    public String getId() {
        return id;
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public Long getReach() {
        return reach;
    }

    public Long getImpressions() {
        return impressions;
    }

    public Long getEngagements() {
        return engagements;
    }

    public String getSource() {
        return source;
    }

    public String getPlatformMediaId() {
        return platformMediaId;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getLink() {
        return link;
    }

    public String getProofScreenshotR2Key() {
        return proofScreenshotR2Key;
    }

    public String getReportedByCreatorId() {
        return reportedByCreatorId;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Creator submits or overwrites their self-reported numbers for this deliverable.
     *
     * <p>DPF-6 fail-closed rule: if this row is already {@link #SOURCE_PLATFORM_VERIFIED}, the
     * numeric fields ({@link #reach}/{@link #impressions}/{@link #engagements}) and {@link
     * #source} are left untouched — a self-report must never downgrade/overwrite a real verified
     * number. {@link #link}/{@link #proofScreenshotR2Key}/{@link #reportedByCreatorId}/{@link
     * #reportedAt} still update either way, since they describe the creator's own submission, not
     * the verified metric values.
     */
    public void applyReport(
            Long reach,
            Long impressions,
            Long engagements,
            String link,
            String proofScreenshotR2Key,
            String reportedByCreatorId) {
        if (!SOURCE_PLATFORM_VERIFIED.equals(this.source)) {
            this.reach = reach;
            this.impressions = impressions;
            this.engagements = engagements;
            this.source = SOURCE_CREATOR_REPORTED;
        }
        this.link = link;
        this.proofScreenshotR2Key = proofScreenshotR2Key;
        this.reportedByCreatorId = reportedByCreatorId;
        this.reportedAt = Instant.now();
        touch();
    }

    /**
     * DPF-6 — {@code DeliverableVerificationService} persists real platform numbers here after a
     * successful fetch from the post's platform API. Always wins over whatever was there before
     * (a verified fetch is strictly more trustworthy than a prior self-report, and re-running
     * verification for the same deliverable is expected to refresh — never double-count, since
     * this replaces the single row for the milestone rather than appending one).
     */
    public void applyVerifiedReport(Long reach, Long impressions, Long engagements, String platformMediaId) {
        this.reach = reach;
        this.impressions = impressions;
        this.engagements = engagements;
        this.source = SOURCE_PLATFORM_VERIFIED;
        this.platformMediaId = platformMediaId;
        this.verifiedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final DeliverableMetric m = new DeliverableMetric();

        public Builder id(String id) {
            m.id = id;
            return this;
        }

        public Builder milestoneId(String milestoneId) {
            m.milestoneId = milestoneId;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            m.collaborationId = collaborationId;
            return this;
        }

        public Builder reach(Long reach) {
            m.reach = reach;
            return this;
        }

        public Builder impressions(Long impressions) {
            m.impressions = impressions;
            return this;
        }

        public Builder engagements(Long engagements) {
            m.engagements = engagements;
            return this;
        }

        public Builder link(String link) {
            m.link = link;
            return this;
        }

        public Builder proofScreenshotR2Key(String proofScreenshotR2Key) {
            m.proofScreenshotR2Key = proofScreenshotR2Key;
            return this;
        }

        public Builder reportedByCreatorId(String reportedByCreatorId) {
            m.reportedByCreatorId = reportedByCreatorId;
            return this;
        }

        public DeliverableMetric build() {
            if (m.source == null) {
                m.source = SOURCE_CREATOR_REPORTED;
            }
            Instant now = Instant.now();
            m.reportedAt = now;
            m.createdAt = now;
            m.updatedAt = now;
            return m;
        }
    }
}
