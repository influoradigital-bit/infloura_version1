package com.influora.service;

import com.influora.domain.entity.PlatformStat;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * F-0965 — the ONE rule for a creator's headline follower total, engagement rate and their
 * provenance (ruling 2026-09-19, option a with a separate imported total):
 *
 * <ol>
 *   <li>If the creator has any {@link PlatformStat#SOURCE_META_API} platform, the total is the sum
 *       of those platforms only, and the source is {@link #VERIFIED}.
 *   <li>Otherwise, if there is an {@link PlatformStat#SOURCE_IMPORTED} platform (Meta Creator
 *       Marketplace or admin import), the total is its followers and the source is {@link
 *       #IMPORTED} — discovery labels it "imported, not verified".
 *   <li>Otherwise the total is 0 and the source is {@link #NONE}.
 * </ol>
 *
 * A {@link PlatformStat#SOURCE_CREATOR_REPORTED} platform never counts: before this rule every
 * writer summed all platforms, so a creator declaring "YouTube: 900,000" inflated the total brands
 * filter and rank on. Declared platforms still appear in the per-platform list (their stat's
 * {@code source} is CREATOR_REPORTED); they simply never count toward this total.
 */
public record FollowerTotals(long totalFollowers, BigDecimal engagementRate, String source) {

    public static final String VERIFIED = "VERIFIED";
    public static final String IMPORTED = "IMPORTED";
    public static final String NONE = "NONE";

    /**
     * The totals for {@code stored} (everything already persisted for the creator) with {@code
     * justWritten} replacing its platform's entry. Writers call this right after upserting one
     * platform, so the result never depends on a flush before a re-read.
     */
    public static FollowerTotals from(Collection<PlatformStat> stored, Collection<PlatformStat> justWritten) {
        Map<String, PlatformStat> byPlatform = new LinkedHashMap<>();
        stored.forEach(s -> byPlatform.put(s.getPlatform(), s));
        justWritten.forEach(s -> byPlatform.put(s.getPlatform(), s));
        return from(List.copyOf(byPlatform.values()));
    }

    public static FollowerTotals from(List<PlatformStat> stats) {
        List<PlatformStat> verified = only(stats, PlatformStat.SOURCE_META_API);
        if (!verified.isEmpty()) {
            return new FollowerTotals(sum(verified), engagement(verified), VERIFIED);
        }
        List<PlatformStat> imported = only(stats, PlatformStat.SOURCE_IMPORTED);
        if (!imported.isEmpty()) {
            return new FollowerTotals(sum(imported), engagement(imported), IMPORTED);
        }
        return new FollowerTotals(0L, null, NONE);
    }

    private static List<PlatformStat> only(List<PlatformStat> stats, String source) {
        return stats.stream().filter(s -> source.equals(s.getSource())).toList();
    }

    private static long sum(List<PlatformStat> stats) {
        return stats.stream().mapToLong(PlatformStat::getFollowers).sum();
    }

    /** The largest audience's rate among the counted platforms — deterministic, never averaged. */
    private static BigDecimal engagement(List<PlatformStat> stats) {
        return stats.stream()
                .filter(s -> s.getEngagementRate() != null)
                .max(Comparator.comparingLong(PlatformStat::getFollowers))
                .map(PlatformStat::getEngagementRate)
                .filter(Objects::nonNull)
                .orElse(null);
    }
}
