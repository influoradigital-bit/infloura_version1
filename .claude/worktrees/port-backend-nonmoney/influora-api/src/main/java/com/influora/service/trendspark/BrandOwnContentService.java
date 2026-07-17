package com.influora.service.trendspark;

import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.Trend;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.MetaOAuthTokenRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * T6 — real own-content gap-check signal, reading the brand's connected Instagram via the
 * existing Meta integration (via {@link InstagramInsightsClient}, {@link MetaTokenStorage},
 * {@link MetaOAuthToken}) instead of {@link ContentGapService}'s T4 MVP proxy ("no own-content
 * match" approximated as "hasn't posted within GAP_DAYS").
 *
 * <p>Derives a coarse per-media "theme signal" from each caption using {@link
 * ThemeMatchService#themesForText}, which reuses the same {@code theme-taxonomy.json}
 * (Nisha, T5) already loaded for trend/brand theme-overlap scoring — no new taxonomy file, no
 * new NLP/ML dependency.
 *
 * <p><b>Fail-closed (schema lock §3, mandatory) — this class never throws to its caller.</b> Any
 * inability to get a REAL answer from Meta (no connected token, revoked, expired/unreadable,
 * rate-limited, timeout, malformed response) returns {@link OwnContentSignal#signalAvailable()}
 * {@code == false} with a logged degradation reason; {@link ContentGapService} then falls back to
 * the {@code last_posted_at} proxy, and ultimately to {@code NudgeMode#OWN_CONTENT} if the brand
 * profile itself is unreadable. A successful Meta call that returns zero media is NOT a
 * degradation — it is schema lock §3's "brand new / empty catalog" signal, reported as {@code
 * signalAvailable=true, hasMatchingOwnContent=false}.
 */
@Service
public class BrandOwnContentService {

    private static final Logger log = LoggerFactory.getLogger(BrandOwnContentService.class);

    /** How many of the brand's most recent media items to pull from Meta for the theme signal —
     * small on purpose: this is a coarse gap-check/personalization hint, not an analytics fetch,
     * and keeps this call cheap against the shared Meta rate-limit budget. */
    private static final int RECENT_MEDIA_LIMIT = 10;

    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final MetaTokenStorage metaTokenStorage;
    private final InstagramInsightsClient instagramInsightsClient;
    private final ThemeMatchService themeMatchService;

    public BrandOwnContentService(
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            MetaTokenStorage metaTokenStorage,
            InstagramInsightsClient instagramInsightsClient,
            ThemeMatchService themeMatchService) {
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.metaTokenStorage = metaTokenStorage;
        this.instagramInsightsClient = instagramInsightsClient;
        this.themeMatchService = themeMatchService;
    }

    /**
     * Real gap-check signal for {@code brandProfile}'s recent own Instagram content against
     * {@code trend}'s themes. Never throws — every failure path logs a degradation reason and
     * returns {@link OwnContentSignal#signalAvailable()} {@code == false} instead.
     */
    public OwnContentSignal checkOwnContent(BrandProfile brandProfile, Trend trend) {
        if (brandProfile == null || trend == null) {
            return unavailable(brandProfile, "NULL_INPUT");
        }
        String workspaceId = brandProfile.getWorkspaceId();

        List<MetaOAuthToken> tokens;
        try {
            tokens = metaOAuthTokenRepository.findByWorkspaceIdAndRevokedFalse(workspaceId);
        } catch (Exception e) {
            return unavailable(brandProfile, "TOKEN_LOOKUP_ERROR: " + e.getClass().getSimpleName());
        }
        if (tokens == null || tokens.isEmpty()) {
            return unavailable(brandProfile, "NO_META_TOKEN");
        }
        // Deterministic pick if this workspace somehow has more than one active connection: most
        // recently connected wins (same "pick one, be consistent" spirit as
        // MetaOAuthTokenRepository's own primary-connection lookups for creators).
        MetaOAuthToken token =
                tokens.stream().max(Comparator.comparing(MetaOAuthToken::getCreatedAt)).orElse(tokens.get(0));
        String igUserId = token.getCreatorProfileId();

        Optional<String> accessToken;
        try {
            accessToken = metaTokenStorage.getValidToken(workspaceId, igUserId);
        } catch (Exception e) {
            return unavailable(brandProfile, "TOKEN_DECRYPT_ERROR: " + e.getClass().getSimpleName());
        }
        if (accessToken.isEmpty()) {
            return unavailable(brandProfile, "TOKEN_EXPIRED_OR_REVOKED");
        }

        InstagramMediaResponse media;
        try {
            media = instagramInsightsClient.getMedia(igUserId, accessToken.get(), RECENT_MEDIA_LIMIT);
        } catch (Exception e) {
            // Catches MetaApiException and its subtypes (rate-limit/token-expired/permission-
            // denied — all unchecked) AND transport-level failures (timeouts, connection resets)
            // that never reach MetaGraphApiClient's own translate(). Fail-closed net per T6 spec:
            // "the call fails, times out, or returns nothing" all land here.
            log.warn(
                    "BrandOwnContentService: Meta media fetch failed for workspace={}: {}",
                    workspaceId,
                    e.getMessage());
            return unavailable(brandProfile, "META_API_ERROR: " + e.getClass().getSimpleName());
        }

        if (media == null || media.data() == null || media.data().isEmpty()) {
            // A real, successful answer from Meta: this brand genuinely has no media yet. Not a
            // degradation — schema lock §3's "brand new / empty catalog" clause IS this case.
            return new OwnContentSignal(true, false, Set.of());
        }

        Set<String> trendThemes = themeMatchService.parseThemeJson(trend.getThemesJson());
        Set<String> recentThemes = new HashSet<>();
        boolean matched = false;
        for (InstagramMediaResponse.MediaItem item : media.data()) {
            Set<String> itemThemes = themeMatchService.themesForText(item.caption());
            recentThemes.addAll(itemThemes);
            if (!matched && !trendThemes.isEmpty() && !itemThemes.isEmpty()) {
                for (String theme : itemThemes) {
                    if (trendThemes.contains(theme)) {
                        matched = true;
                        break;
                    }
                }
            }
        }

        return new OwnContentSignal(true, matched, Set.copyOf(recentThemes));
    }

    private OwnContentSignal unavailable(BrandProfile brandProfile, String reason) {
        log.info(
                "BrandOwnContentService: real Meta signal unavailable for workspace={}, reason={} —"
                        + " ContentGapService falls back to last_posted_at proxy",
                brandProfile == null ? "unknown" : brandProfile.getWorkspaceId(),
                reason);
        return new OwnContentSignal(false, false, Set.of());
    }

    /**
     * Result of the real own-content gap-check.
     *
     * @param signalAvailable {@code true} only if Meta was actually reached and answered — {@code
     *     false} means {@code hasMatchingOwnContent}/{@code recentOwnContentThemes} are safe
     *     defaults and {@link ContentGapService} must fall back to the {@code last_posted_at}
     *     proxy.
     * @param hasMatchingOwnContent {@code true} if a recent media item's caption-derived themes
     *     overlap the trend's themes. Only meaningful when {@code signalAvailable} is true.
     * @param recentOwnContentThemes union of caption-derived themes across the fetched recent
     *     media — the personalization hook (T6: "expose most recent own-content theme(s) so the
     *     nudge can reference what the brand already does"; consumed via {@link
     *     ContentGapService.GapCheckResult#recentOwnContentThemes()}). Empty when unavailable or
     *     the brand has no media yet.
     */
    public record OwnContentSignal(
            boolean signalAvailable, boolean hasMatchingOwnContent, Set<String> recentOwnContentThemes) {}
}
