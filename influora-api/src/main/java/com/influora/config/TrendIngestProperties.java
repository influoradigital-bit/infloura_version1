package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the TrendSpark trend-pull data sources (NewsAPI / TMDB / YouTube Data API) that feed
 * the T4 ingest job. Deliberately a separate class from {@link TrendSparkProperties} rather than an
 * extension of it: {@code TrendSparkProperties} has no yaml block today and its javadoc explicitly
 * disclaims secret-handling discipline, while these are plain third-party API keys that need the
 * same blank-default, non-placeholder convention as {@link RazorpayProperties} and {@link
 * MetaApiProperties}.
 *
 * <p>Modeled on {@link RazorpayProperties} (plain String key fields, blank {@code ""} defaults, an
 * {@code isConfigured()} helper) and {@link CreatorCopilotProperties} (boolean {@code enabled} +
 * cron-string field). All three keys are read-only third-party data-source credentials for an
 * optional, feature-flagged batch job — not signing/encryption secrets or a core always-relied-on
 * payment path — so unlike Razorpay's fields, {@code SecretsStartupValidator} deliberately does not
 * check these; the ingest job is expected to skip a source cleanly via {@link #isConfigured()} when
 * its key is absent, exactly like Meta OAuth silently stays off when {@code META_APP_ID}/{@code
 * META_APP_SECRET} are blank.
 */
@ConfigurationProperties(prefix = "influora.trend-ingest")
public class TrendIngestProperties {

    /** Off by default — same conservative-default convention as {@code
     * CreatorCopilotProperties.enabled} and {@code influora.cleanup.dry-run}. */
    private boolean enabled = false;

    private String newsapiApiKey = "";
    private String tmdbApiKey = "";
    private String youtubeApiKey = "";

    /** Cron for the scheduled pull, overridable via {@code TREND_INGEST_PULL_CRON} — see the
     * {@code pull-cron} key in application.yml. Defaults to 05:00 daily. */
    private String pullCron = "0 0 5 * * *";

    /** True when AT LEAST ONE source key is present — the ingest job can still do useful work with
     * one source, and the n8n workflow this replaces explicitly tolerated a dead source ("on fail
     * continues so one dead source never sinks the run"). An all-three-required gate here would
     * silently disable the whole pull for the most likely first configuration, a NewsAPI key alone,
     * which is the one source the creator-facing news requirement actually needs.
     *
     * <p>Per-source skipping is {@link #hasNewsapiKey()}/{@link #hasTmdbKey()}/{@link
     * #hasYoutubeKey()} — the ingest job MUST branch on those, not on this method, before firing a
     * request, so it never sends a blank key. */
    public boolean isConfigured() {
        return hasNewsapiKey() || hasTmdbKey() || hasYoutubeKey();
    }

    /** NewsAPI key present — check this before issuing a NewsAPI request. */
    public boolean hasNewsapiKey() {
        return newsapiApiKey != null && !newsapiApiKey.isBlank();
    }

    /** TMDB key present — check this before issuing a TMDB request. */
    public boolean hasTmdbKey() {
        return tmdbApiKey != null && !tmdbApiKey.isBlank();
    }

    /** YouTube Data API key present — check this before issuing a YouTube request. */
    public boolean hasYoutubeKey() {
        return youtubeApiKey != null && !youtubeApiKey.isBlank();
    }

    /** All three sources configured — reporting/diagnostics only, never a run gate. */
    public boolean isFullyConfigured() {
        return newsapiApiKey != null && !newsapiApiKey.isBlank()
                && tmdbApiKey != null && !tmdbApiKey.isBlank()
                && youtubeApiKey != null && !youtubeApiKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNewsapiApiKey() {
        return newsapiApiKey;
    }

    public void setNewsapiApiKey(String newsapiApiKey) {
        this.newsapiApiKey = newsapiApiKey == null ? "" : newsapiApiKey;
    }

    public String getTmdbApiKey() {
        return tmdbApiKey;
    }

    public void setTmdbApiKey(String tmdbApiKey) {
        this.tmdbApiKey = tmdbApiKey == null ? "" : tmdbApiKey;
    }

    public String getYoutubeApiKey() {
        return youtubeApiKey;
    }

    public void setYoutubeApiKey(String youtubeApiKey) {
        this.youtubeApiKey = youtubeApiKey == null ? "" : youtubeApiKey;
    }

    public String getPullCron() {
        return pullCron;
    }

    public void setPullCron(String pullCron) {
        this.pullCron = (pullCron == null || pullCron.isBlank()) ? "0 0 5 * * *" : pullCron;
    }
}
