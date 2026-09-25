package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.enums.ChallengeDayType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Meera intelligence v1 (spec &sect;3.2, T15) -- the shared post rules moved out of {@link
 * CreatorPostingPatternService} and {@code CreatorChallengeService}. The move itself is guarded by
 * those two classes' existing tests, unedited; this pins the rules directly.
 */
class CreatorPostRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("settling period is 48 hours")
    void settlingPeriod() {
        assertEquals(Duration.ofHours(48), CreatorPostRules.SETTLING_PERIOD);
    }

    @Test
    @DisplayName("windowLabel: IST weekday/weekend x day part, boundaries inclusive at the lower end")
    void windowLabel() {
        LocalDate friday = LocalDate.of(2026, 9, 25);
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        assertEquals("weekday morning", CreatorPostRules.windowLabel(friday.atTime(5, 0).atZone(IST).toInstant()));
        assertEquals("weekday afternoon", CreatorPostRules.windowLabel(friday.atTime(12, 0).atZone(IST).toInstant()));
        assertEquals("weekday evening", CreatorPostRules.windowLabel(friday.atTime(17, 0).atZone(IST).toInstant()));
        assertEquals("weekday night", CreatorPostRules.windowLabel(friday.atTime(22, 0).atZone(IST).toInstant()));
        assertEquals("weekday night", CreatorPostRules.windowLabel(friday.atTime(4, 59).atZone(IST).toInstant()));
        assertEquals("weekend evening", CreatorPostRules.windowLabel(saturday.atTime(19, 40).atZone(IST).toInstant()));
        // 20:00 UTC on a Friday is 01:30 IST on Saturday: the IST calendar decides.
        assertEquals(
                "weekend night",
                CreatorPostRules.windowLabel(friday.atTime(20, 0).atZone(ZoneId.of("UTC")).toInstant()));
    }

    @Test
    @DisplayName("canonicalType: VIDEO and REELS are one REEL group; unknown values map to null, never a guess")
    void canonicalType() {
        assertEquals(ChallengeDayType.REEL, CreatorPostRules.canonicalType("VIDEO"));
        assertEquals(ChallengeDayType.REEL, CreatorPostRules.canonicalType("REELS"));
        assertEquals(ChallengeDayType.CAROUSEL, CreatorPostRules.canonicalType("CAROUSEL_ALBUM"));
        assertEquals(ChallengeDayType.POST, CreatorPostRules.canonicalType("IMAGE"));
        assertNull(CreatorPostRules.canonicalType("UNKNOWN"));
        assertNull(CreatorPostRules.canonicalType("image"));
        assertNull(CreatorPostRules.canonicalType(null));
    }

    @Test
    @DisplayName("typeMatches: the challenge's mapping, REST matches nothing")
    void typeMatches() {
        assertTrue(CreatorPostRules.typeMatches(ChallengeDayType.REEL, "VIDEO"));
        assertTrue(CreatorPostRules.typeMatches(ChallengeDayType.REEL, "REELS"));
        assertTrue(CreatorPostRules.typeMatches(ChallengeDayType.CAROUSEL, "CAROUSEL_ALBUM"));
        assertTrue(CreatorPostRules.typeMatches(ChallengeDayType.POST, "IMAGE"));
        assertFalse(CreatorPostRules.typeMatches(ChallengeDayType.POST, "VIDEO"));
        assertFalse(CreatorPostRules.typeMatches(ChallengeDayType.REEL, "UNKNOWN"));
        assertFalse(CreatorPostRules.typeMatches(ChallengeDayType.REST, "IMAGE"));
        assertFalse(CreatorPostRules.typeMatches(ChallengeDayType.REEL, null));
    }
}
