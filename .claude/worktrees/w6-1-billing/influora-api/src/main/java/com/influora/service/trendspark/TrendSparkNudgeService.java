package com.influora.service.trendspark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.TrendSparkProperties;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.NudgeLog;
import com.influora.domain.entity.SnapsbyCatalogVideo;
import com.influora.domain.entity.Trend;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.NudgeMessageSource;
import com.influora.domain.enums.NudgeMode;
import com.influora.integration.ai.TrendSparkAiClient;
import com.influora.integration.ai.TrendSparkAiClient.NudgeCopy;
import com.influora.integration.ai.dto.TrendSparkAiDtos.VideoRef;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.NudgeLogRepository;
import com.influora.repository.TrendRepository;
import com.influora.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.influora.web.dto.trendspark.TrendSparkDtos.NudgeResponse;
import com.influora.web.dto.trendspark.TrendSparkDtos.VideoCard;

/**
 * Orchestrates the Trend-Spark nudge (schema lock — Java owns ALL logic and data). Order is
 * mandatory: pick trend -&gt; theme-match score (silent below threshold) -&gt; gap-check -&gt; mode -&gt;
 * catalog-match (SNAPSBY only) -&gt; AI phrasing (fallback template on null) -&gt; write {@code
 * nudge_log} -&gt; return DTO.
 */
@Service
public class TrendSparkNudgeService {

    private static final Logger log = LoggerFactory.getLogger(TrendSparkNudgeService.class);

    private final TrendRepository trendRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final WorkspaceRepository workspaceRepository;
    private final NudgeLogRepository nudgeLogRepository;
    private final ThemeMatchService themeMatchService;
    private final ContentGapService contentGapService;
    private final CatalogMatchService catalogMatchService;
    private final TrendSparkAiClient aiClient;
    private final TrendSparkProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TrendSparkNudgeService(
            TrendRepository trendRepository,
            BrandProfileRepository brandProfileRepository,
            WorkspaceRepository workspaceRepository,
            NudgeLogRepository nudgeLogRepository,
            ThemeMatchService themeMatchService,
            ContentGapService contentGapService,
            CatalogMatchService catalogMatchService,
            TrendSparkAiClient aiClient,
            TrendSparkProperties props) {
        this.trendRepository = trendRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.workspaceRepository = workspaceRepository;
        this.nudgeLogRepository = nudgeLogRepository;
        this.themeMatchService = themeMatchService;
        this.contentGapService = contentGapService;
        this.catalogMatchService = catalogMatchService;
        this.aiClient = aiClient;
        this.props = props;
    }

    /** Returns empty when Trend-Spark should stay silent (no active trend clears the score
     * threshold, or the brand has no profile yet) — a correct outcome, not an error. */
    @Transactional
    public Optional<NudgeResponse> getNudge(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }

        Optional<BrandProfile> brandProfileOpt = brandProfileRepository.findByWorkspaceId(workspaceId);
        if (brandProfileOpt.isEmpty()) {
            // Fail-closed: no profile to score/gap-check against — stay silent.
            return Optional.empty();
        }
        BrandProfile brandProfile = brandProfileOpt.get();

        List<Trend> activeTrends = trendRepository.findActive(Instant.now());
        Trend bestTrend = null;
        int bestScore = -1;
        for (Trend trend : activeTrends) {
            int score = themeMatchService.score(trend, brandProfile.getThemeTagsJson());
            if (score > bestScore) {
                bestScore = score;
                bestTrend = trend;
            }
        }

        if (bestTrend == null || bestScore < props.getScoreThreshold()) {
            return Optional.empty();
        }

        // T6: gap-check result now also carries the brand's recent own-content theme signal
        // (Meta-backed when available) — not consumed here yet, available for the AI phrasing
        // step to reference later; only `mode` drives this method's control flow.
        ContentGapService.GapCheckResult gapCheckResult =
                contentGapService.decide(brandProfile, bestTrend, bestScore);
        NudgeMode mode = gapCheckResult.mode();

        List<SnapsbyCatalogVideo> catalogMatches =
                mode == NudgeMode.SNAPSBY
                        ? catalogMatchService.topMatches(brandProfile, bestTrend)
                        : List.of();

        NudgeCopy aiCopy = callAiSafely(workspaceId, brandProfile, bestTrend, mode, catalogMatches);

        String message;
        NudgeMessageSource messageSource;
        List<String> chosenVideoIds;
        if (aiCopy != null) {
            message = aiCopy.message();
            messageSource = NudgeMessageSource.AI;
            chosenVideoIds =
                    aiCopy.videoIds() != null && !aiCopy.videoIds().isEmpty()
                            ? aiCopy.videoIds()
                            : catalogMatches.stream().map(SnapsbyCatalogVideo::getId).toList();
        } else {
            message = templatedFallback(brandProfile, bestTrend, mode, catalogMatches.size());
            messageSource = NudgeMessageSource.FALLBACK;
            chosenVideoIds = catalogMatches.stream().map(SnapsbyCatalogVideo::getId).toList();
        }

