package com.influora.domain.enums;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4, owner ruling R1) — what a creator turn/brief costs, in whole
 * credits. {@link #VOICE_TURN} costs 2 total but is debited as TWO separate 1-credit ledger rows
 * under two separate reference ids ({@code turn:<id>} DEBIT_TURN, {@code tts:<id>} DEBIT_VOICE) in
 * the SAME locked transaction — see {@code CreatorCreditService#charge} step 9 — so the {@code
 * tts:} half alone can be refunded on a Sarvam failure without touching the {@code turn:} half.
 */
public enum ChargeKind {
    TURN(1),
    VOICE_TURN(2),
    BRIEF(3),
    /**
     * 2026-09-22 (Swapnil, option A) — the creator pressed "Write a script": a chat turn whose
     * reply is a full reel script, several times longer than a normal answer. Debited like a TURN
     * (one DEBIT_TURN row under {@code turn:<id>}, so refund, write-back marker and locking are
     * all the TURN path's) but for {@code script-cost} credits instead of {@code turn-cost}.
     */
    SCRIPT(3),
    /** 2026-09-22 — the creator pressed "Review my profile". Same TURN-shaped debit as {@link #SCRIPT}. */
    PROFILE_REVIEW(3);

    private final int cost;

    ChargeKind(int cost) {
        this.cost = cost;
    }

    public int cost() {
        return cost;
    }
}
