package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag + per-run blast-radius cap for wiring {@code BrandSafetyScoreService} into {@code
 * ScoreCalculationJob} (the brand-safety leg of Wave C task C3).
 *
 * <p><b>Why this is its own flag, defaulting OFF (Priya ruling).</b> {@code ScoreCalculationJob}'s
 * original scope-cut note deferred brand-safety wiring because the influora-ai (Python) service it
 * fans out to "does not exist yet". That dependency now exists ({@code BrandSafetyAiClient} /
 * {@code BrandSafetyScoreService}, both registered), but the residual concern the deferral was
 * really protecting against remains: scoring a creator fans out to influora-ai's LLM classifier,
 * chunked at {@code BrandSafetyScoreService.CHUNK_SIZE} items per HTTP call, so running it for
 * <i>every</i> discoverable creator on every daily run is an unbounded, real-money LLM cost. This
 * flag keeps that cost strictly opt-in:
 *
 * <ul>
 *   <li>{@code enabled=false} by default — the job's behaviour is byte-for-byte unchanged (the 3
 *       brand-safety columns stay {@code null}) until someone deliberately turns it on;
 *   <li>{@code maxCreatorsPerRun} caps how many creators incur the AI fan-out on any single run
 *       even once enabled, so the cost per run is bounded and predictable. {@code
 *       ScoreCalculationJob} prioritises creators whose latest brand-safety score is missing or
 *       stalest, so successive runs rotate coverage across the discoverable pool instead of
 *       re-scoring the same head every day.
 * </ul>
 *
 * <p>Kept deliberately separate from {@link BrandSafetyAiProperties} (which holds the influora-ai
 * connection/timeout/batch-size config) — same separation-of-concerns convention used throughout
 * this codebase (connection config vs. behaviour/credential config live in distinct
 * {@code @ConfigurationProperties} classes).
 */
@ConfigurationProperties(prefix = "influora.brand-safety-scoring")
public class BrandSafetyScoringProperties {

    /**
     * Master switch. {@code false} (default) means {@code ScoreCalculationJob} never calls
     * {@code BrandSafetyScoreService} and leaves {@code brand_safety_score}/{@code garm_flags}/
     * {@code content_sentiment} {@code null} on every row it writes — the pre-existing behaviour.
     */
    private boolean enabled = false;

    /**
     * Upper bound on how many creators get brand-safety scored (i.e. incur the influora-ai LLM
     * fan-out) per {@code ScoreCalculationJob} run, once {@link #enabled} is {@code true}. A value
     * {@code <= 0} disables brand-safety scoring for the run entirely (treated the same as
     * {@code enabled=false}) rather than meaning "unlimited" — unbounded is exactly the cost hazard
     * this cap exists to prevent.
     */
    private int maxCreatorsPerRun = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCreatorsPerRun() {
        return maxCreatorsPerRun;
    }

    public void setMaxCreatorsPerRun(int maxCreatorsPerRun) {
        this.maxCreatorsPerRun = maxCreatorsPerRun;
    }
}
