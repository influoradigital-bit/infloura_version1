package com.influora.service.scoring;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Calculates overall creator quality based on engagement, consistency, and activity, over
 * already-fetched, already-authorized {@link CreatorMetric}/{@link MediaMetric} rows (Phase 2
 * storage layer). Score: 0 = poor quality, 100 = excellent.
 *
 * <p>Pure function over data the caller already fetched — no repository access, no I/O, no
 * workspace-isolation concern here. Per VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md &sect;4.2, callers
 * are responsible for having resolved an authorized {@code creatorProfileId} via {@code
 * MetricsAuthorizationService} before fetching the metrics passed into {@link #calculate}.
 *
 * <p>{@code audienceMatch} is explicitly a placeholder per the spec (neutral 50) — real audience
 * demographic matching against brand targets is out of scope for this iteration.
 *
 * <p>Adaptation from the spec's illustrative pseudocode: {@link MediaMetric#getLikes()}/{@link
 * MediaMetric#getComments()} are nullable ({@code Long}) on the real Phase 2 entity (the spec
 * pseudocode assumed non-null primitives). Nulls are treated as 0 when summing engagement.
 */
@Service
public class QualityScoreService {

    public record QualityScoreResult(
            BigDecimal overall, // 0-100 composite
            BigDecimal engagementScore, // 0-100
            BigDecimal consistency, // 0-100 (low std dev = high score)
            BigDecimal frequency, // 0-100 (based on posts/week)
            BigDecimal audienceMatch // 0-100 (placeholder for brand matching)
            ) {}

    public QualityScoreResult calculate(
            Optional<CreatorMetric> latestMetric, List<MediaMetric> recentMedia) {

        if (latestMetric.isEmpty()) {
            return new QualityScoreResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // === Engagement Score (40% weight) ===
        // Based on engagement rate percentile
        double engagementRate = calculateEngagementRate(latestMetric.get(), recentMedia);
        double engagementScore = scoreEngagementRate(engagementRate);

        // === Consistency Score (25% weight) ===
        // Low variance in engagement = more predictable = higher score
        double consistencyScore = calculateConsistencyScore(recentMedia);

        // === Posting Frequency Score (20% weight) ===
        // Optimal: 3-7 posts per week
        double frequencyScore = calculateFrequencyScore(recentMedia);

        // === Audience Match (15% weight) ===
        // Placeholder - would compare audience demographics to brand targets
        double audienceMatchScore = 50.0; // Default neutral

        // === Composite ===
        double overall =
                (engagementScore * 0.40)
                        + (consistencyScore * 0.25)
                        + (frequencyScore * 0.20)
                        + (audienceMatchScore * 0.15);

        return new QualityScoreResult(
                BigDecimal.valueOf(overall).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(engagementScore).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(consistencyScore).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(frequencyScore).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(audienceMatchScore).setScale(2, RoundingMode.HALF_UP));
    }

    private double calculateEngagementRate(CreatorMetric metric, List<MediaMetric> recentMedia) {
        if (recentMedia.isEmpty() || metric.getFollowers() == 0) return 0;

        double totalEngagement =
                recentMedia.stream()
                        .mapToLong(m -> nullToZero(m.getLikes()) + nullToZero(m.getComments()))
                        .average()
                        .orElse(0);

        return (totalEngagement / metric.getFollowers()) * 100;
    }

    private double scoreEngagementRate(double rate) {
        // Sigmoid curve: 0% -> 0, 2% -> 50, 5%+ -> 90+
        if (rate <= 0) return 0;
        if (rate >= 10) return 100;
        return 100 * (1 - Math.exp(-rate / 2.5));
    }

    private double calculateConsistencyScore(List<MediaMetric> media) {
        if (media.size() < 3) return 50; // Not enough data

        // Calculate coefficient of variation (CV) of engagement
        List<Long> engagements =
                media.stream().map(m -> nullToZero(m.getLikes()) + nullToZero(m.getComments())).toList();

        double mean = engagements.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean == 0) return 50;

        double variance =
                engagements.stream().mapToDouble(e -> Math.pow(e - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double cv = stdDev / mean;

        // Lower CV = more consistent = higher score
        // CV of 0.3 = 70 score, CV of 1.0 = 30 score
        return Math.max(0, Math.min(100, 100 - (cv * 70)));
    }

    private double calculateFrequencyScore(List<MediaMetric> media) {
        if (media.isEmpty()) return 0;

        // Calculate posts per week over last 30 days
        Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
        long recentPosts =
                media.stream()
                        .filter(m -> m.getPostedAt() != null && m.getPostedAt().isAfter(thirtyDaysAgo))
                        .count();

        double postsPerWeek = recentPosts / 4.0; // 30 days ~= 4 weeks

        // Optimal: 3-7 posts/week
        if (postsPerWeek >= 3 && postsPerWeek <= 7) return 100;
        if (postsPerWeek < 1) return 20;
        if (postsPerWeek < 3) return 40 + (postsPerWeek * 20);
        if (postsPerWeek > 14) return 50; // Spam territory
        return 100 - ((postsPerWeek - 7) * 7); // Gradual decrease above 7
    }

    private static long nullToZero(Long value) {
        return value != null ? value : 0L;
    }
}
