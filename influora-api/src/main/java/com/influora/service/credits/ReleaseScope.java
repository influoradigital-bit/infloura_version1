package com.influora.service.credits;

/** T-CREATOR-CREDITS-V2 (SPEC.md §5.1) — which reference id(s) {@code CreatorCreditService#release} restores. */
public enum ReleaseScope {
    /** A text or voice turn — restores {@code turn:<id>} and (if present) {@code tts:<id>}. */
    TURN,
    /** Only the voice surcharge — restores {@code tts:<id>} alone (a Sarvam failure on an otherwise-fine turn). */
    VOICE_ONLY,
    /** A brief paste — restores {@code brief:<id>}. */
    BRIEF
}
