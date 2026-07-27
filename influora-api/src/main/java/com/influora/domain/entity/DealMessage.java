package com.influora.domain.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * [N1] {@code USE_BIG_DECIMAL_FOR_FLOATS} is load-bearing here, not a style preference.
     * {@link #settleStatus} re-serializes the whole metadata document, and a bare mapper reads
     * JSON decimals back as {@code Double} — so an {@code amount} persisted as {@code 25000.00}
     * (DealService writes it as a {@code BigDecimal}) came back {@code 25000.0} on the first
     * settle, silently restating the scale of a negotiated figure on a payment evidence row.
     * Verified against the Jackson 2.17.2 this project resolves: bare drops the trailing zero,
     * this config round-trips it byte-identically.
     *
     * <p>Deliberately a separate instance from {@code DealService.MAPPER} rather than a shared
     * one. The two have different jobs: this one guards what is written to {@code deal_messages}
     * and must preserve scale exactly, while DealService's read path feeds the API response DTO,
     * where enabling this flag would change response bytes ({@code 25000.0} → {@code 25000.00})
     * — a display concern, not an evidence one, and out of this ticket's blast radius. A private
     * bare mapper per class is also the established convention across this codebase (~30 of them,
     * including {@code common/JsonLists}), so a configured singleton shared by exactly two classes
     * would be a special case rather than consolidation. If a project-wide mapper is ever
     * introduced, this flag must survive the move.
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

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

    /**
     * CR-02 [CTO ruling 2026-07-27] — settles this proposal card's negotiation outcome. {@code
     * status} is the only key whose VALUE this can change; every other key is carried across
     * unchanged.
     *
     * <p>[N1] Stated that way on purpose: this parses and re-serializes the entire document, so
     * "touches only the status key" would be a claim about the implementation that is not true.
     * What is true is that the other keys round-trip intact — and for {@code amount} that holds
     * only because {@link #MAPPER} enables {@code USE_BIG_DECIMAL_FOR_FLOATS}; see the note on
     * that field before changing it.
     *
     * <p>Deliberately NOT a {@code setMetadataJson(String)} setter. {@code deal_messages} is the
     * evidence trail in a payment dispute, and a raw setter would let any caller rewrite a
     * historical row wholesale — including the negotiated {@code amount}. That has to be
     * structurally impossible rather than merely avoided by convention, so the parse/copy/write
     * lives here and the field stays private. The cost is that this entity now knows about JSON,
     * which is the trade the ruling accepts.
     *
     * <p>[C1] Only a card still reading {@code "pending"} may be settled. {@code canReject()}
     * permits a withdrawal from TERMS_AGREED and CONTRACTED, so without this guard a
     * post-agreement withdrawal would retro-stamp an already-{@code "accepted"} card to
     * {@code "rejected"} — the timeline would show "Creator accepted the proposal" sitting
     * directly above a card badged Rejected. A settled card is terminal; the withdrawal is
     * carried by the system message instead. This guard is what makes the accept, reject and
     * counter call sites all safe at once rather than each having to remember.
     *
     * <p>Unparseable or absent metadata is left strictly alone: if the blob cannot be read back
     * we cannot know the card is pending, and writing a fresh {@code {"status": ...}} map would
     * destroy the very {@code amount}/{@code deliverables} keys this method exists to preserve.
     * Doing nothing degrades to a stale badge; guessing corrupts evidence.
     */
    public void settleStatus(String status) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        Map<String, Object> metadata;
        try {
            metadata =
                    MAPPER.readValue(
                            metadataJson, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return;
        }
        if (!"pending".equals(metadata.get("status"))) {
            return;
        }
        // LinkedHashMap above keeps the original key order, so the rewritten row stays
        // byte-comparable with its neighbours for anyone reading the trail by eye.
        metadata.put("status", status);
        try {
            metadataJson = MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            // Re-serializing a map that just parsed cleanly should be impossible; if it ever
            // happens the row is better left untouched than half-written.
            throw new IllegalStateException(
                    "Failed to re-serialize deal message metadata for " + id, ex);
        }
    }
}
