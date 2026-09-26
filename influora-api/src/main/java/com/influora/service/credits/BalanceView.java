package com.influora.service.credits;

import java.time.Instant;
import java.util.List;

/** T-CREATOR-CREDITS-V2 (SPEC.md §8) — read-only projection backing {@code GET /creator/credits}. Never written from; {@code CreatorCreditService#balance} performs zero writes (A7). */
public record BalanceView(
        int total,
        int free,
        int paid,
        int dailyUsed,
        int dailyCap,
        Instant dailyResetsAt,
        Instant nextMonthlyGrantAt,
        boolean welcomeEligible,
        boolean welcomeGranted,
        Instant welcomeGrantedAt,
        String monthlyPeriod,
        boolean monthlyGrantedThisPeriod,
        int pendingWelcome,
        int pendingMonthly,
        List<PaidExpiring> paidExpiring) {

    public record PaidExpiring(int credits, Instant expiresAt) {}
}
