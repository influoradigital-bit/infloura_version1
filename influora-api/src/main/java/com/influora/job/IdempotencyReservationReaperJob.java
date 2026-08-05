package com.influora.job;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
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
 * [Red-team F2 fix — stale IN_PROGRESS idempotency reservation reaper.] {@code
 * IdempotencyService#executeOnce} reserves a row {@code IN_PROGRESS} (own transaction, insert-first)
 * BEFORE running the caller's guarded action, and only transitions it to {@code COMPLETED}/{@code
 * FAILED} once that action returns or throws. A hard crash or process kill between the reservation
 * committing and the action returning skips both of those transitions — the row is left {@code
 * IN_PROGRESS} forever, and nothing in {@code IdempotencyService} itself ever revisits it (only
 * {@code FAILED} rows are reclaimable, via {@code reclaimFailedForRetry}). Every future legitimate
 * call with the SAME {@code (idempotencyKey, workspaceId, scope)} — e.g. an admin re-clicking
 * "retry" on a payout whose failure snapshot has not since changed — is then permanently rejected as
 * {@code AlreadyInProgressException}, with no in-app recovery.
 *
 * <p>This job periodically reaps such rows: any {@code IN_PROGRESS} reservation older than {@link
 * #STALE_IN_PROGRESS_GRACE_PERIOD} is atomically flipped to {@code FAILED} — never {@code
 * COMPLETED} (this job has no idea whether the original action actually succeeded, so it must never
 * claim it did) — which hands the row straight to {@code IdempotencyService#executeOnce}'s OWN,
 * already-tested {@code reclaimFailedForRetry} path on the next legitimate call. Generic across
 * every {@code executeOnce} caller/scope in the codebase (Meera tool calls aside — those use their
 * own {@code meera_tool_calls.idempotency_key} ledger, not this table), not specific to payout-retry.
 *
 * <p>Same shape as {@link StaleTokenCleanupJob}/{@link PayoutOrphanedDebitSweepJob}: single-flight
 * guard, grace-period query so this never races a reservation that is still legitimately in flight
 * on another node, and a per-row try/catch so one bad row never aborts the rest of the sweep.
 */
@Component
public class IdempotencyReservationReaperJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReservationReaperJob.class);

    /**
     * How long a reservation may sit {@code IN_PROGRESS} before this job treats it as abandoned
     * rather than merely still running. Generous relative to how long any single guarded action in
     * this codebase could plausibly take (a DB write or two, at most one outbound HTTP call e.g. to
     * RazorpayX) — mirrors {@code PayoutOrphanedDebitSweepJob#ORPHANED_DEBIT_GRACE_PERIOD}.
     */
    static final Duration STALE_IN_PROGRESS_GRACE_PERIOD = Duration.ofMinutes(15);

    private final IdempotencyKeyRecordRepository repository;
    private final AuditLogService auditLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public IdempotencyReservationReaperJob(
            IdempotencyKeyRecordRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /** Runs every 10 minutes — same cadence as PayoutOrphanedDebitSweepJob, whose retry-path sweep this unblocks. */
    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "IdempotencyReservationReaperJob", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void reapStaleReservations() {
        if (!running.compareAndSet(false, true)) {
            log.warn("IdempotencyReservationReaperJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runReap();
        } finally {
            running.set(false);
        }
    }

    private void runReap() {
        Instant threshold = Instant.now().minus(STALE_IN_PROGRESS_GRACE_PERIOD);
        List<IdempotencyKeyRecord> stale =
                repository.findByStatusAndCreatedAtBefore(IdempotencyKeyRecord.Status.IN_PROGRESS, threshold);

        int reclaimed = 0;
        int skipped = 0;

        for (IdempotencyKeyRecord record : stale) {
            try {
                int updated =
                        repository.reclaimStaleInProgress(
                                record.getIdempotencyKey(),
                                IdempotencyKeyRecord.Status.IN_PROGRESS,
                                IdempotencyKeyRecord.Status.FAILED,
                                threshold);
                if (updated == 1) {
                    reclaimed++;
                    log.warn(
                            "IdempotencyReservationReaperJob: reclaimed stale IN_PROGRESS reservation"
                                    + " key={} scope={} (created {}) -> FAILED, now retryable",
                            record.getIdempotencyKey(),
                            record.getScope(),
                            record.getCreatedAt());
                } else {
                    // Lost the race to a concurrent reaper run, or the original caller finally
                    // completed/failed it between the query above and this UPDATE — not an error.
                    skipped++;
                }
            } catch (Exception e) {
                // Defensive catch-all mirroring every other sweep job in this package — one row's
                // unexpected failure must never abort the rest of the reap.
                skipped++;
                log.error(
                        "IdempotencyReservationReaperJob: failed to reclaim key={} — will retry next run",
                        record.getIdempotencyKey(),
                        e);
            }
        }

        if (!stale.isEmpty()) {
            log.warn(
                    "IdempotencyReservationReaperJob: found {} stale IN_PROGRESS reservation(s),"
                            + " reclaimed {}, skipped {}",
                    stale.size(),
                    reclaimed,
                    skipped);
            auditLogService.recordToolCall(
                    null,
                    "IDEMPOTENCY_RESERVATION_REAP_COMPLETED",
                    "SYSTEM",
                    AuditLogService.OUTCOME_ALLOWED,
                    null,
                    null,
                    null,
                    Map.of(
                            "staleFound", stale.size(),
                            "reclaimed", reclaimed,
                            "skipped", skipped));
        }
    }
}
