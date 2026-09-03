package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per CONFIRMED admin custom email send (T-ADMINMAIL-0903, V20260903120000). Backs two of
 * the five required abuse controls -- see the migration header for which and why. Insert-only:
 * nothing in this codebase ever updates or deletes a row here (no setters below except the
 * builder), same discipline as {@link AuditLogEntry}.
 *
 * <p>{@code subject}/{@code bodyText}/{@code ctaLabel}/{@code ctaUrl} store the admin-AUTHORED
 * template with {@code {{token}}} placeholders unresolved -- the source-of-truth record of what
 * was sent, not any one recipient's personalized copy (which lives only in each {@code
 * EmailOutbox} row's {@code templateData}, and those rows age out of relevance once sent).
 */
@Entity
@Table(name = "admin_email_campaigns")
public class AdminEmailCampaign {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "admin_user_id", nullable = false, length = 26)
    private String adminUserId;

    @Column(nullable = false)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "cta_label", length = 100)
    private String ctaLabel;

    @Column(name = "cta_url", length = 2048)
    private String ctaUrl;

    @Column(name = "audience_user_type", nullable = false, length = 16)
    private String audienceUserType;

    @Column(name = "audience_only_verified", nullable = false)
    private boolean audienceOnlyVerified;

    @Column(name = "audience_registered_within_days")
    private Integer audienceRegisteredWithinDays;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "skipped_unsubscribed", nullable = false)
    private int skippedUnsubscribed;

    /**
     * T-ADMINMAIL-0903 round 3, B5 (REVIEW-R2.md, V20260903140000): the number of {@code
     * EmailOutbox} rows actually written for this send ({@code toEnqueue.size()} in {@code
     * AdminCustomEmailService#send}) -- NOT reconstructable as {@code recipientCount -
     * skippedUnsubscribed} in general (that reconstruction is only correct while those are the
     * only two things that can shrink the enqueued set; a stored column does not silently drift
     * if a future control adds a third reason to skip a recipient). {@code
     * AdminCustomEmailService#replaySendResponse} reads this column directly instead of
     * recomputing it, so a replayed response can never disagree with what the audit log recorded
     * as {@code queued} for the original send.
     */
    @Column(name = "queued_count", nullable = false)
    private int queuedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminEmailCampaign() {}

    public String getId() {
        return id;
    }

    public String getAdminUserId() {
        return adminUserId;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyText() {
        return bodyText;
    }

    public String getCtaLabel() {
        return ctaLabel;
    }

    public String getCtaUrl() {
        return ctaUrl;
    }

    public String getAudienceUserType() {
        return audienceUserType;
    }

    public boolean isAudienceOnlyVerified() {
        return audienceOnlyVerified;
    }

    public Integer getAudienceRegisteredWithinDays() {
        return audienceRegisteredWithinDays;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public int getSkippedUnsubscribed() {
        return skippedUnsubscribed;
    }

    public int getQueuedCount() {
        return queuedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AdminEmailCampaign c = new AdminEmailCampaign();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder adminUserId(String adminUserId) {
            c.adminUserId = adminUserId;
            return this;
        }

        public Builder subject(String subject) {
            c.subject = subject;
            return this;
        }

        public Builder bodyText(String bodyText) {
            c.bodyText = bodyText;
            return this;
        }

        public Builder ctaLabel(String ctaLabel) {
            c.ctaLabel = ctaLabel;
            return this;
        }

        public Builder ctaUrl(String ctaUrl) {
            c.ctaUrl = ctaUrl;
            return this;
        }

        public Builder audienceUserType(String audienceUserType) {
            c.audienceUserType = audienceUserType;
            return this;
        }

        public Builder audienceOnlyVerified(boolean audienceOnlyVerified) {
            c.audienceOnlyVerified = audienceOnlyVerified;
            return this;
        }

        public Builder audienceRegisteredWithinDays(Integer audienceRegisteredWithinDays) {
            c.audienceRegisteredWithinDays = audienceRegisteredWithinDays;
            return this;
        }

        public Builder recipientCount(int recipientCount) {
            c.recipientCount = recipientCount;
            return this;
        }

        public Builder skippedUnsubscribed(int skippedUnsubscribed) {
            c.skippedUnsubscribed = skippedUnsubscribed;
            return this;
        }

        public Builder queuedCount(int queuedCount) {
            c.queuedCount = queuedCount;
            return this;
        }

        public AdminEmailCampaign build() {
            c.createdAt = Instant.now();
            return c;
        }
    }
}
