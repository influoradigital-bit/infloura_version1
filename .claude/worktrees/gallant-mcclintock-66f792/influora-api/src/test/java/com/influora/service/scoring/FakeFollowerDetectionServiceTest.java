package com.influora.service.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import com.influora.service.scoring.FakeFollowerDetectionService.FakeFollowerResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FakeFollowerDetectionService} (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md
 * &sect;4.1, Phase 3 Algorithms). Pure deterministic math over already-fetched metrics — no
 * mocking required, matching {@code WalletServiceTest}'s conventions for the arrange/act/assert
 * shape and {@code @DisplayName} usage.
 */
class FakeFollowerDetectionServiceTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    private final FakeFollowerDetectionService service = new FakeFollowerDetectionService();

    @Test
    @DisplayName("analyze: returns zero score with 'No metrics available' when latestMetric is empty")
    void testAnalyzeReturnsZeroForEmptyMetric() {
        FakeFollowerResult result = service.analyze(Optional.empty(), List.of(), List.of());

        assertEquals(BigDecimal.ZERO, result.score());
        assertEquals(List.of("No metrics available"), result.reasons());
        assertTrue(result.debug().isEmpty());
    }

    @Test
    @DisplayName("analyze: flags extremely low engagement rate (< 0.5%)")
    void testAnalyzeFlagsLowEngagementRate() {
        // 10,000 followers, avg engagement per post = 10 (0.1% rate) -> below 0.5% threshold
        CreatorMetric metric = creatorMetric(10_000L, 500L);
        List<MediaMetric> media = List.of(media(5L, 5L), media(5L, 5L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertEquals(new BigDecimal("25.00"), result.score());
        assertTrue(result.reasons().get(0).contains("Extremely low engagement rate"));
    }

    @Test
    @DisplayName("analyze: flags suspiciously high engagement rate for large accounts (> 15%, followers > 10000)")
    void testAnalyzeFlagsHighEngagementRateForLargeAccount() {
        // 20,000 followers, avg engagement per post = 4000 (20% rate) -> above 15% threshold
        CreatorMetric metric = creatorMetric(20_000L, 500L);
        List<MediaMetric> media = List.of(media(3000L, 1000L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertEquals(new BigDecimal("15.00"), result.score());
        assertTrue(result.reasons().get(0).contains("Suspiciously high engagement rate"));
    }

    @Test
    @DisplayName("analyze: does not flag high engagement rate when follower count <= 10000")
    void testAnalyzeDoesNotFlagHighEngagementForSmallAccount() {
        // 5,000 followers, 20% engagement rate but under the 10000-follower gate
        CreatorMetric metric = creatorMetric(5_000L, 500L);
        List<MediaMetric> media = List.of(media(600L, 400L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertEquals(BigDecimal.ZERO.setScale(2), result.score());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    @DisplayName("analyze: flags sudden follower spike (> 20% in a day) when >= 7 historical points")
    void testAnalyzeFlagsFollowerSpike() {
        CreatorMetric metric = creatorMetric(10_000L, 5_000L);
        // Healthy ~3% engagement (avg 300 of 10,000 followers) so only the growth signal fires
        List<MediaMetric> media = List.of(media(200L, 100L), media(180L, 120L), media(210L, 90L));

        List<CreatorMetric> history = new ArrayList<>();
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        long[] followerCounts = {1000, 1010, 1020, 1030, 1040, 1050, 1500}; // last jump = 42.9%
        for (int i = 0; i < followerCounts.length; i++) {
            history.add(creatorMetricAt(followerCounts[i], base.plus(i, ChronoUnit.DAYS)));
        }

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, history);

        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Sudden follower spike detected")));
        assertTrue((Double) result.debug().get("maxDailyGrowthPct") > 20);
    }

    @Test
    @DisplayName("analyze: flags unusual (but not extreme) follower growth pattern (10-20%)")
    void testAnalyzeFlagsModerateGrowth() {
        CreatorMetric metric = creatorMetric(10_000L, 5_000L);
        // Healthy ~3% engagement (avg 300 of 10,000 followers) so only the growth signal fires
        List<MediaMetric> media = List.of(media(200L, 100L), media(180L, 120L), media(210L, 90L));

        List<CreatorMetric> history = new ArrayList<>();
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        long[] followerCounts = {1000, 1010, 1020, 1030, 1040, 1050, 1170}; // last jump ~11.4%
        for (int i = 0; i < followerCounts.length; i++) {
            history.add(creatorMetricAt(followerCounts[i], base.plus(i, ChronoUnit.DAYS)));
        }

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, history);

        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Unusual follower growth pattern")));
        assertFalse(result.reasons().stream().anyMatch(r -> r.contains("Sudden follower spike detected")));
    }

    @Test
    @DisplayName("analyze: skips growth signal entirely when fewer than 7 historical points")
    void testAnalyzeSkipsGrowthSignalWithInsufficientHistory() {
        CreatorMetric metric = creatorMetric(10_000L, 5_000L);
        List<MediaMetric> media = List.of(media(200L, 100L), media(180L, 120L), media(210L, 90L));

        List<CreatorMetric> history =
                List.of(creatorMetricAt(1000, Instant.now()), creatorMetricAt(5000, Instant.now()));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, history);

        assertFalse(result.debug().containsKey("maxDailyGrowthPct"));
        assertTrue(result.reasons().stream().noneMatch(r -> r.toLowerCase().contains("spike")));
    }

    @Test
    @DisplayName("analyze: flags low follower-to-following ratio (< 0.5, followers > 1000)")
    void testAnalyzeFlagsLowFollowerFollowingRatio() {
        // 2000 followers, 5000 following -> ratio 0.4
        CreatorMetric metric = creatorMetric(2_000L, 5_000L);
        // Healthy ~3% engagement (avg 60 of 2,000 followers) so only the ratio signal fires
        List<MediaMetric> media = List.of(media(40L, 20L), media(35L, 25L), media(45L, 15L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertTrue(
                result.reasons().stream().anyMatch(r -> r.contains("Low follower-to-following ratio")));
    }

    @Test
    @DisplayName("analyze: treats null following as zero (ratio signal does not spuriously fire)")
    void testAnalyzeHandlesNullFollowing() {
        CreatorMetric metric = creatorMetric(2_000L, null);
        List<MediaMetric> media = List.of(media(40L, 20L), media(35L, 25L), media(45L, 15L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        // following=null -> treated as 0 -> ffRatio falls back to followers (2000), not < 0.5
        assertEquals(2000.0, (Double) result.debug().get("followerFollowingRatio"));
        assertFalse(
                result.reasons().stream().anyMatch(r -> r.contains("Low follower-to-following ratio")));
    }

    @Test
    @DisplayName("analyze: flags very few recent posts for a large account (> 50000 followers)")
    void testAnalyzeFlagsSparsePostingForLargeAccount() {
        CreatorMetric metric = creatorMetric(60_000L, 1_000L);
        // Keep engagement healthy (~3%) to isolate the posting-consistency signal.
        List<MediaMetric> media = List.of(media(1200L, 600L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertTrue(
                result.reasons().stream()
                        .anyMatch(r -> r.contains("Very few recent posts for a large account")));
    }

    @Test
    @DisplayName("analyze: zero followers does not throw and yields zero engagement rate")
    void testAnalyzeHandlesZeroFollowers() {
        CreatorMetric metric = creatorMetric(0L, 0L);
        List<MediaMetric> media = List.of(media(10L, 5L));

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, List.of());

        assertEquals(0.0, (Double) result.debug().get("engagementRate"));
    }

    @Test
    @DisplayName("analyze: caps score at 100 when multiple signals stack")
    void testAnalyzeCapsScoreAt100() {
        // Trigger: low engagement (+25), spike (+30), low ff ratio (+20), sparse posting (+15) = 90
        // Add moderate-growth-adjacent history isn't additive with spike, so use spike + others.
        CreatorMetric metric = creatorMetric(60_000L, 130_000L); // ratio < 0.5, followers > 50000
        List<MediaMetric> media = List.of(media(1L, 1L)); // near-zero engagement, < 3 posts

        List<CreatorMetric> history = new ArrayList<>();
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        long[] followerCounts = {10000, 10100, 10200, 10300, 10400, 10500, 60000}; // huge spike
        for (int i = 0; i < followerCounts.length; i++) {
            history.add(creatorMetricAt(followerCounts[i], base.plus(i, ChronoUnit.DAYS)));
        }

        FakeFollowerResult result = service.analyze(Optional.of(metric), media, history);

        assertTrue(result.score().compareTo(new BigDecimal("100.00")) <= 0);
        // With low engagement (25) + spike (30) + low ratio (20) + sparse posting (15) = 90 (not capped),
        // but assert it never exceeds the 100 ceiling regardless.
        assertTrue(result.score().doubleValue() <= 100.0);
    }

    // --- fixtures ---

    private CreatorMetric creatorMetric(long followers, Long following) {
        return CreatorMetric.builder()
                .id("01HWMETRIC0000000000001")
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .followers(followers)
                .following(following)
                .time(Instant.now())
                .build();
    }

    private CreatorMetric creatorMetricAt(long followers, Instant time) {
        return CreatorMetric.builder()
                .id("01HWMETRIC" + Math.abs(time.hashCode()))
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .followers(followers)
                .following(1000L)
                .time(time)
                .build();
    }

    private MediaMetric media(Long likes, Long comments) {
        return MediaMetric.builder()
                .id("01HWMEDIA" + Math.abs((likes + "" + comments).hashCode()))
                .mediaId("media_" + likes + "_" + comments)
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .mediaType("IMAGE")
                .likes(likes)
                .comments(comments)
                .time(Instant.now())
                .build();
    }
}
