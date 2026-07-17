package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * In-app notification entity (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Every email event also
 * creates an in-app notification; some events (AI analysis, credits reset) are in-app only.
 *
 * <p>Tenant-scoped via {@code workspaceId} (nullable for user-level notifications).
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "link", length = 512)
    private String link;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {}

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLink() {
        return link;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Notification n = new Notification();

        public Builder id(String id) {
            n.id = id;
            return this;
        }

        public Builder userId(String userId) {
            n.userId = userId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            n.workspaceId = workspaceId;
            return this;
        }

        public Builder eventType(String eventType) {
            n.eventType = eventType;
            return this;
        }

        public Builder title(String title) {
            n.title = title;
            return this;
        }

        public Builder body(String body) {
            n.body = body;
            return this;
        }

        public Builder link(String link) {
            n.link = link;
            return this;
        }

        public Notification build() {
            n.isRead = false;
            n.createdAt = Instant.now();
            return n;
        }
    }
}
