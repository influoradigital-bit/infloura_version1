package com.influora.job;

import com.influora.config.MeeraInteractionLogRetentionProperties;
import com.influora.repository.MeeraInteractionLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention purge for {@code meera_interaction_log} (Phase 2 item 2.3's flywheel event log,
 * V20260721160000).
 *
 * <p><b>Why this exists now.</b> Priya's PARTIAL-2 ruling (wiki/build/partials-resolution-plan.md)
 * locks this job as a HARD, MANDATORY predecessor to ANY future read/join/analytics consumer of
 * {@code meera_interaction_log} — "No read query merges without it landing first." Kabir's L1
 * finding (wiki/build/phase2-kabir-security.md, MANDATORY-GATE: PASS with this as the one required
 * fast-follow) independently flags the same gap: with redaction + write-only + workspace-scoping
 * there is no active leak today, so retention was not gate-blocking, but the table grows unbounded
 * without it. Landing this now — before any reader exists — is ops hygiene (row-growth hygiene,
 * Rohan's cost line), decoupled from and NOT itself gated on a consumer ever being built.
 *
 * <p><b>Info-barrier note (structural, not a policed convention).</b> This job performs exactly one
 * database operation: {@link MeeraInteractionLogRepository#deleteByCreatedAtBefore}, an age-only
 * bulk DELETE keyed solely on {@code created_at}. It issues no SELECT, no read of any other column
 * (workspace, campaign, event type, revision reason all stay untouched and unread), no join, and
 * returns nothing but a row count. It cannot observe — let alone leak — BRAND vs CREATOR data,
 * because it never reads any column that would let it. This preserves exactly the write-only
 * info-barrier invariant Kabir verified (check 3, phase2-kabir-security.md: "no read/analytics
 * query exists, so there is no joined view that could co-locate both sides' private data") — a
 * delete-by-age job adds no query surface beyond what the write-only table already had.
 *
 * <p><b>Scope boundary (deliberate).</b> This job builds NOTHING beyond the purge: no read
 * consumer, no analytics view, no funnel query. That is out of scope per Priya's ruling — a reader
 * is a separate, future changeset that must ship together with L2's {@code campaign_id} ownership
 * check (phase2-kabir-security.md L2) when someone actually proposes one.
 *
 * <p>Mirrors {@link StaleTokenCleanupJob}/{@link PayoutOrphanedDebitSweepJob}'s shape: a single
 * {@code @Scheduled} method, an {@link AtomicBoolean} overlap guard so a slow run is never
 * double-triggered by the next tick, and {@code @SchedulerLock} for the multi-instance case. Unlike
 * those two, this purge carries no money/security signal worth an {@code AuditLogService} entry
 * (same reasoning {@code DeliverableCleanupJob} uses for its own age-based cleanup) — a log line
 * with the deleted count is sufficient for an ops-hygiene sweep.
 *
 * <p>See {@link MeeraInteractionLogRepository#deleteByCreatedAtBefore}'s javadoc for a note on the
 * bulk delete's indexing characteristics (age-only predicate against a composite index whose
 * leading column is {@code workspace_id}, ruled acceptable at current row volumes).
 */
@Component
public class MeeraInteractionLogRetentionPurgeJob {

    private static final Logger log =
            LoggerFactory.getLogger(MeeraInteractionLogRetentionPurgeJob.class);

    private final MeeraInteractionLogRepository repository;
    private final MeeraInteractionLogRetentionProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public MeeraInteractionLogRetentionPurgeJob(
            MeeraInteractionLogRepository repository,
            MeeraInteractionLogRetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Daily at 3 AM — off-peak, and deliberately offset from the other daily jobs already at this
     * hour band ({@code DeliverableCleanupJob} 2:00/2:30 AM, {@code ScoreCalculationJob}/{@code
     * StaleTokenCleanupJob} both 4:00 AM) so this purge never contends with them for DB I/O.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(
            name = "MeeraInteractionLogRetentionPurgeJob",
            lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT1M")
    public void purgeExpiredInteractionLogs() {
        if (!properties.isEnabled()) {
            log.debug("MeeraInteractionLogRetentionPurgeJob: disabled, skipping run");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn(
                    "MeeraInteractionLogRetentionPurgeJob: previous run still in progress, skipping"
                            + " this trigger");
            return;
        }
        try {
            runPurge();
        } finally {
            running.set(false);
        }
    }

    private void runPurge() {
        int retentionDays = properties.getRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deleted = repository.deleteByCreatedAtBefore(cutoff);

        log.info(
                "MeeraInteractionLogRetentionPurgeJob: purged {} row(s) older than {} (retention={}"
                        + " days)",
                deleted,
                cutoff,
                retentionDays);
    }
}
