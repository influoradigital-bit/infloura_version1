package com.influora.domain.enums;

/**
 * What a {@link com.influora.domain.entity.MeeraDraft} is a draft OF (SPEC.md
 * T-MEERA-CREATOR-PHASE-B &sect;2.4). This, not the set of populated parent ids, is what tells a
 * reader which parent matters and which approve path the draft takes (SPEC.md &sect;3.7: REPLY routes
 * to {@code sendMessage}, COUNTER to {@code counter}, DECLINE to {@code reject}).
 *
 * <p>{@code ROUTINE} is the only kind that carries an {@code intent}, and it is Phase-B1 territory
 * (level-1 routine replies); B0 writes REPLY, COUNTER, DECLINE and APPLICATION only.
 */
public enum DraftKind {
    REPLY,
    COUNTER,
    DECLINE,
    APPLICATION,
    ROUTINE
}
