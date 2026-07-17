package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret rule config for Trend-Spark AI (T4) — schema lock §2/§3. Config values, not
 * secrets, so no signing-secret discipline needed here (contrast {@link
 * BrandSafetyServiceTokenProperties}). */
@ConfigurationProperties(prefix = "influora.trendspark")
public class TrendSparkProperties {

    /** Minimum theme-overlap score to trigger a nudge at all (schema lock §2). Below this,
     * Trend-Spark stays silent — correct, not an error. */
    private int scoreThreshold = 2;

    /** Days since {@code brand_profiles.last_posted_at} before we consider the brand's own
     * content stale (schema lock §3). */
    private int gapDays = 4;

    public int getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(int scoreThreshold) {
        this.scoreThreshold = scoreThreshold <= 0 ? 2 : scoreThreshold;
    }

    public int getGapDays() {
        return gapDays;
    }

    public void setGapDays(int gapDays) {
        this.gapDays = gapDays <= 0 ? 4 : gapDays;
    }
}
