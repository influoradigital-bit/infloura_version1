package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- unit tests for {@link CreatorAiCredit}'s builder
 * defaults and {@code total()}. CREDITS-SPEC.md §2.5(a): this entity uses UTC everywhere
 * (Priya's correction -- do NOT copy {@code BrandAiCredit.Builder}'s system-zone {@code
 * LocalDate.now()}).
 */
class CreatorAiCreditTest {

    @Test
    @DisplayName("Builder defaults cycleStart to today in UTC when omitted")
    void builderDefaultsCycleStartToUtcToday() {
        CreatorAiCredit credit = CreatorAiCredit.builder().creatorUserId("01HCREATOR000000000000001").build();

        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getCycleStart());
    }

    @Test
    @DisplayName("Builder defaults lastReset to cycleStart when omitted")
    void builderDefaultsLastResetToCycleStart() {
        LocalDate cycleStart = LocalDate.of(2026, 9, 1);
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId("01HCREATOR000000000000001")
                        .cycleStart(cycleStart)
                        .build();

        assertEquals(cycleStart, credit.getLastReset());
    }

    @Test
    @DisplayName("Builder keeps an explicitly set lastReset distinct from cycleStart")
    void builderKeepsExplicitLastReset() {
        LocalDate cycleStart = LocalDate.of(2026, 9, 1);
        LocalDate lastReset = LocalDate.of(2026, 8, 15);
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId("01HCREATOR000000000000001")
                        .cycleStart(cycleStart)
                        .lastReset(lastReset)
                        .build();

        assertEquals(lastReset, credit.getLastReset());
    }

    @Test
    @DisplayName("total() sums monthlyRemaining and purchasedBalance, both in tenths")
    void totalSumsBothBucketsInTenths() {
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId("01HCREATOR000000000000001")
                        .monthlyRemaining(400) // 40.0 credits
                        .purchasedBalance(300) // 30.0 credits, e.g. the signup grant
                        .build();

        assertEquals(700, credit.total());
    }

    @Test
    @DisplayName("total() reflects a partial spend correctly (2.5-credit search debit)")
    void totalReflectsPartialSpend() {
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId("01HCREATOR000000000000001")
                        .monthlyRemaining(375) // 40.0 - 2.5 = 37.5
                        .purchasedBalance(0)
                        .build();

        assertEquals(375, credit.total());
    }

    @Test
    @DisplayName("freeSearchWeekStart is null on a freshly built row -- load-bearing for tryClaimFreeSearch")
    void freeSearchWeekStartDefaultsToNull() {
        CreatorAiCredit credit = CreatorAiCredit.builder().creatorUserId("01HCREATOR000000000000001").build();

        assertNull(
                credit.getFreeSearchWeekStart(),
                "a fresh row must have a NULL free_search_week_start, not some sentinel date -- "
                        + "CreatorAiCreditRepository#tryClaimFreeSearch's WHERE clause depends on this");
    }

    @Test
    @DisplayName("dailyActionsUsed and freeSearchesUsed are event counts, not tenths -- builder passes them through unscaled")
    void countersAreNotScaledAsTenths() {
        CreatorAiCredit credit =
                CreatorAiCredit.builder()
                        .creatorUserId("01HCREATOR000000000000001")
                        .dailyActionsUsed(3)
                        .freeSearchesUsed(2)
                        .build();

        assertEquals(3, credit.getDailyActionsUsed());
        assertEquals(2, credit.getFreeSearchesUsed());
    }
}
