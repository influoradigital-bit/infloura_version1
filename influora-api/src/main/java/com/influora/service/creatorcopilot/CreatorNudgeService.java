package com.influora.service.creatorcopilot;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.CreatorNudgeLog;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.NudgeMessageSource;
import com.influora.integration.ai.CreatorSuggestionAiClient;
import com.influora.integration.ai.CreatorSuggestionAiClient.SuggestionCopy;
import com.influora.repository.CreatorNudgeLogRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.TrendRepository;
import com.influora.service.trendspark.ThemeMatchService;
import com.influora.web.dto.creatorcopilot.CreatorCopilotDtos.SuggestionDto;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the Creator AI Co-pilot's daily suggestion (be-services-plan.md §1) — a FORK of
 * {@link com.influora.service.trendspark.TrendSparkNudgeService}'s structure/discipline, not a
 * shared class: no {@code BrandProfile}/catalog/gap-check/SNAPSBY-mode concepts exist on this
 * path (spec §2.2). Order is mandatory: idempotent-read-first (the per-day cap mechanism, not a
 * separate check) -&gt; theme-tagging-pending check -&gt; pick best-scoring active trend -&gt; AI
 * phrasing (fallback template on null) -&gt; write {@code creator_nudge_log} -&gt; return DTO.
 */
