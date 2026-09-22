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
    BRIEF(3);

    private final int cost;

    ChargeKind(int cost) {
        this.cost = cost;
    }

    public int cost() {
        return cost;
    }
}
