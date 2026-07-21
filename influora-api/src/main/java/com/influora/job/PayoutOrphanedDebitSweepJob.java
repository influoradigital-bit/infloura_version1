package com.influora.job;

import com.influora.domain.entity.Payout;
import com.influora.repository.PayoutRepository;
import com.influora.service.AuditLogService;
import com.influora.service.PayoutReconciliationService;
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
 * [P1 fix, SEC: Kabir, landed-money-path audit 2b — orphaned-debit sweeper.] Sweeps for {@link
 * Payout} rows stuck in {@link Payout#STATUS_PENDING} — {@code PayoutService#doQueuePayout}
 * persists that status BEFORE it debits the creator's wallet and calls RazorpayX, and only
 * advances it past PENDING once the gateway call returns and the result is durably saved. A
 * process crash/kill in that window (after the debit commits, before the gateway response is
 * recorded) leaves the debit orphaned: the creator has been debited toward a payout that was
 * never confirmed sent, with nothing else in the system watching for it. See {@code
 * PayoutService}'s class javadoc (why {@code doQueuePayout}'s {@code @Transactional} cannot fix
 * this — it's a Spring self-invocation no-op, and making it a real transaction would reintroduce
 * the double-pay hole by rolling back a debit whose paired RazorpayX send may have partially
 * succeeded) and {@link PayoutReconciliationService#reconcileOrphanedPendingPayout} for the actual
 * retry-or-reverse resolution this job triggers per stuck row.
 *
 * <p>Same shape as {@code AffiliateEarningReconciliationJob}: single-flight guard, grace-period
 * query so this never races a payout that is still legitimately mid-flight on another node, and a
 * per-row try/catch so one bad row never aborts the rest of the sweep.
 */
@Component
public class PayoutOrphanedDebitSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutOrphanedDebitSweepJob.class);

    /**
     * How long a {@link Payout} may sit in {@link Payout#STATUS_PENDING} before this job treats it
     * as orphaned rather than merely still in flight. Generous relative to how long a single
     * {@code doQueuePayout} gateway call could plausibly take (fund-account resolution + one
     * RazorpayX HTTP round trip).
     */
    static final Duration ORPHANED_DEBIT_GRACE_PERIOD = Duration.ofMinutes(15);

    private final PayoutRepository payoutRepository;
    private final PayoutReconciliationService reconciliationService;
    private final AuditLogService auditLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public PayoutOrphanedDebitSweepJob(
            PayoutRepository payoutRepository,
            PayoutReconciliationService reconciliationService,
            AuditLogService auditLogService) {
        this.payoutRepository = payoutRepository;
        this.reconciliationService = reconciliationService;
        this.auditLogService = auditLogService;
    }

    /** Runs every 10 minutes — frequent enough that an orphaned debit is caught well within the same business hour. */
    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "PayoutOrphanedDebitSweepJob", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void sweepOrphanedPendingPayouts() {
        if (!running.compareAndSet(false, true)) {
            log.warn("PayoutOrphanedDebitSweepJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runSweep();
        } finally {
            running.set(false);
        }
    }

    private void runSweep() {
        Instant olderThan = Instant.now().minus(ORPHANED_DEBIT_GRACE_PERIOD);
        List<Payout> stuck =
                payoutRepository.findByStatusAndCreatedAtBefore(Payout.STATUS_PENDING, olderThan);

        int reconciled = 0;
        int failed = 0;

        for (Payout payout : stuck) {
            try {
                reconciliationService.reconcileOrphanedPendingPayout(payout);
                reconciled++;
            } catch (Exception e) {
                // Defensive catch-all mirroring AffiliateEarningReconciliationJob/
                // AffiliateSettlementJob — one payout's unexpected failure must never abort the
                // rest of the sweep. It remains a candidate for the next run.
                failed++;
                log.error(
                        "PayoutOrphanedDebitSweepJob: failed to reconcile PENDING payout {} (milestone {})"
                                + " — will retry next run",
                        payout.getId(),
                        payout.getMilestoneId(),
                        e);
            }
        }

        if (!stuck.isEmpty() || failed > 0) {
            // [Money-safety monitoring] A nonzero count here means the queue-time path (debit +
            // RazorpayX call) failed to complete synchronously for at least one payout — a real
            // signal worth investigating, not routine noise.
            log.warn(
                    "PayoutOrphanedDebitSweepJob: found {} stuck PENDING payout(s), reconciled {}, failed {}",
                    stuck.size(),
                    reconciled,
                    failed);
            auditLogService.recordToolCall(
                    null,
                    "PAYOUT_ORPHANED_DEBIT_SWEEP_COMPLETED",
                    "SYSTEM",
                    failed > 0 ? AuditLogService.OUTCOME_FAILED : AuditLogService.OUTCOME_ALLOWED,
                    null,
                    null,
                    null,
                    Map.of(
                            "stuckPendingFound", stuck.size(),
                            "reconciled", reconciled,
                            "failed", failed));
        }
    }
}
