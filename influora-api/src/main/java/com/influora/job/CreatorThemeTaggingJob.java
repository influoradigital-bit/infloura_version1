package com.influora.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.CreatorCaptionCache;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CaptionTagStatus;
import com.influora.repository.CreatorCaptionCacheRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.trendspark.ThemeMatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly, deterministic (no model call — see class discussion below) theme-tagging batch for the
 * Creator AI Co-pilot (be-services-plan.md §2.6). Pulls a page of {@code PENDING} rows from {@code
 * creator_captions}, scores each against {@code trendspark/theme-taxonomy.json} via {@link
 * ThemeMatchService#themesForText(String)} — pure Java keyword-match, the same taxonomy Trend-Spark
 * already loads, zero model exposure to caption text (Kabir gate threat-2: PASS, downgraded
 * P0-&gt;Low precisely because this tagging pass is non-LLM) — then unions each creator's
 * newly-tagged themes into {@code CreatorProfile.theme_tags}.
 *
 * <p><b>Scope note (be-services-plan §2.6 open question, folded in here):</b> this job assumes
 * captions already exist in {@code creator_captions}. Populating that table (calling {@code
 * InstagramMetricsFetcher} per connected creator and writing rows) is a SEPARATE batch concern —
 * deliberately NOT built as part of this job, to avoid overloading one scheduled method with both
 * "sync captions from Meta" and "tag cached captions" responsibilities. A future {@code
 * CreatorCaptionSyncJob} owns that half; until it exists, this job simply has nothing to do (an
 * empty {@code PENDING} page each run — harmless, not an error).
 *
 * <p>Per-item try/catch so one caption's failure never aborts the batch — same resilience
 * discipline as {@code ScoreCalculationJob}. {@code themesForText} is documented never to throw
 * (fail-closed: bad input yields an empty theme set), so this catch is defensive-only.
 */
@Component
public class CreatorThemeTaggingJob {

    private static final Logger log = LoggerFactory.getLogger(CreatorThemeTaggingJob.class);
    private static final int PAGE_SIZE = 200;

    private final CreatorCaptionCacheRepository captionRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ThemeMatchService themeMatchService;
    private final CreatorCopilotProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreatorThemeTaggingJob(
            CreatorCaptionCacheRepository captionRepository,
            CreatorProfileRepository creatorProfileRepository,
            ThemeMatchService themeMatchService,
            CreatorCopilotProperties props) {
        this.captionRepository = captionRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.themeMatchService = themeMatchService;
        this.props = props;
    }

    /** Nightly at 3 AM UTC by default — OFF by default via {@link CreatorCopilotProperties#isEnabled()}
     * (same "flag gates whether the body runs" pattern as {@code BrandSafetyScoringProperties}). */
    @Scheduled(cron = "${influora.creator-copilot.theme-tag-batch-cron:0 0 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "CreatorThemeTaggingJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void tagCaptions() {
        if (!props.isEnabled()) {
            log.info("CreatorThemeTaggingJob: disabled (influora.creator-copilot.enabled=false), skipping run");
            return;
        }

        List<CreatorCaptionCache> pending =
                captionRepository.findByTagStatusOrderByCreatedAtAsc(
                        CaptionTagStatus.PENDING, PageRequest.of(0, PAGE_SIZE));

        Map<String, Set<String>> newThemesByCreator = new HashMap<>();
        int tagged = 0;
        int failed = 0;

        for (CreatorCaptionCache caption : pending) {
            try {
                Set<String> themes = themeMatchService.themesForText(caption.getCaptionText());
                caption.applyTagResult(writeJsonSafely(themes));
                captionRepository.save(caption);
                if (!themes.isEmpty()) {
                    newThemesByCreator
                            .computeIfAbsent(caption.getCreatorProfileId(), k -> new HashSet<>())
                            .addAll(themes);
                }
                tagged++;
            } catch (Exception e) {
                failed++;
                log.warn(
                        "CreatorThemeTaggingJob: unexpected failure tagging caption {} for creator {}: {}",
                        caption.getId(),
                        caption.getCreatorProfileId(),
                        e.getMessage());
                try {
                    caption.markFailed();
                    captionRepository.save(caption);
                } catch (Exception saveFailure) {
                    log.error(
                            "CreatorThemeTaggingJob: failed to mark caption {} FAILED after a tagging"
                                    + " error — it will be retried next run",
                            caption.getId(),
                            saveFailure);
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : newThemesByCreator.entrySet()) {
            unionIntoCreatorThemeTags(entry.getKey(), entry.getValue());
        }

        log.info(
                "CreatorThemeTaggingJob: completed run — {} tagged, {} failed, {} creators' theme_tags updated",
                tagged,
                failed,
                newThemesByCreator.size());
    }

    private void unionIntoCreatorThemeTags(String creatorProfileId, Set<String> newThemes) {
        creatorProfileRepository
                .findById(creatorProfileId)
                .ifPresent(
                        profile -> {
                            Set<String> existing = new HashSet<>(parseThemesSafely(profile.getThemeTagsJson()));
                            existing.addAll(newThemes);
                            profile.setThemeTagsJson(writeJsonSafely(existing));
                            creatorProfileRepository.save(profile);
                        });
    }

    private Set<String> parseThemesSafely(String json) {
        return themeMatchService.parseThemeJson(json);
    }

    private String writeJsonSafely(Set<String> themes) {
        try {
            return objectMapper.writeValueAsString(themes);
        } catch (Exception e) {
            return "[]";
        }
    }
}
