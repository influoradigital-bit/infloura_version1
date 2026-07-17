package com.influora.job;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p><b>Per-post media insights (spec §3.1's inner loop over {@code InstagramInsightsClient
 * .getMediaInsights}) are intentionally NOT wired up yet</b> — TODO, tracked below — because {@code
 * media_metrics} persistence needs a mapping from Meta's insight metric-name/value shape
 * ({@link com.influora.integration.meta.dto.InstagramInsightsResponse}) to {@code MediaMetric}
 * fields, and that mapping wasn't specced with enough precision (e.g. which Instagram media types
 * support which metrics — the client's own javadoc notes Meta 400s on unsupported metric/type
 * combinations) to hardcode confidently without over-scoping this pass. The storage layer
 * ({@code MediaMetricsRepository}/{@code MediaMetric}) is complete and ready for that follow-up.
 */
@Component
public class MetricsPollingJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsPollingJob.class);
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    private static final String DATA_SOURCE_META_API = "META_API";
    // Spec §3.1's recent-media fetch limit — currently referenced only in the class javadoc TODO
    // for the not-yet-implemented media_metrics polling; kept here so that follow-up work has the
    // agreed cap in one place rather than re-deriving it from the spec.
    private static final int RECENT_MEDIA_LIMIT = 25;

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MetaRateLimitTracker rateLimitTracker;
    private final AuditLogService auditLog;

    /** In-memory overlap guard — matches the per-instance style already used by MetaRateLimitTracker. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MetricsPollingJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            CreatorMetricsRepository creatorMetricsRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.auditLog = auditLog;
    }

    /** Every 6 hours (spec §3.1) — rate-limit safe cadence for Meta's per-account usage windows. */
    @Scheduled(cron = "0 0 */6 * * *")
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
            String workspaceId = tokenRow.getWorkspaceId();
            String creatorProfileId = tokenRow.getCreatorProfileId();

            try {
                if (pollOne(workspaceId, creatorProfileId)) {
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

    /** @return true if a metric row was successfully written for this creator. */
    private boolean pollOne(String workspaceId, String creatorProfileId) {
        Optional<String> token = tokenStorage.getValidToken(workspaceId, creatorProfileId);
        if (token.isEmpty()) {
            log.warn("MetricsPollingJob: no valid token for creator {}, skipping", creatorProfileId);
            return false;
        }

        // Pre-flight rate-limit guard mirrors MetaGraphApiClient's own check — belt-and-braces so a
        // creator already known to be near-limited doesn't spend budget on the profile call before
        // the media call would have tripped it anyway.
        int usage = rateLimitTracker.getCurrentUsage(creatorProfileId);
        if (usage >= 90) {
            log.warn(
                    "MetricsPollingJob: creator {} at {}% Meta rate-limit usage, deferring to next cycle",
                    creatorProfileId,
                    usage);
            return false;
        }

        try {
            InstagramUserResponse profile = instagramClient.getProfile(creatorProfileId, token.get());

            CreatorMetric metric =
                    CreatorMetric.builder()
                            .id(Ulids.newUlid())
                            .time(Instant.now())
                            .creatorProfileId(creatorProfileId)
                            .platform(PLATFORM_INSTAGRAM)
                            .followers(profile.followersCount() == null ? 0L : profile.followersCount())
                            .following(profile.followsCount())
                            .mediaCount(
                                    profile.mediaCount() == null ? null : profile.mediaCount().intValue())
                            .dataSource(DATA_SOURCE_META_API)
                            .fetchedAt(Instant.now())
                            .build();

            creatorMetricsRepository.save(metric);

            // TODO(media_metrics): spec §3.1 also fetches recent media (limit RECENT_MEDIA_LIMIT)
            // and per-post insights here, mapping InstagramInsightsResponse -> MediaMetric and
            // saving via MediaMetricsRepository. Deferred — see class javadoc for why (the metric
            // name/value -> field mapping, and which metrics apply to which media_type, wasn't
            // specced with enough precision to hardcode confidently). Intentionally not fetching
            // the media list either, since that would burn rate-limit budget for data we don't yet
            // persist. The storage layer (MediaMetric/MediaMetricsRepository) is ready for this.

            return true;
        } catch (MetaRateLimitException e) {
            rateLimitTracker.markLimited(creatorProfileId);
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
}
