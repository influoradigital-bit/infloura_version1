package com.influora.service.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QualityScoreService} (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md &sect;4.2,
 * Phase 3 Algorithms). Pure deterministic math over already-fetched metrics — no mocking
 * required, matching {@code WalletServiceTest}'s conventions.
 */
class QualityScoreServiceTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    private final QualityScoreService service = new QualityScoreService();

    @Test
    @DisplayName("calculate: returns all-zero result when latestMetric is empty")
    void testCalculateReturnsZeroForEmptyMetric() {
        QualityScoreResult result = service.calculate(Optional.empty(), List.of());

        assertEquals(BigDecimal.ZERO, result.overall());
        assertEquals(BigDecimal.ZERO, result.engagementScore());
        assertEquals(BigDecimal.ZERO, result.consistency());
        assertEquals(BigDecimal.ZERO, result.frequency());
        assertEquals(BigDecimal.ZERO, result.audienceMatch());
    }

    @Test
    @DisplayName("calculate: audienceMatch is always the neutral 50 placeholder")
    void testCalculateAudienceMatchIsNeutralPlaceholder() {
        CreatorMetric metric = creatorMetric(10_000L);
        QualityScoreResult result = service.calculate(Optional.of(metric), List.of());

        assertEquals(new BigDecimal("50.00"), result.audienceMatch());
    }

    @Test
    @DisplayName("calculate: zero engagement rate (no media or zero followers) yields engagementScore 0")
    void testCalculateZeroEngagementRateYieldsZeroScore() {
        CreatorMetric metric = creatorMetric(10_000L);
        QualityScoreResult result = service.calculate(Optional.of(metric), List.of());

        assertEquals(new BigDecimal("0.00"), result.engagementScore());
    }

    @Test
    @DisplayName("calculate: zero followers does not throw and yields zero engagement score")
    void testCalculateHandlesZeroFollowers() {
        CreatorMetric metric = creatorMetric(0L);
        List<MediaMetric> media = List.of(media(10L, 5L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("0.00"), result.engagementScore());
    }

    @Test
    @DisplayName("calculate: engagement rate >= 10% saturates engagementScore at 100")
    void testCalculateSaturatesEngagementScoreAtHighRate() {
        // 10,000 followers, avg engagement 1500/post = 15% rate (>= 10% ceiling)
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> media = List.of(media(1000L, 500L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("100.00"), result.engagementScore());
    }

    @Test
    @DisplayName("calculate: engagement rate of ~2% scores roughly 55 on the sigmoid curve")
    void testCalculateSigmoidMidRange() {
        // 10,000 followers, avg engagement 200/post = 2% rate
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> media = List.of(media(150L, 50L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        // 100 * (1 - e^(-2/2.5)) = 100 * (1 - e^-0.8) ~= 55.07
        assertTrue(
                Math.abs(result.engagementScore().doubleValue() - 55.07) <= 0.5,
                "Expected ~55.07 but was " + result.engagementScore());
    }

    @Test
    @DisplayName("calculate: fewer than 3 media items yields neutral consistency score of 50")
    void testCalculateConsistencyNeutralForFewerThanThreeMedia() {
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> media = List.of(media(100L, 50L), media(90L, 40L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("50.00"), result.consistency());
    }

    @Test
    @DisplayName("calculate: identical engagement across posts (CV=0) yields consistency score of 100")
    void testCalculateConsistencyPerfectForZeroVariance() {
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> media =
                List.of(media(70L, 30L), media(70L, 30L), media(70L, 30L), media(70L, 30L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("100.00"), result.consistency());
    }

    @Test
    @DisplayName("calculate: zero mean engagement (all likes/comments null/zero) yields neutral consistency of 50")
    void testCalculateConsistencyNeutralForZeroMeanEngagement() {
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> media = List.of(media(null, null), media(null, null), media(0L, 0L));

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("50.00"), result.consistency());
    }

    @Test
    @DisplayName("calculate: highly variable engagement yields a lower consistency score than stable engagement")
    void testCalculateConsistencyPenalizesHighVariance() {
        CreatorMetric metric = creatorMetric(10_000L);
        List<MediaMetric> stableMedia =
                List.of(media(70L, 30L), media(72L, 28L), media(68L, 32L), media(70L, 30L));
        List<MediaMetric> volatileMedia =
                List.of(media(10L, 0L), media(500L, 300L), media(5L, 2L), media(300L, 100L));

        QualityScoreResult stable = service.calculate(Optional.of(metric), stableMedia);
        QualityScoreResult volatile_ = service.calculate(Optional.of(metric), volatileMedia);

        assertTrue(stable.consistency().doubleValue() > volatile_.consistency().doubleValue());
    }

    @Test
    @DisplayName("calculate: empty media list yields frequency score of 0")
    void testCalculateFrequencyZeroForEmptyMedia() {
        CreatorMetric metric = creatorMetric(10_000L);

        QualityScoreResult result = service.calculate(Optional.of(metric), List.of());

        assertEquals(new BigDecimal("0.00"), result.frequency());
    }

    @Test
    @DisplayName("calculate: 3-7 posts/week (optimal cadence) yields frequency score of 100")
    void testCalculateFrequencyOptimalCadence() {
        CreatorMetric metric = creatorMetric(10_000L);
        // 20 posts in the last 30 days -> 5 posts/week, within the 3-7 optimal band
        List<MediaMetric> media = postsOverLastNDays(20, 30);

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("100.00"), result.frequency());
    }

    @Test
    @DisplayName("calculate: less than 1 post/week yields low frequency score of 20")
    void testCalculateFrequencyLowForSparsePosting() {
        CreatorMetric metric = creatorMetric(10_000L);
        // 2 posts in the last 30 days -> 0.5 posts/week, below the "< 1" threshold
        List<MediaMetric> media = postsOverLastNDays(2, 30);

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("20.00"), result.frequency());
    }

    @Test
    @DisplayName("calculate: posting cadence over 14/week (spam territory) yields frequency score of 50")
    void testCalculateFrequencySpamTerritory() {
        CreatorMetric metric = creatorMetric(10_000L);
        // 450 posts in the last 30 days -> 112.5 posts/week, well above the 14/week spam threshold
        List<MediaMetric> media = postsOverLastNDays(450, 30);

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        assertEquals(new BigDecimal("50.00"), result.frequency());
    }

    @Test
    @DisplayName("calculate: posts with null postedAt are excluded from the frequency window")
    void testCalculateFrequencyIgnoresNullPostedAt() {
        CreatorMetric metric = creatorMetric(10_000L);
        MediaMetric noDate =
                MediaMetric.builder()
                        .id("01HWMEDIANODATE00000001")
                        .mediaId("no_date")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .mediaType("IMAGE")
                        .likes(10L)
                        .comments(5L)
                        .build(); // postedAt left null

        QualityScoreResult result = service.calculate(Optional.of(metric), List.of(noDate));

        // 0 counted posts in the 30-day window -> 0 posts/week -> "< 1" bucket -> score 20
        assertEquals(new BigDecimal("20.00"), result.frequency());
    }

    @Test
    @DisplayName("calculate: overall is the 40/25/20/15 weighted composite of the four sub-scores")
    void testCalculateOverallIsWeightedComposite() {
        CreatorMetric metric = creatorMetric(10_000L);
        // Engagement: avg 1500/post on 10,000 followers = 15% rate -> saturates at 100
        // Consistency: identical engagement across posts -> CV=0 -> 100
        // Frequency: 20 posts over 30 days -> 5/week -> optimal -> 100
        // AudienceMatch: fixed neutral 50
        List<MediaMetric> media = postsOverLastNDays(20, 30, 1000L, 500L);

        QualityScoreResult result = service.calculate(Optional.of(metric), media);

        double expectedOverall = (100 * 0.40) + (100 * 0.25) + (100 * 0.20) + (50 * 0.15);
        assertEquals(
                BigDecimal.valueOf(expectedOverall).setScale(2, java.math.RoundingMode.HALF_UP),
                result.overall());
    }

    // --- fixtures ---

    private CreatorMetric creatorMetric(long followers) {
        return CreatorMetric.builder()
                .id("01HWMETRIC0000000000001")
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .followers(followers)
                .time(Instant.now())
                .build();
    }

    private MediaMetric media(Long likes, Long comments) {
        return MediaMetric.builder()
                .id("01HWMEDIA" + Math.abs((likes + "_" + comments).hashCode()))
                .mediaId("media_" + likes + "_" + comments)
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .mediaType("IMAGE")
                .likes(likes)
                .comments(comments)
                .time(Instant.now())
                .build();
    }

    private List<MediaMetric> postsOverLastNDays(int count, int windowDays) {
        return postsOverLastNDays(count, windowDays, 70L, 30L);
    }

    private List<MediaMetric> postsOverLastNDays(int count, int windowDays, Long likes, Long comments) {
        Instant now = Instant.now();
        java.util.List<MediaMetric> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant postedAt = now.minus(i % windowDays, ChronoUnit.DAYS);
            result.add(
                    MediaMetric.builder()
                            .id("01HWMEDIA" + i + "000000000")
                            .mediaId("media_" + i)
                            .creatorProfileId(CREATOR_ID)
                            .platform("INSTAGRAM")
                            .mediaType("IMAGE")
                            .likes(likes)
                            .comments(comments)
                            .postedAt(postedAt)
                            .time(now)
                            .build());
        }
        return result;
    }
}
