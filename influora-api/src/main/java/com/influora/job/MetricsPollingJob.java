package com.influora.job;

import com.influora.common.Ulids;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.InstagramMetricsFetcher;
import com.influora.integration.meta.service.MediaMetricMapper;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Polls Instagram metrics for every creator with a valid (non-revoked, non-expired) Meta token and
 * writes {@code creator_metrics} rows (Phase 2 Data Pipeline, VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md
 * §3.1).
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Writes go
 * through {@link CreatorMetricsRepository} only (the storage-abstraction seam), never raw SQL —
 * this is what lets a TimescaleDB-backed implementation swap in later without touching this class.
 *
 * <p>Iterates {@link MetaOAuthToken} rows (not {@code creator_profiles} directly) because the token
 * row is what actually carries the (workspaceId, creatorProfileId) pairing established during
 * Phase 1 OAuth — see {@code MetaTokenStorage}. This job runs system-wide across all workspaces by
 * design (same as {@code MetaTokenRefreshService}'s expiring-soon sweep); it does not need
 * per-request workspace scoping since it is not serving a single tenant's request.
 *
 * <p><b>Per-post media insights (spec §3.1's inner loop) are now wired — F-0479.</b> They were
 * deferred for a long time on the grounds that the mapping from Meta's insight metric-name/value
 * shape ({@link com.influora.integration.meta.dto.InstagramInsightsResponse}) to {@code MediaMetric}
 * fields "wasn't specced with enough precision", particularly which media types support which
 * metrics. That reasoning is resolved in {@link
 * com.influora.integration.meta.service.MediaMetricMapper}: Meta omits what it does not support and
 * the fetcher degrades its 400s to a null insights response, so unsupported metrics simply stay
 * {@code null} — no media-type compatibility table is required, and building one would have been
 * the wrong shape.
 *
 * <p>While it stayed deferred, {@code media_metrics} had a reader ({@code ScoreCalculationJob},
 * {@code BrandSafetyScoreService}) and no writer, so every creator scored off an empty list — see
 * F-0478 for what that produced. The fetch is delegated to {@link
 * com.influora.integration.meta.service.InstagramMetricsFetcher}; this job owns only the
 * persistence, and it is gated by {@code influora.meta.media-metrics-enabled} because it costs
 * roughly {@code 1 + RECENT_MEDIA_LIMIT} extra Graph calls per creator per cycle.
 */
@Component
public class MetricsPollingJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsPollingJob.class);
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    // CR-119 — points at the canonical constant instead of re-declaring the literal; the same
    // string now decides PlatformStat.verified via CreatorMetric#isPlatformVerified(), so a
    // drifted local copy would mislabel real Meta data as creator-reported.
    private static final String DATA_SOURCE_META_API = CreatorMetric.DATA_SOURCE_META_API;
    // Spec §3.1's recent-media fetch limit. F-0479 — this is now live rather than reserved for a
    // follow-up: it caps both the Graph spend per creator per cycle and the number of media_metrics
    // rows one poll appends. ScoreCalculationJob reads back the same count, so raising it here
    // without raising its RECENT_MEDIA_LIMIT just writes rows nothing scores over.
    private static final int RECENT_MEDIA_LIMIT = 25;

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MetaRateLimitTracker rateLimitTracker;
    private final AuditLogService auditLog;
    // F-0479. The fetch half is delegated rather than re-implemented: InstagramMetricsFetcher
    // already owns the media-list + per-item insights orchestration and its degradation rules, and
    // is tested. This job keeps the persistence half, which is what its class javadoc says it owns.
    private final InstagramMetricsFetcher metricsFetcher;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final MetaApiProperties metaProperties;

    /** In-memory overlap guard — matches the per-instance style already used by MetaRateLimitTracker. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MetricsPollingJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            CreatorMetricsRepository creatorMetricsRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog,
            InstagramMetricsFetcher metricsFetcher,
            MediaMetricsRepository mediaMetricsRepository,
            MetaApiProperties metaProperties) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.auditLog = auditLog;
        this.metricsFetcher = metricsFetcher;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.metaProperties = metaProperties;
    }

    /**
     * Every 6 hours UTC-aligned (spec §3.1) — rate-limit safe cadence for Meta's per-account usage
     * windows.
     */
    @Scheduled(cron = "0 0 */6 * * *", zone = "UTC")
    @SchedulerLock(name = "MetricsPollingJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void pollMetrics() {
        if (!running.compareAndSet(false, true)) {
            log.warn("MetricsPollingJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runPoll();
        } finally {
            running.set(false);
        }
    }

    private void runPoll() {
        List<MetaOAuthToken> connectedTokens =
                tokenRepository.findByRevokedFalseAndExpiresAtAfter(Instant.now());

        int polled = 0;
        int failed = 0;

        for (MetaOAuthToken tokenRow : connectedTokens) {
            String creatorProfileId = tokenRow.getCreatorProfileId();
            String igBusinessAccountId = tokenRow.getIgBusinessAccountId();

            try {
                if (pollOne(creatorProfileId, igBusinessAccountId)) {
                    polled++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // Defensive catch-all so one creator's unexpected failure never aborts the batch.
                failed++;
                log.error(
                        "MetricsPollingJob: unexpected failure polling creator {}",
                        creatorProfileId,
                        e);
            }
        }

        auditLog.recordToolCall(
                null,
                "METRICS_POLLING_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("creatorsPolled", polled, "creatorsFailed", failed, "totalTokens", connectedTokens.size()));
    }

    /**
     * F-0954 — polls ONE creator as soon as their Meta connect commits, so /creator/analytics
     * is not empty ("No metrics yet") for up to 6 hours while Settings already shows their
     * follower count. The 6-hourly cron stays the guarantee; this is only a head start.
     *
     * <p>{@code AFTER_COMMIT}: {@code CreatorMetaOAuthService.connect} is {@code @Transactional},
     * so a plain {@code @EventListener} on another thread can run before the token row is
     * committed, find nothing, and skip. {@code @Async}: the connect response never waits on a
     * Graph round trip. It reuses {@link #pollOne}, so rate limiting, auth path and error
     * handling are exactly the cron's. <b>Never throws</b> — a failure here must not surface
     * anywhere; the creator simply waits for the next cron run, as before.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreatorConnected(CreatorMetaConnectedEvent event) {
        String creatorProfileId = event.creatorProfileId();
        try {
            Optional<MetaOAuthToken> token =
                    tokenRepository
                            .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                            .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(Instant.now()));
            if (token.isEmpty()) {
                log.info(
                        "MetricsPollingJob: connect-triggered poll for creator {} found no live token;"
                                + " leaving it to the scheduled run",
                        creatorProfileId);
                return;
            }
            boolean written = pollOne(creatorProfileId, token.get().getIgBusinessAccountId());
            log.info(
                    "MetricsPollingJob: connect-triggered poll for creator {} — metric row written: {}",
                    creatorProfileId,
                    written);
        } catch (Exception e) {
            // Deliberately swallowed — see the javadoc. The cron is the guarantee.
            log.warn(
                    "MetricsPollingJob: connect-triggered poll failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
        }
    }

    /** @return true if a metric row was successfully written for this creator. */
    private boolean pollOne(String creatorProfileId, String igBusinessAccountId) {
        // CR-99/F-0113 fix: Meta's Graph API requires the numeric IG Business Account ID in the
        // request path, not our internal ULID creatorProfileId — a token row with none on file
        // (pre-dating the field, or an incomplete OAuth exchange) cannot be polled at all.
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            log.warn(
                    "MetricsPollingJob: no igBusinessAccountId on file for creator {}, skipping",
                    creatorProfileId);
            return false;
        }

        // F-0166 follow-up (Priya review): this called the WORKSPACE-scoped getValidToken with
        // `workspaceId`, which is always null for a creator-owned row. The repository method
        // backing it carries an explicit `workspaceId IS NOT NULL` predicate (CR-111 hardening),
        // so a null workspaceId can never match ANY row — this could not have found a token for
        // a single creator, independent of the igBusinessAccountId check above it.
        Optional<String> token = tokenStorage.getValidCreatorToken(creatorProfileId);
        if (token.isEmpty()) {
            log.warn("MetricsPollingJob: no valid token for creator {}, skipping", creatorProfileId);
            return false;
        }

        // Pre-flight rate-limit guard mirrors MetaGraphApiClient's own check — belt-and-braces so a
        // creator already known to be near-limited doesn't spend budget on the profile call before
        // the media call would have tripped it anyway.
        // F-0126: keyed on creatorProfileId (internal ULID) while MetaGraphApiClient's own
        // enforcement and update (usage-header parsing) key on igBusinessAccountId — two disjoint
        // namespaces for the same account meant this pre-flight guard's getCurrentUsage() could
        // never see usage the client itself had recorded, so it always read 0 and never tripped.
        int usage = rateLimitTracker.getCurrentUsage(igBusinessAccountId);
        if (usage >= 90) {
            log.warn(
                    "MetricsPollingJob: creator {} at {}% Meta rate-limit usage, deferring to next cycle",
                    creatorProfileId,
                    usage);
            return false;
        }

        // T-IGLOGIN-0820: host follows the token, never a default. Hoisted out of the getProfile
        // call because the media/insights fetch below must use the SAME auth path — resolving it
        // twice invites the two calls drifting apart.
        MetaAuthPath authPath =
                tokenStorage.getCreatorAuthPath(creatorProfileId).orElse(MetaAuthPath.FACEBOOK_LOGIN);

        try {
            InstagramUserResponse profile =
                    instagramClient.getProfile(igBusinessAccountId, token.get(), authPath);

            // F-0479/F-0506 — media_metrics has a writer (pollRecentMedia) and CreatorMetric's
            // three avg* columns (avgReachPerPost, avgImpressionsPerPost, avgEngagementRate) are
            // now aggregated from THIS SAME poll's per-post rows, rather than left permanently
            // null. Fetched BEFORE the CreatorMetric row is built (not after, as media persistence
            // used to run) so the averages can be included in the same immutable snapshot as the
            // follower count they describe — CreatorMetric rows have no update/mutator method by
            // design (see class javadoc), so there is no way to retrofit averages onto a row
            // already saved. This still preserves the original fault-tolerance guarantee:
            // pollRecentMedia never throws (its own try/catch swallows everything and returns an
            // empty list), so a media/insights failure still can never prevent the profile
            // snapshot below from being built and saved — it just means the averages are null,
            // same as "no media metrics polled" already meant before this change.
            List<MediaMetric> mediaRows =
                    pollRecentMedia(creatorProfileId, igBusinessAccountId, token.get(), authPath);

            CreatorMetric metric =
                    CreatorMetric.builder()
                            .id(Ulids.newUlid())
                            .time(Instant.now())
                            .creatorProfileId(creatorProfileId)
                            .platform(PLATFORM_INSTAGRAM)
                            // CR-116 — was fetched into `profile` and then discarded; now flows
                            // through to platform_stats.handle via PlatformStatsAggregationJob.
                            .username(profile.username())
                            .followers(profile.followersCount() == null ? 0L : profile.followersCount())
                            .following(profile.followsCount())
                            .mediaCount(
                                    profile.mediaCount() == null ? null : profile.mediaCount().intValue())
                            .avgReachPerPost(averageOf(mediaRows, MediaMetric::getReach))
                            .avgImpressionsPerPost(averageOf(mediaRows, MediaMetric::getImpressions))
                            // Same aggregation, same null-not-zero contract, from the same poll's
                            // per-post rows — these two are surfaced to brands on
                            // PlatformStatResponse alongside reach and views.
                            .avgLikesPerPost(averageOf(mediaRows, MediaMetric::getLikes))
                            .avgCommentsPerPost(averageOf(mediaRows, MediaMetric::getComments))
                            .avgEngagementRate(
                                    averageEngagementRate(mediaRows, profile.followersCount()))
                            .dataSource(DATA_SOURCE_META_API)
                            .fetchedAt(Instant.now())
                            .build();

            creatorMetricsRepository.save(metric);

            return true;
        } catch (MetaRateLimitException e) {
            // F-0126: markLimited must use the same key as getCurrentUsage() above (and the same
            // key MetaGraphApiClient itself uses) — igBusinessAccountId, not creatorProfileId.
            rateLimitTracker.markLimited(igBusinessAccountId);
            log.warn("MetricsPollingJob: rate limited for creator {}, will retry next cycle", creatorProfileId);
            return false;
        } catch (MetaTokenExpiredException e) {
            log.warn("MetricsPollingJob: token expired for creator {}, needs re-auth", creatorProfileId);
            return false;
        } catch (MetaApiException e) {
            log.error("MetricsPollingJob: Meta API error polling creator {}: {}", creatorProfileId, e.getMessage());
            return false;
        }
    }

    /**
     * F-0479 — fetches this creator's recent media with per-post insights and writes one immutable
     * {@code media_metrics} row per post (spec §3.1's inner loop).
     *
     * <p>Never throws. {@code InstagramMetricsFetcher} already degrades a rate-limited or
     * unsupported per-item insights call to {@code insights == null} rather than failing the batch,
     * and this method additionally swallows anything it did not anticipate — the caller builds the
     * creator's profile snapshot from this method's return value and must not lose that snapshot
     * over a media/insights failure, so this always returns (never throws), an empty list meaning
     * "nothing polled this cycle" in every failure/disabled/no-posts case alike.
     *
     * <p>Rows are immutable snapshots (one per poll per post, per the {@code MediaMetric} javadoc),
     * so this appends rather than upserting. {@code ScoreCalculationJob} reads the newest
     * {@code RECENT_MEDIA_LIMIT} rows, which is exactly one poll's worth.
     *
     * @return the rows written this cycle (possibly empty), used by {@link #pollOne} to compute
     *     {@code CreatorMetric}'s {@code avgReachPerPost}/{@code avgImpressionsPerPost}/{@code
     *     avgEngagementRate} (F-0506) from the SAME per-post snapshot the caller is about to save.
     */
    private List<MediaMetric> pollRecentMedia(
            String creatorProfileId, String igBusinessAccountId, String token, MetaAuthPath authPath) {
        if (!metaProperties.isMediaMetricsEnabled()) {
            log.debug(
                    "MetricsPollingJob: media_metrics disabled (influora.meta.media-metrics-enabled=false),"
                            + " skipping per-post poll for creator {}",
                    creatorProfileId);
            return List.of();
        }

        try {
            List<InstagramMetricsFetcher.MediaWithInsights> media =
                    metricsFetcher.fetchMediaWithInsights(
                            igBusinessAccountId, token, RECENT_MEDIA_LIMIT, authPath);

            if (media.isEmpty()) {
                // Either the creator has posted nothing, or the fetcher declined on rate limit. Both
                // legitimately produce no rows; writing a placeholder would be F-0478 all over again.
                log.debug("MetricsPollingJob: no media returned for creator {}", creatorProfileId);
                return List.of();
            }

            Instant fetchedAt = Instant.now();
            List<MediaMetric> rows = new ArrayList<>(media.size());
            int degraded = 0;
            for (InstagramMetricsFetcher.MediaWithInsights item : media) {
                if (item.insights() == null) {
                    degraded++;
                }
                rows.add(
                        MediaMetricMapper.toMediaMetric(
                                creatorProfileId,
                                PLATFORM_INSTAGRAM,
                                item.mediaItem(),
                                item.insights(),
                                fetchedAt));
            }

            mediaMetricsRepository.saveAll(rows);
            log.info(
                    "MetricsPollingJob: wrote {} media_metrics row(s) for creator {} ({} without insights)",
                    rows.size(),
                    creatorProfileId,
                    degraded);
            return rows;
        } catch (Exception e) {
            // Includes anything the fetcher did not already absorb. The creator's CreatorMetric row
            // is built from this method's return value, not lost by it: an empty list here just
            // means null averages, the same outcome as "no media metrics polled" already produced.
            log.error(
                    "MetricsPollingJob: media_metrics poll failed for creator {} (profile snapshot kept)",
                    creatorProfileId,
                    e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------------------------------
    // F-0506 (dead-metric repair, T-DEADMETRIC-REPAIR-0915) — CreatorMetric.avgReachPerPost /
    // avgImpressionsPerPost / avgEngagementRate. GROUP 1 of that ticket: MediaMetric already stores
    // real per-post reach/impressions/engagement (MediaMetricMapper.java:84-86), and this job is the
    // one writer of those rows (pollRecentMedia above), so these three averages are computed HERE,
    // from THIS poll's per-post rows, rather than at read time or in a separate scheduled job.
    //
    // WHY HERE, not a scheduled aggregation job or on-read: this job is the only place that already
    // holds "this creator's freshest per-post snapshot" in memory in the same transaction as the
    // CreatorMetric row that reports it — computing it here keeps followers/mediaCount (this poll)
    // and avgReachPerPost/avgImpressionsPerPost/avgEngagementRate (this poll's own posts)
    // temporally consistent as ONE snapshot, matching CreatorMetric's documented append-only,
    // one-row-per-poll semantics. A separate scheduled aggregation job would have to re-derive which
    // MediaMetric rows belong to "the latest poll" per creator (there is no explicit poll-id FK) and
    // would run on its own cadence, decoupled from and possibly stale against the CreatorMetric row
    // it annotates. Computing on read (in AnalyticsService, per API call) would recompute the same
    // aggregate on every request instead of once per 6-hour poll, and would face the same "which
    // MediaMetric rows count as this creator's latest batch" ambiguity with no poll-id to key on.
    //
    // DEFINITIONS (each stated explicitly per the ticket's instruction — these choices are visible
    // to brands and creators, so silence here would be its own defect):
    //   avgReachPerPost        = mean of MediaMetric.reach across this poll's fetched posts, over
    //                            only the posts where Meta actually reported reach (never treating
    //                            an absent metric as zero — same "absence is not zero" contract
    //                            InstagramInsightValues documents). Null when no post in this poll
    //                            has a reach value (nothing to average, not zero).
    //   avgImpressionsPerPost  = same, over MediaMetric.impressions (Meta's "views" metric, per
    //                            MediaMetricMapper's mapping comment).
    //   avgEngagementRate      = (T-ENGAGEMENT-DENOMINATOR-0917, Swapnil ruling 2026-09-17, wiki/
    //                            tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §7) mean, across posts
    //                            with at least one of likes/comments present, of (likes + comments)
    //                            per post, divided by FOLLOWERS (this poll's profile snapshot) x
    //                            100. This matches QualityScoreService.calculateEngagementRate and
    //                            FakeFollowerDetectionService's private method of the same name —
    //                            the scale RateEstimationService's +30%/-30% thresholds (lines
    //                            ~92-110) were built against. The previous version of this comment
    //                            claimed the codebase had "already picked" engagement/reach; that
    //                            was wrong — REACH ran several times higher than the follower
    //                            scale and pushed most creators into the +30% band the moment
    //                            e71938c started writing this field (previously always null, so
    //                            the multiplier sat neutral).
    //
    //                            Two deliberate differences from QualityScoreService, both because
    //                            this value is STORED and read later (QualityScoreService computes
    //                            it fresh, in-request, and returns 0 for these same cases):
    //                              1. followers null or 0 -> NULL, never 0. A stored 0 would trip
    //                                 RateEstimationService's `< 1` branch and apply the -30%
    //                                 penalty to a creator we simply have no follower reading for;
    //                                 null leaves that multiplier at neutral 1.0 (confirmed at
    //                                 RateEstimationService.java ~104-110 — avgEngagement == null
    //                                 skips the branch entirely, per the F-0749 comment there).
    //                              2. no contributing posts -> NULL, never 0, for the same reason.
    //                            A post with BOTH likes and comments absent is excluded from the
    //                            mean entirely (no data is not zero); a post with only one of the
    //                            two absent counts the missing one as 0 (mirrors nullToZero in the
    //                            two services above).
    //
    //                            Overflow: the column is DECIMAL(8,4) (V21__creator_metrics.sql),
    //                            max magnitude 9999.9999. Negative likes/comments are treated as
    //                            absent, so the rate is always >= 0; the worst case is a
    //                            small/new follower count with a viral post (e.g. followers=1,
    //                            likes+comments in the thousands), which is arithmetically
    //                            unbounded and CAN exceed the column. NOT clamped — a follower
    //                            base too small to divide by meaningfully is the same defect class
    //                            as followers=0 above: clamping to 9999.9999 would hand
    //                            RateEstimationService's `> 5` branch its maximum +30% multiplier
    //                            for a number that carries no signal, exactly the over-reward the
    //                            ruling exists to prevent. Returns null instead, same as the
    //                            null-followers/no-posts cases, so the multiplier stays neutral.
    // ------------------------------------------------------------------------------------------

    /** Package-private for direct unit testing (see MetricsPollingJobTest). */
    static Long averageOf(
            List<MediaMetric> media, java.util.function.Function<MediaMetric, Long> extractor) {
        List<Long> present = media.stream().map(extractor).filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        long sum = 0L;
        for (Long value : present) {
            sum += value;
        }
        return Math.round((double) sum / present.size());
    }

    /**
     * DECIMAL(8,4) ceiling from V21__creator_metrics.sql. A computed rate above this returns null
     * rather than being clamped to it — see the overflow note above.
     */
    private static final BigDecimal MAX_ENGAGEMENT_RATE = new BigDecimal("9999.9999");

    /**
     * Package-private for direct unit testing (see MetricsPollingJobTest).
     *
     * @param followers this poll's profile-snapshot follower count — the same reading {@code
     *     pollOne} writes onto this CreatorMetric row's {@code followers} column, passed through
     *     as the raw nullable {@code InstagramUserResponse.followersCount()} (not the 0-defaulted
     *     value the row itself stores). Null and 0 both return null here — see the overflow/null
     *     comment block above this method.
     */
    static BigDecimal averageEngagementRate(List<MediaMetric> media, Long followers) {
        if (followers == null || followers <= 0) {
            return null;
        }
        long sumLikesPlusComments = 0L;
        int contributingPosts = 0;
        for (MediaMetric m : media) {
            // A negative count is not a reading; treat it as absent rather than let it push the
            // rate into RateEstimationService's -30% band.
            Long likes = nonNegativeOrNull(m.getLikes());
            Long comments = nonNegativeOrNull(m.getComments());
            if (likes == null && comments == null) {
                continue; // no data at all for this post — excluded, not counted as 0
            }
            try {
                sumLikesPlusComments =
                        Math.addExact(
                                sumLikesPlusComments,
                                Math.addExact(nullToZero(likes), nullToZero(comments)));
            } catch (ArithmeticException overflow) {
                return null; // a wrapped sum would read as a small, valid-looking rate
            }
            contributingPosts++;
        }
        if (contributingPosts == 0) {
            return null;
        }
        BigDecimal meanEngagement =
                BigDecimal.valueOf(sumLikesPlusComments)
                        .divide(BigDecimal.valueOf(contributingPosts), 10, RoundingMode.HALF_UP);
        BigDecimal rate =
                meanEngagement
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(followers), 4, RoundingMode.HALF_UP);
        if (rate.compareTo(MAX_ENGAGEMENT_RATE) > 0) {
            // Follower base too small to divide by meaningfully — same "no real reading" case as
            // followers null/0, not a number to clamp and hand to RateEstimationService's +30%
            // branch. See the overflow note in the DEFINITIONS block above.
            return null;
        }
        return rate;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Long nonNegativeOrNull(Long value) {
        return value == null || value < 0 ? null : value;
    }
}
