package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Email preference entity (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Tracks user unsubscribe
 * preferences per event type. GDPR/CAN-SPAM compliance requirement.
 */
@Entity
@Table(name = "email_preferences")
public class EmailPreference {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "unsubscribed", nullable = false)
    private boolean unsubscribed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailPreference() {}

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getEventType() {
        return eventType;
    }

    public boolean isUnsubscribed() {
        return unsubscribed;
    }

    public void setUnsubscribed(boolean unsubscribed) {
        this.unsubscribed = unsubscribed;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EmailPreference p = new EmailPreference();

        public Builder id(String id) {
            p.id = id;
            return this;
        }

        public Builder userId(String userId) {
            p.userId = userId;
            return this;
        }

        public Builder eventType(String eventType) {
            p.eventType = eventType;
            return this;
        }

        public Builder unsubscribed(boolean unsubscribed) {
            p.unsubscribed = unsubscribed;
            return this;
        }

        public EmailPreference build() {
            Instant now = Instant.now();
            p.createdAt = now;
            p.updatedAt = now;
            return p;
        }
    }
}
