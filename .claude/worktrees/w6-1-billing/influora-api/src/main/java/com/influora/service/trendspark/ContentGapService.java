package com.influora.service.trendspark;

import com.influora.config.TrendSparkProperties;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.NudgeMode;
import com.influora.service.trendspark.BrandOwnContentService.OwnContentSignal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Anti-spam content-gap check (schema lock §3, LOCKED). Order is mandatory across the caller:
 * theme-match score first, THEN this gap-check, THEN mode is decided here.
 *
 * <pre>
 * gap = (last_posted_at is null OR older than GAP_DAYS[default 4])
 *    OR (no own-content video matching trend theme)
 *    OR (peak_window_days <= 3 and brand has nothing ready)
 *    OR (brand new / empty catalog)
 * mode = gap ? SNAPSBY : OWN_CONTENT
 * </pre>
 *
 * <p><b>"no own-content video matching trend theme"</b> (T6): resolved by {@link
 * BrandOwnContentService} against the brand's real, connected Instagram (caption-derived theme
 * overlap with the trend). <b>Fail-closed fallback chain, mandatory order:</b>
 *
 * <ol>
 *   <li>Real Meta signal ({@link OwnContentSignal#signalAvailable()} true) — use it.
 *   <li>Meta signal unavailable (no token / revoked / expired / rate-limited / timeout /
 *       malformed response — anything short of a real answer) — fall back to the T4 MVP proxy:
 *       "the brand hasn't posted within GAP_DAYS" (same signal as the first clause).
 *   <li>Brand profile itself missing/unreadable — default to {@link NudgeMode#OWN_CONTENT}
 *       directly, before any of the above runs (never push the Snapsby marketplace on missing
 *       data).
 * </ol>
 *
 * Every degradation is logged by {@link BrandOwnContentService} with a reason.
 */
@Service
public class ContentGapService {

    private static final Logger log = LoggerFactory.getLogger(ContentGapService.class);

    private final TrendSparkProperties props;
    private final BrandOwnContentService brandOwnContentService;

    public ContentGapService(TrendSparkProperties props, BrandOwnContentService brandOwnContentService) {
        this.props = props;
        this.brandOwnContentService = brandOwnContentService;
    }

    public GapCheckResult decide(BrandProfile brandProfile, Trend trend, int matchScore) {
        if (brandProfile == null) {
            log.warn("ContentGapService: null brand profile — failing closed to OWN_CONTENT");
            return new GapCheckResult(NudgeMode.OWN_CONTENT, Set.of());
        }

        boolean lastPostedGap = isLastPostedStale(brandProfile.getLastPostedAt());
        boolean brandEmpty = isBrandEmpty(brandProfile);
        boolean hypeWindowTight =
                trend != null
                        && trend.getPeakWindowDays() != null
                        && trend.getPeakWindowDays() <= 3
                        && lastPostedGap;

        // T6: try the real Meta-backed signal first; fail-closed to the T4 last_posted_at proxy
        // if it isn't available (see class javadoc for the mandatory fallback order).
        OwnContentSignal ownContentSignal = brandOwnContentService.checkOwnContent(brandProfile, trend);
        boolean noOwnContentMatch =
                ownContentSignal.signalAvailable() ? !ownContentSignal.hasMatchingOwnContent() : lastPostedGap;

        boolean gap = lastPostedGap || noOwnContentMatch || hypeWindowTight || brandEmpty;
        NudgeMode mode = gap ? NudgeMode.SNAPSBY : NudgeMode.OWN_CONTENT;
        return new GapCheckResult(mode, ownContentSignal.recentOwnContentThemes());
    }

    /**
     * @param mode the decided nudge mode (schema lock §3).
     * @param recentOwnContentThemes personalization hook (T6): caption-derived themes from the
     *     brand's most recent own Instagram content, when a real Meta signal was available;
     *     empty otherwise. Not used by this service — exposed for the nudge phrasing step (Ash)
     *     to optionally reference "what the brand already does."
     */
    public record GapCheckResult(NudgeMode mode, Set<String> recentOwnContentThemes) {}

    private boolean isLastPostedStale(Instant lastPostedAt) {
        if (lastPostedAt == null) {
            return true;
        }
        Instant staleBefore = Instant.now().minus(props.getGapDays(), ChronoUnit.DAYS);
        return lastPostedAt.isBefore(staleBefore);
    }

    private boolean isBrandEmpty(BrandProfile brandProfile) {
        return isBlankJson(brandProfile.getThemeTagsJson())
                && isBlankJson(brandProfile.getNicheTagsJson())
                && isBlankJson(brandProfile.getProductCatalogJson());
    }

    private boolean isBlankJson(String json) {
        return json == null || json.isBlank() || "[]".equals(json.trim()) || "{}".equals(json.trim());
    }
}