@Service
public class CreatorNudgeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorNudgeService.class);

    private final TrendRepository trendRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorNudgeLogRepository creatorNudgeLogRepository;
    private final ThemeMatchService themeMatchService;
    private final CreatorSuggestionAiClient aiClient;
    private final CreatorCopilotProperties props;

    public CreatorNudgeService(
            TrendRepository trendRepository,
            CreatorProfileRepository creatorProfileRepository,
            CreatorNudgeLogRepository creatorNudgeLogRepository,
            ThemeMatchService themeMatchService,
            CreatorSuggestionAiClient aiClient,
            CreatorCopilotProperties props) {
        this.trendRepository = trendRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorNudgeLogRepository = creatorNudgeLogRepository;
        this.themeMatchService = themeMatchService;
        this.aiClient = aiClient;
        this.props = props;
    }

    /** 3-way status the FE contract needs (API-CONTRACT.md §1.1/§2) — richer than Trend-Spark's
     * binary present/absent, since "nothing to show" here has two distinct causes the FE renders
     * differently (zero-themes-yet vs. zero-matching-trend-today). */
    public record SuggestionResult(String status, SuggestionDto suggestion) {
        public static SuggestionResult pendingTagging() {
            return new SuggestionResult("pending_tagging", null);
        }

        public static SuggestionResult noSuggestionToday() {
            return new SuggestionResult("no_suggestion_today", null);
        }

        public static SuggestionResult ready(SuggestionDto dto) {
            return new SuggestionResult("ready", dto);
        }
    }

    @Transactional
    public SuggestionResult getSuggestion(String creatorProfileId) {
        // Defensive: the caller (CreatorContextService.requireCreatorProfile) already 404s before
        // this method is ever invoked, but this method never assumes that contract holds.
        CreatorProfile profile =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found",
                                                HttpStatus.NOT_FOUND));

        // Idempotent-read-first — THIS is the per-creator/day cap mechanism (be-services-plan §1
        // step 2), not a separate check. A same-day repeat call returns the identical row with no
        // re-scoring and no AI spend (AC-4).
        Optional<CreatorNudgeLog> todays =
                creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        creatorProfileId, startOfUtcDay());
        if (todays.isPresent()) {
            return SuggestionResult.ready(toDto(todays.get()));
        }

        String creatorThemeTags = profile.getThemeTagsJson();
        if (creatorThemeTags == null || creatorThemeTags.isBlank()) {
            // The nightly CreatorThemeTaggingJob hasn't produced a rollup for this creator yet —
            // distinct from "no matching trend today" so the FE can render different copy.
            return SuggestionResult.pendingTagging();
        }

        List<Trend> activeTrends = trendRepository.findActive(Instant.now());
        Trend bestTrend = null;
        int bestScore = -1;
        for (Trend trend : activeTrends) {
            int score = themeMatchService.score(trend, creatorThemeTags);
            if (score > bestScore) {
                bestScore = score;
                bestTrend = trend;
            }
        }

        if (bestTrend == null || bestScore < props.getScoreThreshold()) {
            // No log row written — nothing to cap, since there's nothing to show.
            return SuggestionResult.noSuggestionToday();
        }

        // Server-sourced, closed-vocab theme — the same value themeMatchService.score matched
        // against. Never comes from the AI call or the fallback template.
        String theme = bestMatchedTheme(bestTrend, creatorThemeTags);

        SuggestionCopy copy = callAiSafely(creatorProfileId, theme, bestTrend.getTrendText());
        NudgeMessageSource messageSource;
        String headline;
        String contentIdea;
        if (copy != null) {
            headline = copy.headline();
            contentIdea = copy.contentIdea();
            messageSource = NudgeMessageSource.AI;
        } else {
            SuggestionCopy fallback = templatedFallback(profile, bestTrend, theme);
            headline = fallback.headline();
            contentIdea = fallback.contentIdea();
            messageSource = NudgeMessageSource.FALLBACK;
        }

        CreatorNudgeLog nudgeLog =
                CreatorNudgeLog.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(creatorProfileId)
                        .trendId(bestTrend.getId())
                        .matchScore(bestScore)
                        .theme(theme)
                        .headline(headline)
                        .contentIdea(contentIdea)
                        .messageSource(messageSource)
                        .promptVersion(props.getPromptVersion())
                        .build();

        try {
            nudgeLog = creatorNudgeLogRepository.save(nudgeLog);
        } catch (DataIntegrityViolationException e) {
            // Lost the per-day-cap race (V20260721140000's uq_creator_nudge_day unique key) to a
            // concurrent first-of-day request — the winner's row now exists; return that instead
            // of propagating a 500 (be-services-plan §1 step 8 / §5).
            return creatorNudgeLogRepository
                    .findByCreatorProfileIdAndShownAtAfter(creatorProfileId, startOfUtcDay())
                    .map(row -> SuggestionResult.ready(toDto(row)))
                    .orElseThrow(() -> e);
        }

        return SuggestionResult.ready(toDto(nudgeLog));
    }

    @Transactional
    public void markDismissed(String creatorProfileId, String suggestionId) {
        CreatorNudgeLog nudgeLog = requireOwnedSuggestion(creatorProfileId, suggestionId);
        nudgeLog.markDismissed();
        creatorNudgeLogRepository.save(nudgeLog);
    }

    @Transactional
    public void markActed(String creatorProfileId, String suggestionId) {
        CreatorNudgeLog nudgeLog = requireOwnedSuggestion(creatorProfileId, suggestionId);
        nudgeLog.markActed();
        creatorNudgeLogRepository.save(nudgeLog);
    }

    /** Resolves the row THEN checks creator ownership — never trusts the id path param alone
     * (Guardrail 2 discipline, same as {@code TrendSparkNudgeService.requireOwnedNudge}). "Not
     * found" and "not yours" return the identical 404 (no IDOR oracle, API-CONTRACT.md §1.2). */
    private CreatorNudgeLog requireOwnedSuggestion(String creatorProfileId, String suggestionId) {
        return creatorNudgeLogRepository
                .findByIdAndCreatorProfileId(suggestionId, creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "SUGGESTION_NOT_FOUND", "Suggestion not found", HttpStatus.NOT_FOUND));
    }

    private SuggestionCopy callAiSafely(String creatorProfileId, String theme, String trendText) {
        try {
            return aiClient.requestSuggestion(creatorProfileId, theme, trendText);
        } catch (Exception e) {
            // Belt-and-braces: CreatorSuggestionAiClient already never throws, but the caller must
            // never see an exception surface from the phrasing step either way.
            log.warn(
                    "CreatorNudgeService: AI suggestion call errored for creator={}: {}",
                    creatorProfileId,
                    e.getMessage());
            return null;
        }
    }

    /** Fallback templated copy — placeholder text mirroring {@code
     * TrendSparkNudgeService.templatedFallback}'s discipline (plain, safe, never breaks tone
     * rules); final copy is Ash/Tejas's call per the spec's still-open zero-state ruling (§6/§8),
     * unaffected by this stub. Returns the same two-value tuple shape the AI client returns, so
     * this class never branches into "AI gives 2 fields, template gives 1". */
    private SuggestionCopy templatedFallback(CreatorProfile profile, Trend trend, String theme) {
        String name = displayName(profile);
        String headline = String.format("%s, your %s content is trending right now", name, theme);
        String contentIdea =
                String.format(
                        "There's a trend around \"%s\" that fits your %s niche — a quick post while"
                                + " it's hot could land well.",
                        trend.getTrendText(), theme);
        return new SuggestionCopy(headline, contentIdea);
    }

    private String displayName(CreatorProfile profile) {
        String name = profile.getDisplayName();
        return (name == null || name.isBlank()) ? "You" : name;
    }

    /** Overlap-intersection pick between the trend's themes and the creator's theme_tags — the
     * exact vocabulary member {@code themeMatchService.score} counted as matching. Deterministic
     * (sorted, not first-in-iteration-order) so repeated calls against the same inputs agree. When
     * {@code bestScore >= scoreThreshold} (always true by the time this is called, since score IS
     * overlap count), the intersection is guaranteed non-empty — the final {@code orElse} branch is
     * defensive only. */
    private String bestMatchedTheme(Trend trend, String creatorThemeTagsJson) {
        Set<String> trendThemes = themeMatchService.parseThemeJson(trend.getThemesJson());
        Set<String> creatorThemes = themeMatchService.parseThemeJson(creatorThemeTagsJson);
        return trendThemes.stream()
                .filter(creatorThemes::contains)
                .sorted()
                .findFirst()
                .orElseGet(() -> trendThemes.stream().sorted().findFirst().orElse(""));
    }

    private SuggestionDto toDto(CreatorNudgeLog row) {
        return new SuggestionDto(
                row.getId(), row.getTheme(), row.getHeadline(), row.getContentIdea(), expiresAt(row.getShownAt()));
    }

    /** End of the UTC day containing {@code shownAt} — NOT a stored column, computed on every
     * read (API-CONTRACT.md §2, "display-only for Tier-1"). */
    private String expiresAt(Instant shownAt) {
        return shownAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toString();
    }

    private static Instant startOfUtcDay() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
