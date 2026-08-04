package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock private IdempotencyService idempotencyService;

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
                        razorpayXClient,
                        idempotencyService);
    }

    /** Stubs {@link IdempotencyService#executeOnce} to actually run the supplied action — the real
     * concurrency arbitration is IdempotencyService's own (separately tested) job; these tests only
     * need to prove {@link PayoutReconciliationService#retryFailedPayout} calls through it and
     * reacts correctly to its outcome. */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyPassthrough() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<Object> action = invocation.getArgument(3);
                            return action.get();
                        });
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
        // [Red-team F1] Re-credit clearing -> creator for the SAME amount, keyed off the
        // razorpayPayoutId in effect at the time (the Payout#createPending placeholder
        // "pending:<id>", since markGatewayConfirmed never ran for this row) -- NOT the payout
        // row's own id -- identical shape to confirmExecuted's C-6 reversal path.
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
                        eq("payout-reversed:pending:" + PAYOUT_ID),
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
                        eq("payout-reversed:payout_xyz"),
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

    // ------------------------------------------------------------------
    // [Admin finance console, payout-retry bug fix] confirmExecuted must re-credit on REJECTED and
    // CANCELLED too, not just REVERSED -- the bug that left a rejected/cancelled payout's debit
    // permanently stranded.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "confirmExecuted: a `rejected` webhook re-credits the creator's wallet exactly once (bug"
                    + " fix -- previously only `reversed` did)")
    void testConfirmExecutedReCreditsOnRejectedWebhook() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "processing");
        when(payoutRepository.findByRazorpayPayoutId("payout_xyz")).thenReturn(Optional.of(payout));
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);

        service.confirmExecuted("payout_xyz", "rejected", "{}");

        assertEquals("rejected", payout.getStatus());
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
                        eq("payout-reversed:payout_xyz"),
                        eq("payout_xyz"));
    }

    @Test
    @DisplayName(
            "confirmExecuted: a `cancelled` webhook re-credits the creator's wallet exactly once (bug"
                    + " fix -- previously only `reversed` did)")
    void testConfirmExecutedReCreditsOnCancelledWebhook() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "processing");
        when(payoutRepository.findByRazorpayPayoutId("payout_xyz")).thenReturn(Optional.of(payout));
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);

        service.confirmExecuted("payout_xyz", "cancelled", "{}");

        assertEquals("cancelled", payout.getStatus());
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
                        eq("payout-reversed:payout_xyz"),
                        eq("payout_xyz"));
    }

    // ------------------------------------------------------------------
    // [Admin finance console, payout-retry] retryFailedPayout
    // ------------------------------------------------------------------

    private Payout rejectedPayout() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "rejected");
        return payout;
    }

    private void stubRetryWallets() {
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);
    }

    @Test
    @DisplayName("retryFailedPayout: rejects a payout that is not in a terminal-failure state")
    void testRetryFailedPayoutRejectsNonFailedPayout() {
        Payout payout = pendingPayout();
        payout.markGatewayConfirmed("payout_xyz", "processing");
        when(payoutRepository.findById(PAYOUT_ID)).thenReturn(Optional.of(payout));

        assertThrows(ApiException.class, () -> service.retryFailedPayout(PAYOUT_ID));

        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("retryFailedPayout: rejects an unknown payout id")
    void testRetryFailedPayoutUnknownId() {
        when(payoutRepository.findById("nope")).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.retryFailedPayout("nope"));
    }

    @Test
    @DisplayName(
            "retryFailedPayout: re-credits the original debit, re-debits under a FRESH key, and calls"
                    + " RazorpayX with a FRESH idempotency key (never the original, already-rejected one)")
    void testRetryFailedPayoutSucceedsWithFreshIdempotencyKey() {
        Payout payout = rejectedPayout();
        // Captured BEFORE the call -- retryFailedPayout/attemptGatewayPayout mutate payout's own
        // updatedAt on success, so reading it afterwards would silently use the WRONG (post-retry)
        // snapshot instead of the pre-retry one the production code actually keyed off. Uses the
        // REAL production key builders (package-private for exactly this) rather than a hand-rolled
        // duplicate of the hashing scheme, so this test tracks the actual implementation.
        String expectedAttemptKey = PayoutReconciliationService.buildRetryAttemptKey(payout);
        when(payoutRepository.findById(PAYOUT_ID)).thenReturn(Optional.of(payout));
        stubIdempotencyPassthrough();
        stubRetryWallets();
        when(razorpayXClient.initiatePayout(eq(FUND_ACCOUNT_ID), eq(AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_retry_1", "queued"));

        Payout result = service.retryFailedPayout(PAYOUT_ID);

        assertEquals("queued", result.getStatus());
        assertEquals("payout_retry_1", result.getRazorpayPayoutId());

        ArgumentCaptor<String> gatewayKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(razorpayXClient)
                .initiatePayout(eq(FUND_ACCOUNT_ID), eq(AMOUNT), eq("INR"), gatewayKeyCaptor.capture());
        // FRESH -- never a reuse of the payout's original (already-rejected) idempotency key --
        // and [Red-team F2] deterministic (a hash of payoutId:status:updatedAt), not a random id,
        // so a crashed retry's debit stays recoverable by reconcileFailedPayoutRetry.
        assertNotEquals(IDEMPOTENCY_KEY, gatewayKeyCaptor.getValue());
        assertEquals(
                PayoutReconciliationService.retryGatewayKey(expectedAttemptKey), gatewayKeyCaptor.getValue());

        // [Red-team F1] Two ledger posts: (1) re-credit of the ORIGINAL queue-time debit, keyed on
        // the OLD attempt's own razorpayPayoutId ("payout_xyz") -- never the payout row id, which
        // was the F1 collision bug -- (2) the fresh debit for THIS retry attempt.
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
                        eq("payout-reversed:payout_xyz"),
                        anyString());
        verify(ledgerService, times(1))
                .post(
                        eq(CREATOR_WALLET_ID),
                        eq(CLEARING_WALLET_ID),
                        eq(AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.PAYOUT),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        anyString(),
                        eq(PayoutReconciliationService.retryDebitKey(expectedAttemptKey)),
                        any());
    }

    @Test
    @DisplayName(
            "F2 regression: retryDebitKey/retryReversalKey stay within wallet_transactions"
                    + ".idempotency_key's VARCHAR(64) even AFTER WalletLedgerService's :D/:C suffix,"
                    + " using a realistic 26-char ULID id and the longest FAILURE_STATUSES value"
                    + " (\"cancelled\") -- the exact overflow the un-hashed raw-concatenation version"
                    + " had (86-91 chars measured)")
    void testRetryLedgerKeysFitWalletTransactionsVarchar64AfterSuffix() {
        Payout payout = rejectedPayout();
        payout.confirmStatus("cancelled", null); // longest of FAILURE_STATUSES -- worst case

        String attemptKey = PayoutReconciliationService.buildRetryAttemptKey(payout);
        String debitKey = PayoutReconciliationService.retryDebitKey(attemptKey);
        String reversalKey = PayoutReconciliationService.retryReversalKey(attemptKey);

        // WalletLedgerService#post appends ":D" (debit leg) / ":C" (credit leg) -- 2 chars -- before
        // writing to wallet_transactions.idempotency_key, VARCHAR(64)
        // (V8__wallet_transactions.sql:15, WalletTransaction.java:61 length=64). Both must fit
        // AFTER that suffix, not just on their own.
        assertTrue(
                debitKey.length() + 2 <= 64,
                "retryDebitKey (\"" + debitKey + "\") + \":D\" is " + (debitKey.length() + 2)
                        + " chars, over VARCHAR(64)");
        assertTrue(
                reversalKey.length() + 2 <= 64,
                "retryReversalKey (\"" + reversalKey + "\") + \":C\" is " + (reversalKey.length() + 2)
                        + " chars, over VARCHAR(64)");
    }

    @Test
    @DisplayName(
            "retryFailedPayout: a double-retry never double-pays -- when the idempotency guard reports"
                    + " a collision (a concurrent duplicate retry for the SAME failure), the call is"
                    + " rejected and RazorpayX is never reached")
    void testRetryFailedPayoutRejectedOnIdempotencyCollision() {
        Payout payout = rejectedPayout();
        when(payoutRepository.findById(PAYOUT_ID)).thenReturn(Optional.of(payout));
        // Simulates a concurrent caller that already reserved this exact
        // (payoutId+status+updatedAt) key -- IdempotencyService's own DB-level UNIQUE constraint is
        // the real arbiter (tested separately); this proves retryFailedPayout actually delegates to
        // it and never falls back to running the gateway call itself on a collision.
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException("dup"));

        assertThrows(ApiException.class, () -> service.retryFailedPayout(PAYOUT_ID));

        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // [Red-team F1 fix, HIGH -- lost creator money] Full retry-then-later-reversal sequence: the
    // NEW gateway attempt's own reversal must re-credit the creator for the RETRY debit, distinct
    // from (not colliding with) the credit the retry's own step-1 safety net already wrote for the
    // ORIGINAL debit.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "F1 regression: retry succeeds (queued under a NEW razorpayPayoutId), that NEW attempt"
                    + " later reverses via webhook -- creator is re-credited for the retry amount"
                    + " EXACTLY ONCE (not zero -- the F1 bug -- and not twice)")
    void testRetryThenNewAttemptReversalReCreditsExactlyOnce() {
        Payout payout = rejectedPayout(); // razorpayPayoutId="payout_xyz", status="rejected"
        when(payoutRepository.findById(PAYOUT_ID)).thenReturn(Optional.of(payout));
        when(payoutRepository.findByRazorpayPayoutId("pout_new")).thenReturn(Optional.of(payout));
        stubIdempotencyPassthrough();
        stubRetryWallets();
        when(razorpayXClient.initiatePayout(eq(FUND_ACCOUNT_ID), eq(AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("pout_new", "queued"));

        service.retryFailedPayout(PAYOUT_ID);
        assertEquals("queued", payout.getStatus());
        assertEquals("pout_new", payout.getRazorpayPayoutId());

        // The retry's own step-1 safety net already re-credited the ORIGINAL debit under the OLD
        // attempt's id.
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
                        eq("payout-reversed:payout_xyz"),
                        anyString());

        // The NEW attempt (pout_new) now reverses via a real webhook.
        service.confirmExecuted("pout_new", "reversed", "{}");

        assertEquals("reversed", payout.getStatus());
        // The RETRY debit gets its OWN, distinct re-credit -- this is the F1 fix: before it, this
        // call collided with the "payout-reversed:payout_xyz" key above and silently wrote nothing.
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
                        eq("payout-reversed:pout_new"),
                        eq("pout_new"));
        // The OLD (pre-fix) collision key must never be used -- regression guard.
        verify(ledgerService, never())
                .post(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("payout-reversed:" + PAYOUT_ID),
                        any());
    }

    // ------------------------------------------------------------------
    // [Red-team F2 fix, MEDIUM -- retry debit stranded on crash] reconcileFailedPayoutRetry -- the
    // sweep counterpart for a crashed retry attempt's orphaned debit, never visible to
    // reconcileOrphanedPendingPayout (which only ever scans Payout#STATUS_PENDING rows).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reconcileFailedPayoutRetry: payout already progressed past a failure status -- no-op")
    void testReconcileFailedPayoutRetryNotFailureStatusIsNoOp() {
        Payout payout = rejectedPayout();
        payout.markGatewayConfirmed("payout_new", "queued"); // the retry already succeeded

        service.reconcileFailedPayoutRetry(payout);

        verify(walletTransactionRepository, never()).findByIdempotencyKey(any());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "reconcileFailedPayoutRetry: failure status but no retry debit was ever posted for this"
                    + " exact failure snapshot -- nothing orphaned, no-op")
    void testReconcileFailedPayoutRetryNoDebitPostedIsNoOp() {
        Payout payout = rejectedPayout();
        when(walletTransactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        service.reconcileFailedPayoutRetry(payout);

        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(payoutRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "reconcileFailedPayoutRetry: a crashed retry's orphaned debit is found by recomputing the"
                    + " SAME deterministic key doRetryFailedPayout used, and the gateway call is"
                    + " resumed (confirms on success)")
    void testReconcileFailedPayoutRetryResumesStuckDebitOnSuccess() {
        Payout payout = rejectedPayout();
        String expectedAttemptKey = PayoutReconciliationService.buildRetryAttemptKey(payout);
        String expectedDebitKey = PayoutReconciliationService.retryDebitKey(expectedAttemptKey);
        when(walletTransactionRepository.findByIdempotencyKey(expectedDebitKey))
                .thenReturn(Optional.of(debitLeg())); // the orphaned retry debit, found by the sweep
        when(razorpayXClient.initiatePayout(eq(FUND_ACCOUNT_ID), eq(AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_resumed_1", "queued"));

        service.reconcileFailedPayoutRetry(payout);

        assertEquals("queued", payout.getStatus());
        assertEquals("payout_resumed_1", payout.getRazorpayPayoutId());
        verify(razorpayXClient)
                .initiatePayout(
                        eq(FUND_ACCOUNT_ID),
                        eq(AMOUNT),
                        eq("INR"),
                        eq(PayoutReconciliationService.retryGatewayKey(expectedAttemptKey)));
        // A successful resume is not a reversal -- no re-credit on top of a payout that RazorpayX's
        // own idempotent dedup says is legitimately in flight/complete.
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "reconcileFailedPayoutRetry: resumed gateway call fails again -- reverses via the SAME"
                    + " deterministic reversal key, re-crediting the orphaned retry debit")
    void testReconcileFailedPayoutRetryResumeFailsReverses() {
        Payout payout = rejectedPayout();
        String expectedAttemptKey = PayoutReconciliationService.buildRetryAttemptKey(payout);
        String expectedDebitKey = PayoutReconciliationService.retryDebitKey(expectedAttemptKey);
        when(walletTransactionRepository.findByIdempotencyKey(expectedDebitKey))
                .thenReturn(Optional.of(debitLeg()));
        when(razorpayXClient.initiatePayout(eq(FUND_ACCOUNT_ID), eq(AMOUNT), eq("INR"), anyString()))
                .thenThrow(new RuntimeException("RazorpayX unreachable"));
        stubRetryWallets();

        service.reconcileFailedPayoutRetry(payout);

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
                        eq(PayoutReconciliationService.retryReversalKey(expectedAttemptKey)),
                        any());
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
