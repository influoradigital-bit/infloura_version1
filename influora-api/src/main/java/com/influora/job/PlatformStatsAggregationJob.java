package com.influora.job;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorProfileSpecifications;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * H-10 fix — the discovery-ranking substrate ({@code platform_stats} and {@code
 * creator_profiles.total_followers}/{@code engagement_rate}) had no production writer anywhere in
 * the codebase: no setters on either entity, no {@code save} call site. {@code MetricsPollingJob}
 * writes {@code creator_metrics} (the raw per-poll snapshot) but never rolled it up, so every real
 * creator showed 0 followers and failed every {@code minFollowers} discovery filter — only V7's
 * seeded fake creators (which write {@code platform_stats} directly in the seed script) ever
 * ranked.
 *
 * <p>This job closes that gap: for every discoverable creator, it reads the latest {@code
 * creator_metrics} row per platform (written by {@link MetricsPollingJob}) and upserts the
 * corresponding {@code platform_stats} row, then denormalizes the summed/most-recent values onto
 * {@link CreatorProfile#applyAggregatedStats}. Never fabricates data — a creator with no metrics
 * yet is skipped, not zero-filled (a zero-filled row would be indistinguishable from "confirmed
 * zero followers" and would incorrectly pass a {@code minFollowers=0} filter while still being
 * fabricated).
 *
 * <p><b>Only INSTAGRAM today</b> — matches {@code MetricsPollingJob}, the only platform currently
 * polled. When a second platform's polling job exists, add its platform constant to {@link
 * #SUPPORTED_PLATFORMS} — the per-platform loop and the cross-platform sum already generalize.
 *
 * <p><b>Engagement rate</b> is only written when {@code CreatorMetric.avgEngagementRate} is
 * non-null. {@code MetricsPollingJob} does not populate that field yet (see its class javadoc's
 * {@code media_metrics} TODO) — until it does, {@code platform_stats.engagement_rate} for
 * Instagram will legitimately stay at its existing/default value rather than being overwritten
 * with a fabricated number.
 *
 * <p>Same resilience/overlap-guard conventions as {@code MetricsPollingJob}/{@code
 * ScoreCalculationJob}: per-creator try/catch isolation, an {@link AtomicBoolean} single-instance
 * overlap guard (H-13's ShedLock note applies here too — see that job's own follow-up).
 */
@Component
public class PlatformStatsAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(PlatformStatsAggregationJob.class);

    private static final List<String> SUPPORTED_PLATFORMS = List.of("INSTAGRAM");

    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final PlatformStatRepository platformStatRepository;
    private final AuditLogService auditLog;

    /** In-memory overlap guard — same per-instance style as MetricsPollingJob/ScoreCalculationJob. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PlatformStatsAggregationJob(
            CreatorProfileRepository creatorProfileRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            PlatformStatRepository platformStatRepository,
            AuditLogService auditLog) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.platformStatRepository = platformStatRepository;
        this.auditLog = auditLog;
    }

    /**
     * Daily at 3:45 AM — after {@code MetricsPollingJob}'s most recent 6h cycle, before {@code
     * ScoreCalculationJob}'s 4 AM run so a freshly-onboarded creator's very first poll is reflected
     * in discovery ranking the same day.
     */
    @Scheduled(cron = "0 45 3 * * *")
    @SchedulerLock(name = "PlatformStatsAggregationJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void aggregate() {
        if (!running.compareAndSet(false, true)) {
            log.warn("PlatformStatsAggregationJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runAggregate();
        } finally {
            running.set(false);
        }
    }

    /** M-17: page size for the discoverable-creator scan below — bounds this job to one page of
     * full {@link CreatorProfile} entities in memory at a time instead of the entire discoverable
     * pool (previously an unbounded {@code findAll(Specification)}). */
    private static final int PAGE_SIZE = 500;

    private void runAggregate() {
        int aggregated = 0;
        int skipped = 0;
        long totalDiscoverable = 0;

        int pageNumber = 0;
        Page<CreatorProfile> page;
        do {
            page =
                    creatorProfileRepository.findAll(
                            CreatorProfileSpecifications.discoverable(), PageRequest.of(pageNumber, PAGE_SIZE));
            for (CreatorProfile creator : page.getContent()) {
                try {
                    if (aggregateOne(creator)) {
                        aggregated++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // Defensive catch-all so one creator's unexpected failure never aborts the
                    // batch — same resilience pattern as MetricsPollingJob/ScoreCalculationJob.
                    skipped++;
                    log.error(
                            "PlatformStatsAggregationJob: unexpected failure aggregating creator {}",
                            creator.getId(),
                            e);
                }
            }
            totalDiscoverable += page.getNumberOfElements();
            pageNumber++;
        } while (page.hasNext());

        auditLog.recordToolCall(
                null,
                "PLATFORM_STATS_AGGREGATION_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("creatorsAggregated", aggregated, "creatorsSkipped", skipped, "totalDiscoverable", totalDiscoverable));
    }

    /** @return true if at least one platform_stats row was upserted for this creator. */
    @Transactional
    boolean aggregateOne(CreatorProfile creator) {
        String creatorProfileId = creator.getId();
        long totalFollowers = 0L;
        BigDecimal latestEngagementRate = null;
        boolean wroteAny = false;

        for (String platform : SUPPORTED_PLATFORMS) {
            Optional<CreatorMetric> latest =
                    creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                            creatorProfileId, platform);
            if (latest.isEmpty()) {
                continue;
            }

            CreatorMetric metric = latest.get();
            upsertPlatformStat(creatorProfileId, platform, metric);
            wroteAny = true;
            totalFollowers += metric.getFollowers();
            if (metric.getAvgEngagementRate() != null) {
                latestEngagementRate = metric.getAvgEngagementRate();
            }
        }

        if (!wroteAny) {
            log.info(
                    "PlatformStatsAggregationJob: no creator_metrics yet for creator {}, skipping"
                            + " (never fabricating a 0-follower row)",
                    creatorProfileId);
            return false;
        }

        creator.applyAggregatedStats(totalFollowers, latestEngagementRate);
        creatorProfileRepository.save(creator);
        return true;
    }

    private void upsertPlatformStat(String creatorProfileId, String platform, CreatorMetric metric) {
        Optional<PlatformStat> existing =
                platformStatRepository.findByCreatorProfileIdAndPlatform(creatorProfileId, platform);

        if (existing.isPresent()) {
            // CR-116 — only overwrite an existing handle when this snapshot actually carries one;
            // a poll that legitimately returned no username (Meta omits it) must not clobber a
            // previously-recorded one with null.
            String handle = metric.getUsername() != null ? metric.getUsername() : existing.get().getHandle();
            existing.get()
                    .applySnapshot(metric.getFollowers(), metric.getAvgEngagementRate(), existing.get().isVerified(), handle);
            platformStatRepository.save(existing.get());
        } else {
            PlatformStat created =
                    PlatformStat.builder()
                            .id(Ulids.newUlid())
                            .creatorProfileId(creatorProfileId)
                            .platform(platform)
                            .handle(metric.getUsername())
                            .followers(metric.getFollowers())
                            .engagementRate(metric.getAvgEngagementRate())
                            .verified(false)
                            .build();
            platformStatRepository.save(created);
        }
    }
}
