package com.influora.domain.enums;

/**
 * Who caused a {@link com.influora.domain.entity.DealOfferHistory} row (SPEC.md
 * T-MEERA-CREATOR-PHASE-B &sect;2.6). Deliberately NOT reusing {@code DealSenderType}: this records
 * the actor behind a negotiation EVENT, including {@code SYSTEM} events no party sent as a message.
 *
 * <p>Note this is orthogonal to authorship: a {@code CREATOR} offer that Meera drafted is
 * {@code actor = CREATOR} with {@code meeraDrafted = true}, because the creator approved and owns
 * it. Actor answers "whose offer is this", not "who typed it".
 */
public enum OfferActor {
    BRAND,
    CREATOR,
    SYSTEM
}
