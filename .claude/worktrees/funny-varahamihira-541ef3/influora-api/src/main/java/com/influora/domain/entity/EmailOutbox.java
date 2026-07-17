package com.influora.domain.entity;

import com.influora.domain.enums.EmailOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Email outbox entity (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Implements the transactional
 * outbox pattern: email send is not in the critical path, retries are automatic.
 *
 * <p>Idempotency via {@code idempotencyKey} prevents duplicate emails for the same event.
 * Format: {@code {event_type}:{entity_id}:{user_id}}.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    private static final int MAX_RETRIES = 5;

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Column(name = "to_email", nullable = false)
    private String toEmail;

    @Column(name = "template_key", nullable = false, length = 64)
    private String templateKey;

    @Column(name = "template_data", nullable = false, columnDefinition = "JSON")
    private String templateData;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmailOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    protected EmailOutbox() {}

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getToEmail() {
        return toEmail;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getTemplateData() {
        return templateData;
    }

    public EmailOutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void markSent() {
        this.status = EmailOutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.nextRetryAt = null;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.retryCount++;
        this.errorMessage = errorMessage;
        if (this.retryCount >= MAX_RETRIES) {
            this.status = EmailOutboxStatus.FAILED;
            this.nextRetryAt = null;
        } else {
            // Exponential backoff: 30s, 90s, 270s, 810s (~13min)
            long delaySeconds = (long) (30 * Math.pow(3, this.retryCount - 1));
            this.nextRetryAt = Instant.now().plusSeconds(delaySeconds);
        }
    }

    public boolean canRetry() {
        return this.retryCount < MAX_RETRIES;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EmailOutbox e = new EmailOutbox();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder userId(String userId) {
            e.userId = userId;
            return this;
        }

        public Builder toEmail(String toEmail) {
            e.toEmail = toEmail;
            return this;
        }

        public Builder templateKey(String templateKey) {
            e.templateKey = templateKey;
            return this;
        }

        public Builder templateData(String templateData) {
            e.templateData = templateData;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            e.idempotencyKey = idempotencyKey;
            return this;
        }

        public EmailOutbox build() {
            e.status = EmailOutboxStatus.PENDING;
            e.retryCount = 0;
            e.createdAt = Instant.now();
            return e;
        }
    }
}
