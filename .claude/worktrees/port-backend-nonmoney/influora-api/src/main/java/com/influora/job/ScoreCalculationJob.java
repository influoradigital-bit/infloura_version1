package com.influora.job;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.CreatorProfileSpecifications;
import com.influora.service.scoring.FakeFollowerDetectionService;
import com.influora.service.scoring.FakeFollowerDetectionService.FakeFollowerResult;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Phase 3 scoring algorithms (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §4) for every
 * discoverable creator and persists one {@link CreatorScore} row per creator per run.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Writes go
 * through {@link CreatorScoreRepository} only (the storage-abstraction seam), never raw SQL — same
 * discipline as {@code MetricsPollingJob} and {@code CreatorMetricsRepository}.
 *
 * <p><b>Deliberate scope cut — {@code BrandSafetyScoreService} is NOT wired in here.</b> The spec's
 * §3.3 pseudocode calls a {@code brandSafetyScorer.analyze(creatorId)} that requires cross-repo
 * integration with influora-ai (a separate Python service); that service does not exist yet and is
 * scoped as its own follow-up task. This job computes and stores only what the 3 existing scoring
 * services provide: {@link FakeFollowerDetectionService}, {@link QualityScoreService}, {@link
 * RateEstimationService}. The {@code CreatorScore} columns {@code brand_safety_score}, {@code
 * garm_flags}, and {@code content_sentiment} are left {@code null} on every row written by this job
 * — they are nullable in the V22 migration specifically so this is valid. Whoever builds {@code
 * BrandSafetyScoreService} next should extend {@link #scoreOne(CreatorProfile)} to also call it and
 * populate those 3 fields via the corresponding {@code CreatorScore.Builder} methods (which already
 * exist, documented as "not yet populated") — no schema or entity change should be needed.
 *
 * <p><b>Creator enumeration:</b> the spec's pseudocode calls an unspecified {@code
 * creatorRepo.findAllDiscoverable()}. The real {@link CreatorProfileRepository} has no such method,
 * but it does extend {@code JpaSpecificationExecutor} and there is already an established
 * "discoverable pool" concept used elsewhere in this codebase (e.g. {@code ShowCreatorsExecutor},
 * {@code ConfirmLaunchExecutor}, both filtering on {@code CreatorProfile.isDiscoverable()} /
 * {@code CreatorProfileSpecifications.discoverable()}). This job reuses that same specification
 * rather than inventing a parallel "has metrics data" enumeration — discoverable is the correct
 * scope because scores exist to power creator discovery/rate-estimation for brands, which only
 * ever looks at the discoverable pool in the first place. A creator with no metrics yet is still
 * enumerated (since scoring eligibility isn't the same as metrics availability) but is skipped
 * gracefully in {@link #scoreOne} when {@link CreatorMetricsRepository} has nothing for it yet.
 *
 * <p><b>Metrics lookup:</b> the spec's pseudocode assumes {@code
 * CreatorMetricRepository#findLatestByCreator} and {@code MediaMetricRepository#findRecentByCreator
 * (creatorId, 30)} exist verbatim. Neither does on the real Phase 2 repositories. The closest real
 * equivalents were used instead: {@link CreatorMetricsRepository
 * #findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc} (latest snapshot, scoped to {@code
 * PLATFORM_INSTAGRAM} since that's the only platform {@code MetricsPollingJob} currently polls) and
 * {@link MediaMetricsRepository#findByCreatorProfileIdOrderByTimeDesc} with a {@code
 * PageRequest.of(0, RECENT_MEDIA_LIMIT)} in place of the spec's "last 30" (the real repository has
 * no day-count overload; a page-size cap on the newest-first query is the equivalent "recent N"
 * read).
 *
 * <p>Each creator's scoring is wrapped in its own try/catch, matching {@code MetricsPollingJob}'s
 * resilience pattern — one creator's exception (or missing metrics) is logged and skipped, never
 * aborts the batch.
 */
@Component
public class ScoreCalculationJob {

    private static final Logger log = LoggerFactory.getLogger(ScoreCalculationJob.class);
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    private static final String ALGORITHM_VERSION = "v1.0.0";
    // Spec §3.3 asks for the last 30 days of media; the real MediaMetricsRepository has no
    // day-count overload, so this caps the newest-first page instead (see class javadoc).
    private static final int RECENT_MEDIA_LIMIT = 30;

    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final FakeFollowerDetectionService fakeFollowerDetectionService;
    private final QualityScoreService qualityScoreService;
    private final RateEstimationService rateEstimationService;
    private final CreatorScoreRepository creatorScoreRepository;

