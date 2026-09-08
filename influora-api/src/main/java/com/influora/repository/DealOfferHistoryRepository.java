package com.influora.repository;

import com.influora.domain.entity.DealOfferHistory;
import com.influora.domain.enums.OfferEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.6). */
public interface DealOfferHistoryRepository extends JpaRepository<DealOfferHistory, String> {

    /** The negotiation timeline for one deal, in the order it happened. */
    List<DealOfferHistory> findByCollaborationIdOrderBySequenceNoAsc(String collaborationId);

    /**
     * The {@code + 1} half of {@code sequenceNo}'s derivation. Correct ONLY when called inside the
     * transaction already holding the collaboration row lock — see {@link DealOfferHistory}'s class
     * javadoc. Outside that lock this is a TOCTOU, and the table's
     * {@code UNIQUE KEY uk_doh_collab_seq} is what turns such a mistake into a duplicate-key failure
     * rather than silent corruption of the ordering.
     */
    int countByCollaborationId(String collaborationId);

    /**
     * SPEC.md &sect;14.1.d's {@code meeraAnchoredShare} depends on this, and on it being DISTINCT.
     *
     * <p>The table's unique key is {@code (collaboration_id, sequence_no)}, NOT
     * {@code (collaboration_id, event)}, so several {@link OfferEvent#MEERA_COUNTER} rows on one
     * collaboration are legal and expected on any negotiation with more than one Meera-drafted
     * counter. A row COUNT over this table is therefore not a collaboration count: using one for the
     * share produces values above 1.0. Do not add a {@code countByCollaborationIdInAndEvent}
     * alongside this (SPEC.md &sect;2.6 PRIYA note, finding W4).
     */
    @Query("select distinct h.collaborationId from DealOfferHistory h "
            + "where h.collaborationId in :ids and h.event = :event")
    List<String> findDistinctCollaborationIdsByEvent(
            @Param("ids") Collection<String> ids, @Param("event") OfferEvent event);
}
