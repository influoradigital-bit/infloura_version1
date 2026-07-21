package com.influora.service;

import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [B7/C-6] Webhook-driven {@link Payout} reconciliation — the counterpart to {@link
 * PayoutService#queuePayout}'s queue-time persistence. Called from {@code
 * RazorpayWebhookController} on a verified {@code payout.processed}/{@code payout.reversed}
 * RazorpayX webhook, never synchronously by a client request (same out-of-band-confirm discipline
 * as every other money-state transition in this codebase).
 *
 * <p>Deliberately a separate service/bean from {@link PayoutService} rather than a method on it:
 * {@link PayoutService}'s constructor is pinned by its existing unit test suite (fund-account
 * resolution + idempotent queueing, no wallet-ledger dependency) — adding {@link
 * WalletLedgerService}/{@link PlatformWalletService}/{@link WalletService} to that constructor
 * would break that suite's exact wiring. Splitting the webhook-reconciliation concern out here
 * keeps {@code PayoutService}'s pinned shape intact while still giving the re-credit-on-reversal
 * logic below the wallet-ledger access it needs.
 */
@Service
public class PayoutReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationService.class);

    /** RazorpayX payout status meaning the payout failed/bounced back — money never reached the creator. */
    private static final String STATUS_REVERSED = "reversed";

    private final PayoutRepository payoutRepository;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final RazorpayXClient razorpayXClient;

    public PayoutReconciliationService(
            PayoutRepository payoutRepository,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            WalletService walletService,
            WalletTransactionRepository walletTransactionRepository,
            RazorpayXClient razorpayXClient) {
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.walletService = walletService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.razorpayXClient = razorpayXClient;
    }

    /**
     * Applies a verified {@code payout.processed}/{@code payout.reversed}/{@code payout.rejected}
     * webhook to the matching {@link Payout} row (looked up by {@code razorpayPayoutId} — the id
     * {@link PayoutService#queuePayout} persisted at queue time). Idempotent: a duplicate webhook
     * delivery reporting the SAME status is a no-op (checked before touching the wallet ledger),
     * and — for a genuine {@code reversed} — the re-credit itself is idempotent via the ledger's
     * own {@code idempotencyKey} dedup, so a retried delivery can never re-credit the creator
     * twice.
     *
     * @param razorpayPayoutId RazorpayX's own payout id (webhook {@code payload.payout.entity.id})
     * @param newStatus the webhook's reported terminal status (RazorpayX payout status values:
     *     {@code processing}, {@code processed}, {@code reversed}, {@code cancelled}, {@code
     *     rejected})
     * @param rawWebhookPayload the full verified webhook body, stored on the row for audit/replay
     */
    @Transactional
    public void confirmExecuted(String razorpayPayoutId, String newStatus, String rawWebhookPayload) {
        if (razorpayPayoutId == null || razorpayPayoutId.isBlank()) {
            log.warn("payout webhook missing payload.payout.entity.id — nothing to reconcile");
            return;
        }

        Payout payout = payoutRepository.findByRazorpayPayoutId(razorpayPayoutId).orElse(null);
        if (payout == null) {
            // Defensive only: every payout queued via PayoutService#doQueuePayout after the B7/C-5/C-6
            // fix persists a row up front, so this should not happen for a genuinely-queued payout.
            // Ack the webhook rather than 500/retry-storm on an id we have no record of (e.g. a
            // payout queued before this fix shipped, or a manual RazorpayX dashboard action outside
            // this codebase).
            log.warn("No Payout row found for razorpayPayoutId={} — webhook acknowledged, not applied", razorpayPayoutId);
            return;
        }

        if (newStatus != null && newStatus.equals(payout.getStatus())) {
            // Duplicate delivery reporting the same status this row already has — no-op.
            return;
        }

        boolean wasAlreadyReversed = STATUS_REVERSED.equals(payout.getStatus());
        payout.confirmStatus(newStatus, rawWebhookPayload);
        payoutRepository.save(payout);

        // [C-6] Re-credit on reversal — a payout that RazorpayX reports as `reversed` means the
        // money never actually reached the creator's bank/UPI account (bounced, rejected by the
        // bank, etc.), but EscrowService#release already credited the creator's Influora wallet's
        // NET amount out of escrow before the payout was ever queued. Without this, the creator
        // would be left short — their wallet no longer reflects money that, in reality, never left
        // the platform. Re-crediting the clearing wallet -> creator wallet mirrors a refund, using
        // the same ledger idempotency-key dedup every other money movement in this codebase relies
        // on, so a retried `reversed` delivery can never double-credit.
        if (STATUS_REVERSED.equals(newStatus) && !wasAlreadyReversed) {
            reCreditReversedPayout(payout, razorpayPayoutId);
        }
    }

    /**
     * [P1 fix, SEC: Kabir, landed-money-path audit 2b — orphaned-debit sweeper.] Called by {@code
     * PayoutOrphanedDebitSweepJob} for every {@link Payout} row still sitting in {@link
     * Payout#STATUS_PENDING} past the sweep's grace period — i.e. {@code
     * PayoutService#doQueuePayout} persisted the row before calling RazorpayX, but no result was
     * ever durably recorded (a crash/failure between the wallet debit committing and the gateway
     * response being saved; see that method's javadoc).
     *
     * <p>Two cases, distinguished by whether the queue-time debit actually posted:
     *
     * <ul>
     *   <li><b>No debit posted</b> ({@code payout-debit:&lt;milestoneId&gt;} ledger key absent) —
     *       the process crashed before ever debiting the creator. There is no orphaned debit to
     *       reconcile; this row is simply awaiting a legitimate retry through {@code
     *       PayoutService#queuePayout} (which will find and reuse this same row by {@code
     *       idempotencyKey}). Logged only, no money movement.
     *   <li><b>Debit posted</b> — retry the (idempotent) RazorpayX call first, reusing the SAME
     *       {@code idempotencyKey}/{@code reference_id} the original attempt used, so this can
     *       never double-pay even if the original call actually succeeded and only the local
     *       confirmation was lost. Only if that retry itself fails do we reverse the debit — the
     *       SAME re-credit path {@link #confirmExecuted} already uses for a genuine RazorpayX
     *       {@code reversed} webhook (C-6), kept as the one re-credit code path in this codebase
     *       rather than inventing a second one.
     * </ul>
     */
    @Transactional
    public void reconcileOrphanedPendingPayout(Payout payout) {
        if (!Payout.STATUS_PENDING.equals(payout.getStatus())) {
            // Already progressed past PENDING between the sweep query running and this row being
            // processed (e.g. the original request finally completed, or a prior sweep run already
            // handled it) — nothing to do.
            return;
        }

        boolean debitPosted =
                walletTransactionRepository
                        .findByIdempotencyKey("payout-debit:" + payout.getMilestoneId() + ":D")
                        .isPresent();
        if (!debitPosted) {
            log.warn(
                    "PayoutReconciliation: PENDING payout {} (milestone {}) has no debit posted yet —"
                            + " nothing orphaned, awaiting a legitimate retry via PayoutService#queuePayout",
                    payout.getId(),
                    payout.getMilestoneId());
            return;
        }

        try {
            RazorpayXClient.PayoutResult result =
                    razorpayXClient.initiatePayout(
                            payout.getFundAccountId(),
                            payout.getAmount(),
                            payout.getCurrency(),
                            payout.getIdempotencyKey());
            payout.markGatewayConfirmed(result.payoutId(), result.status());
            payoutRepository.save(payout);
            log.info(
                    "PayoutReconciliation: retried orphaned-debit payout {} — gateway confirmed"
                            + " razorpayPayoutId={} status={}",
                    payout.getId(),
                    result.payoutId(),
                    result.status());
        } catch (Exception e) {
            log.error(
                    "PayoutReconciliation: gateway retry failed for orphaned-debit payout {} (milestone"
                            + " {}) — reversing the debit instead",
                    payout.getId(),
                    payout.getMilestoneId(),
                    e);
            boolean wasAlreadyReversed = STATUS_REVERSED.equals(payout.getStatus());
            payout.confirmStatus(STATUS_REVERSED, null);
            payoutRepository.save(payout);
            if (!wasAlreadyReversed) {
                reCreditReversedPayout(payout, payout.getRazorpayPayoutId());
            }
        }
    }

    private void reCreditReversedPayout(Payout payout, String razorpayPayoutId) {
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(payout.getCreatorUserId());

        String ledgerIdempotencyKey = "payout-reversed:" + payout.getId();
        ledgerService.post(
                clearingWallet.getId(),
                creatorWallet.getId(),
                payout.getAmount(),
                payout.getCurrency(),
                WalletTransactionType.PAYOUT,
                TxnReferenceType.MILESTONE,
                payout.getMilestoneId(),
                "Payout reversed by RazorpayX — re-credited to creator wallet",
                ledgerIdempotencyKey,
                razorpayPayoutId);
    }
}
