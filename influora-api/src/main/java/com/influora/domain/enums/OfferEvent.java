package com.influora.domain.enums;

/**
 * What happened at one step of a negotiation (SPEC.md T-MEERA-CREATOR-PHASE-B &sect;2.6), written at
 * {@code DealService}'s four existing write points: {@code createProposal} -&gt; {@link #OFFER},
 * {@code doCounter} -&gt; {@link #COUNTER} or {@link #MEERA_COUNTER}, {@code doAccept} -&gt;
 * {@link #ACCEPT}, {@code doReject} -&gt; {@link #REJECT}.
 *
 * <p>{@link #MEERA_COUNTER} is a distinct EVENT rather than a flag on {@link #COUNTER} because it is
 * what SPEC.md &sect;14.1.d's {@code meeraAnchoredShare} selects on. Several {@code MEERA_COUNTER}
 * rows on one collaboration are legal -- the table's unique key is
 * {@code (collaboration_id, sequence_no)}, not {@code (collaboration_id, event)} -- so that share
 * must be computed over DISTINCT collaboration ids, never over a row count.
 */
public enum OfferEvent {
    OFFER,
    COUNTER,
    MEERA_COUNTER,
    ACCEPT,
    REJECT
}
