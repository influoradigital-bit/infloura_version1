package com.influora.domain.entity;

import com.influora.domain.enums.ConversationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_conversations")
public class AiConversation {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "started_by", nullable = false, length = 26)
    private String startedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationStatus status;

    @Column(length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    protected AiConversation() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public void setStatus(ConversationStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void markMessageAt(Instant when) {
        this.lastMessageAt = when;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AiConversation c = new AiConversation();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder startedBy(String startedBy) {
            c.startedBy = startedBy;
            return this;
        }

        public Builder status(ConversationStatus status) {
            c.status = status;
            return this;
        }

        public Builder title(String title) {
            c.title = title;
            return this;
        }

        public AiConversation build() {
            c.createdAt = Instant.now();
            if (c.status == null) {
                c.status = ConversationStatus.ACTIVE;
            }
            return c;
        }
    }
}
