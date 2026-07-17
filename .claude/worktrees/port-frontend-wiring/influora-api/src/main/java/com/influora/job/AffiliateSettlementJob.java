package com.influora.job;

import com.influora.common.Ulids;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.AffiliateSettlementBatchRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly affiliate settlement job (Wave D task D4, wiki/tech/REMAINING_WORK_PLAN.md). Sweeps every
 * creator with at least one {@code PENDING} or {@code FAILED} (retryable) {@link AffiliateEarning}
 * and settles them under one {@link AffiliateSettlementBatch} per run.
 *
 * <p><b>What "settle" means in this slice</b> -- exactly like {@code PayoutService#queuePayout},
 * settlement here is the internal ledger action (marking {@link AffiliateEarning} rows {@code
 * SETTLED} under a batch) that represents the creator becoming entitled to be paid; it does NOT
 * itself call RazorpayX or move real money. Wiring a settled creator's total into an actual bank
 * transfer (via the existing {@code PayoutService}/RazorpayX path, or a dedicated affiliate payout
 * flow) is a separate, deliberately out-of-scope follow-up -- inventing a second money-movement
 * gateway integration in this task, on top of an already money-moving schema/job, was judged
 * out of scope without a product/Rohan decision on how affiliate payouts should actually be
 * disbursed (same wallet as milestone payouts? a separate RazorpayX contact? batched wire?). This
 * job's job is to make "how much does creator X get for period Y, and has it already been paid"
 * unambiguous and safe to compute exactly once -- the disbursement mechanism can be layered on top
 * without reopening this class's idempotency guarantees.
 *
 * <p><b>Double-payout structurally impossible [mirrors PayoutService/IdempotencyService's
 * ALREADY-FIXED pattern -- see class javadocs there for the full incident history]:</b>
 *
 * <ul>
 *   <li><b>Server-derived idempotency key</b> -- {@link #deriveIdempotencyKey} is {@code
 *       "affiliate.settlement:" + creatorId + ":" + periodYearMonth}, built entirely from
 *       server-side state (the creator id being swept and the period the scheduled trigger is
 *       running for), never from anything caller/request-supplied. Two concurrent triggers for the
 *       same period (e.g. a manual retry firing while the cron trigger is still running) derive the
 *       IDENTICAL key for the same creator -- this is what makes concurrent double-settlement
 *       structurally impossible rather than merely unlikely.
 *   <li><b>DB uniqueness constraint backstop</b> -- the key above is reserved through the shared
 *       {@link IdempotencyService#executeOnce} (V15 {@code idempotency_keys}, {@code
 *       UNIQUE(idempotency_key)}), the exact same insert-first-wins arbiter {@code PayoutService}
 *       and {@code RedemptionService} use. The database's constraint, not this job's control flow,
 *       decides which of two concurrent attempts for the same (creator, period) actually runs {@link
 *       #settleOneCreator}.
 *   <li><b>Provider-side reference-id dedup as backstop</b> -- not applicable in this slice because
 *       this job does not itself call an external payment gateway (see "what settle means" above);
 *       the equivalent backstop here is {@link AffiliateEarning}'s own {@code UNIQUE(redemption_id)}
 *       (V27), which independently guarantees a given redemption can only ever be represented by one
 *       earning row, so even a hypothetical bug that queried "pending earnings" twice could not
 *       manufacture a second payable row for the same underlying conversion.
 *   <li><b>Validate BEFORE {@code executeOnce}, not inside it [mirrors PayoutService E2 HIGH-1]</b>
 *       -- {@link #settleOneCreator} loads and totals the creator's settleable earnings BEFORE
 *       calling {@code executeOnce}; only the actual status-flip-and-persist step runs inside the
 *       supplier. A creator with zero settleable earnings never reserves a key at all (nothing to
 *       fail on), so a later period where that creator DOES have earnings is never wedged behind a
 *       stale reservation.
 *   <li><b>Failure states recoverable, not a permanent wedge [mirrors PayoutService/IdempotencyService
 *       FAILED-is-retryable fix]</b> -- if {@link #settleOneCreator} throws for one creator (e.g. a
 *       persistence error), {@code executeOnce} marks that creator's key {@code FAILED} (not
 *       terminal -- {@link IdempotencyService} now reclaims FAILED keys for a fresh attempt, see its
 *       class javadoc), the batch itself is marked {@link AffiliateSettlementBatch#markFailed()} if
 *       ANY creator failed (also not terminal, see that entity's javadoc), and -- critically -- one
 *       creator's failure never aborts the sweep for every other creator in the batch (same
 *       defensive-catch-per-item discipline as {@code MetricsPollingJob}/{@code
 *       StaleTokenCleanupJob}). The next scheduled run creates a fresh batch and retries every
 *       creator whose key is still FAILED or who still has PENDING earnings, exactly mirroring how a
 *       failed {@code PayoutService} attempt is retried by the next legitimate call.
 * </ul>
 */
@Component
public class AffiliateSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(AffiliateSettlementJob.class);

    private static final String IDEMPOTENCY_SCOPE = "affiliate.settlement";

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final List<AffiliateEarning.Status> SETTLEABLE_STATUSES =
            List.of(AffiliateEarning.Status.PENDING, AffiliateEarning.Status.FAILED);

    private final AffiliateEarningRepository affiliateEarningRepository;
    private final AffiliateSettlementBatchRepository settlementBatchRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public AffiliateSettlementJob(
            AffiliateEarningRepository affiliateEarningRepository,
            AffiliateSettlementBatchRepository settlementBatchRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService) {
        this.affiliateEarningRepository = affiliateEarningRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
    }

    /** Runs once a month, on the 1st at 05:00 UTC -- after {@code StaleTokenCleanupJob}'s daily 04:00 slot so the two never contend. */
    @Scheduled(cron = "0 0 5 1 * *")
    public void runMonthlySettlement() {
        // Settles the PREVIOUS calendar month -- by the 1st of the current month, the prior month's
        // conversions are final (no more redemptions can be backdated into a closed period).
        String periodYearMonth = PERIOD_FORMATTER.format(Instant.now().atZone(ZoneOffset.UTC).minusMonths(1));
        runSettlementForPeriod(periodYearMonth);
    }

    /**
     * Runs (or re-runs) settlement for an explicit period -- the entry point both the scheduled
     * trigger and a manual retry/backfill call use. Safe to call multiple times for the SAME period,
     * including concurrently: see class javadoc for why double-settlement is structurally
     * impossible, not merely guarded by the {@link #running} flag below (which only prevents this
     * JVM's own overlapping scheduled triggers, not a genuinely concurrent manual retry from another
     * node).
     */
    public void runSettlementForPeriod(String periodYearMonth) {
        if (!running.compareAndSet(false, true)) {
            log.warn(
                    "AffiliateSettlementJob: previous run still in progress, skipping trigger for period {}",
                    periodYearMonth);
            return;
        }
        try {
            executeSettlementBatch(periodYearMonth);
        } finally {
            running.set(false);
        }
    }

    private void executeSettlementBatch(String periodYearMonth) {
        AffiliateSettlementBatch batch =
                AffiliateSettlementBatch.builder().id(Ulids.newUlid()).periodYearMonth(periodYearMonth).build();
        settlementBatchRepository.save(batch);

        List<String> creatorIds = affiliateEarningRepository.findDistinctCreatorIdByStatusIn(SETTLEABLE_STATUSES);

        int settledCount = 0;
        int failedCount = 0;

        for (String creatorId : creatorIds) {
            try {
                boolean settled = settleOneCreator(creatorId, periodYearMonth, batch);
                if (settled) {
                    settledCount++;
                }
            } catch (Exception e) {
                // Defensive catch-all mirroring MetricsPollingJob/StaleTokenCleanupJob -- one
                // creator's unexpected failure must never abort the rest of the settlement batch.
                failedCount++;
                log.error(
                        "AffiliateSettlementJob: unexpected failure settling creator {} for period {}",
                        creatorId,
                        periodYearMonth,
                        e);
            }
        }

        settlementBatchRepository.save(batch);
        if (failedCount > 0) {
            batch.markFailed();
        } else {
            batch.markCompleted();
        }
        settlementBatchRepository.save(batch);

        auditLogService.recordToolCall(
                null,
                "AFFILIATE_SETTLEMENT_BATCH_COMPLETED",
                "SYSTEM",
                failedCount > 0 ? AuditLogService.OUTCOME_FAILED : AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                batch.getTotalAmount(),
                Map.of(
                        "batchId", batch.getId(),
                        "periodYearMonth", periodYearMonth,
                        "creatorsSettled", settledCount,
                        "creatorsFailed", failedCount,
                        "totalCreatorsConsidered", creatorIds.size()));
    }

    /**
     * Settles one creator's accumulated PENDING/FAILED earnings for {@code periodYearMonth}.
     *
     * <p>Validation (loading and totaling the creator's settleable earnings) happens BEFORE {@code
     * executeOnce} reserves the key -- see class javadoc, mirrors {@code
     * PayoutService#validateForPayout} running before its own {@code executeOnce} call. A creator
     * with no settleable earnings by the time this runs never reserves a key at all.
     *
     * @return {@code true} if this creator was newly settled by this call, {@code false} if there
     *     was nothing to settle (not an error -- a creator can legitimately have zero PENDING/FAILED
     *     earnings if every one of their redemptions rolled up to another already-settled batch)
     */
    private boolean settleOneCreator(String creatorId, String periodYearMonth, AffiliateSettlementBatch batch) {
        List<AffiliateEarning> settleable =
                affiliateEarningRepository.findByCreatorIdAndStatusIn(creatorId, SETTLEABLE_STATUSES);
        if (settleable.isEmpty()) {
            return false;
        }

        BigDecimal total = settleable.stream().map(AffiliateEarning::getCommissionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String idempotencyKey = deriveIdempotencyKey(creatorId, periodYearMonth);

        try {
            idempotencyService.executeOnce(
                    idempotencyKey,
                    null,
                    IDEMPOTENCY_SCOPE,
                    (Supplier<Void>)
                            () -> {
                                doSettleCreator(settleable, batch);
                                return null;
                            });
        } catch (IdempotencyService.AlreadyCompletedException alreadyDone) {
            // Clean, idempotent no-op -- this creator/period pair was already settled by an earlier
            // run (e.g. a manual retry after the batch otherwise completed). Never double-settles.
            return false;
        } catch (IdempotencyService.AlreadyInProgressException inProgress) {
            // A concurrent settlement attempt for this exact creator/period is already running
            // (e.g. an overlapping manual trigger on another node) -- never proceed alongside it.
            log.warn(
                    "AffiliateSettlementJob: settlement for creator {} period {} already in progress"
                            + " elsewhere -- skipping this attempt",
                    creatorId,
                    periodYearMonth);
            return false;
        }

        batch.recordCreatorSettled(total);
        return true;
    }

    /**
     * Runs ONLY inside {@code executeOnce} -- {@code settleable} was already loaded/validated before
     * the idempotency key was reserved (see {@link #settleOneCreator}). Marks every earning in the
     * batch SETTLED and links it to {@code batch.getId()}; on any exception here, {@code
     * executeOnce} marks the key FAILED (retryable, not terminal) and this creator's earnings remain
     * whatever state they were last durably saved in.
     */
    @Transactional
    protected void doSettleCreator(List<AffiliateEarning> settleable, AffiliateSettlementBatch batch) {
        for (AffiliateEarning earning : settleable) {
            earning.markSettled(batch.getId());
            affiliateEarningRepository.save(earning);
        }
    }

    /**
     * Derives the settlement idempotency key from (creatorId, periodYearMonth) -- deliberately NOT
     * (batchId, creatorId). A retried/duplicate job run for the same period must collide with the
     * PRIOR run's key so it is recognized as a replay of the SAME settlement, not a fresh one under
     * a new batch id -- keying by batchId would let every new batch attempt re-settle every creator
     * from scratch, since each batch would mint a distinct key space. Both server-derived values
     * (creator id from the DB sweep, period from either the scheduled trigger's own clock or an
     * explicit backfill argument) -- never caller-supplied in a way that could be manipulated.
     */
    static String deriveIdempotencyKey(String creatorId, String periodYearMonth) {
        return "affiliate.settlement:" + creatorId + ":" + periodYearMonth;
    }
}
