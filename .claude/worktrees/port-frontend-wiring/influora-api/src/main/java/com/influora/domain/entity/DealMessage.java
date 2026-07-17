package com.influora.domain.entity;

import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Unified per-{@link Collaboration} timeline event (message/proposal/contract/deliverable/payment/
 * system). Replaces the specs' separate NegotiationChat/Conversation/Message families — mirrors
 * {@code TimelineEvent} / {@code DealMessage} in {@code src/lib/types.ts} and {@code src/lib/api.ts}.
 */
@Entity
@Table(name = "deal_messages")
public class DealMessage {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DealMessageKind kind;

    @Column(name = "sender_id", nullable = false, length = 26)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private DealSenderType senderType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private String metadataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "read_by_json", columnDefinition = "json")
    private String readByJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DealMessage() {}

    public static DealMessage create(
            String id,
            String collaborationId,
            DealMessageKind kind,
            String senderId,
            DealSenderType senderType,
            String content,
            String metadataJson) {
        DealMessage m = new DealMessage();
        m.id = id;
        m.collaborationId = collaborationId;
        m.kind = kind;
        m.senderId = senderId;
        m.senderType = senderType;
        m.content = content;
        m.metadataJson = metadataJson;
        m.readByJson = "[]";
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public DealMessageKind getKind() {
        return kind;
    }

    public String getSenderId() {
        return senderId;
    }

    public DealSenderType getSenderType() {
        return senderType;
    }

    public String getContent() {
        return content;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public String getReadByJson() {
        return readByJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setReadByJson(String readByJson) {
        this.readByJson = readByJson;
    }
}
