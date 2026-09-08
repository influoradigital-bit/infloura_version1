package com.influora.domain.enums;

/**
 * Lifecycle of a {@link com.influora.domain.entity.CreatorBrief} (SPEC.md T-MEERA-CREATOR-PHASE-B
 * &sect;2.2): {@code NEW} on paste, {@code ANALYZED} once extraction/risk/quote have been written
 * back, {@code DRAFTED} once Meera has composed a reply for it, {@code SECURED} once it has become a
 * real collaboration. {@code DISMISSED} is reachable from any of the four -- the creator decided the
 * brief was not worth pursuing.
 */
public enum BriefStatus {
    NEW,
    ANALYZED,
    DRAFTED,
    SECURED,
    DISMISSED
}
