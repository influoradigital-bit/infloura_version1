package com.influora.job;

import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorAccountInsight;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.AccountInsightsResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CreatorAccountInsightRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import com.influora.service.creatorcopilot.CreatorMetaConnectedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Daily fetch of each connected creator's account-level Instagram insights over the last 28 full
 * days (IST): accounts reached, views, interactions, accounts engaged and profile-link taps
 * (2026-09-24). {@link InstagramInsightsClient#getAccountInsights} existed but nothing called it,
 * so none of these numbers were ever collected, and Meera's {@code get_my_metrics} had to return
 * {@code reach_30d} as null.
 *
 * <p>Same conventions as {@link AudienceDemographicsJob}: a single-threaded sweep over live Meta
 * tokens, an {@link AtomicBoolean} overlap guard plus ShedLock, per-creator isolation, a
 * rate-limit pre-flight, immutable snapshot rows, and one fetch right after a creator connects.
 *
 * <p>28 days, not 30: Meta caps a {@code since}/{@code until} range at 30 days, and four whole
 * weeks keep weekday mix constant from one day's snapshot to the next.
 */
@Component
public class AccountInsightsJob {

    private static final Logger log = LoggerFactory.getLogger(AccountInsightsJob.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int WINDOW_DAYS = 28;
    private static final String PLATFORM_INSTAGRAM = "INSTAGRAM";
    private static final String DATA_SOURCE_META_API = "META_API";
    private static final int RATE_LIMIT_THRESHOLD_PERCENT = 90;

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final InstagramInsightsClient instagramClient;
    private final CreatorAccountInsightRepository insightRepository;
    private final MetaRateLimitTracker rateLimitTracker;
    private final AuditLogService auditLog;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public AccountInsightsJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            CreatorAccountInsightRepository insightRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog) {
        this(tokenRepository, tokenStorage, instagramClient, insightRepository, rateLimitTracker, auditLog,
                Clock.systemUTC());
    }

    /** Tests pass a fixed clock so the 28-day window is exact. */
    AccountInsightsJob(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            InstagramInsightsClient instagramClient,
            CreatorAccountInsightRepository insightRepository,
            MetaRateLimitTracker rateLimitTracker,
            AuditLogService auditLog,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.instagramClient = instagramClient;
        this.insightRepository = insightRepository;
        this.rateLimitTracker = rateLimitTracker;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    // 05:00 IST: after the night's MetricsPollingJob run, well clear of the 03:30 Sunday audience job.
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "AccountInsightsJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void pollAccountInsights() {
        if (!running.compareAndSet(false, true)) {
            log.warn("AccountInsightsJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runPoll();
        } finally {
            running.set(false);
        }
    }

    private void runPoll() {
        List<MetaOAuthToken> connectedTokens = tokenRepository.findByRevokedFalseAndExpiresAtAfter(clock.instant());
        int polled = 0;
        int failed = 0;
        for (MetaOAuthToken tokenRow : connectedTokens) {
            try {
                if (pollOne(tokenRow.getCreatorProfileId(), tokenRow.getIgBusinessAccountId())) {
                    polled++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("AccountInsightsJob: unexpected failure polling creator {}", tokenRow.getCreatorProfileId(), e);
            }
        }
        auditLog.recordToolCall(
                null,
                "ACCOUNT_INSIGHTS_POLL_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("creatorsPolled", polled, "creatorsFailed", failed, "totalTokens", connectedTokens.size()));
    }

    /**
     * Fetches ONE creator's numbers as soon as their Meta connect commits, so the Analytics page
     * and Meera have them the same day. Same shape as {@code AudienceDemographicsJob#onCreatorConnected}.
     * <b>Never throws</b>: the daily run is the guarantee.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreatorConnected(CreatorMetaConnectedEvent event) {
        String creatorProfileId = event.creatorProfileId();
        try {
            Optional<MetaOAuthToken> token =
                    tokenRepository
                            .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                            .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(clock.instant()));
            if (token.isEmpty()) {
                log.info(
                        "AccountInsightsJob: connect-triggered fetch for creator {} found no live token;"
                                + " leaving it to the daily run",
                        creatorProfileId);
                return;
            }
            boolean written = pollOne(creatorProfileId, token.get().getIgBusinessAccountId());
            log.info("AccountInsightsJob: connect-triggered fetch for creator {}, row written: {}", creatorProfileId, written);
        } catch (Exception e) {
            log.warn("AccountInsightsJob: connect-triggered fetch failed for creator {}: {}", creatorProfileId, e.getMessage());
        }
    }

    private boolean pollOne(String creatorProfileId, String igBusinessAccountId) {
        if (igBusinessAccountId == null || igBusinessAccountId.isBlank()) {
            log.warn("AccountInsightsJob: no igBusinessAccountId on file for creator {}, skipping", creatorProfileId);
            return false;
        }
        Optional<String> token = tokenStorage.getValidCreatorToken(creatorProfileId);
        if (token.isEmpty()) {
            log.warn("AccountInsightsJob: no valid token for creator {}, skipping", creatorProfileId);
            return false;
        }
        int usage = rateLimitTracker.getCurrentUsage(igBusinessAccountId);
        if (usage >= RATE_LIMIT_THRESHOLD_PERCENT) {
            log.warn("AccountInsightsJob: creator {} at {}% Meta rate-limit usage, deferring", creatorProfileId, usage);
            return false;
        }

        // The last 28 FULL days in IST: today is still filling up, so it is left out.
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate periodStart = today.minusDays(WINDOW_DAYS);
        LocalDate periodEnd = today.minusDays(1);
        long since = periodStart.atStartOfDay(IST).toEpochSecond();
        long until = today.atStartOfDay(IST).toEpochSecond();

        try {
            AccountInsightsResponse response =
                    instagramClient.getAccountInsights(
                            igBusinessAccountId,
                            token.get(),
                            since,
                            until,
                            tokenStorage.getCreatorAuthPath(creatorProfileId).orElse(MetaAuthPath.FACEBOOK_LOGIN));
            CreatorAccountInsight snapshot =
                    new CreatorAccountInsight(
                            Ulids.newUlid(),
                            creatorProfileId,
                            PLATFORM_INSTAGRAM,
                            periodStart,
                            periodEnd,
                            response == null ? null : response.valueOf("reach"),
                            response == null ? null : response.valueOf("views"),
                            response == null ? null : response.valueOf("total_interactions"),
                            response == null ? null : response.valueOf("accounts_engaged"),
                            response == null ? null : response.valueOf("profile_links_taps"),
                            DATA_SOURCE_META_API,
                            clock.instant());
            if (!snapshot.hasAnyMetric()) {
                log.warn("AccountInsightsJob: Meta returned no account insights for creator {}, skipping", creatorProfileId);
                return false;
            }
            insightRepository.save(snapshot);
            return true;
        } catch (MetaRateLimitException e) {
            rateLimitTracker.markLimited(igBusinessAccountId);
            log.warn("AccountInsightsJob: rate limited for creator {}, will retry next run", creatorProfileId);
            return false;
        } catch (MetaTokenExpiredException e) {
            log.warn("AccountInsightsJob: token expired for creator {}, needs re-auth", creatorProfileId);
            return false;
        } catch (MetaApiException e) {
            log.error("AccountInsightsJob: Meta API error polling creator {}: {}", creatorProfileId, e.getMessage());
            return false;
        }
    }
}
