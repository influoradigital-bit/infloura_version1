package com.influora.job;

import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.service.credits.CreatorCreditOrderService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.6, K-08) — cloned from {@link
 * com.influora.service.WalletTopUpService}'s reconciliation sibling, {@code
 * WalletTopUpReconciliationJob}: credits a creator-credit order whose {@code order.paid}/{@code
 * payment.captured} webhook never arrived. Same 20-minute grace / 7-day give-up / {@code
 * @SchedulerLock} / {@code AtomicBoolean} shape, and the same "Razorpay is the source of truth,
 * reused through {@code confirmPaid} so the amount/currency check and the PENDING→CREDITED
 * transition are identical to the webhook path" reasoning.
 *
 * <p>NOT flag-gated (K-24, same as {@code confirmPaid} itself) — a stranded PENDING order is
 * repaired here even if {@code CREATOR_CREDITS_ENABLED} has since been turned off.
 */
@Component
public class CreatorCreditOrderReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditOrderReconciliationJob.class);

    static final Duration WEBHOOK_GRACE_PERIOD = Duration.ofMinutes(20);
    static final Duration GIVE_UP_AFTER = Duration.ofDays(7);

    private final CreatorCreditOrderService orderService;
    private final RazorpayClient razorpayClient;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public CreatorCreditOrderReconciliationJob(CreatorCreditOrderService orderService, RazorpayClient razorpayClient) {
        this.orderService = orderService;
        this.razorpayClient = razorpayClient;
    }

    /** :50 — an unused hourly minute (review-sla :05, an unnamed job :15, WalletTopUpReconciliationJob :35, another unnamed job :45). */
    @Scheduled(cron = "0 50 * * * *")
    @SchedulerLock(name = "CreatorCreditOrderReconciliationJob", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void reconcileUncreditedOrders() {
        if (!running.compareAndSet(false, true)) {
            log.warn("CreatorCreditOrderReconciliationJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runReconciliation();
        } finally {
            running.set(false);
        }
    }

    private void runReconciliation() {
        if (!razorpayClient.isConfigured()) {
            return;
        }

        Instant now = Instant.now();

        // F-6: orders that crossed the 7-day give-up horizon without ever being picked up by the
        // window below (which stops at that same horizon) get an ERROR alert every run until
        // someone investigates — silence here previously meant these just stopped being
        // considered at all, with no signal that a creator's money was stuck.
        for (CreatorCreditOrder order : orderService.findGivenUpPending(now.minus(GIVE_UP_AFTER))) {
            log.error(
                    "CreatorCreditOrderReconciliationJob: order {} (creator {}, razorpay order {}) has"
                            + " been PENDING for over {} with no order.paid/payment.captured webhook and"
                            + " was never auto-credited — manual investigation required",
                    order.getId(),
                    order.getCreatorUserId(),
                    order.getRazorpayOrderId(),
                    GIVE_UP_AFTER);
        }

        List<CreatorCreditOrder> candidates =
                orderService.findStalePending(now.minus(GIVE_UP_AFTER), now.minus(WEBHOOK_GRACE_PERIOD));
        if (candidates.isEmpty()) {
            return;
        }

        int credited = 0;
        int stillUnpaid = 0;
        int failed = 0;

        for (CreatorCreditOrder order : candidates) {
            try {
                if (order.getRazorpayOrderId() == null || order.getRazorpayOrderId().isBlank()) {
                    log.error(
                            "CreatorCreditOrderReconciliationJob: order {} (creator {}) is PENDING with no"
                                    + " Razorpay order id — manual review required",
                            order.getId(),
                            order.getCreatorUserId());
                    failed++;
                    continue;
                }

                RazorpayClient.OrderPaymentState state =
                        razorpayClient.fetchOrderPaymentState(order.getRazorpayOrderId());
                if (!state.isPaid()) {
                    stillUnpaid++;
                    continue;
                }

                // No razorpay_payment_id from a plain order-state fetch (unlike the webhook, which
                // carries payload.payment.entity.id) — confirmPaid's own payment-id UNIQUE
                // constraint still protects against a double-credit if a webhook lands moments
                // later with the real payment id; that later call is then the CREDITED
                // short-circuit, a clean no-op.
                orderService.confirmPaid(
                        order.getId(),
                        "reconciled:" + order.getRazorpayOrderId(),
                        order.getRazorpayOrderId(),
                        state.amountPaidInPaise(),
                        state.currency());
                credited++;
                log.warn(
                        "CreatorCreditOrderReconciliationJob: credited order {} ({} credits) for creator"
                                + " {} from Razorpay order {} — its order.paid/payment.captured webhook"
                                + " never arrived, which should be investigated",
                        order.getId(),
                        order.getCredits(),
                        order.getCreatorUserId(),
                        order.getRazorpayOrderId());
            } catch (Exception e) {
                failed++;
                log.error(
                        "CreatorCreditOrderReconciliationJob: could not reconcile order {} (razorpay order"
                                + " {}) — will retry next run",
                        order.getId(),
                        order.getRazorpayOrderId(),
                        e);
            }
        }

        if (credited > 0 || failed > 0) {
            log.warn(
                    "CreatorCreditOrderReconciliationJob: swept {} PENDING order(s) — credited {}, still"
                            + " unpaid {}, failed {}",
                    candidates.size(),
                    credited,
                    stillUnpaid,
                    failed);
        }
    }
}
