package com.influora.repository;

import com.influora.domain.entity.DealMessage;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DealMessageRepository extends JpaRepository<DealMessage, String> {

    List<DealMessage> findByCollaborationIdOrderByCreatedAtAsc(String collaborationId);

    /** W3-1 — "is this the first message from this sender type" check for FirstMessageSentEvent/CreatorFirstMessageEvent. */
    boolean existsByCollaborationIdAndSenderType(String collaborationId, DealSenderType senderType);

    /**
     * B-4 "can't accept your own last offer" guard — the most recent proposal/counter
     * event on the deal, used to tell who made the offer currently on the table.
     */
    Optional<DealMessage> findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
            String collaborationId, DealMessageKind kind);

    @Query(
            "SELECT m FROM DealMessage m WHERE m.collaborationId = :collaborationId "
                    + "AND (:before IS NULL OR m.createdAt < :before) ORDER BY m.createdAt DESC")
    List<DealMessage> findPageBefore(
            @Param("collaborationId") String collaborationId,
            @Param("before") Instant before,
            org.springframework.data.domain.Pageable pageable);

    Optional<DealMessage> findFirstByCollaborationIdOrderByCreatedAtDesc(String collaborationId);

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.1, A1) — one row per (collaboration, sender_type) with
     * the FIRST timestamp that sender wrote in that deal; {@code
     * CreatorAgentBaselineService} pairs the {@code brand} and {@code creator} rows per
     * collaboration to derive the reply-time baseline (first creator message minus first brand
     * message), never a full message-history load.
     */
    interface FirstMessageBySenderRow {
        String getCollaborationId();

        DealSenderType getSenderType();

        Instant getFirstAt();
    }

    @Query(
            "SELECT m.collaborationId AS collaborationId, m.senderType AS senderType, MIN(m.createdAt) AS firstAt "
                    + "FROM DealMessage m WHERE m.senderType IN (com.influora.domain.enums.DealSenderType.brand, "
                    + "com.influora.domain.enums.DealSenderType.creator) "
                    + "GROUP BY m.collaborationId, m.senderType")
    List<FirstMessageBySenderRow> findFirstMessageTimestampsBySender();
}
