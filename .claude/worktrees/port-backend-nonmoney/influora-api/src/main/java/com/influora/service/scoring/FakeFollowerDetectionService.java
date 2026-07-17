package com.influora.service.scoring;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Detects likely fake/bot followers using statistical anomaly detection over already-fetched,
 * already-authorized {@link CreatorMetric}/{@link MediaMetric} rows (Phase 2 storage layer).
 * Scoring: 0 = definitely real, 100 = definitely fake.
 *
 * <p>Pure function over data the caller already fetched — no repository access, no I/O, no
 * workspace-isolation concern here. Per VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md &sect;4.1, callers
 * are responsible for having resolved an authorized {@code creatorProfileId} via {@code
 * MetricsAuthorizationService} before fetching the metrics passed into {@link #analyze}.
 *
 * <p>Implements 4 of the spec's 5 signals: engagement-rate anomaly, follower-growth spike,
 * follower/following ratio, and posting consistency. Signal 4 ("comment quality") is explicitly a
 * placeholder in the spec pending NLP integration and is deliberately not implemented here.
 *
 * <p>Adaptation from the spec's illustrative pseudocode: {@link CreatorMetric#getFollowing()} and
 * {@link MediaMetric#getLikes()}/{@link MediaMetric#getComments()} are nullable ({@code Long}) on
 * the real Phase 2 entities (the spec pseudocode assumed non-null primitives). Nulls are treated
 * as 0 for these signals — a creator metric row with no following/likes/comments data simply
 * contributes nothing to that particular signal rather than throwing.
 */
@Service
public class FakeFollowerDetectionService {

    public record FakeFollowerResult(
            BigDecimal score, // 0-100
            List<String> reasons, // Explanations
            Map<String, Object> debug // Raw signal values
            ) {}

    /** Analyzes a creator's metrics for fake follower indicators. */
    public FakeFollowerResult analyze(
            Optional<CreatorMetric> latestMetric,
            List<MediaMetric> recentMedia,
            List<CreatorMetric> historicalMetrics) {

        List<String> reasons = new ArrayList<>();
        double score = 0.0;
        Map<String, Object> debug = new HashMap<>();

        if (latestMetric.isEmpty()) {
            return new FakeFollowerResult(BigDecimal.ZERO, List.of("No metrics available"), Map.of());
        }

        CreatorMetric metric = latestMetric.get();

        // === Signal 1: Engagement Rate Anomaly ===
        // Typical healthy rate: 1-5%. Below 0.5% or above 15% is suspicious.
        double engagementRate = calculateEngagementRate(metric, recentMedia);
        debug.put("engagementRate", engagementRate);

        if (engagementRate < 0.5) {
            score += 25;
            reasons.add("Extremely low engagement rate (" + String.format("%.2f%%", engagementRate) + ")");
        } else if (engagementRate > 15 && metric.getFollowers() > 10000) {
            score += 15;
            reasons.add("Suspiciously high engagement rate for follower count");
        }

        // === Signal 2: Follower Growth Spike ===
        // Sudden jumps (>20% in a day) suggest purchased followers
        if (historicalMetrics.size() >= 7) {
            double maxDailyGrowth = calculateMaxDailyGrowthRate(historicalMetrics);
            debug.put("maxDailyGrowthPct", maxDailyGrowth);

            if (maxDailyGrowth > 20) {
                score += 30;
                reasons.add(
                        "Sudden follower spike detected ("
                                + String.format("%.1f%%", maxDailyGrowth)
                                + " in one day)");
            } else if (maxDailyGrowth > 10) {
                score += 15;
                reasons.add("Unusual follower growth pattern");
            }
        }

        // === Signal 3: Follower-to-Following Ratio ===
        // Healthy creators: followers >> following. Bots often follow many to get followbacks.
        long following = metric.getFollowing() != null ? metric.getFollowing() : 0L;
        double ffRatio = following > 0 ? (double) metric.getFollowers() / following : metric.getFollowers();
        debug.put("followerFollowingRatio", ffRatio);

        if (ffRatio < 0.5 && metric.getFollowers() > 1000) {
            score += 20;
            reasons.add("Low follower-to-following ratio (" + String.format("%.2f", ffRatio) + ")");
        }

        // === Signal 4: Comment Quality (if available) ===
        // Generic comments ("Nice!", emoji-only) suggest bots.
        // This requires NLP integration - placeholder, deliberately not implemented.

        // === Signal 5: Posting Consistency ===
        // Real influencers post regularly. Accounts that post rarely but have high followers =
        // suspicious
        if (recentMedia.size() < 3 && metric.getFollowers() > 50000) {
            score += 15;
            reasons.add("Very few recent posts for a large account");
        }

        // Cap at 100
        score = Math.min(score, 100);

        return new FakeFollowerResult(
                BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP), reasons, debug);
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

    private double calculateMaxDailyGrowthRate(List<CreatorMetric> metrics) {
        // Sort by time
        List<CreatorMetric> sorted = new ArrayList<>(metrics);
        sorted.sort(Comparator.comparing(CreatorMetric::getTime));

        double maxGrowth = 0;
        for (int i = 1; i < sorted.size(); i++) {
            long prev = sorted.get(i - 1).getFollowers();
            long curr = sorted.get(i).getFollowers();
            if (prev > 0) {
                double growth = ((double) (curr - prev) / prev) * 100;
                maxGrowth = Math.max(maxGrowth, growth);
            }
        }
        return maxGrowth;
    }

    private static long nullToZero(Long value) {
        return value != null ? value : 0L;
    }
}
