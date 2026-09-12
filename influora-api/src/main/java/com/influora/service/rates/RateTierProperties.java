package com.influora.service.rates;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.h, B0-30) — admin-overridable per-tier base rate
 * bands, with the compiled constants as the fallback.
 *
 * <p><b>What this is for.</b> Every cold-start quote is currently a formula over
 * {@code RateEstimationService.TIER_BASE_RATES}, constants written for the brand-side estimate
 * card and never checked against a real close. &sect;14.1.h's day-3 task is to run the calibration
 * report against the production replica and, for any tier with {@code realised_n >= 20}, set that
 * tier's yml override to {@code realised_median x 0.6 / x 1.4} — a data-backed band replacing a
 * guess, without a deploy. Tiers below 20 stay on the constants and keep the honest
 * "benchmark, not market data" label.
 *
 * <p><b>Why the defaults are duplicated here, and what stops them drifting.</b>
 * {@code RateEstimationService.TIER_BASE_RATES} is {@code private static final} — it cannot be
 * read from outside, and SPEC.md &sect;4.3 1c explicitly says not to try. So the five pairs are
 * restated as this component's placeholder defaults. That duplication is a real drift risk, and
 * {@code RateTierPropertiesTest} is the gate: for each tier it runs
 * {@code RateEstimationService.estimate()} over a creator with a neutral engagement, no category
 * and an absent quality score — a creator for whom every multiplier is 1.0, so the returned
 * min/max ARE the tier base rates — and asserts they equal this class's defaults. If either side
 * moves, that test goes red.
 *
 * <p><b>An override replaces the BASE, not the estimate.</b> {@code estimate()} applies engagement,
 * category and quality multipliers on top of the base range; substituting an override for its
 * OUTPUT would silently discard all three signals. {@code RateQuoteService} therefore reads the
 * three multipliers back out of {@code RateEstimation.factors()} and re-applies them to the
 * override — see {@code RateQuoteService#benchmarkUnit}.
 *
 * <p>Plain {@code @Value}-injected {@code @Component}, not {@code @ConfigurationProperties} —
 * same reasoning as {@link RateAddOns} and
 * {@link com.influora.config.MeeraCreatorFeatureProperties}.
 */
@Component
public class RateTierProperties {

    /**
     * Mirrors {@code RateEstimationService.TIER_BASE_RATES} (INR, per post). Keys are
     * {@code CreatorTiers} strings; {@code "MEGA"} is one of them and is NOT a {@code CreatorTier}
     * enum constant.
     */
    public static final Map<String, long[]> DEFAULT_TIER_BASE_RATES =
            Map.of(
                    "NANO", new long[] {1000, 5000},
                    "MICRO", new long[] {5000, 25000},
                    "MID", new long[] {25000, 100000},
                    "MACRO", new long[] {100000, 500000},
                    "MEGA", new long[] {500000, 2500000});

    private final Map<String, Long> overrideMin;
    private final Map<String, Long> overrideMax;

    /**
     * {@code #{null}} is the placeholder default on purpose: an UNSET key must be distinguishable
     * from a zero. A blank string default would fail to bind to {@code Long}, and a {@code 0}
     * default would read as "this tier is worth nothing" the moment someone set only the max.
     */
    public RateTierProperties(
            @Value("${influora.rates.tier.nano.min:#{null}}") Long nanoMin,
            @Value("${influora.rates.tier.nano.max:#{null}}") Long nanoMax,
            @Value("${influora.rates.tier.micro.min:#{null}}") Long microMin,
            @Value("${influora.rates.tier.micro.max:#{null}}") Long microMax,
            @Value("${influora.rates.tier.mid.min:#{null}}") Long midMin,
            @Value("${influora.rates.tier.mid.max:#{null}}") Long midMax,
            @Value("${influora.rates.tier.macro.min:#{null}}") Long macroMin,
            @Value("${influora.rates.tier.macro.max:#{null}}") Long macroMax,
            @Value("${influora.rates.tier.mega.min:#{null}}") Long megaMin,
            @Value("${influora.rates.tier.mega.max:#{null}}") Long megaMax) {
        this.overrideMin = mapOfNullable(nanoMin, microMin, midMin, macroMin, megaMin);
        this.overrideMax = mapOfNullable(nanoMax, microMax, midMax, macroMax, megaMax);
    }

    /**
     * The band to price this tier from: the yml override when BOTH bounds are set and coherent,
     * else the compiled constant.
     *
     * <p>Both-or-neither is deliberate. A half-set override (min from yml, max from the constant)
     * is how a calibrated 90th-percentile min ends up paired with an uncalibrated max and produces
     * a range nobody chose. A partial or inverted override is ignored entirely and the tier keeps
     * its compiled band.
     *
     * @return {@code null} for an unknown tier — including {@code "UNKNOWN"}, which
     *     {@code RateEstimationService.estimate()} returns when there is no metric row
     */
    public long[] resolve(String tier) {
        String key = normalise(tier);
        if (key == null) {
            return null;
        }
        return override(key).orElseGet(() -> copy(DEFAULT_TIER_BASE_RATES.get(key)));
    }

    /** The yml override alone, empty when this tier is not (coherently) overridden. */
    public Optional<long[]> override(String tier) {
        String key = normalise(tier);
        if (key == null) {
            return Optional.empty();
        }
        Long min = overrideMin.get(key);
        Long max = overrideMax.get(key);
        if (min == null || max == null || min <= 0 || max < min) {
            return Optional.empty();
        }
        return Optional.of(new long[] {min, max});
    }

    /** The compiled constant alone, ignoring any override. */
    public long[] compiledDefault(String tier) {
        String key = normalise(tier);
        return key == null ? null : copy(DEFAULT_TIER_BASE_RATES.get(key));
    }

    /** Defensive copy — {@link #DEFAULT_TIER_BASE_RATES} is public and its values are mutable arrays. */
    private static long[] copy(long[] band) {
        return band == null ? null : new long[] {band[0], band[1]};
    }

    private static String normalise(String tier) {
        if (tier == null) {
            return null;
        }
        String key = tier.trim().toUpperCase(Locale.ROOT);
        return DEFAULT_TIER_BASE_RATES.containsKey(key) ? key : null;
    }

    private static Map<String, Long> mapOfNullable(
            Long nano, Long micro, Long mid, Long macro, Long mega) {
        Map<String, Long> map = new java.util.HashMap<>();
        map.put("NANO", nano);
        map.put("MICRO", micro);
        map.put("MID", mid);
        map.put("MACRO", macro);
        map.put("MEGA", mega);
        return java.util.Collections.unmodifiableMap(map);
    }
}
