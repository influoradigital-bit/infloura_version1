package com.influora.service.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.CreatorMetric;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateEstimationService} (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md &sect;4.4,
 * Phase 3 Algorithms). Pure deterministic math over already-fetched metrics and an
 * already-computed quality score — no mocking required, matching {@code
 * QualityScoreServiceTest}/{@code FakeFollowerDetectionServiceTest}'s conventions.
 */
class RateEstimationServiceTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    private final RateEstimationService service = new RateEstimationService();

    // A neutral quality score: overall = 50 (no quality multiplier kick, "> 0" true for confidence)
    private static final QualityScoreResult NEUTRAL_QUALITY = qualityResult(50);

    // === empty metric ===

    @Test
    @DisplayName("estimate: returns zero/UNKNOWN result when metric is empty")
    void testEstimateReturnsUnknownForEmptyMetric() {
        RateEstimation result = service.estimate(Optional.empty(), NEUTRAL_QUALITY, List.of("FASHION"));

        assertEquals(BigDecimal.ZERO, result.min());
        assertEquals(BigDecimal.ZERO, result.max());
        assertEquals("INR", result.currency());
        assertEquals(BigDecimal.ZERO, result.confidence());
        assertEquals("UNKNOWN", result.tier());
        assertEquals(Map.of(), result.factors());
    }

    // === tier boundaries ===

    @Test
    @DisplayName("determineTier: 999 followers is NANO (just below MICRO boundary)")
    void testTierNanoBelowMicroBoundary() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(999L);
        assertEquals("NANO", result.tier());
    }

    @Test
    @DisplayName("determineTier: 10,000 followers is MICRO (exact boundary)")
    void testTierMicroAtExactBoundary() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(10_000L);
        assertEquals("MICRO", result.tier());
    }

    @Test
    @DisplayName("determineTier: 9,999 followers is NANO (just below MICRO)")
    void testTierNanoJustBelowMicro() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(9_999L);
        assertEquals("NANO", result.tier());
    }

    @Test
    @DisplayName("determineTier: 50,000 followers is MID (exact boundary)")
    void testTierMidAtExactBoundary() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(50_000L);
        assertEquals("MID", result.tier());
    }

    @Test
    @DisplayName("determineTier: 49,999 followers is MICRO (just below MID)")
    void testTierMicroJustBelowMid() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(49_999L);
        assertEquals("MICRO", result.tier());
    }

    @Test
    @DisplayName("determineTier: 500,000 followers is MACRO (exact boundary)")
    void testTierMacroAtExactBoundary() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(500_000L);
        assertEquals("MACRO", result.tier());
    }

    @Test
    @DisplayName("determineTier: 499,999 followers is MID (just below MACRO)")
    void testTierMidJustBelowMacro() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(499_999L);
        assertEquals("MID", result.tier());
    }

    @Test
    @DisplayName("determineTier: 1,000,000 followers is MEGA (exact boundary)")
    void testTierMegaAtExactBoundary() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(1_000_000L);
        assertEquals("MEGA", result.tier());
    }

    @Test
    @DisplayName("determineTier: 999,999 followers is MACRO (just below MEGA)")
    void testTierMacroJustBelowMega() {
        RateEstimation result = estimateWithFollowersAndNeutralEverything(999_999L);
        assertEquals("MACRO", result.tier());
    }

    @Test
    @DisplayName("estimate: base range for MICRO tier with no multipliers applied is the raw spec range")
    void testBaseRangeForMicroTierWithNeutralMultipliers() {
        // 10,000 followers -> MICRO tier, base [5000, 25000]
        // engagementRate = 2 (neutral, no multiplier kicks in since 1 <= 2 <= 3)
        // categories empty -> neutral categoryMultiplier 1.0
        // quality = 50 -> neutral qualityMultiplier 1.0
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(BigDecimal.valueOf(5000), result.min());
        assertEquals(BigDecimal.valueOf(25000), result.max());
        assertEquals("MICRO", result.tier());
    }

    // === engagement multiplier thresholds ===

    @Test
    @DisplayName("estimate: engagement rate > 5 applies +30% multiplier")
    void testEngagementMultiplierHighRate() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("5.01"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(1.3, (double) result.factors().get("engagementMultiplier"));
        // base [5000, 25000] * 1.3
        assertEquals(BigDecimal.valueOf(6500), result.min());
        assertEquals(BigDecimal.valueOf(32500), result.max());
    }

    @Test
    @DisplayName("estimate: engagement rate exactly 5 does not qualify for the >5 tier (boundary exclusive)")
    void testEngagementMultiplierExactlyFiveIsNotHighTier() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("5"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        // 5 is not > 5, but is > 3 -> mid tier multiplier 1.15
        assertEquals(1.15, (double) result.factors().get("engagementMultiplier"));
    }

    @Test
    @DisplayName("estimate: engagement rate > 3 and <= 5 applies +15% multiplier")
    void testEngagementMultiplierMidRate() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("3.5"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(1.15, (double) result.factors().get("engagementMultiplier"));
    }

    @Test
    @DisplayName("estimate: engagement rate exactly 3 does not qualify for the >3 tier (boundary exclusive)")
    void testEngagementMultiplierExactlyThreeIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("3"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        // Not > 3 and not < 1 -> neutral 1.0
        assertEquals(1.0, (double) result.factors().get("engagementMultiplier"));
    }

    @Test
    @DisplayName("estimate: engagement rate < 1 applies -30% multiplier")
    void testEngagementMultiplierLowRate() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("0.99"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(0.7, (double) result.factors().get("engagementMultiplier"));
    }

    @Test
    @DisplayName("estimate: engagement rate exactly 1 does not qualify for the <1 tier (boundary exclusive)")
    void testEngagementMultiplierExactlyOneIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("1"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(1.0, (double) result.factors().get("engagementMultiplier"));
    }

    // === category multiplier ===

    @Test
    @DisplayName("estimate: empty categories list defaults to neutral 1.0 category multiplier")
    void testCategoryMultiplierEmptyListIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());

        assertEquals(1.0, (double) result.factors().get("categoryMultiplier"));
    }

    @Test
    @DisplayName("estimate: unknown category defaults to neutral 1.0 category multiplier")
    void testCategoryMultiplierUnknownCategoryIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result =
                service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of("UNDERWATER_BASKET_WEAVING"));

        assertEquals(1.0, (double) result.factors().get("categoryMultiplier"));
    }

    @Test
    @DisplayName("estimate: category matching is case-insensitive")
    void testCategoryMultiplierIsCaseInsensitive() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result =
                service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of("fashion"));

        assertEquals(1.3, (double) result.factors().get("categoryMultiplier"));
    }

    @Test
    @DisplayName("estimate: multiple categories use the maximum multiplier across all of them")
    void testCategoryMultiplierUsesMaxAcrossMultipleCategories() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        // GAMING (0.95) and FASHION (1.3) -> max is 1.3
        RateEstimation result =
                service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of("GAMING", "FASHION", "EDUCATION"));

        assertEquals(1.3, (double) result.factors().get("categoryMultiplier"));
    }

    @Test
    @DisplayName("estimate: lowest category multiplier (EDUCATION, 0.9) applies when it is the only category")
    void testCategoryMultiplierLowestCategory() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of("EDUCATION"));

        assertEquals(0.9, (double) result.factors().get("categoryMultiplier"));
    }

    // === quality score multiplier ===

    @Test
    @DisplayName("estimate: quality overall > 80 applies +20% multiplier")
    void testQualityMultiplierHighScore() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(80.01), List.of());

        assertEquals(1.2, (double) result.factors().get("qualityMultiplier"));
    }

    @Test
    @DisplayName("estimate: quality overall exactly 80 does not qualify for the >80 tier (boundary exclusive)")
    void testQualityMultiplierExactlyEightyIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(80), List.of());

        assertEquals(1.0, (double) result.factors().get("qualityMultiplier"));
    }

    @Test
    @DisplayName("estimate: quality overall < 40 applies -20% multiplier")
    void testQualityMultiplierLowScore() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(39.99), List.of());

        assertEquals(0.8, (double) result.factors().get("qualityMultiplier"));
    }

    @Test
    @DisplayName("estimate: quality overall exactly 40 does not qualify for the <40 tier (boundary exclusive)")
    void testQualityMultiplierExactlyFortyIsNeutral() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(40), List.of());

        assertEquals(1.0, (double) result.factors().get("qualityMultiplier"));
    }

    // === confidence ===

    @Test
    @DisplayName("estimate: confidence is 100 when dataSource is META_API and quality overall > 0")
    void testConfidenceFullWithMetaApiAndPositiveQuality() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "META_API");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(50), List.of());

        assertEquals(BigDecimal.valueOf(100.0), result.confidence());
    }

    @Test
    @DisplayName("estimate: confidence is 50 base when dataSource is not META_API and quality overall is 0")
    void testConfidenceBaseOnlyWithNonMetaApiAndZeroQuality() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(0), List.of());

        assertEquals(BigDecimal.valueOf(50.0), result.confidence());
    }

    @Test
    @DisplayName("estimate: confidence is 80 when dataSource is META_API but quality overall is 0")
    void testConfidenceMetaApiOnlyNoQualityBonus() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "META_API");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(0), List.of());

        assertEquals(BigDecimal.valueOf(80.0), result.confidence());
    }

    @Test
    @DisplayName("estimate: confidence is 70 when dataSource is not META_API but quality overall > 0")
    void testConfidenceQualityBonusOnlyNoMetaApi() {
        CreatorMetric metric = creatorMetric(10_000L, new BigDecimal("2"), "MANUAL");
        RateEstimation result = service.estimate(Optional.of(metric), qualityResult(50), List.of());

        assertEquals(BigDecimal.valueOf(70.0), result.confidence());
    }

    // --- fixtures ---

    private RateEstimation estimateWithFollowersAndNeutralEverything(long followers) {
        CreatorMetric metric = creatorMetric(followers, new BigDecimal("2"), "MANUAL");
        return service.estimate(Optional.of(metric), NEUTRAL_QUALITY, List.of());
    }

    private CreatorMetric creatorMetric(long followers, BigDecimal engagementRate, String dataSource) {
        return CreatorMetric.builder()
                .id("01HWMETRIC0000000000001")
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .followers(followers)
                .avgEngagementRate(engagementRate)
                .dataSource(dataSource)
                .time(Instant.now())
                .build();
    }

    private static QualityScoreResult qualityResult(double overall) {
        BigDecimal value = BigDecimal.valueOf(overall);
        return new QualityScoreResult(value, value, value, value, value);
    }
}
