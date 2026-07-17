package com.influora.domain.entity;

import com.influora.domain.enums.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_messages")
public class AiMessage {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 26)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private String metadataJson;

    @Column(name = "credits_charged", nullable = false)
    private int creditsCharged;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiMessage() {}

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public int getCreditsCharged() {
        return creditsCharged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AiMessage m = new AiMessage();

        public Builder id(String id) {
            m.id = id;
            return this;
        }

        public Builder conversationId(String conversationId) {
            m.conversationId = conversationId;
            return this;
        }

        public Builder role(MessageRole role) {
            m.role = role;
            return this;
        }

        public Builder content(String content) {
            m.content = content;
            return this;
        }

        public Builder metadataJson(String metadataJson) {
            m.metadataJson = metadataJson;
            return this;
        }

        public Builder creditsCharged(int creditsCharged) {
            m.creditsCharged = creditsCharged;
            return this;
        }

        public AiMessage build() {
            m.createdAt = Instant.now();
            return m;
        }
    }
}
