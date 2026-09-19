package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.domain.entity.PlatformStat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0965 — the one rule for a creator's follower total (ruling 2026-09-19: option a with a separate
 * imported total). Every writer of CreatorProfile.totalFollowers goes through it.
 */
class FollowerTotalsTest {

    private static PlatformStat stat(String platform, String source, long followers, String rate) {
        return PlatformStat.builder()
                .id("01HSTAT" + platform + source)
                .creatorProfileId("01HCREATORF0965000001")
                .platform(platform)
                .followers(followers)
                .engagementRate(rate == null ? null : new BigDecimal(rate))
                .verified(PlatformStat.SOURCE_META_API.equals(source))
                .source(source)
                .build();
    }

    @Test
    @DisplayName("verified platforms only: a declared 900k YouTube and an imported row never count")
    void verifiedOnly() {
        FollowerTotals t =
                FollowerTotals.from(
                        List.of(
                                stat("INSTAGRAM", PlatformStat.SOURCE_META_API, 12_000, "4.10"),
                                stat("FACEBOOK", PlatformStat.SOURCE_META_API, 3_000, "2.00"),
                                stat("YOUTUBE", PlatformStat.SOURCE_CREATOR_REPORTED, 900_000, "9.99"),
                                stat("TIKTOK", PlatformStat.SOURCE_IMPORTED, 50_000, "7.00")));

        assertEquals(15_000L, t.totalFollowers());
        assertEquals(FollowerTotals.VERIFIED, t.source());
        // The largest VERIFIED audience's rate, never a declared or imported one.
        assertEquals(new BigDecimal("4.10"), t.engagementRate());
    }

    @Test
    @DisplayName("no verified platform: the imported total counts, labelled IMPORTED; declared still ignored")
    void importedWhenNothingVerified() {
        FollowerTotals t =
                FollowerTotals.from(
                        List.of(
                                stat("INSTAGRAM", PlatformStat.SOURCE_IMPORTED, 184_000, "3.20"),
                                stat("YOUTUBE", PlatformStat.SOURCE_CREATOR_REPORTED, 900_000, null)));

        assertEquals(184_000L, t.totalFollowers());
        assertEquals(FollowerTotals.IMPORTED, t.source());
        assertEquals(new BigDecimal("3.20"), t.engagementRate());
    }

    @Test
    @DisplayName("only creator-declared platforms: total 0, source NONE, no engagement")
    void declaredOnlyCountsNothing() {
        FollowerTotals t =
                FollowerTotals.from(List.of(stat("YOUTUBE", PlatformStat.SOURCE_CREATOR_REPORTED, 900_000, "5.00")));

        assertEquals(0L, t.totalFollowers());
        assertEquals(FollowerTotals.NONE, t.source());
        assertNull(t.engagementRate());
    }

    @Test
    @DisplayName("no platforms at all: total 0, source NONE")
    void emptyIsNone() {
        FollowerTotals t = FollowerTotals.from(List.of());
        assertEquals(0L, t.totalFollowers());
        assertEquals(FollowerTotals.NONE, t.source());
    }

    @Test
    @DisplayName("a just-written stat replaces the stored stat for its platform (no double count)")
    void justWrittenReplacesStoredPlatform() {
        PlatformStat storedDeclared = stat("INSTAGRAM", PlatformStat.SOURCE_CREATOR_REPORTED, 1_000, null);
        PlatformStat justSynced = stat("INSTAGRAM", PlatformStat.SOURCE_META_API, 12_500, "4.00");

        FollowerTotals t = FollowerTotals.from(List.of(storedDeclared), List.of(justSynced));

        assertEquals(12_500L, t.totalFollowers());
        assertEquals(FollowerTotals.VERIFIED, t.source());
    }

    @Test
    @DisplayName("the builder derives the source from the verified flag unless set explicitly")
    void builderDerivesSource() {
        PlatformStat verified =
                PlatformStat.builder().id("a").creatorProfileId("c").platform("INSTAGRAM").verified(true).build();
        PlatformStat unverified =
                PlatformStat.builder().id("b").creatorProfileId("c").platform("YOUTUBE").verified(false).build();

        assertEquals(PlatformStat.SOURCE_META_API, verified.getSource());
        assertEquals(PlatformStat.SOURCE_CREATOR_REPORTED, unverified.getSource());

        verified.applySnapshot(10, null, false, null);
        assertEquals(PlatformStat.SOURCE_CREATOR_REPORTED, verified.getSource());
    }
}
