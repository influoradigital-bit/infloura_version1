package com.influora.job;

import com.influora.domain.entity.WalletTopUp;
import com.influora.domain.enums.WalletTopUpStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.WalletTopUpRepository;
import com.influora.service.WalletTopUpService;
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
 * Credits a wallet top-up whose {@code payment.captured} webhook never arrived.
 *
 * <p>The only thing that credits a top-up is a signature-verified webhook ({@code
 * RazorpayWebhookController} then {@link WalletTopUpService#confirmCredited}). There was no other
 * path: no verify-on-return endpoint (the browser discards the payment signature), no sweep, and
 * the admin finance console's reconciliation view is read-only. A single missed delivery — a deploy
 * inside the delivery window, a 5xx, a webhook misconfiguration — therefore left money taken by
 * Razorpay and never credited, with nothing in the product to notice or repair it.
 *
 * <p>Razorpay is the source of truth here, never the client: this asks the gateway whether the
 * order was actually paid and credits only on {@code paid}, reusing {@code confirmCredited} so the
 * ledger idempotency key, the amount/currency validation and the PENDING to CREDITED transition
 * are identical to the webhook path. A row the webhook credits first is a no-op here, and the
 * reverse is equally safe.
 *
 * <p>Mirrors {@link AffiliateEarningReconciliationJob}: hourly, {@code @SchedulerLock} so only one
 * node sweeps, an {@link AtomicBoolean} so a slow run never overlaps itself, a per-row catch so one
 * failure cannot abort the sweep, and WARN logging on every repair because a nonzero count means a
 * webhook WAS missed — a real defect signal, not routine bookkeeping.
 */
@Component
public class WalletTopUpReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(WalletTopUpReconciliationJob.class);

    /**
     * How long the webhook is given to arrive on its own before the gateway is asked. Razorpay
     * delivers in seconds, so this is generous enough that a healthy delivery is never
     * second-guessed.
     */
    static final Duration WEBHOOK_GRACE_PERIOD = Duration.ofMinutes(20);

    /**
     * How far back to keep asking. Beyond this a PENDING row is treated as an abandoned checkout —
     * the normal outcome for a brand who opened the Razorpay dialog and never paid — so the sweep
     * stops spending a gateway call on it every hour forever.
     */
    static final Duration GIVE_UP_AFTER = Duration.ofDays(7);

    private final WalletTopUpRepository topUpRepository;
    private final WalletTopUpService topUpService;
    private final RazorpayClient razorpayClient;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public WalletTopUpReconciliationJob(
            WalletTopUpRepository topUpRepository,
            WalletTopUpService topUpService,
            RazorpayClient razorpayClient) {
        this.topUpRepository = topUpRepository;
        this.topUpService = topUpService;
        this.razorpayClient = razorpayClient;
    }

    /** Offset from the affiliate sweep at :15 so the two never contend for the same window. */
    @Scheduled(cron = "0 35 * * * *")
    @SchedulerLock(name = "WalletTopUpReconciliationJob", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void reconcileUncreditedTopUps() {
        if (!running.compareAndSet(false, true)) {
            log.warn("WalletTopUpReconciliationJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runReconciliation();
        } finally {
            running.set(false);
        }
    }

    private void runReconciliation() {
        // Without credentials every fetch comes back "unconfigured" (RazorpayClient fails closed),
        // so the sweep could only log. Skip it outright in that environment.
        if (!razorpayClient.isConfigured()) {
            return;
        }

        Instant now = Instant.now();
        List<WalletTopUp> candidates =
                topUpRepository.findByStatusAndCreatedAtBetween(
                        WalletTopUpStatus.PENDING, now.minus(GIVE_UP_AFTER), now.minus(WEBHOOK_GRACE_PERIOD));
        if (candidates.isEmpty()) {
            return;
        }

        int credited = 0;
        int stillUnpaid = 0;
        int failed = 0;

        for (WalletTopUp topUp : candidates) {
            try {
                if (topUp.getRazorpayOrderId() == null || topUp.getRazorpayOrderId().isBlank()) {
                    // initiateTopUp always stores the order id, so this is a data anomaly rather
                    // than an uncredited payment. Nothing to ask the gateway about.
                    log.error(
                            "WalletTopUpReconciliationJob: top-up {} (workspace {}) is PENDING with no"
                                    + " Razorpay order id — manual review required",
                            topUp.getId(),
                            topUp.getWorkspaceId());
                    failed++;
                    continue;
                }

                RazorpayClient.OrderPaymentState state =
                        razorpayClient.fetchOrderPaymentState(topUp.getRazorpayOrderId());
                if (!state.isPaid()) {
                    stillUnpaid++;
                    continue;
                }

                // The same entry point the webhook uses, so the amount/currency check still runs
                // against what the gateway reports as actually paid and the ledger key is unchanged.
                topUpService.confirmCredited(
                        topUp.getId(), topUp.getRazorpayOrderId(), state.amountPaidInPaise(), state.currency());
                credited++;
                log.warn(
                        "WalletTopUpReconciliationJob: credited top-up {} ({} {}) for workspace {} from"
                                + " Razorpay order {} — its payment.captured webhook never arrived, which"
                                + " should be investigated",
                        topUp.getId(),
                        topUp.getAmount(),
                        topUp.getCurrency(),
                        topUp.getWorkspaceId(),
                        topUp.getRazorpayOrderId());
            } catch (Exception e) {
                // One row's failure (a gateway error, or an amount mismatch that needs a human)
                // must never abort the rest of the sweep; it stays a candidate for the next run.
                failed++;
                log.error(
                        "WalletTopUpReconciliationJob: could not reconcile top-up {} (order {}) — will"
                                + " retry next run",
                        topUp.getId(),
                        topUp.getRazorpayOrderId(),
                        e);
            }
        }

        if (credited > 0 || failed > 0) {
            log.warn(
                    "WalletTopUpReconciliationJob: swept {} PENDING top-up(s) — credited {}, still"
                            + " unpaid {}, failed {}",
                    candidates.size(),
                    credited,
                    stillUnpaid,
                    failed);
        }
    }
}
