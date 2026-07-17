package com.influora.repository;

import com.influora.domain.entity.DealMessage;
import com.influora.domain.enums.DealMessageKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DealMessageRepository extends JpaRepository<DealMessage, String> {

    List<DealMessage> findByCollaborationIdOrderByCreatedAtAsc(String collaborationId);

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
}
