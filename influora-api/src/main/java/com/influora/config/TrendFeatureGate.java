package com.influora.config;

import com.influora.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * T-TSOFF-0920 — the single server-side gate every trend-derived user-facing endpoint asks before
 * doing any work, so "TrendSpark is off for the beta" is true end to end instead of true only by
 * accident.
 *
 * <p><b>Why a gate and not just the empty table.</b> Before this, both trend-derived read
 * endpoints were <em>accidentally</em> honest: {@code trends} is empty with ingest off, so
 * {@code TrendSparkNudgeService#getNudge} returned {@code Optional.empty()} (HTTP 204) and
 * {@code CreatorNudgeService#getSuggestion} returned {@code no_suggestion_today} (HTTP 200). Both
 * of those are the SAME responses the feature emits on a normal working day when nothing scored
 * above threshold, so no client — ours or anyone else's — could tell "the feature is switched
 * off" from "no trend matched you today". That ambiguity is what produced the dishonest UI: the
 * creator surface answered a permanent off-state with "your first idea lands by tomorrow
 * morning", which was never going to happen.
 *
 * <p>Off is now an explicit, documented {@code 404 TRENDS_DISABLED} in the standard {@code
 * ApiResponse} error envelope — the same shape and HTTP status this codebase already uses for a
 * switched-off feature ({@code FEATURE_DISABLED} in {@code CreatorAgentController},
 * {@code CreatorMeeraController}, {@code PublicCreatorController}), so there is one convention,
 * not two.
 *
 * <p><b>Source of truth.</b> {@link TrendIngestProperties#canProduceTrends()} only — never a
 * second flag of its own. {@code GET /config/public}'s {@code trendsEnabled} is read from this
 * same method, so the SPA hides exactly the surfaces this gate would refuse, and flipping
 * {@code TREND_INGEST_ENABLED} (plus a source key and
 * {@code TREND_INGEST_CLASSIFIER_WORKSPACE_ID}) turns ingest, the endpoints and the UI back on
 * together. There is no frontend constant to keep in sync.
 */
@Component
public class TrendFeatureGate {

    /** Error code carried in the standard envelope. Mirrored in {@code src/lib/api.ts}. */
    public static final String DISABLED_CODE = "TRENDS_DISABLED";

    /** User-facing message — plain, final, and never an "it is coming" promise (T-TSOFF-0920). */
    public static final String DISABLED_MESSAGE =
            "Trend suggestions are not available on Influora yet.";

    private final TrendIngestProperties trendIngest;

    public TrendFeatureGate(TrendIngestProperties trendIngest) {
        this.trendIngest = trendIngest;
    }

    /** True when a trend row can actually exist — see {@link TrendIngestProperties#canProduceTrends()}. */
    public boolean isEnabled() {
        return trendIngest.canProduceTrends();
    }

    /**
     * Throws {@code 404 TRENDS_DISABLED} when trend data can never exist. Called as the FIRST
     * statement of every trend-derived endpoint, BEFORE workspace/creator resolution, so a
     * disabled feature cannot be used to probe whether an id exists and so no AI-billable work is
     * reachable while the feature is off.
     */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ApiException(DISABLED_CODE, DISABLED_MESSAGE, HttpStatus.NOT_FOUND);
        }
    }
}
