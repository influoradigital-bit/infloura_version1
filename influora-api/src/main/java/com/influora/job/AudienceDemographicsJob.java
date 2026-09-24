package com.influora.job;

import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.AudienceDemographics;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.AudienceBreakdowns;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Weekly fetch of Meta follower demographics (age/gender, country and city breakdowns) for every
 * creator with a valid (non-revoked, non-expired) Meta token, plus one fetch right after a creator
 * connects Instagram ({@link #onCreatorConnected}) — Wave B task
 * B4 (wiki/tech/REMAINING_WORK_PLAN.md). Writes {@code audience_demographics} rows (V25).
 *
 * <p>Mirrors {@link MetricsPollingJob}/{@link MetaTokenRefreshService}'s exact conventions: a
 * single-threaded scheduled sweep over {@link MetaOAuthTokenRepository}, an {@link AtomicBoolean}
 * overlap guard, per-creator try/catch isolation so one creator's failure never aborts the batch,
 * and a pre-flight {@link MetaRateLimitTracker} check before spending budget on the call.
 *
 * <p>Weekly cadence (not the 6h {@code MetricsPollingJob} cadence): demographic breakdowns shift
 * slowly compared to engagement metrics, and Meta's {@code follower_demographics} metric is a
 * {@code period=lifetime} snapshot recomputed server-side rather than a per-interaction counter, so
 * polling more often buys nothing.
 *
 * <p>Iterates {@link MetaOAuthToken} rows, not {@code creator_profiles} directly — same reasoning
 * as {@code MetricsPollingJob}: the token row carries the (workspaceId, creatorProfileId) pairing,
 * and this job runs system-wide across all workspaces, not per-request, so {@code
 * MetricsAuthorizationService} does not apply here (see that service's javadoc, "Not wired into
 * MetricsPollingJob").
 *
 * <p><b>Graceful degradation, not fabrication:</b> Meta's audience insights are only available for
 * accounts with 100+ followers ({@link InstagramInsightsClient#getAudienceDemographics} javadoc) —
 * an empty/error response for a smaller creator is logged and skipped, never turned into a
 * fabricated zero-filled snapshot row.
 */
@Component
public class AudienceDemographicsJob {

    private static final Logger log = LoggerFactory.getLogger(AudienceDemographicsJob.class);
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    private static final String DATA_SOURCE_META_API = "META_API";
    private static final int RATE_LIMIT_THRESHOLD_PERCENT = 90;


    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final AudienceDemographicsRepository demographicsRepository;
    private final MetaRateLimitTracker rateLimitTracker;
    private final AuditLogService auditLog;

    /** In-memory overlap guard — same per-instance style as {@code MetricsPollingJob.running}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AudienceDemographicsJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            AudienceDemographicsRepository demographicsRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.demographicsRepository = demographicsRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.auditLog = auditLog;
    }

    /** Every Sunday at 3:30 AM — weekly cadence, offset from the daily 2:30/3:00/4:00 AM token jobs. */
    @Scheduled(cron = "0 30 3 * * SUN")
    @SchedulerLock(name = "AudienceDemographicsJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void pollDemographics() {
        if (!running.compareAndSet(false, true)) {
            log.warn("AudienceDemographicsJob: previous run still in progress, skipping this trigger");
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
                        "AudienceDemographicsJob: unexpected failure polling creator {}",
                        creatorProfileId,
                        e);
            }
        }

        auditLog.recordToolCall(
                null,
                "AUDIENCE_DEMOGRAPHICS_POLL_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "creatorsPolled", polled,
                        "creatorsFailed", failed,
                        "totalTokens", connectedTokens.size()));
    }

    /**
     * Fetches ONE creator's follower demographics as soon as their Meta connect commits, so a new
     * creator's Audience panel is not empty for up to a week waiting for Sunday's run. Same shape
     * as {@code MetricsPollingJob#onCreatorConnected}: {@code AFTER_COMMIT} so the token row is
     * visible, {@code @Async} so the connect response never waits on three Graph calls, and it
     * reuses {@link #pollOne}. <b>Never throws</b>: the weekly run is the guarantee.
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
                        "AudienceDemographicsJob: connect-triggered fetch for creator {} found no live"
                                + " token; leaving it to the weekly run",
                        creatorProfileId);
                return;
            }
            boolean written = pollOne(creatorProfileId, token.get().getIgBusinessAccountId());
            log.info(
                    "AudienceDemographicsJob: connect-triggered fetch for creator {}, row written: {}",
                    creatorProfileId,
                    written);
        } catch (Exception e) {
            log.warn(
                    "AudienceDemographicsJob: connect-triggered fetch failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
        }
    }

    /** @return true if a demographics snapshot was successfully written for this creator. */
    private boolean pollOne(String creatorProfileId, String igBusinessAccountId) {
        // CR-99/F-0113 fix: same ULID-vs-real-Meta-ID bug as MetricsPollingJob — Meta's Graph API
        // path segment must be the numeric IG Business Account ID, never our internal ULID.
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            log.warn(
                    "AudienceDemographicsJob: no igBusinessAccountId on file for creator {}, skipping",
                    creatorProfileId);
            return false;
        }

        // F-0166 follow-up (Priya review): this called the WORKSPACE-scoped getValidToken with
        // `workspaceId`, always null for a creator-owned row — the backing repository query has
        // an explicit `workspaceId IS NOT NULL` predicate (CR-111), so it could never match.
        Optional<String> token = tokenStorage.getValidCreatorToken(creatorProfileId);
        if (token.isEmpty()) {
            log.warn(
                    "AudienceDemographicsJob: no valid token for creator {}, skipping", creatorProfileId);
            return false;
        }

        // F-0126: keyed on creatorProfileId (internal ULID) while MetaGraphApiClient's own
        // enforcement and usage-header parsing key on igBusinessAccountId — two disjoint key
        // namespaces for the same account meant this pre-flight guard could never see usage the
        // client itself had recorded, so it always read 0 and was decorative.
        int usage = rateLimitTracker.getCurrentUsage(igBusinessAccountId);
        if (usage >= RATE_LIMIT_THRESHOLD_PERCENT) {
            log.warn(
                    "AudienceDemographicsJob: creator {} at {}% Meta rate-limit usage, deferring to next"
                            + " cycle",
                    creatorProfileId,
                    usage);
            return false;
        }

        try {
            AudienceBreakdowns breakdowns =
                    instagramClient.getAudienceDemographics(
                            igBusinessAccountId,
                            token.get(),
                            // T-IGLOGIN-0820: route to the host the token belongs to. Defaulting
                            // to FACEBOOK_LOGIN is safe only because getCreatorAuthPath is empty
                            // exactly when there is no usable token, which this method already
                            // returned on above.
                            tokenStorage
                                    .getCreatorAuthPath(creatorProfileId)
                                    .orElse(MetaAuthPath.FACEBOOK_LOGIN));

            if (breakdowns == null || breakdowns.isEmpty()) {
                // Meta returns no follower_demographics for an account under 100 followers (or
                // with none computed yet): logged and skipped, never saved as an empty row.
                log.warn(
                        "AudienceDemographicsJob: no follower demographics for creator {}"
                                + " (likely below Meta's 100-follower threshold), skipping",
                        creatorProfileId);
                return false;
            }

            AudienceDemographics snapshot =
                    AudienceDemographics.builder()
                            .id(Ulids.newUlid())
                            .time(Instant.now())
                            .creatorProfileId(creatorProfileId)
                            .platform(PLATFORM_INSTAGRAM)
                            .ageGenderBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(breakdowns.ageGender())))
                            .countryBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(breakdowns.country())))
                            .cityBreakdownJson(JsonLists.toJsonObject(nullIfEmpty(breakdowns.city())))
                            // Meta has no language breakdown any more (audience_locale went with
                            // the other audience_* metrics), so the column stays empty.
                            .localeBreakdownJson(null)
                            .dataSource(DATA_SOURCE_META_API)
                            .fetchedAt(Instant.now())
                            .build();

            demographicsRepository.save(snapshot);
            return true;
        } catch (MetaRateLimitException e) {
            // F-0126: markLimited must use the same key as getCurrentUsage() above (and the same
            // key MetaGraphApiClient itself uses) — igBusinessAccountId, not creatorProfileId.
            rateLimitTracker.markLimited(igBusinessAccountId);
            log.warn(
                    "AudienceDemographicsJob: rate limited for creator {}, will retry next cycle",
                    creatorProfileId);
            return false;
        } catch (MetaTokenExpiredException e) {
            log.warn(
                    "AudienceDemographicsJob: token expired for creator {}, needs re-auth",
                    creatorProfileId);
            return false;
        } catch (MetaApiException e) {
            log.error(
                    "AudienceDemographicsJob: Meta API error polling creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return false;
        }
    }

    private static Map<String, Long> nullIfEmpty(Map<String, Long> map) {
        return (map == null || map.isEmpty()) ? null : map;
    }
}
