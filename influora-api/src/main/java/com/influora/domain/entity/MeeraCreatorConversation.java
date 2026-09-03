package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 1.4, A6) — DPDP-compliance index over one creator Meera
 * conversation: a 1:1 pointer at an {@code ai_conversations} row (via {@link #conversationId}),
 * plus the rollup fields ({@link #lastMessageAt}, {@link #messageCount}) list/export/delete need
 * without adding a nullable creator column to the shared BRAND+CREATOR {@code ai_conversations}
 * table. {@code creatorId} is a {@code creator_profiles.id}, same convention as {@link
 * CreatorAgentPreferences#getCreatorId()}.
 */
@Entity
@Table(name = "meera_creator_conversations")
public class MeeraCreatorConversation {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_id", nullable = false, length = 26)
    private String creatorId;

    @Column(name = "conversation_id", nullable = false, unique = true, length = 26)
    private String conversationId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    protected MeeraCreatorConversation() {}

    public static MeeraCreatorConversation start(String id, String creatorId, String conversationId, Instant when) {
        MeeraCreatorConversation c = new MeeraCreatorConversation();
        c.id = id;
        c.creatorId = creatorId;
        c.conversationId = conversationId;
        c.startedAt = when;
        c.lastMessageAt = when;
        c.messageCount = 0;
        return c;
    }

    public String getId() {
        return id;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    /** Called once per turn persisted into this conversation. */
    public void recordMessage(Instant when) {
        this.messageCount += 1;
        if (when != null && (this.lastMessageAt == null || when.isAfter(this.lastMessageAt))) {
            this.lastMessageAt = when;
        }
    }
}
