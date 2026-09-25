package com.influora.domain.enums;

/**
 * Meera intelligence v1, slice 2 (spec 8.4) -- a {@code creator_recommendations} row's outcome
 * state. OPEN and MATCHED rows are re-evaluated lazily on read; SETTLED, MISSED and NO_OUTCOME
 * rows are frozen and never touched again. Stored as {@code name()} in {@code status VARCHAR(12)}.
 */
public enum CreatorRecommendationStatus {
    /** No post has filled it yet, and its day (plus the checking grace) has not passed. */
    OPEN,
    /** A post filled it; its outcome waits for that post's reading to settle (48 h). */
    MATCHED,
    /** The matched post settled and its outcome is stored. Frozen. */
    SETTLED,
    /** No post by the end of its matching window plus the 12 h checking grace. Frozen. */
    MISSED,
    /**
     * The outcome can no longer be decided. Frozen. A MATCHED row whose post never settled by
     * {@code match_until} + the 48 h settling period + a 7-day margin (account switched, post
     * deleted, post dropped out of the 25-post poll), or an OPEN row whose matching window lies
     * before the evaluator's 180-day scan floor (its posts were never scanned, so MISSED would be
     * a guess). Kabir M-2: without it such rows stayed live, and scanned, forever.
     */
    NO_OUTCOME
}
