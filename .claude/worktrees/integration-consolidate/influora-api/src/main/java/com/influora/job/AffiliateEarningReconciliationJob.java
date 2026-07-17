package com.influora.job;

import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AffiliateEarningsService;
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
 * [SEC: Kabir, Wave D task D4 HIGH-1 -- reconciliation floor] Sweeps for {@link CouponRedemption}
 * rows with no matching {@code affiliate_earnings.redemption_id} row and backfills them by
 * re-invoking {@link AffiliateEarningsService#recordEarning}.
 *
 * <p><b>Why this exists even after the self-invocation transactional fix</b> -- {@code
 * RedemptionService#doRedeem} now runs inside a real, proxy-honored {@code @Transactional}
 * boundary (see that class's javadoc for the "[SEC: Kabir ... FIXED]" history), so a {@code
 * RuntimeException} thrown by {@code affiliateEarningsService.recordEarning} correctly rolls back
 * the whole redemption and is retried in full on the next webhook delivery. This job is the
 * belt-and-suspenders floor Kabir's report explicitly asked for on top of that fix, covering the
 * residual gaps the transaction boundary alone cannot: a process crash/kill between this
 * transaction's commit and the idempotency row being marked COMPLETED, a non-{@code
 * RuntimeException} {@code Throwable} escaping {@code IdempotencyService#runAndFinalize}'s {@code
 * catch (RuntimeException ...)} clause uncaught, or any future code path that calls {@code
 * recordEarning} outside of {@code doRedeem}'s transaction entirely. Regardless of *why* a
 * redemption ended up without a corresponding earning, this job makes the system self-healing
 * rather than dependent on every call site's exception handling being exactly right forever.
 *
 * <p><b>Idempotent and safe to re-run</b> -- {@link AffiliateEarningsService#recordEarning} already
 * has its own replay guard ({@code UNIQUE(redemption_id)} on {@code affiliate_earnings}, V27) and
 * validate-before-executeOnce discipline, so calling it again for a redemption that (by the time
 * this job gets to it) already has an earning is a clean no-op, not a double-credit. This job adds
 * no new idempotency mechanism of its own -- it is a pure caller of the existing one, exactly the
 * "future retry/backfill path" {@code AffiliateEarningsService}'s own class javadoc already
 * anticipates.
 *
 * <p><b>Grace window</b> -- only redemptions older than {@link #RECONCILIATION_GRACE_PERIOD} are
 * considered, so this sweep never races a redemption that is still legitimately mid-transaction on
 * another node (mirrors {@code StaleTokenCleanupJob}'s grace-period reasoning for the same class of
 * "don't act on something that might still resolve itself normally" concern).
 *
 * <p><b>One redemption's failure never aborts the sweep</b> -- same defensive per-item try/catch
 * discipline as {@code AffiliateSettlementJob}/{@code MetricsPollingJob}/{@code
 * StaleTokenCleanupJob}.
 */
@Component
public class AffiliateEarningReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(AffiliateEarningReconciliationJob.class);

    /**
     * How long a redemption may sit without a matching affiliate earning before this job treats it
     * as orphaned rather than merely still-in-flight. Generous relative to how long any single
     * {@code doRedeem} transaction could plausibly take.
     */
    static final Duration RECONCILIATION_GRACE_PERIOD = Duration.ofMinutes(30);

    private final CouponRedemptionRepository redemptionRepository;
    private final AffiliateEarningsService affiliateEarningsService;
    private final AuditLogService auditLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public AffiliateEarningReconciliationJob(
            CouponRedemptionRepository redemptionRepository,
            AffiliateEarningsService affiliateEarningsService,
            AuditLogService auditLogService) {
        this.redemptionRepository = redemptionRepository;
        this.affiliateEarningsService = affiliateEarningsService;
        this.auditLogService = auditLogService;
    }

    /** Runs hourly -- frequent enough that a silently-lost commission is caught well within the same business day. */
    @Scheduled(cron = "0 15 * * * *")
    @SchedulerLock(name = "AffiliateEarningReconciliationJob", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void reconcileMissingAffiliateEarnings() {
        if (!running.compareAndSet(false, true)) {
            log.warn("AffiliateEarningReconciliationJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runReconciliation();
        } finally {
            running.set(false);
        }
    }

    private void runReconciliation() {
        Instant olderThan = Instant.now().minus(RECONCILIATION_GRACE_PERIOD);
        List<CouponRedemption> orphaned = redemptionRepository.findOrphanedWithoutAffiliateEarning(olderThan);

        int backfilled = 0;
        int failed = 0;

        for (CouponRedemption redemption : orphaned) {
            try {
                affiliateEarningsService.recordEarning(redemption);
                backfilled++;
                log.warn(
                        "AffiliateEarningReconciliationJob: backfilled missing affiliate earning for"
                                + " redemption {} (coupon {}) -- this indicates a prior recordEarning"
                                + " failure that should be investigated",
                        redemption.getId(),
                        redemption.getCouponId());
            } catch (Exception e) {
                // Defensive catch-all mirroring AffiliateSettlementJob/StaleTokenCleanupJob -- one
                // redemption's unexpected failure must never abort the rest of the sweep. It
                // remains a candidate for the next hourly run.
                failed++;
                log.error(
                        "AffiliateEarningReconciliationJob: failed to backfill affiliate earning for"
                                + " redemption {} -- will retry next run",
                        redemption.getId(),
                        e);
            }
        }

        // [Priya's ruling, wiki/tech/tracking-subsystem-ruling.md, P1 monitoring] Post-fix, a
        // nonzero backfill count is no longer "routine belt-and-suspenders" -- it means the
        // synchronous RedemptionService#performRedemption -> AffiliateEarningsService#recordEarning
        // path MISSED a redemption (a real defect signal), not an expected residual gap. Log at
        // WARN with the count so this is visible in aggregate, not just per-item.
        if (backfilled > 0) {
            log.warn(
                    "AffiliateEarningReconciliationJob: backfilled {} missing affiliate earning(s) this run"
                            + " -- nonzero backfill indicates the synchronous recordEarning path missed"
                            + " one or more redemptions and should be investigated",
                    backfilled);
        }

        if (!orphaned.isEmpty() || failed > 0) {
            auditLogService.recordToolCall(
                    null,
                    "AFFILIATE_EARNING_RECONCILIATION_COMPLETED",
                    "SYSTEM",
                    failed > 0 ? AuditLogService.OUTCOME_FAILED : AuditLogService.OUTCOME_ALLOWED,
                    null,
                    null,
                    null,
                    Map.of(
                            "orphanedRedemptionsFound", orphaned.size(),
                            "backfilled", backfilled,
                            "failed", failed));
        }
    }
}
