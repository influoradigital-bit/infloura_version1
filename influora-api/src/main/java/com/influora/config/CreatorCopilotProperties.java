package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret rule config for the Creator AI Co-pilot (be-services-plan.md §2.5) — mirrors {@link
 * TrendSparkProperties}'s shape and defensive floor-on-bad-value convention exactly.
 *
 * <p><b>Field naming (Priya R2 ruling, item 3):</b> the field is {@code
 * maxSuggestionsPerCreatorPerDay}, not {@code dailyCap} — Spring relaxed binding maps this to the
 * yaml/env key {@code max-suggestions-per-creator-per-day} (kebab-case of the field name)
 * automatically, so the {@code CREATOR_COPILOT_DAILY_CAP} env var stays as originally drafted; only
 * the Java field name changed to be self-documenting. This field documents intent only — the DB
 * unique constraint on {@code creator_nudge_log(creator_profile_id, shown_day)}
 * (V20260721140000) is what actually enforces the cap.
 *
 * <p>{@code model} (AI-service config) and {@code theme-tag-batch-cron}/{@code
 * caption-sync-cron} (bound directly as {@code @Scheduled} cron placeholders, not fields here)
 * deliberately do NOT live on this class — see {@code CreatorThemeTaggingJob}/{@code
 * CreatorCaptionSyncJob}. */
@ConfigurationProperties(prefix = "influora.creator-copilot")
public class CreatorCopilotProperties {

    /** Off by default — gates whether {@code CreatorThemeTaggingJob}'s scheduled body actually
     * runs, same pattern as {@code BrandSafetyScoringProperties.isEnabled()}. Also gates {@code
     * CreatorCaptionSyncJob}, which shares this flag rather than getting its own. */
    private boolean enabled = false;

    /** Minimum theme-overlap score to surface a suggestion at all. Below this, the co-pilot stays
     * silent ({@code no_suggestion_today}) — correct, not an error. */
    private int scoreThreshold = 2;

    /** Documents the intent; the DB generated-column + unique-key constraint on {@code
     * creator_nudge_log} is what actually enforces the per-creator/day cap. */
    private int maxSuggestionsPerCreatorPerDay = 1;

    private String promptVersion = "creator-copilot-v1";

    /** Recent-media page size {@code CreatorCaptionSyncJob} requests per creator per run — mirrors
     * {@code InstagramMetricsFetcher.DEFAULT_MEDIA_LIMIT}'s default of 25, capped at Meta's 100. */
    private int captionSyncMediaLimit = 25;

    /** Optional cap on how many creators {@code CreatorCaptionSyncJob} processes in a single run
     * (0 = unlimited) — a safety valve if the connected-creator count grows large enough that one
     * nightly run risks running long or burning too much of the Meta rate-limit budget. */
    private int captionSyncMaxCreatorsPerRun = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(int scoreThreshold) {
        this.scoreThreshold = scoreThreshold <= 0 ? 2 : scoreThreshold;
    }

    public int getMaxSuggestionsPerCreatorPerDay() {
        return maxSuggestionsPerCreatorPerDay;
    }

    public void setMaxSuggestionsPerCreatorPerDay(int maxSuggestionsPerCreatorPerDay) {
        this.maxSuggestionsPerCreatorPerDay =
                maxSuggestionsPerCreatorPerDay <= 0 ? 1 : maxSuggestionsPerCreatorPerDay;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion =
                (promptVersion == null || promptVersion.isBlank()) ? "creator-copilot-v1" : promptVersion;
    }

    public int getCaptionSyncMediaLimit() {
        return captionSyncMediaLimit;
    }

    public void setCaptionSyncMediaLimit(int captionSyncMediaLimit) {
        this.captionSyncMediaLimit = captionSyncMediaLimit <= 0 ? 25 : captionSyncMediaLimit;
    }

    public int getCaptionSyncMaxCreatorsPerRun() {
        return captionSyncMaxCreatorsPerRun;
    }

    public void setCaptionSyncMaxCreatorsPerRun(int captionSyncMaxCreatorsPerRun) {
        this.captionSyncMaxCreatorsPerRun = Math.max(captionSyncMaxCreatorsPerRun, 0);
    }
}
