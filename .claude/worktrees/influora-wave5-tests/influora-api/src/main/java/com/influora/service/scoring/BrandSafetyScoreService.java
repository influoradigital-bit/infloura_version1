package com.influora.service.scoring;

import com.influora.common.JsonLists;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.repository.MetaOAuthTokenRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the brand-safety leg of {@code ScoreCalculationJob} (Wave C task C3): pulls a
 * creator's recent {@link MediaMetric} rows, calls {@link BrandSafetyAiClient}, and maps the GARM
 * response into the {@code creator_scores.brand_safety_score} / {@code garm_flags} / {@code
 * content_sentiment} shape expected by {@code CreatorScore.Builder}.
 *
 * <p><b>Graceful degradation is a hard acceptance criterion (task C3):</b> every public method
 * here returns {@link Optional#empty()} instead of throwing when influora-ai is unreachable,
 * times out, errors, or a workspace connection can't be resolved for the creator — it NEVER lets
 * an exception escape to {@code ScoreCalculationJob}. {@code ScoreCalculationJob} maps {@link
 * Optional#empty()} straight to {@code null} on the 3 brand-safety columns (never a {@code 0} or
 * other sentinel) — a {@code null} row means "unscored", never "scored as safe". This service
 * never returns a partial/best-effort result — every method either returns a result built from a
 * fully-succeeded classification, or {@link Optional#empty()}. The chunking policy below is
 * fail-safe for exactly this reason.
 *
 * <p><b>Chunking (≤25 items/call) is fail-safe — ANY chunk failure fails the whole creator
 * (CTO ruling, post-QA):</b> {@code recentMedia} is split into ≤25-item batches (mirroring
 * influora-ai's {@code brand_safety_max_items_per_call} and {@code BrandSafetyAiClient}'s
 * configured max-items-per-call, which throws if exceeded in one call) — see {@link
 * #classifyInChunks}. If *any* chunk fails — even when every other chunk succeeded — the entire
 * creator degrades to {@link Optional#empty()} (NULL columns); it is never scored off the
 * surviving chunks alone. Rationale: this is a SAFETY signal, and scoring a creator "safe" from a
 * partial view that happens to have missed the very chunk containing the unsafe post is a
 * false-negative hazard — worse than no score at all. "Unscored, not unsafe" (rule 5) extends to
 * partial data, not just total failure.
 *
 * <p><b>Caption egress discipline (Kabir's C1 sign-off MED-1 condition):</b> this service passes
 * captions straight through to {@link BrandSafetyAiClient} and never persists, caches, or logs
 * them itself — only counts/ids are logged, mirroring {@code MetricsPollingJob}.
 */
@Service
public class BrandSafetyScoreService {

    private static final Logger log = LoggerFactory.getLogger(BrandSafetyScoreService.class);

    /** GARM risk levels below this are "no concern" and never surface as a flag (influora-ai's
     * {@code GARM_RISK_LEVELS = ("floor", "low", "medium", "high")}, {@code app/tools/schemas.py}
     * — "floor" is the explicit no-concern default every category gets scored). */
    private static final String NO_CONCERN_RISK_LEVEL = "floor";

    /**
     * Max items sent to {@link BrandSafetyAiClient#classify} per HTTP call. Mirrors influora-ai's
     * {@code brand_safety_max_items_per_call = 25} ({@code influora-ai/app/config.py:176}) and
     * {@code BrandSafetyAiProperties#getMaxItemsPerCall()}'s default (also 25) — {@code
     * BrandSafetyAiClient#classify} throws {@link BrandSafetyAiException} if a single call exceeds
     * its configured max, and its javadoc explicitly makes chunking this caller's responsibility.
     * {@code ScoreCalculationJob}'s {@code RECENT_MEDIA_LIMIT = 30} exceeds this, so a single
     * creator's recent media routinely needs 2 chunks. Kept as a local constant rather than reading
     * {@code BrandSafetyAiProperties} here to avoid widening this service's constructor; if the
     * configured max ever changes, keep this constant in sync.
     */
    private static final int CHUNK_SIZE = 25;

    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final BrandSafetyAiClient brandSafetyAiClient;

    public BrandSafetyScoreService(
            MetaOAuthTokenRepository metaOAuthTokenRepository, BrandSafetyAiClient brandSafetyAiClient) {
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.brandSafetyAiClient = brandSafetyAiClient;
    }

    /** Aggregate result to fold into a {@code CreatorScore.Builder}. */
    public record BrandSafetyResult(
            BigDecimal brandSafetyScore, String garmFlagsJson, BigDecimal contentSentiment) {}

    /**
     * Scores a creator's recent media. Returns {@link Optional#empty()} (never throws) if:
     *
     * <ul>
     *   <li>{@code recentMedia} is empty (nothing to classify)
     *   <li>no active (non-revoked) workspace connection exists for this creator
     *   <li>influora-ai is unreachable, times out, or returns an error/malformed response
     * </ul>
     *
     * On success, picks the single highest-severity item (lowest {@code brand_safety_score}) as
     * the creator-level {@code brand_safety_score}/{@code content_sentiment}, and stores the
     * distinct set of above-floor GARM category names (across every classified item, not just the
     * worst one — a category can be flagged on a post other than the single-worst-score post) into
     * {@code garm_flags} as a flat JSON string array.
     *
     * <p><b>Wire shape fix (found in C4 QA, Ananya's frontend investigation):</b> {@code
     * garm_flags} is read back by {@code AnalyticsService.getCreatorScores} via {@code
     * JsonLists.stringListFromJson} — a {@code List<String>} parser — and surfaced through {@code
     * AnalyticsDtos.CreatorScoresResponse.garmFlags} (also {@code List<String>}), consumed by
     * {@code BrandSafetyBadge.tsx} as flat category tags. An earlier version of this method wrote
     * the full {@code List<ClassifiedItem>} (nested {category, risk, rationale} objects) here,
     * which {@code stringListFromJson} cannot parse — it throws, gets caught, and silently
     * degrades to {@code Collections.emptyList()}, so every brand-facing response showed zero
     * flags even when GARM found real risk. The per-post rationale/risk-level detail
     * {@code ClassifiedItem} carries is not dropped by design — it simply has no reader anywhere
     * in this codebase yet (no DTO field, no UI) — if/when a future UI wants per-post drill-down,
     * that needs its own column or DTO field, not a shape change to this one.
     */
    public Optional<BrandSafetyResult> scoreCreator(String creatorProfileId, List<MediaMetric> recentMedia) {
        if (recentMedia == null || recentMedia.isEmpty()) {
            log.info("BrandSafetyScoreService: no recent media for creator {}, skipping", creatorProfileId);
            return Optional.empty();
        }

        Optional<MetaOAuthToken> tokenRow =
                metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        creatorProfileId);
        if (tokenRow.isEmpty()) {
            log.info(
                    "BrandSafetyScoreService: no active workspace connection for creator {}, skipping",
                    creatorProfileId);
            return Optional.empty();
        }
        String workspaceId = tokenRow.get().getWorkspaceId();

        List<ContentItem> items = toContentItems(recentMedia);

        List<ClassifiedItem> classified = classifyInChunks(workspaceId, items, creatorProfileId);
        if (classified.isEmpty()) {
            // Any chunk failure (fail-safe policy, see classifyInChunks javadoc), or
            // (defensively) a chunk returned nothing usable — both cases degrade to "no result"
            // so ScoreCalculationJob leaves the brand-safety columns untouched for this creator
            // and continues scoring everyone else.
            return Optional.empty();
        }

        return mapToResult(creatorProfileId, classified);
    }

    /**
     * Splits {@code items} into ≤{@link #CHUNK_SIZE}-item batches and calls {@link
     * BrandSafetyAiClient#classify} once per batch (matching influora-ai's max-items-per-call
     * contract), merging every batch's {@link ClassifiedItem} results into one list.
     *
     * <p><b>Fail-safe policy (CTO ruling, post-QA):</b> if *any* chunk throws — a {@link
     * BrandSafetyAiException} or any unexpected exception — that failure is logged and the whole
     * call returns an empty list, discarding results already merged from other, successful
     * chunks. This is intentionally NOT "best effort": for a safety signal, a score derived from
     * only some of a creator's recent media risks certifying them "safe" purely because the chunk
     * containing the unsafe post happened to be the one that failed. {@link #scoreCreator} maps
     * an empty list here straight to {@link Optional#empty()} — same contract as a single-call
     * failure, whether one chunk failed or all of them did. Deliberately no caption content in
     * these log lines (Kabir's C1 MED-1 condition).
     */
    private List<ClassifiedItem> classifyInChunks(
            String workspaceId, List<ContentItem> items, String creatorProfileId) {
        int totalChunks = (items.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        List<ClassifiedItem> merged = new ArrayList<>(items.size());
        int failedChunks = 0;

        for (int start = 0; start < items.size(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, items.size());
            List<ContentItem> chunk = items.subList(start, end);
            try {
                merged.addAll(brandSafetyAiClient.classify(workspaceId, chunk));
            } catch (BrandSafetyAiException e) {
                failedChunks++;
                log.warn(
                        "BrandSafetyScoreService: influora-ai call failed for creator {} chunk "
                                + "[{}-{}) of {} chunk(s) — degrading ENTIRE creator to NULL "
                                + "brand-safety columns this run (fail-safe: a partial safety "
                                + "score is a false-negative hazard): {}",
                        creatorProfileId,
                        start,
                        end,
                        totalChunks,
                        e.getMessage());
            } catch (Exception e) {
                failedChunks++;
                log.error(
                        "BrandSafetyScoreService: unexpected failure classifying chunk [{}-{}) of "
                                + "{} chunk(s) for creator {} — degrading ENTIRE creator to NULL "
                                + "brand-safety columns this run (fail-safe)",
                        start,
                        end,
                        totalChunks,
                        creatorProfileId,
                        e);
            }
        }

        if (failedChunks > 0) {
            // Fail-safe (CTO ruling): ANY chunk failure discards the whole creator's result for
            // this run, even chunks that already succeeded — never a partial/best-effort score.
            return List.of();
        }
        return merged;
    }

    private List<ContentItem> toContentItems(List<MediaMetric> recentMedia) {
        List<ContentItem> items = new ArrayList<>(recentMedia.size());
        for (MediaMetric media : recentMedia) {
            String postedAt =
                    media.getPostedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(media.getPostedAt());
            items.add(
                    new ContentItem(media.getMediaId(), media.getCaption(), media.getMediaType(), postedAt));
        }
        return items;
    }

    private Optional<BrandSafetyResult> mapToResult(String creatorProfileId, List<ClassifiedItem> classified) {
        if (classified == null || classified.isEmpty()) {
            return Optional.empty();
        }

        // Worst-finding-driven, same principle Kavya's C2 review confirmed for the per-item
        // aggregate score: the creator-level score is the minimum (most severe) across all
        // classified items, never an average that could dilute a single unsafe post.
        ClassifiedItem worst = null;
        for (ClassifiedItem item : classified) {
            if (item.brandSafetyScore() == null) {
                continue;
            }
            if (worst == null || item.brandSafetyScore() < worst.brandSafetyScore()) {
                worst = item;
            }
        }
        if (worst == null) {
            log.warn(
                    "BrandSafetyScoreService: no item had a usable brand_safety_score for creator {}",
                    creatorProfileId);
            return Optional.empty();
        }

        BigDecimal brandSafetyScore = toScaledBigDecimal(worst.brandSafetyScore());
        BigDecimal contentSentiment = toScaledBigDecimal(worst.sentimentScore());
        String garmFlagsJson = writeGarmFlagsJson(classified);

        return Optional.of(new BrandSafetyResult(brandSafetyScore, garmFlagsJson, contentSentiment));
    }

    private BigDecimal toScaledBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Flattens every classified item's per-category GARM findings into the distinct set of
     * above-floor category names, in first-seen order, then serializes as a plain JSON string
     * array via the shared {@link JsonLists} helper — the same {@code List<String>} shape {@code
     * JsonLists#stringListFromJson(String)} expects on the read side ({@code
     * AnalyticsService.getCreatorScores}), so the round trip actually survives. Category names
     * with risk {@code "floor"} (no concern, always present per influora-ai's validation — see
     * {@code app/prompt/brand_safety.py}) are intentionally excluded so brand-facing consumers only
     * ever see flags that mean something.
     */
    private String writeGarmFlagsJson(List<ClassifiedItem> classified) {
        Set<String> aboveFloorCategories = new LinkedHashSet<>();
        for (ClassifiedItem item : classified) {
            if (item.garmFlags() == null) {
                continue;
            }
            for (GarmFlag flag : item.garmFlags()) {
                if (flag == null || flag.category() == null) {
                    continue;
                }
                if (!NO_CONCERN_RISK_LEVEL.equals(flag.risk())) {
                    aboveFloorCategories.add(flag.category());
                }
            }
        }
        return JsonLists.toJson(new ArrayList<>(aboveFloorCategories));
    }
}
