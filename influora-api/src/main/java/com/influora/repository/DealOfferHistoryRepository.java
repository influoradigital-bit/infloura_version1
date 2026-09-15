package com.influora.repository;

import com.influora.domain.entity.DealOfferHistory;
import com.influora.domain.enums.OfferActor;
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
     * The {@code + 1} half of {@code sequenceNo}'s derivation. Correct ONLY when called while the
     * collaboration row lock is held, which today means from {@code DealService.recordOffer} and
     * nowhere else — that method takes the lock itself rather than trusting its callers to, because
     * three of the four did not. See {@link DealOfferHistory}'s class javadoc. Outside that lock this
     * is a TOCTOU, and the table's
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
     *
     * <p><b>{@code actor} is a required parameter and not an implicit {@code CREATOR}</b>, so that
     * every call site has to state whose rows it is counting. A Meera-drafted counter is a CREATOR
     * action by definition — Meera drafts for creators, and {@code DealService.doCounter} refuses to
     * stamp authorship on a brand-actor row — but {@code counter} is a MUTUAL route and a brand client
     * posts to it too. Before the actor clause existed, one brand sending any non-blank
     * {@code meeraDraftId} inflated {@code meeraAnchoredShare} for every creator in the band, which is
     * the number that labels a price "mostly Meera-quoted" and the number the B0-to-B1 decision
     * reads. Filtering on the event alone was the second half of that hole.
     */
    @Query("select distinct h.collaborationId from DealOfferHistory h "
            + "where h.collaborationId in :ids and h.event = :event and h.actor = :actor")
    List<String> findDistinctCollaborationIdsByEvent(
            @Param("ids") Collection<String> ids,
            @Param("event") OfferEvent event,
            @Param("actor") OfferActor actor);
}
