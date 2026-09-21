package com.influora.domain.enums;

/**
 * Where a {@link com.influora.domain.entity.CreatorBrief} came from (SPEC.md T-MEERA-CREATOR-PHASE-B
 * &sect;2.2).
 *
 * <p>{@code PASTED} is the Phase-B0 product: the creator pastes a DM or email Meera has no other way
 * to see, and the brief has no {@code collaboration_id} until a secure link is redeemed.
 * {@code PLATFORM} is a brief lifted from a collaboration that already exists on Influora, so its
 * {@code collaboration_id} is set from the start.
 */
public enum BriefSource {
    PASTED,
    PLATFORM
}