        String videoIdsJson = writeJsonSafely(chosenVideoIds);

        NudgeLog nudgeLog =
                NudgeLog.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .trendId(bestTrend.getId())
                        .campaignType(bestTrend.getCampaignType())
                        .matchScore(bestScore)
                        .mode(mode)
                        .videoIdsJson(mode == NudgeMode.SNAPSBY ? videoIdsJson : null)
                        .message(message)
                        .messageSource(messageSource)
                        .build();
        nudgeLog = nudgeLogRepository.save(nudgeLog);

        List<VideoCard> videoCards =
                mode == NudgeMode.SNAPSBY
                        ? catalogMatches.stream()
                                .filter(v -> chosenVideoIds.isEmpty() || chosenVideoIds.contains(v.getId()))
                                .map(
                                        v ->
                                                new VideoCard(
                                                        v.getId(), v.getTitle(), v.getPreviewUrl(), v.getPriceInr()))
                                .toList()
                        : List.of();

        return Optional.of(
                new NudgeResponse(
                        nudgeLog.getId(),
                        mode.name(),
                        bestTrend.getCampaignType().name(),
                        bestTrend.getTrendText(),
                        message,
                        messageSource.name(),
                        videoCards));
    }

    @Transactional
    public void markClicked(String workspaceId, String nudgeId) {
        NudgeLog nudgeLog = requireOwnedNudge(workspaceId, nudgeId);
        nudgeLog.markClicked();
        nudgeLogRepository.save(nudgeLog);
    }

    @Transactional
    public void markPurchased(String workspaceId, String nudgeId, String purchasedVideoId) {
        NudgeLog nudgeLog = requireOwnedNudge(workspaceId, nudgeId);
        nudgeLog.markPurchased(purchasedVideoId);
        nudgeLogRepository.save(nudgeLog);
    }

    /** Resolves the row THEN checks workspace ownership — never trusts the id path param alone
     * (Guardrail 2 / schema lock §5.2). */
    private NudgeLog requireOwnedNudge(String workspaceId, String nudgeId) {
        return nudgeLogRepository
                .findByIdAndWorkspaceId(nudgeId, workspaceId)
                .orElseThrow(
                        () -> new ApiException("NUDGE_NOT_FOUND", "Nudge not found", HttpStatus.NOT_FOUND));
    }

    private NudgeCopy callAiSafely(
            String workspaceId,
            BrandProfile brandProfile,
            Trend trend,
            NudgeMode mode,
            List<SnapsbyCatalogVideo> catalogMatches) {
        try {
            List<VideoRef> videoRefs = new ArrayList<>();
            if (mode == NudgeMode.SNAPSBY) {
                for (SnapsbyCatalogVideo v : catalogMatches) {
                    videoRefs.add(new VideoRef(v.getId(), v.getTitle(), parseThemesSafely(v.getThemesJson())));
                }
            }
            return aiClient.requestPhrasing(
                    workspaceId,
                    brandDisplayName(brandProfile),
                    trend.getCampaignType().name(),
                    trend.getTrendText(),
                    mode.name(),
                    videoRefs);
        } catch (Exception e) {
            // Belt-and-braces: TrendSparkAiClient already never throws, but the caller must never
            // see an exception surface from the phrasing step either way (schema lock §4).
            log.warn("TrendSparkNudgeService: AI phrasing call errored for workspace={}: {}", workspaceId, e.getMessage());
            return null;
        }
    }

    /** Fallback templated message — exact copy from trendspark/meera-tone-guide.md §7 (Nisha/Tejas,
     * T5). Plain, safe, never breaks tone rules. */
    private String templatedFallback(
            BrandProfile brandProfile, Trend trend, NudgeMode mode, int videoCount) {
        String brandName = brandDisplayName(brandProfile);
        String campaignType = trend.getCampaignType().name();
        String trendText = trend.getTrendText();
        if (mode == NudgeMode.SNAPSBY) {
            return String.format(
                    "%s, there's a %s trend around %s that fits your niche. I found %d ready videos you could use. Want to take a look?",
                    brandName, campaignType, trendText, videoCount);
        }
        return String.format(
                "%s, there's a %s trend around %s that matches your recent content. Good timing to post!",
                brandName, campaignType, trendText);
    }

    private String brandDisplayName(BrandProfile brandProfile) {
        // BrandProfile has no name field of its own — the human-facing brand name lives on
        // Workspace (Guardrail-safe: same workspaceId already resolved via BrandContextService
        // upstream, this is a plain by-id lookup, not a new trust boundary).
        return workspaceRepository
                .findById(brandProfile.getWorkspaceId())
                .map(Workspace::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(brandProfile.getWorkspaceId());
    }

    private List<String> parseThemesSafely(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJsonSafely(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }
}
