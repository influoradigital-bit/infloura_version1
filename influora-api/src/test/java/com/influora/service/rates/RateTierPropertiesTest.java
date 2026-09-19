package com.influora.service.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.domain.entity.CreatorMetric;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.h, B0-30). */
class RateTierPropertiesTest {

    /** Followers that land squarely inside each tier's band, per {@code CreatorTiers.derive}. */
    private static final Map<String, Long> FOLLOWERS_IN_TIER =
            Map.of(
                    "NANO", 3_000L,
                    "MICRO", 20_000L,
                    "MID", 100_000L,
                    "MACRO", 700_000L,
                    "MEGA", 2_000_000L);

    private final RateEstimationService rateEstimationService = new RateEstimationService();

    private static RateTierProperties unset() {
        return new RateTierProperties(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * <b>The drift gate.</b> {@code RateEstimationService.TIER_BASE_RATES} is
     * {@code private static final} and cannot be read from outside, so
     * {@link RateTierProperties#DEFAULT_TIER_BASE_RATES} restates the five pairs. This test is what
     * stops the two copies diverging: for a creator with a null engagement reading (multiplier
     * 1.0), no categories (1.0) and an absent quality score (1.0), {@code estimate()}'s min/max ARE
     * the tier base rates, so the two can be compared directly.
     *
     * <p>Note this needs {@code mvn clean compile} to falsify — javac inlines a changed constant
     * into already-compiled classes, so an incremental run can test the old value.
     */
    @Test
    @DisplayName("14.1.h - the compiled defaults still equal RateEstimationService's TIER_BASE_RATES")
    void defaultsHaveNotDriftedFromRateEstimationService() {
        RateTierProperties properties = unset();

        FOLLOWERS_IN_TIER.forEach(
                (tier, followers) -> {
                    RateEstimation neutral =
                            rateEstimationService.estimate(
                                    Optional.of(neutralMetric(followers)),
                                    QualityScoreResult.absent(),
                                    List.of());

                    assertThat(neutral.tier())
                            .as("fixture must actually land in %s", tier)
                            .isEqualTo(tier);

                    long[] band = properties.resolve(tier);
                    assertThat(BigDecimal.valueOf(band[0]))
                            .as("%s min drifted from RateEstimationService.TIER_BASE_RATES", tier)
                            .isEqualByComparingTo(neutral.min());
                    assertThat(BigDecimal.valueOf(band[1]))
                            .as("%s max drifted from RateEstimationService.TIER_BASE_RATES", tier)
                            .isEqualByComparingTo(neutral.max());
                });
    }

    @Test
    @DisplayName("14.1.h - with nothing set in yml, every tier resolves to its compiled default")
    void unsetFallsBackToConstants() {
        RateTierProperties properties = unset();
        for (String tier : RateTierProperties.DEFAULT_TIER_BASE_RATES.keySet()) {
            assertThat(properties.override(tier)).isEmpty();
            assertThat(properties.resolve(tier)).containsExactly(properties.compiledDefault(tier));
        }
    }

    @Test
    @DisplayName("14.1.h - a fully-set tier override wins over the compiled default")
    void overrideWins() {
        RateTierProperties properties =
                new RateTierProperties(1200L, 4800L, null, null, null, null, null, null, null, null);

        assertThat(properties.override("NANO")).isPresent();
        assertThat(properties.override("NANO").orElseThrow()).containsExactly(1200L, 4800L);
        assertThat(properties.resolve("NANO")).containsExactly(1200L, 4800L);
        // Untouched tiers keep the constants.
        assertThat(properties.resolve("MICRO")).containsExactly(5000L, 25000L);
    }

    /**
     * Both-or-neither. A half-set override pairs a calibrated bound with an uncalibrated one and
     * produces a band nobody chose — the failure mode B0-36 would hit by setting only a min.
     */
    @Test
    @DisplayName("14.1.h - a half-set, zero or inverted override is ignored entirely")
    void incoherentOverridesAreIgnored() {
        assertThat(
                        new RateTierProperties(1200L, null, null, null, null, null, null, null, null, null)
                                .resolve("NANO"))
                .containsExactly(1000L, 5000L);
        assertThat(
                        new RateTierProperties(null, 4800L, null, null, null, null, null, null, null, null)
                                .resolve("NANO"))
                .containsExactly(1000L, 5000L);
        assertThat(
                        new RateTierProperties(0L, 4800L, null, null, null, null, null, null, null, null)
                                .resolve("NANO"))
                .containsExactly(1000L, 5000L);
        // max < min
        assertThat(
                        new RateTierProperties(4800L, 1200L, null, null, null, null, null, null, null, null)
                                .resolve("NANO"))
                .containsExactly(1000L, 5000L);
    }

    /**
     * {@code "UNKNOWN"} is what {@code RateEstimationService.estimate()} returns when there is no
     * metric row. It is not a tier and must not resolve to one.
     */
    @Test
    @DisplayName("14.1.h - UNKNOWN and nonsense tiers resolve to null, never to a band")
    void unknownTierResolvesToNull() {
        RateTierProperties properties = unset();
        assertThat(properties.resolve("UNKNOWN")).isNull();
        assertThat(properties.resolve(null)).isNull();
        assertThat(properties.resolve("BRONZE")).isNull();
        assertThat(properties.override("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("14.1.h - MEGA is a first-class tier here, even though it is not a CreatorTier constant")
    void megaIsPresent() {
        assertThat(unset().resolve("MEGA")).containsExactly(500_000L, 2_500_000L);
    }

    @Test
    @DisplayName("14.1.h - the public default map cannot be mutated through a resolved band")
    void resolvedBandIsACopy() {
        RateTierProperties properties = unset();
        long[] band = properties.resolve("NANO");
        band[0] = 1L;
        assertThat(properties.resolve("NANO")).containsExactly(1000L, 5000L);
    }

    /** Null engagement reads as "unknown", not "low" (F-0749) — so every multiplier is 1.0. */
    private static CreatorMetric neutralMetric(long followers) {
        return CreatorMetric.builder()
                .id("01HWMETRICTIERBASELINE1")
                .creatorProfileId("01HWPROFILETIERBASELINE")
                .platform("INSTAGRAM")
                .followers(followers)
                .avgEngagementRate(null)
                .dataSource("META_API")
                .time(Instant.now())
                .build();
    }
}
