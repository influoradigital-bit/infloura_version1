package com.influora.domain.enums;

/**
 * State of a {@link com.influora.domain.entity.MeeraDraft} (SPEC.md T-MEERA-CREATOR-PHASE-B
 * &sect;2.4).
 *
 * <p><b>Only three of these five are ever written.</b> Creation writes {@link #PENDING}, SPEC.md
 * &sect;3.7's approve writes {@link #SENT}, discard writes {@link #DISCARDED}. Nothing in Phase B
 * writes {@link #APPROVED} or {@link #EDITED} and no code path should be added that does -- whether
 * the creator changed the text before sending is recorded by {@code MeeraDraft.edited}, a real
 * column, because that is what Phase-B0 gate metric 3 (SPEC.md &sect;14.5.c) is measured against.
 * The two inert names are kept rather than deleted because this enum is persisted as a string:
 * removing a declared value a stored row could hold is the worse trade.
 */
public enum DraftStatus {
    PENDING,
    APPROVED,
    EDITED,
    DISCARDED,
    SENT
}