    public ScoreCalculationJob(
            CreatorProfileRepository creatorProfileRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            MediaMetricsRepository mediaMetricsRepository,
            FakeFollowerDetectionService fakeFollowerDetectionService,
            QualityScoreService qualityScoreService,
            RateEstimationService rateEstimationService,
            CreatorScoreRepository creatorScoreRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.fakeFollowerDetectionService = fakeFollowerDetectionService;
        this.qualityScoreService = qualityScoreService;
        this.rateEstimationService = rateEstimationService;
        this.creatorScoreRepository = creatorScoreRepository;
    }

    /** Daily at 4 AM UTC (spec §3.3). */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void calculateScores() {
        List<CreatorProfile> creators =
                creatorProfileRepository.findAll(CreatorProfileSpecifications.discoverable());

        int scored = 0;
        int skipped = 0;

        for (CreatorProfile creator : creators) {
            String creatorProfileId = creator.getId();
            try {
                if (scoreOne(creator)) {
                    scored++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // Defensive catch-all so one creator's unexpected failure never aborts the batch —
                // same resilience pattern as MetricsPollingJob.
                skipped++;
                log.error(
                        "ScoreCalculationJob: unexpected failure scoring creator {}",
                        creatorProfileId,
                        e);
            }
        }

        log.info(
                "ScoreCalculationJob: completed run — {} scored, {} skipped, {} total discoverable creators",
                scored,
                skipped,
                creators.size());
    }

    /** @return true if a {@link CreatorScore} row was successfully written for this creator. */
    private boolean scoreOne(CreatorProfile creator) {
        String creatorProfileId = creator.getId();
        Optional<CreatorMetric> latestMetric =
                creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        creatorProfileId, PLATFORM_INSTAGRAM);

        if (latestMetric.isEmpty()) {
            log.info(
                    "ScoreCalculationJob: no metrics yet for creator {}, skipping", creatorProfileId);
            return false;
        }

        List<MediaMetric> recentMedia =
                mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        creatorProfileId, PageRequest.of(0, RECENT_MEDIA_LIMIT));

        // FakeFollowerDetectionService also accepts historical CreatorMetric rows for growth-spike
        // detection (spec §4.1 signal 2); this job only has the single latest snapshot readily
        // available per creator per run, so historicalMetrics is passed as empty — the service
        // already handles that gracefully (it requires >= 7 historical points before that signal
        // fires at all, so an empty list simply skips that one signal rather than failing).
        FakeFollowerResult fakeFollowerResult =
                fakeFollowerDetectionService.analyze(latestMetric, recentMedia, List.of());

        QualityScoreResult qualityResult = qualityScoreService.calculate(latestMetric, recentMedia);

        // Content categories feed the RateEstimationService's category multiplier (spec §4.4).
        // CreatorProfile stores them as a JSON string (same convention as every other JSON column
        // in this codebase) — parse via the shared JsonLists helper rather than re-implementing.
        List<String> categories = JsonLists.stringListFromJson(creator.getCategoriesJson());
        RateEstimation rateEstimation =
                rateEstimationService.estimate(latestMetric, qualityResult, categories);

        CreatorScore score =
                CreatorScore.builder()
                        .id(Ulids.newUlid())
                        .time(Instant.now())
                        .creatorProfileId(creatorProfileId)
                        .fakeFollowerScore(fakeFollowerResult.score())
                        .fakeFollowerReasonsJson(JsonLists.toJson(fakeFollowerResult.reasons()))
                        .qualityScore(qualityResult.overall())
                        .engagementConsistency(qualityResult.consistency())
                        .postingFrequency(qualityResult.frequency())
                        .audienceMatchScore(qualityResult.audienceMatch())
                        // brandSafetyScore / garmFlagsJson / contentSentiment intentionally left
                        // unset (null) — see class javadoc scope-cut note.
                        .estimatedRateMin(rateEstimation.min())
                        .estimatedRateMax(rateEstimation.max())
                        .rateCurrency(rateEstimation.currency())
                        .rateConfidence(rateEstimation.confidence())
                        .algorithmVersion(ALGORITHM_VERSION)
                        .computedAt(Instant.now())
                        .build();

        creatorScoreRepository.save(score);
        return true;
    }
}
