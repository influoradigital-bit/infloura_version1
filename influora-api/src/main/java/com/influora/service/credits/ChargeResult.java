package com.influora.service.credits;

import com.influora.domain.enums.ChargeKind;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.1) — the outcome of {@code CreatorCreditService#charge}.
 * {@link #charged()} turns are the only ones that debited anything; {@link #refused()} turns
 * (DAILY_CAP / INSUFFICIENT) must be turned into a 402/429 by the caller via {@code
 * CreatorCreditService#refusal} BEFORE anything else is persisted for that turn. DISABLED and
 * ALREADY_CHARGED are neither refused nor charged — DISABLED means the caller must behave exactly
 * as if creator credits do not exist (R8); ALREADY_CHARGED means a retry landed on an already-
 * settled turn and the caller should proceed without charging or refusing again.
 */
public record ChargeResult(Outcome outcome, ChargeKind kind, int cost, int balanceAfter, int dailyUsedAfter) {

    public enum Outcome {
        DISABLED,
        ALREADY_CHARGED,
        DAILY_CAP,
        INSUFFICIENT,
        CHARGED
    }

    public boolean refused() {
        return outcome == Outcome.DAILY_CAP || outcome == Outcome.INSUFFICIENT;
    }

    public boolean charged() {
        return outcome == Outcome.CHARGED;
    }

    /**
     * Review finding #18 — every factory below now takes {@code cost} explicitly from the caller
     * (which computes it from the LIVE {@code CreatorCreditProperties}, the same values {@code
     * GET /creator/credits}'s {@code costs} field shows) rather than defaulting to {@link
     * ChargeKind#cost()}'s fixed nominal constant. If {@code turn-cost}/{@code voice-surcharge}/
     * {@code brief-cost} are ever tuned away from their current defaults (1/1/3), the amount
     * actually debited and the amount reported here can no longer diverge.
     */
    public static ChargeResult disabled(ChargeKind kind, int cost) {
        return new ChargeResult(Outcome.DISABLED, kind, cost, 0, 0);
    }

    public static ChargeResult alreadyCharged(ChargeKind kind, int cost, int balanceAfter, int dailyUsedAfter) {
        return new ChargeResult(Outcome.ALREADY_CHARGED, kind, cost, balanceAfter, dailyUsedAfter);
    }

    public static ChargeResult dailyCap(ChargeKind kind, int cost, int balanceAfter, int dailyUsedAfter) {
        return new ChargeResult(Outcome.DAILY_CAP, kind, cost, balanceAfter, dailyUsedAfter);
    }

    public static ChargeResult insufficient(ChargeKind kind, int cost, int balanceAfter, int dailyUsedAfter) {
        return new ChargeResult(Outcome.INSUFFICIENT, kind, cost, balanceAfter, dailyUsedAfter);
    }

    public static ChargeResult charged(ChargeKind kind, int cost, int balanceAfter, int dailyUsedAfter) {
        return new ChargeResult(Outcome.CHARGED, kind, cost, balanceAfter, dailyUsedAfter);
    }
}
