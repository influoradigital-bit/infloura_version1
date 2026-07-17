package com.influora.job;

import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Soft-revokes Meta tokens that have sat expired past a grace period without a successful refresh
 * (Wave B task B2, REMAINING_WORK_PLAN.md). Complements {@link MetaTokenRefreshService}: that
 * service refreshes tokens *before* expiry daily; if a token is still expired {@link
 * #STALE_GRACE_PERIOD} after the fact, every daily refresh attempt in that window has failed (or
 * the creator disconnected/revoked access at Meta's end and the refresh call is permanently
 * rejected) — this job marks it {@code revoked} so it stops being retried by the refresh sweep and
 * stops being read by {@link MetricsPollingJob}'s poll (both query on {@code revoked = false}).
 *
 * <p>No new column is introduced. The codebase's standing pattern (see {@link MetaTokenStorage
 * #revoke}) is soft-revoke, never hard-delete — the token row is kept as an append-only audit
 * trail. This job reuses exactly that: {@link MetaOAuthTokenRepository#save} with {@link
 * MetaOAuthToken#revoke()} called, never a {@code delete}.
 *
 * <p>Grace-period semantics were chosen deliberately over inventing a "failed refresh count"
 * column: {@code expiresAt} + {@code revoked} are the only two fields the schema already offers to
 * express staleness, and a token still {@code revoked = false} but expired for longer than the
 * grace period is, by construction, one the refresh sweep has failed to rotate on every one of its
 * daily attempts within that window.
 */
@Component
public class StaleTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleTokenCleanupJob.class);

    /**
     * How long a token may sit expired (but not yet revoked) before this job treats it as
     * permanently stale. Generous relative to the refresh sweep's daily cadence and the 7-day
     * {@code token-refresh-days-before-expiry} lead time — a token only reaches this state if
     * multiple consecutive daily refresh attempts have already failed.
     */
    static final Duration STALE_GRACE_PERIOD = Duration.ofDays(14);

    private final MetaOAuthTokenRepository tokenRepository;
    private final AuditLogService auditLog;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public StaleTokenCleanupJob(MetaOAuthTokenRepository tokenRepository, AuditLogService auditLog) {
        this.tokenRepository = tokenRepository;
        this.auditLog = auditLog;
    }

    /** Once a day, offset from the refresh sweep so cleanup always sees that day's refresh attempts first. */
    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "StaleTokenCleanupJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void cleanupStaleTokens() {
        if (!running.compareAndSet(false, true)) {
            log.warn("StaleTokenCleanupJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runCleanup();
        } finally {
            running.set(false);
        }
    }

    private void runCleanup() {
        Instant staleThreshold = Instant.now().minus(STALE_GRACE_PERIOD);

        // Not workspace-scoped by design — same system-wide sweep pattern as MetricsPollingJob and
        // MetaTokenRefreshService; findByExpiresAtBeforeAndRevokedFalse already exists for the
        // refresh sweep and is reused here with a much older threshold (grace period vs. lead time).
        List<MetaOAuthToken> staleCandidates =
                tokenRepository.findByExpiresAtBeforeAndRevokedFalse(staleThreshold);

        int revoked = 0;
        int failed = 0;

        for (MetaOAuthToken tokenRow : staleCandidates) {
            String creatorProfileId = tokenRow.getCreatorProfileId();
            try {
                revokeOne(tokenRow);
                revoked++;
            } catch (Exception e) {
                // Defensive catch-all mirroring MetricsPollingJob/MetaTokenRefreshService: one
                // token's unexpected failure must never abort the rest of the cleanup batch.
                failed++;
                log.error(
                        "StaleTokenCleanupJob: unexpected failure revoking stale token for creator {}",
                        creatorProfileId,
                        e);
            }
        }

        auditLog.recordToolCall(
                null,
                "META_STALE_TOKEN_CLEANUP_COMPLETED",
                "SYSTEM",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "tokensRevoked", revoked,
                        "tokensFailed", failed,
                        "totalStaleCandidates", staleCandidates.size()));
    }

    private void revokeOne(MetaOAuthToken tokenRow) {
        tokenRow.revoke();
        tokenRepository.save(tokenRow);
        log.warn(
                "StaleTokenCleanupJob: revoked stale Meta token for creator {} (expired since {})",
                tokenRow.getCreatorProfileId(),
                tokenRow.getExpiresAt());
    }
}
