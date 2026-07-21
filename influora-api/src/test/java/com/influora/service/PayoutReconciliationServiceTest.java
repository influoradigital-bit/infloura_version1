package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.integration.razorpay.RazorpayXClient.PayoutResult;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [P1 fix, SEC: Kabir, landed-money-path audit 2b] Unit tests for {@link
 * PayoutReconciliationService#reconcileOrphanedPendingPayout} -- the orphaned-debit sweeper's
 * actual reconciliation logic (called per stuck row by {@code PayoutOrphanedDebitSweepJob}).
 * Priority: proving the sweeper never double-pays (retries the idempotent gateway call first) and
 * never leaves a genuinely-failed payout's debit stranded (reverses via the SAME re-credit path
 * {@link #testConfirmExecutedReCreditsOnReversedWebhook} already exercises for a real webhook).
 */
@ExtendWith(MockitoExtension.class)
class PayoutReconciliationServiceTest {

    private static final String PAYOUT_ID = "01HPAYOUT1234567890AB";
    private static final String MILESTONE_ID = "01HMILESTONE1234567AB";
    private static final String CREATOR_ID = "01HCREATORUSER1234567";
    private static final String FUND_ACCOUNT_ID = "fa_test123";
    private static final String IDEMPOTENCY_KEY = "payout:" + MILESTONE_ID;
    private static final String CREATOR_WALLET_ID = "01HCREATORWALLET12345";
    private static final String CLEARING_WALLET_ID = "01HCLEARINGWALLET1234";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(4250);

    @Mock private PayoutRepository payoutRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private WalletService walletService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private RazorpayXClient razorpayXClient;

    private PayoutReconciliationService service;

    @BeforeEach
    void setUp() {
        service =
                new PayoutReconciliationService(
                        payoutRepository,
                        ledgerService,
                        platformWalletService,
                        walletService,
                        walletTransactionRepository,
                        razorpayXClient);
    }

    private Payout pendingPayout() {
        return Payout.createPending(
                PAYOUT_ID,
                MILESTONE_ID,
                CREATOR_ID,
                FUND_ACCOUNT_ID,
                AMOUNT,
                "INR",
                IDEMPOTENCY_KEY,
                Instant.now());
    }

    // ------------------------------------------------------------------
    // [P1] reconcileOrphanedPendingPayout -- the sweeper's core logic
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "reconcileOrphanedPendingPayout: no debit posted yet -- not orphaned, no money movement,"
                    + " row left PENDING for a legitimate retry via PayoutService#queuePayout")
    void testNoDebitPostedIsNotOrphanedAndDoesNothing() {
        Payout payout = pendingPayout();
        when(walletTransactionRepository.findByIdempotencyKey("payout-debit:" + MILESTONE_ID + ":D"))
                .thenReturn(Optional.empty());

        service.reconcileOrphanedPendingPayout(payout);

        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(payoutRepository, never()).save(any());
        assertEquals(Payout.STATUS_PENDING, payout.getStatus());
    }

    @Test
    @DisplayName(
            "reconcileOrphanedPendingPayout: debit posted, gateway retry succeeds -- confirms the SAME"
                    + " row via the idempotent reference_id, never re-credits/reverses")
    void testDebitPostedGatewayRetrySucceedsConfirmsRow() {
        Payout payout = pendingPayout();
        when(walletTransactionRepository.findByIdempotencyKey("payout-debit:" + MILESTONE_ID + ":D"))
                .thenReturn(Optional.of(debitLeg()));
        when(razorpayXClient.initiatePayout(FUND_ACCOUNT_ID, AMOUNT, "INR", IDEMPOTENCY_KEY))
                .thenReturn(new PayoutResult("payout_recovered_1", "queued"));

        service.reconcileOrphanedPendingPayout(payout);

        assertEquals("queued", payout.getStatus());
        assertEquals("payout_recovered_1", payout.getRazorpayPayoutId());
        verify(payoutRepository, times(1)).save(payout);
        // A successful retry is NOT a reversal -- the creator must never be re-credited on top of
        // a payout that (per RazorpayX's own idempotent dedup) is legitimately in flight/complete.
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "reconcileOrphanedPendingPayout: debit posted, gateway retry itself fails -- reverses the"
                    + " debit via the SAME re-credit path confirmExecuted uses for a real `reversed` webhook")
    void testDebitPostedGatewayRetryFailsReversesDebit() {
        Payout payout = pendingPayout();
        when(walletTransactionRepository.findByIdempotencyKey("payout-debit:" + MILESTONE_ID + ":D"))
                .thenReturn(Optional.of(debitLeg()));
        when(razorpayXClient.initiatePayout(FUND_ACCOUNT_ID, AMOUNT, "INR", IDEMPOTENCY_KEY))
                .thenThrow(new RuntimeException("RazorpayX rejected: fund account invalid"));
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);

        service.reconcileOrphanedPendingPayout(payout);

        assertEquals("reversed", payout.getStatus());
        verify(payoutRepository, times(1)).save(payout);
        // Re-credit clearing -> creator for the SAME amount, keyed off the Payout row's own id --
        // identical shape to confirmExecuted's C-6 reversal path.
        verify(ledgerService, times(1))
                .post(
                        eq(CLEARING_WALLET_ID),
                        eq(CREATOR_WALLET_ID),
                        eq(AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.PAYOUT),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        anyString(),
                        eq("payout-reversed:" + PAYOUT_ID),
                        anyString());
    }

    @Test
    @DisplayName(
            "reconcileOrphanedPendingPayout: row already progressed past PENDING before the sweep got"
                    + " to it -- no-op, no double-handling")
    void testAlreadyPastPendingIsNoOp() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_already_done", "queued");

        service.reconcileOrphanedPendingPayout(payout);

        verify(walletTransactionRepository, never()).findByIdempotencyKey(any());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(payoutRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Pre-existing webhook-driven reversal (unchanged) -- pinned so the P1 additions above don't
    // regress it.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "confirmExecuted: a `reversed` webhook re-credits the creator's wallet for the payout's"
                    + " amount exactly once")
    void testConfirmExecutedReCreditsOnReversedWebhook() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "processing");
        when(payoutRepository.findByRazorpayPayoutId("payout_xyz")).thenReturn(Optional.of(payout));
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);

        service.confirmExecuted("payout_xyz", "reversed", "{}");

        assertEquals("reversed", payout.getStatus());
        verify(ledgerService, times(1))
                .post(
                        eq(CLEARING_WALLET_ID),
                        eq(CREATOR_WALLET_ID),
                        eq(AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.PAYOUT),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        anyString(),
                        eq("payout-reversed:" + PAYOUT_ID),
                        eq("payout_xyz"));
    }

    @Test
    @DisplayName("confirmExecuted: a duplicate delivery reporting the SAME status is a no-op")
    void testConfirmExecutedDuplicateDeliveryIsNoOp() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "processed");
        when(payoutRepository.findByRazorpayPayoutId("payout_xyz")).thenReturn(Optional.of(payout));

        service.confirmExecuted("payout_xyz", "processed", "{}");

        verify(payoutRepository, never()).save(any());
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private WalletTransaction debitLeg() {
        return WalletTransaction.builder()
                .id("01HDEBITLEG1234567890")
                .walletId(CREATOR_WALLET_ID)
                .groupId("01HGROUP2234567890AB")
                .direction(TxnDirection.DEBIT)
                .type(WalletTransactionType.PAYOUT)
                .amount(AMOUNT)
                .currency("INR")
                .balanceAfter(BigDecimal.ZERO)
                .referenceType(TxnReferenceType.MILESTONE)
                .referenceId(MILESTONE_ID)
                .idempotencyKey("payout-debit:" + MILESTONE_ID + ":D")
                .build();
    }
}
