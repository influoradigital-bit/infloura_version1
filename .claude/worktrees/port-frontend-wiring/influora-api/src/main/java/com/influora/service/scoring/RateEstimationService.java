package com.influora.service.scoring;

import com.influora.domain.entity.CreatorMetric;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Estimates a fair market rate-per-post range (INR) for a creator based on follower tier,
 * engagement rate, content category, and overall quality score, over already-fetched,
 * already-authorized {@link CreatorMetric} rows (Phase 2 storage layer) and an already-computed
 * {@link QualityScoreResult} (Phase 3, {@link QualityScoreService}).
 *
 * <p>Pure function over data the caller already fetched — no repository access, no I/O, no
 * workspace-isolation concern here. Per VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md &sect;4.4, callers
 * are responsible for having resolved an authorized {@code creatorProfileId} via {@code
 * MetricsAuthorizationService} before fetching the metrics passed into {@link #estimate}, and for
 * having already run {@link QualityScoreService#calculate} to produce the {@code qualityScore}
 * argument.
 *
 * <p>No adaptation from the spec's illustrative pseudocode was required here: {@link
 * CreatorMetric#getFollowers()} (primitive {@code long}), {@link
 * CreatorMetric#getAvgEngagementRate()} ({@code BigDecimal}), and {@link
 * CreatorMetric#getDataSource()} ({@code String}) all match the spec's assumptions exactly, and
 * {@link QualityScoreResult#overall()} is a {@code BigDecimal} as used in the spec's {@code
 * qualityScore.overall().doubleValue()} calls.
 */
@Service
public class RateEstimationService {

    // Base rates per post by follower tier (INR)
    private static final Map<String, long[]> TIER_BASE_RATES =
            Map.of(
                    "NANO", new long[] {1000, 5000}, // 1K-10K followers
                    "MICRO", new long[] {5000, 25000}, // 10K-50K followers
                    "MID", new long[] {25000, 100000}, // 50K-500K followers
                    "MACRO", new long[] {100000, 500000}, // 500K-1M followers
                    "MEGA", new long[] {500000, 2500000} // 1M+ followers
                    );

    // Category multipliers
    private static final Map<String, Double> CATEGORY_MULTIPLIERS =
            Map.of(
                    "FASHION", 1.3,
                    "BEAUTY", 1.25,
                    "LIFESTYLE", 1.2,
                    "TRAVEL", 1.15,
                    "FOOD", 1.1,
                    "TECH", 1.05,
                    "FITNESS", 1.0,
                    "GAMING", 0.95,
                    "EDUCATION", 0.9);

    public record RateEstimation(
            BigDecimal min,
            BigDecimal max,
            String currency,
            BigDecimal confidence, // 0-100
            String tier,
            Map<String, Object> factors) {}

    /**
     * Estimates a fair-market rate range for a creator.
     *
     * @param metric latest creator metrics snapshot, or empty if none is available
     * @param qualityScore the creator's already-computed {@link QualityScoreResult}
     * @param categories the creator's content categories (case-insensitive); the highest-multiplier
     *     match across all provided categories is used. Unknown or empty categories default to a
     *     neutral 1.0 multiplier.
     */
    public RateEstimation estimate(
            Optional<CreatorMetric> metric, QualityScoreResult qualityScore, List<String> categories) {

        if (metric.isEmpty()) {
            return new RateEstimation(
                    BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", Map.of());
        }

        long followers = metric.get().getFollowers();
        String tier = determineTier(followers);
        long[] baseRange = TIER_BASE_RATES.get(tier);

        // Start with base range
        double minRate = baseRange[0];
        double maxRate = baseRange[1];

        // === Engagement multiplier ===
        // High engagement (>5%) = +30%, Low (<1%) = -30%
        double engagementRate = metric.get().getAvgEngagementRate().doubleValue();
        double engagementMultiplier = 1.0;
        if (engagementRate > 5) engagementMultiplier = 1.3;
        else if (engagementRate > 3) engagementMultiplier = 1.15;
        else if (engagementRate < 1) engagementMultiplier = 0.7;

        minRate *= engagementMultiplier;
        maxRate *= engagementMultiplier;

        // === Category multiplier ===
        double categoryMultiplier =
                categories.stream()
                        .map(c -> CATEGORY_MULTIPLIERS.getOrDefault(c.toUpperCase(), 1.0))
                        .max(Double::compare)
                        .orElse(1.0);

        minRate *= categoryMultiplier;
        maxRate *= categoryMultiplier;

        // === Quality score adjustment ===
        // Quality > 80 = +20%, Quality < 40 = -20%
        double qualityMultiplier = 1.0;
        if (qualityScore.overall().doubleValue() > 80) qualityMultiplier = 1.2;
        else if (qualityScore.overall().doubleValue() < 40) qualityMultiplier = 0.8;

        minRate *= qualityMultiplier;
        maxRate *= qualityMultiplier;

        // === Confidence ===
        // Based on data completeness
        double confidence = 50.0;
        if (metric.get().getDataSource().equals("META_API")) confidence += 30;
        if (qualityScore.overall().doubleValue() > 0) confidence += 20;
        confidence = Math.min(100, confidence);

        Map<String, Object> factors =
                Map.of(
                        "tier", tier,
                        "engagementMultiplier", engagementMultiplier,
                        "categoryMultiplier", categoryMultiplier,
                        "qualityMultiplier", qualityMultiplier);

        return new RateEstimation(
                BigDecimal.valueOf(Math.round(minRate)),
                BigDecimal.valueOf(Math.round(maxRate)),
                "INR",
                BigDecimal.valueOf(confidence),
                tier,
                factors);
    }

    private String determineTier(long followers) {
        if (followers >= 1_000_000) return "MEGA";
        if (followers >= 500_000) return "MACRO";
        if (followers >= 50_000) return "MID";
        if (followers >= 10_000) return "MICRO";
        return "NANO";
    }
}
