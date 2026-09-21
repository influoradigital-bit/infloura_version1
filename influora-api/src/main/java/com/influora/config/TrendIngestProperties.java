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

    // T-GOLIVE-0918 [vikram · 2026-09-18] — L10 trend-pull job knobs. Source:
    // .proof-os/tasks/T-COPILOT-ON-0910/job-design.md steps 1/13. Added here (not a new class)
    // because these are tuning knobs on the same feature-flagged batch job, not a second
    // "connection config vs secret" split — only classifierWorkspaceId below is secret-adjacent
    // and is documented as such.

    /** Hard cap on rows written in a single run, applied AFTER within-run dedup. Never a priority
     * sort (no source row carries a priority field) — first-N in fetch order. */
    private int maxRowsPerRun = 100;

    /** Region tag stamped on every row this run pulls (schema: {@code trends.region}). All three
     * sources are India-scoped today (TMDB region=IN, NewsAPI country=in, YouTube regionCode=IN). */
    private String region = "IN";

    /**
     * Workspace whose AI-credit balance pays for the {@code POST /internal/brand-safety} GARM
     * classification calls this job makes (wiki/decisions/2026-09-18-trend-headline-screening.md
     * step 2). Blank by default so the job FAILS CLOSED — with no workspace configured, every
     * candidate trend is dropped rather than classified, matching the ruling's "fail closed"
     * requirement rather than guessing a workspace to bill.
     *
     * <p><b>Not decided by this lane:</b> {@code BrandSafetyAiClient#classify} is a per-workspace,
     * credit-metered call (influora-ai debits {@code cost_usd} against exactly the {@code
     * workspace_id} in the request — {@code app/routes/brand_safety.py}); there is no
     * platform/system workspace concept anywhere in this codebase (verified: no {@code
     * SYSTEM_WORKSPACE}/{@code PLATFORM_WORKSPACE} constant exists). Which real workspace's AI
     * credits should fund platform-wide trend screening — or whether a dedicated
     * internal/zero-cost workspace should be created for it — is a billing/business decision this
     * brief does not make, so it is left as an explicit, off-by-default config value rather than
     * guessed. Escalated to Arjun; see this lane's final report. */
    private String classifierWorkspaceId = "";

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

    public int getMaxRowsPerRun() {
        return maxRowsPerRun;
    }

    /** Floors to the default on a non-positive value — same convention as every other int setter
     * in this codebase's {@code @ConfigurationProperties} classes (e.g. {@code
     * CreatorCopilotProperties}). */
    public void setMaxRowsPerRun(int maxRowsPerRun) {
        this.maxRowsPerRun = maxRowsPerRun <= 0 ? 100 : maxRowsPerRun;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = (region == null || region.isBlank()) ? "IN" : region;
    }

    public String getClassifierWorkspaceId() {
        return classifierWorkspaceId;
    }

    public void setClassifierWorkspaceId(String classifierWorkspaceId) {
        this.classifierWorkspaceId = classifierWorkspaceId == null ? "" : classifierWorkspaceId;
    }

    /** Classification is configured only when a workspace to bill is set — checked before every
     * {@code BrandSafetyAiClient#classify} call so the job never sends a blank workspace id. */
    public boolean hasClassifierWorkspaceId() {
        return classifierWorkspaceId != null && !classifierWorkspaceId.isBlank();
    }

    /**
     * T-TSOFF-0920 — the ONE server-authoritative answer to "can a trend row ever exist?", and the
     * only thing {@code /config/public}'s {@code trendsEnabled} and {@link
     * com.influora.config.TrendFeatureGate} are allowed to be derived from.
     *
     * <p>Deliberately NOT a new independent flag. A separate {@code influora.trendspark.enabled}
     * would let an operator switch the user-facing surfaces on while ingest stayed off, which is
     * exactly the dishonest state this exists to prevent. Instead it re-states, in one place, the
     * three conditions {@link com.influora.job.TrendPullJob#pullTrends()} already enforces
     * individually — every one of them, alone, means zero rows are ever written to {@code trends}:
     *
     * <ul>
     *   <li>{@link #isEnabled()} false → {@code pullTrends} returns at its first line
     *       ("disabled ... skipping run").
     *   <li>{@link #isConfigured()} false → every {@link
     *       com.influora.service.trendspark.ingest.TrendSourceClient} is skipped for a missing key
     *       and {@code fetched} is empty ("nothing written").
     *   <li>{@link #hasClassifierWorkspaceId()} false → every fetched row is rejected
     *       {@code reason=classifier_unconfigured} before the write (EV-013's fail-closed leg).
     * </ul>
     *
     * <p>Consequence for the UI: with this false, {@code TrendSparkNudgeService#getNudge} and
     * {@code CreatorNudgeService#getSuggestion} can only ever reach their "nothing to say" exits,
     * because both score against {@code TrendRepository.findActive} and that list is permanently
     * empty. Rendering a trend/idea surface in that state is a promise the backend cannot keep, so
     * the surfaces hide instead — see {@link TrendFeatureGate}.
     */
    public boolean canProduceTrends() {
        return isEnabled() && isConfigured() && hasClassifierWorkspaceId();
    }
}
