package com.influora.service.tracking;

import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AffiliateEarningsService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records the creator's affiliate commission immediately AFTER the redemption transaction commits
 * (Wave D task D4, revised — see "Why not inside the transaction" below).
 *
 * <h2>Why this exists at all</h2>
 *
 * Commission must be recorded promptly at redemption time. Before Wave D's fix landed, the hourly
 * {@code AffiliateEarningReconciliationJob} was the ONLY thing creating commissions, so every
 * creator's earnings lagged up to ~90 minutes and a disabled cron meant nobody was ever paid.
 * {@code wiki/tech/tracking-subsystem-ruling.md} Q1 (Priya, CTO) ruled that a P0 regression. This
 * listener is what makes recording prompt.
 *
 * <h2>Why NOT inside the redemption transaction — this revises Part B of that ruling</h2>
 *
 * The ruling also required the commission to commit atomically WITH the redemption, on the stated
 * grounds that a failure would be "retried on the next webhook delivery and, failing that, swept by
 * the cron". <b>The second half of that is false, and it is the half that matters.</b> The
 * reconciliation job sweeps redemptions that EXIST but have no matching earning; a redemption that
 * was rolled back does not exist, so the cron can never recover it. Atomicity therefore did not buy
 * a safety net — it bought a new way to lose a sale:
 * {@code AffiliateEarningsService#recordEarning} throws {@code IDEMPOTENCY_KEY_IN_PROGRESS} (409)
 * whenever its inner reservation cannot be taken, and inside the sale's transaction that
 * propagates out and rolls back the redemption row, the coupon usage increment AND the
 * {@code COUPON_REDEEMED} money-audit event. A transient failure on a bookkeeping row destroyed a
 * real sale.
 *
 * <p>Moving the call to {@code AFTER_COMMIT} inverts the failure mode correctly:
 *
 * <ul>
 *   <li>The sale is durable first. Nothing about commission accounting can unwrite it.
 *   <li>The commission still lands in milliseconds, not ~90 minutes — the ruling's actual goal.
 *   <li>If this listener fails, the redemption row EXISTS with no earning, which is precisely the
 *       state the reconciliation cron was built to sweep. The cron becomes the backstop it was
 *       always documented to be, for the first time.
 * </ul>
 *
 * <p>The trade accepted, stated plainly: there is now a brief window (and, on a crash between
 * commit and this listener, up to one cron cycle) where a sale exists with no commission recorded.
 * That is recoverable by design. The previous arrangement's failure — a sale that never existed —
 * was not.
 *
 * <h2>Two Spring mechanics that are load-bearing here</h2>
 *
 * <b>1.</b> {@code @TransactionalEventListener(AFTER_COMMIT)} is SILENTLY NEVER INVOKED for an
 * event published with no transaction active — a trap this codebase has hit before (see
 * {@code SubscriptionDunningJob} and {@code RazorpayWebhookController}'s notes on the same thing).
 * {@link RedemptionWriter#doRedeem} is {@code @Transactional} and is invoked through its own Spring
 * proxy from {@code RedemptionService}, so the event does fire. If that method ever stops being
 * transactional, commissions stop being recorded here and only the cron will catch them.
 *
 * <b>2.</b> {@code REQUIRES_NEW} — after commit there is no transaction left to join, so the
 * commission write needs its own.
 */
@Component
public class AffiliateEarningRecordingListener {

    private static final Logger log = LoggerFactory.getLogger(AffiliateEarningRecordingListener.class);

    private final CouponRedemptionRepository couponRedemptionRepository;
    private final AffiliateEarningsService affiliateEarningsService;

    public AffiliateEarningRecordingListener(
            CouponRedemptionRepository couponRedemptionRepository,
            AffiliateEarningsService affiliateEarningsService) {
        this.couponRedemptionRepository = couponRedemptionRepository;
        this.affiliateEarningsService = affiliateEarningsService;
    }

    /**
     * Never rethrows. After the sale has committed there is nothing left to roll back, so an
     * exception here would only be logged by Spring anyway — but swallowing it EXPLICITLY, at
     * ERROR, is the honest form: it says the sale is safe and the commission is now the cron's
     * problem, rather than leaving a stack trace that reads like the redemption failed.
     *
     * <p>A brand-level (creator-less) coupon is handled inside {@code recordEarning}, which returns
     * null without creating a row — a page-level Festival Box code has nobody to pay. That guard is
     * deliberately not duplicated here; one owner for that rule.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCouponRedeemed(CouponRedeemedEvent event) {
        try {
            Optional<CouponRedemption> redemption =
                    couponRedemptionRepository.findById(event.redemptionId());
            if (redemption.isEmpty()) {
                // Should be unreachable: the row committed before this fired. Logged rather than
                // thrown so a surprise here is visible without pretending the sale is at risk.
                log.error(
                        "Affiliate commission skipped: redemption {} not found after commit —"
                                + " reconciliation job will not see it either, investigate",
                        event.redemptionId());
                return;
            }
            affiliateEarningsService.recordEarning(redemption.get());
        } catch (RuntimeException e) {
            log.error(
                    "Affiliate commission failed for redemption {} — the SALE IS SAFE and committed;"
                            + " AffiliateEarningReconciliationJob will backfill this within its next"
                            + " cycle. A nonzero backfill count there is the alert for this.",
                    event.redemptionId(),
                    e);
        }
    }
}
