package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [EV-003] The two short, genuinely-transactional halves of a creator withdrawal, with the
 * RazorpayX call deliberately left OUTSIDE both of them.
 *
 * <p><b>Why a separate bean and not two methods on {@code WalletService}.</b> Spring applies
 * {@code @Transactional} through an AOP proxy, so a {@code @Transactional} method a class calls on
 * {@code this} is a no-op — the established, repeatedly-bitten fact in this codebase (see {@code
 * PayoutService}'s class javadoc, which documents {@code doQueuePayout}'s {@code @Transactional} as
 * a known no-op, and {@link IdempotencyReservationOps}, which exists for exactly the same reason).
 * {@code WalletService} needs these two steps to be real, separately-committing transactions with a
 * non-transactional gap between them; self-invocation cannot give it that, so they live on a bean
 * it injects.
 *
 * <p><b>The defect this fixes.</b> {@code WalletService#requestCreatorWithdrawal} was annotated
 * {@code @Transactional} and did everything inside that one transaction: it took a pessimistic
 * write lock on the creator's wallet, took a second lock on the platform clearing wallet inside
 * {@link WalletLedgerService#post}, and then — still holding both — resolved a RazorpayX fund
 * account and called the RazorpayX payout API. Two consequences, both money-path:
 *
 * <ul>
 *   <li><b>A slow gateway holds money locks.</b> The clearing wallet is the counterparty of every
 *       top-up, escrow movement and payout on the platform, so one creator's withdrawal sitting on
 *       a RazorpayX socket timeout blocks every other wallet posting behind it for the duration.
 *   <li><b>A partial failure can pay out without a committed debit.</b> If the RazorpayX request
 *       reached the gateway but the response did not reach us, the surrounding transaction rolls
 *       back — the debit disappears, the creator's balance is restored, and the bank transfer is
 *       still in flight. The money leaves twice.
 * </ul>
 *
 * <p><b>The shape now.</b> {@link #reserveWithdrawal} commits the {@link Payout#STATUS_PENDING} row
 * AND the ledger debit in one short transaction, and releases both wallet locks at its commit. The
 * gateway is called with no transaction open at all. {@link #recordGatewayResult} then commits the
 * gateway's answer in a second short transaction, and only after THAT commits does {@code
 * IdempotencyService#executeOnce} mark the reservation COMPLETED.
 *
 * <p>Note this is deliberately NOT the ordering {@code PayoutService#doQueuePayout} uses. That
 * method writes the PENDING row in one transaction and the debit in another, because it has no real
 * ambient transaction to put them in; a crash between the two leaves a PENDING row with no debit,
 * which its sweep then has to recognise and ignore. Here both are in one transaction, so that
 * particular in-between state cannot exist: a crash before the commit leaves nothing at all, and
 * after it leaves exactly one PENDING row and exactly one debit.
 *
 * <p><b>The window that remains, and who closes it.</b> Between {@link #reserveWithdrawal}
 * committing and {@link #recordGatewayResult} committing, the creator is debited against a payout
 * whose gateway outcome is not recorded. That is the orphaned-debit window, and it is why the row
 * is written {@code PENDING} first: {@code PayoutOrphanedDebitSweepJob} picks up every {@code
 * PENDING} payout older than its grace period and hands it to {@code
 * PayoutReconciliationService#reconcileOrphanedPendingPayout}, which re-drives the (idempotent)
 * gateway call under the SAME key and reverses the debit only if that fails.
 *
 * <p>This bean holds no reference to {@link RazorpayXClient} — only to repositories and the ledger.
 * That is a structural guarantee, not a convention: no method here can make a gateway call inside
 * its transaction, and {@code WalletServiceWithdrawalTransactionBoundaryTest} asserts it stays that
 * way.
 */
@Service
public class CreatorWithdrawalOps {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PayoutRepository payoutRepository;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;

    public CreatorWithdrawalOps(
            WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository,
            PayoutRepository payoutRepository,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService) {
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
    }

    /**
     * What the caller needs after phase one in order to make the gateway call and then record its
     * result, without reopening a transaction to look either of them up again.
     */
    public record ReservedWithdrawal(String payoutRowId, String currency) {}

    /**
     * Phase one — one short transaction: lock the creator's wallet, re-check the balance and the
     * daily cap under that lock, persist the {@link Payout#STATUS_PENDING} intent, and post the
     * ledger debit. Commits, releasing both wallet locks, before the caller touches RazorpayX.
     *
     * <p><b>Retry semantics.</b> Both writes are keyed on {@code scopedKey}: {@code
     * payouts.idempotency_key} is {@code UNIQUE} and {@link WalletLedgerService#post} dedupes on
     * {@code uq_wtx_idem}. A second call with the same key therefore finds the existing payout row
     * and replays the existing posting instead of creating a second of either — which is what makes
     * a retry after a gateway timeout safe. When the row already exists the balance and daily-cap
     * checks are deliberately SKIPPED: the money was already committed to this withdrawal on the
     * first attempt and has already left the balance, so re-running those checks would reject a
     * legitimate retry with {@code INSUFFICIENT_BALANCE}.
     *
     * @param maxWithdrawalsPerDay the caller's own policy constant, passed in rather than
     *     duplicated here so {@code WalletService} stays the single place that states the limit.
     */
    @Transactional
    public ReservedWithdrawal reserveWithdrawal(
            String userId,
            String fundAccountId,
            BigDecimal amount,
            String scopedKey,
            int maxWithdrawalsPerDay) {

        // Pessimistic owner lock serialises concurrent withdrawals for the same creator so the
        // balance and daily-count checks cannot race ahead of ledgerService.post (Kabir
        // M-18-1/M-18-2). Held only for this transaction now — never across the gateway call.
        Wallet wallet =
                walletRepository
                        .findByOwnerIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INSUFFICIENT_BALANCE",
                                                "Insufficient available balance",
                                                HttpStatus.BAD_REQUEST));

        Payout payout = payoutRepository.findByIdempotencyKey(scopedKey).orElse(null);
        boolean firstAttempt = payout == null;

        if (firstAttempt) {
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new ApiException(
                        "INSUFFICIENT_BALANCE",
                        "Insufficient available balance",
                        HttpStatus.BAD_REQUEST);
            }

            Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
            long withdrawalsToday =
                    walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                            wallet.getId(), WalletTransactionType.WITHDRAWAL, dayStart);
            if (withdrawalsToday >= maxWithdrawalsPerDay) {
                throw new ApiException(
                        "WITHDRAWAL_RATE_LIMIT",
                        "Maximum " + maxWithdrawalsPerDay + " withdrawals per day",
                        HttpStatus.TOO_MANY_REQUESTS);
            }

            payout =
                    Payout.createPending(
                            Ulids.newUlid(),
                            null, // lump-sum wallet withdrawal — not tied to any milestone
                            userId,
                            fundAccountId,
                            amount,
                            wallet.getCurrency(),
                            scopedKey,
                            Instant.now());
            payoutRepository.save(payout);
        }

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        ledgerService.post(
                wallet.getId(),
                clearingWallet.getId(),
                amount,
                wallet.getCurrency(),
                WalletTransactionType.WITHDRAWAL,
                TxnReferenceType.MANUAL,
                payout.getId(),
                "Creator withdrawal",
                scopedKey,
                null);

        return new ReservedWithdrawal(payout.getId(), wallet.getCurrency());
    }

    /**
     * Phase three — one short transaction recording what RazorpayX answered. Separated from phase
     * one purely so the gateway call sits between two committed transactions rather than inside
     * one; nothing here can block on anything external.
     *
     * <p>Idempotent on re-entry: a row already advanced past {@link Payout#STATUS_PENDING} (the
     * reaper resolved it first, or a duplicate call arrived) is left exactly as it is rather than
     * being overwritten with this call's older view of the world.
     */
    @Transactional
    public void recordGatewayResult(String payoutRowId, String razorpayPayoutId, String status) {
        Payout payout =
                payoutRepository
                        .findById(payoutRowId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "PAYOUT_NOT_FOUND",
                                                "The withdrawal's payout record disappeared before its"
                                                        + " gateway result could be recorded",
                                                HttpStatus.INTERNAL_SERVER_ERROR));

        if (!Payout.STATUS_PENDING.equals(payout.getStatus())) {
            return;
        }

        payout.markGatewayConfirmed(razorpayPayoutId, status);
        payoutRepository.save(payout);
    }
}
