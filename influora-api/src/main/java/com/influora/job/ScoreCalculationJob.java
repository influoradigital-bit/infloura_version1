package com.influora.job;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.config.BrandSafetyScoringProperties;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.CreatorProfileSpecifications;
import com.influora.service.scoring.BrandSafetyScoreService;
import com.influora.service.scoring.BrandSafetyScoreService.BrandSafetyResult;
import com.influora.service.scoring.FakeFollowerDetectionService;
import com.influora.service.scoring.FakeFollowerDetectionService.FakeFollowerResult;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * <p><b>Brand-safety scoring — wired, but behind an OFF-by-default flag (Priya ruling).</b> The
 * original scope cut deferred {@code BrandSafetyScoreService} because the influora-ai (Python)
 * service it fans out to "did not exist yet". That dependency now exists ({@code
 * BrandSafetyScoreService} → {@code BrandSafetyAiClient}, both registered), so this job now CAN
 * populate the {@code brand_safety_score}/{@code garm_flags}/{@code content_sentiment} columns —
 * but only when {@link BrandSafetyScoringProperties#isEnabled()} is {@code true}. The residual
 * concern the deferral really protected against is cost/volume: every scored creator triggers a
 * chunked LLM classification call to influora-ai, so scoring the whole discoverable pool daily is
 * an unbounded, real-money fan-out. The flag honours that concern two ways: (1) OFF by default —
 * with it off, this job's behaviour is byte-for-byte unchanged and those 3 columns stay {@code
 * null} on every row (still valid: they are nullable in the V22 migration); (2) even when ON, at
 * most {@link BrandSafetyScoringProperties#getMaxCreatorsPerRun()} creators per run incur the AI
 * call, prioritising those whose latest brand-safety score is missing or stalest so coverage
 * rotates across the pool over successive runs instead of re-scoring the same head daily.
 *
 * <p>A brand-safety call NEVER breaks the rest of a creator's score: {@code
 * BrandSafetyScoreService.scoreCreator} is contracted to return {@link Optional#empty()} (never
 * throw) on any influora-ai failure, and this job additionally wraps the call in its own try/catch
 * so even an unexpected exception degrades to {@code null} brand-safety columns on an otherwise
 * fully-populated row, rather than losing the fake-follower/quality/rate scores for that creator.
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
    private final BrandSafetyScoreService brandSafetyScoreService;
    private final BrandSafetyScoringProperties brandSafetyScoringProperties;

    public ScoreCalculationJob(
            CreatorProfileRepository creatorProfileRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            MediaMetricsRepository mediaMetricsRepository,
            FakeFollowerDetectionService fakeFollowerDetectionService,
            QualityScoreService qualityScoreService,
            RateEstimationService rateEstimationService,
            CreatorScoreRepository creatorScoreRepository,
            BrandSafetyScoreService brandSafetyScoreService,
            BrandSafetyScoringProperties brandSafetyScoringProperties) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.fakeFollowerDetectionService = fakeFollowerDetectionService;
        this.qualityScoreService = qualityScoreService;
        this.rateEstimationService = rateEstimationService;
        this.creatorScoreRepository = creatorScoreRepository;
        this.brandSafetyScoreService = brandSafetyScoreService;
        this.brandSafetyScoringProperties = brandSafetyScoringProperties;
    }

    /** Daily at 4 AM UTC (spec §3.3). */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @SchedulerLock(name = "ScoreCalculationJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void calculateScores() {
        List<CreatorProfile> creators =
                creatorProfileRepository.findAll(CreatorProfileSpecifications.discoverable());

        // Brand-safety scoring is OFF by default (Priya ruling): only when enabled do we pick up to
        // maxCreatorsPerRun creators (prioritising missing/stalest brand-safety scores) for the
        // costly influora-ai LLM fan-out. Empty set => no creator gets brand-safety scored this run.
        Set<String> brandSafetyTargets = selectBrandSafetyTargets(creators);

        int scored = 0;
        int skipped = 0;
        RunCounters counters = new RunCounters();

        for (CreatorProfile creator : creators) {
            String creatorProfileId = creator.getId();
            try {
                if (scoreOne(creator, brandSafetyTargets.contains(creatorProfileId), counters)) {
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
                "ScoreCalculationJob: completed run — {} scored, {} skipped, {} total discoverable"
                        + " creators; brand-safety: enabled={}, targeted={}, populated={}, degraded={}",
                scored,
                skipped,
                creators.size(),
                brandSafetyScoringProperties.isEnabled(),
                brandSafetyTargets.size(),
                counters.brandSafetyPopulated,
                counters.brandSafetyDegraded);
    }

    /**
     * Picks the creators that will incur the influora-ai brand-safety fan-out this run. Returns an
     * empty set (so nobody gets brand-safety scored) when the feature is off or the cap is
     * non-positive. Otherwise selects up to {@link BrandSafetyScoringProperties#getMaxCreatorsPerRun()}
     * creators, prioritising those whose most recent {@link CreatorScore} has NO brand-safety score
     * yet (never scored) first, then by oldest {@code computedAt} (stalest) — so successive runs
     * rotate coverage across the discoverable pool rather than re-scoring the same head daily.
     *
     * <p>The per-creator "latest score" lookups only happen when the feature is enabled, so the
     * default (off) path adds zero extra queries and leaves the job's behaviour unchanged.
     *
     * <p><b>Materialize-then-sort (Kavya/Kabir QA finding):</b> the priority key is computed ONCE
     * per creator, in a single pass, before sorting. An earlier version instead did {@code
     * byStaleness.sort(Comparator.comparing(this::lastBrandSafetyScoredAt))} — since {@code
     * Comparator.comparing} re-invokes the key extractor on every comparison (and a merge sort over
     * N creators performs O(N log N) comparisons, each one re-extracting the key for both operands),
     * that ran a {@code creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc} DB query
     * per comparison — O(N log N) round-trips across the ENTIRE discoverable pool, even when {@code
     * maxCreatorsPerRun=1}. Precomputing each creator's key exactly once bounds this to one query
     * per creator, period, independent of the sort algorithm's comparison count.
     */
    private Set<String> selectBrandSafetyTargets(List<CreatorProfile> creators) {
        if (!brandSafetyScoringProperties.isEnabled()) {
            return Set.of();
        }
        int cap = brandSafetyScoringProperties.getMaxCreatorsPerRun();
        if (cap <= 0) {
            log.warn(
                    "ScoreCalculationJob: brand-safety scoring enabled but max-creators-per-run={} "
                            + "(<=0) — no creators will be brand-safety scored this run",
                    cap);
            return Set.of();
        }

        // One findFirstByCreatorProfileIdOrderByTimeDesc query per creator, exactly once each —
        // never re-queried during the sort below.
        List<CreatorStaleness> byStaleness = new ArrayList<>(creators.size());
        for (CreatorProfile creator : creators) {
            byStaleness.add(new CreatorStaleness(creator.getId(), lastBrandSafetyScoredAt(creator)));
        }
        // Priority key: creators never brand-safety scored sort first (Instant.EPOCH), then by
        // ascending last brand-safety computedAt (oldest/stalest first). Sorting the precomputed
        // pairs never re-invokes lastBrandSafetyScoredAt.
        byStaleness.sort(Comparator.comparing(CreatorStaleness::lastScoredAt));

        Set<String> targets = new LinkedHashSet<>();
        for (CreatorStaleness entry : byStaleness) {
            if (targets.size() >= cap) {
                break;
            }
            targets.add(entry.creatorProfileId());
        }
        return targets;
    }

    /** Precomputed (creatorProfileId, priority-key) pair — see {@link #selectBrandSafetyTargets}. */
    private record CreatorStaleness(String creatorProfileId, Instant lastScoredAt) {}

    /**
     * Timestamp used to prioritise brand-safety scoring: the {@code computedAt} of the creator's
     * most recent {@link CreatorScore} that actually carries a brand-safety score, or {@link
     * Instant#EPOCH} when they have no score row yet or their latest row's brand-safety column is
     * still {@code null} — so "never brand-safety scored" always sorts ahead of "scored a while ago".
     * Called exactly once per creator by {@link #selectBrandSafetyTargets} (see that method's
     * javadoc) — never call this from inside a {@link Comparator}.
     */
    private Instant lastBrandSafetyScoredAt(CreatorProfile creator) {
        return creatorScoreRepository
                .findFirstByCreatorProfileIdOrderByTimeDesc(creator.getId())
                .filter(s -> s.getBrandSafetyScore() != null)
                .map(CreatorScore::getComputedAt)
                .orElse(Instant.EPOCH);
    }

    /** Mutable per-run tally for the brand-safety leg, folded into the completion log line. */
    private static final class RunCounters {
        private int brandSafetyPopulated;
        private int brandSafetyDegraded;
    }

    /**
     * @return true if a {@link CreatorScore} row was successfully written for this creator.
     * @param runBrandSafety when true, this creator was selected for the influora-ai brand-safety
     *     fan-out this run; when false the 3 brand-safety columns are left {@code null} exactly as
     *     before the flag existed.
     */
    private boolean scoreOne(CreatorProfile creator, boolean runBrandSafety, RunCounters counters) {
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

        CreatorScore.Builder builder =
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
                        .estimatedRateMin(rateEstimation.min())
                        .estimatedRateMax(rateEstimation.max())
                        .rateCurrency(rateEstimation.currency())
                        .rateConfidence(rateEstimation.confidence())
                        .algorithmVersion(ALGORITHM_VERSION)
                        .computedAt(Instant.now());

        // Brand-safety columns stay null unless (a) the feature is on AND (b) this creator was one
        // of the capped targets this run. A NULL row means "unscored", never "scored safe".
        if (runBrandSafety) {
            populateBrandSafety(builder, creatorProfileId, recentMedia, counters);
        }

        creatorScoreRepository.save(builder.build());
        return true;
    }

    /**
     * Runs the influora-ai brand-safety classification for one creator and folds the result into
     * the score {@link CreatorScore.Builder}. {@code BrandSafetyScoreService.scoreCreator} is
     * contracted never to throw (it returns {@link Optional#empty()} on any influora-ai failure or
     * missing-connection case), but this call is still wrapped in its own try/catch so that even an
     * unexpected exception degrades ONLY the 3 brand-safety columns to {@code null} — the creator's
     * fake-follower/quality/rate scores on the same row are never lost. A {@code null} brand-safety
     * column always means "unscored", never "scored safe".
     */
    private void populateBrandSafety(
            CreatorScore.Builder builder,
            String creatorProfileId,
            List<MediaMetric> recentMedia,
            RunCounters counters) {
        try {
            Optional<BrandSafetyResult> result =
                    brandSafetyScoreService.scoreCreator(creatorProfileId, recentMedia);
            if (result.isPresent()) {
                BrandSafetyResult bs = result.get();
                builder.brandSafetyScore(bs.brandSafetyScore())
                        .garmFlagsJson(bs.garmFlagsJson())
                        .contentSentiment(bs.contentSentiment());
                counters.brandSafetyPopulated++;
            } else {
                // Unreachable-service / no-connection / partial-data fail-safe: leave columns null.
                counters.brandSafetyDegraded++;
            }
        } catch (Exception e) {
            // scoreCreator is documented never to throw; defend the rest of the row anyway.
            counters.brandSafetyDegraded++;
            log.warn(
                    "ScoreCalculationJob: brand-safety scoring failed unexpectedly for creator {} —"
                            + " persisting other scores with null brand-safety columns",
                    creatorProfileId,
                    e);
        }
    }
}
