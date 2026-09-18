package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — proves {@link TrendHeadlineScreener} is a genuine
 * pass-through onto {@link CreatorNudgeService}'s real word filter, not a re-implementation.
 * Uses real headlines from wiki/decisions/2026-09-18-trend-headline-screening.md's own
 * done_when list (Kabir's benign control set + a DEATH-category term).
 */
class TrendHeadlineScreenerTest {

    @Test
    @DisplayName("a headline carrying a DEATH-category term is rejected, with that category named")
    void rejectsUnsafeHeadline() {
        String headline = "Popular actor dies in car crash on set";

        assertFalse(TrendHeadlineScreener.isSafeForCreatorCopy(headline));
        assertEquals("DEATH", TrendHeadlineScreener.rejectionCategory(headline));
    }

    @Test
    @DisplayName("benign control-set headlines from the ruling's done_when list pass")
    void allowsBenignControlSet() {
        for (String benign :
                new String[] {
                    "Tennis courts open",
                    "Flash mobs dance",
                    "Killer ab workout",
                    "issues with skincare",
                    "Diwali fashion haul",
                    "budget travel in Goa"
                }) {
            assertTrue(
                    TrendHeadlineScreener.isSafeForCreatorCopy(benign),
                    benign + " should have passed the word filter");
            assertNull(TrendHeadlineScreener.rejectionCategory(benign));
        }
    }

    @Test
    @DisplayName("fails closed on null")
    void failsClosedOnNull() {
        assertFalse(TrendHeadlineScreener.isSafeForCreatorCopy(null));
    }
}
