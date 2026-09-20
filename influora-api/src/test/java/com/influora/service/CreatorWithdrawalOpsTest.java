package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [EV-003] The reservation half of a creator withdrawal — the part that has to be exactly-once
 * under retry, because it is the part that moves money.
 *
 * <p>The scenario each of these models is the one EV-003 describes: the RazorpayX call after
 * {@link CreatorWithdrawalOps#reserveWithdrawal} times out, the caller's idempotency reservation is
 * marked FAILED and then reclaimed, and the whole thing runs again with the same key. Getting that
 * wrong debits the creator twice.
 */
@ExtendWith(MockitoExtension.class)
class CreatorWithdrawalOpsTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String WALLET_ID = "01HWXYZWALLET123456789";
    private static final String CLEARING_WALLET_ID = "01HWXYZPLATFORM1234567";
    private static final String FUND_ACCOUNT_ID = "fund_acc_1";
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final int MAX_PER_DAY = 3;

    private static final String SCOPED_KEY =
            LedgerIdempotencyKeys.creatorWithdrawal(USER_ID, "client-key-123");

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private PayoutRepository payoutRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;

    private CreatorWithdrawalOps ops;

    @BeforeEach
    void setUp() {
        ops =
                new CreatorWithdrawalOps(
                        walletRepository,
                        walletTransactionRepository,
                        payoutRepository,
                        ledgerService,
                        platformWalletService);
    }

    private Wallet creatorWallet(BigDecimal balance) {
        Wallet w = Wallet.forUser(WALLET_ID, USER_ID);
        w.applyBalanceDelta(balance);
        return w;
    }

    private void stubLockedWallet(BigDecimal balance) {
        when(walletRepository.findByOwnerIdForUpdate(USER_ID))
                .thenReturn(Optional.of(creatorWallet(balance)));
    }

    private void stubClearingWallet() {
        when(platformWalletService.requireClearingWallet())
                .thenReturn(Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing"));
    }

    private CreatorWithdrawalOps.ReservedWithdrawal reserve() {
        return ops.reserveWithdrawal(USER_ID, FUND_ACCOUNT_ID, AMOUNT, SCOPED_KEY, MAX_PER_DAY);
    }

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("first attempt: writes the PENDING payout row and the ledger debit under the same key")
    void firstAttemptWritesPendingRowAndDebit() {
        stubLockedWallet(new BigDecimal("5000.00"));
        stubClearingWallet();
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.empty());
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn(0L);

        CreatorWithdrawalOps.ReservedWithdrawal reserved = reserve();

        ArgumentCaptor<Payout> saved = ArgumentCaptor.forClass(Payout.class);
        verify(payoutRepository).save(saved.capture());
        Payout payout = saved.getValue();

        // PENDING, not a gateway status: PayoutOrphanedDebitSweepJob scans on exactly this value,
        // and it is the only thing standing between a crashed withdrawal and a stranded debit.
        assertEquals(Payout.STATUS_PENDING, payout.getStatus());
        assertEquals(SCOPED_KEY, payout.getIdempotencyKey());
        assertEquals(USER_ID, payout.getCreatorUserId());
        assertEquals(FUND_ACCOUNT_ID, payout.getFundAccountId());
        assertNull(payout.getMilestoneId(), "a lump-sum wallet withdrawal is tied to no milestone");
        assertEquals(payout.getId(), reserved.payoutRowId());
        assertEquals("INR", reserved.currency());

        verify(ledgerService)
                .post(
                        eq(WALLET_ID),
                        eq(CLEARING_WALLET_ID),
                        eq(AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.WITHDRAWAL),
                        eq(TxnReferenceType.MANUAL),
                        eq(payout.getId()),
                        eq("Creator withdrawal"),
                        // The ledger key MUST be the payout row's key: PayoutReconciliationService
                        // recomputes it from the row to find an orphaned debit.
                        eq(SCOPED_KEY),
                        eq(null));
    }

    @Test
    @DisplayName("retry with the same key reuses the existing payout row — never a second one")
    void retryReusesTheExistingPayoutRow() {
        stubLockedWallet(new BigDecimal("4000.00"));
        stubClearingWallet();
        Payout existing =
                Payout.createPending(
                        "01HPAYOUTROW000000000",
                        null,
                        USER_ID,
                        FUND_ACCOUNT_ID,
                        AMOUNT,
                        "INR",
                        SCOPED_KEY,
                        Instant.now());
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.of(existing));

        CreatorWithdrawalOps.ReservedWithdrawal reserved = reserve();

        assertEquals("01HPAYOUTROW000000000", reserved.payoutRowId());
        // payouts.idempotency_key is UNIQUE — inserting a second row for the same key would be a
        // constraint violation, and would also hide the row the sweeper needs.
        verify(payoutRepository, never()).save(any(Payout.class));
        // Same key into the ledger, so WalletLedgerService#post replays the first attempt's posting
        // rather than debiting again. This is the no-double-debit guarantee.
        verify(ledgerService, times(1))
                .post(any(), any(), any(), any(), any(), any(), any(), any(), eq(SCOPED_KEY), any());
    }

    @Test
    @DisplayName(
            "retry does NOT re-run the balance or daily-cap checks — the first attempt already spent"
                    + " that balance, so re-checking would reject a legitimate retry")
    void retrySkipsTheFreshRequestGuards() {
        // Balance is now BELOW the amount, precisely because the first attempt's debit committed.
        stubLockedWallet(new BigDecimal("0.00"));
        stubClearingWallet();
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY))
                .thenReturn(
                        Optional.of(
                                Payout.createPending(
                                        "01HPAYOUTROW000000000",
                                        null,
                                        USER_ID,
                                        FUND_ACCOUNT_ID,
                                        AMOUNT,
                                        "INR",
                                        SCOPED_KEY,
                                        Instant.now())));

        CreatorWithdrawalOps.ReservedWithdrawal reserved = reserve();

        assertEquals("01HPAYOUTROW000000000", reserved.payoutRowId());
        // Never consulted: the daily cap is a guard on NEW withdrawals, not on finishing one that
        // was already accepted. Counting the first attempt's own debit against the retry would
        // eventually wedge a creator out of completing their own in-flight withdrawal.
        verify(walletTransactionRepository, never())
                .countByWalletIdAndTypeAndCreatedAtAfter(anyString(), any(), any());
    }

    @Test
    @DisplayName("a first attempt over the balance is refused before anything is written")
    void insufficientBalanceIsRefusedUnderTheLock() {
        stubLockedWallet(new BigDecimal("100.00"));
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, this::reserve);

        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        verify(payoutRepository, never()).save(any(Payout.class));
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a first attempt over the daily cap is refused before anything is written")
    void dailyCapIsEnforcedUnderTheLock() {
        stubLockedWallet(new BigDecimal("50000.00"));
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.empty());
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn((long) MAX_PER_DAY);

        ApiException ex = assertThrows(ApiException.class, this::reserve);

        assertEquals("WITHDRAWAL_RATE_LIMIT", ex.getCode());
        verify(payoutRepository, never()).save(any(Payout.class));
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("recordGatewayResult moves the PENDING row onto its real gateway identity")
    void recordGatewayResultAdvancesThePendingRow() {
        Payout pending =
                Payout.createPending(
                        "01HPAYOUTROW000000000",
                        null,
                        USER_ID,
                        FUND_ACCOUNT_ID,
                        AMOUNT,
                        "INR",
                        SCOPED_KEY,
                        Instant.now());
        when(payoutRepository.findById("01HPAYOUTROW000000000")).thenReturn(Optional.of(pending));

        ops.recordGatewayResult("01HPAYOUTROW000000000", "pout_ABC123", "queued");

        assertEquals("pout_ABC123", pending.getRazorpayPayoutId());
        assertEquals("queued", pending.getStatus());
        verify(payoutRepository).save(pending);
    }

    @Test
    @DisplayName(
            "recordGatewayResult leaves a row the reaper already resolved alone — a late result must"
                    + " not overwrite a settled outcome")
    void recordGatewayResultDoesNotOverwriteAnAlreadyResolvedRow() {
        Payout resolved =
                Payout.createQueued(
                        "01HPAYOUTROW000000000",
                        null,
                        USER_ID,
                        "pout_FROM_REAPER",
                        FUND_ACCOUNT_ID,
                        AMOUNT,
                        "INR",
                        "processed",
                        SCOPED_KEY,
                        Instant.now());
        when(payoutRepository.findById("01HPAYOUTROW000000000")).thenReturn(Optional.of(resolved));

        ops.recordGatewayResult("01HPAYOUTROW000000000", "pout_LATE", "queued");

        assertEquals("pout_FROM_REAPER", resolved.getRazorpayPayoutId());
        assertEquals("processed", resolved.getStatus());
        verify(payoutRepository, never()).save(any(Payout.class));
    }

    @Test
    @DisplayName("this bean cannot call the gateway: it holds no RazorpayX client at all")
    void holdsNoGatewayClient() {
        // Structural, not behavioural. Every method here runs inside a real transaction, so a
        // gateway call reaching one of them reinstates EV-003 exactly. No field of that type means
        // no method here can make one, whatever a future edit does to the bodies.
        for (java.lang.reflect.Field field : CreatorWithdrawalOps.class.getDeclaredFields()) {
            assertSame(
                    false,
                    com.influora.integration.razorpay.RazorpayXClient.class.isAssignableFrom(
                            field.getType()),
                    "CreatorWithdrawalOps gained a RazorpayXClient ("
                            + field.getName()
                            + "). Its methods are @Transactional, so calling the gateway from one puts"
                            + " an HTTP round trip back inside a transaction holding wallet locks —"
                            + " the EV-003 defect. The gateway call belongs in"
                            + " WalletService#processWithdrawal, between the two transactions.");
        }
    }

    @Test
    @DisplayName("both phases really are @Transactional — self-invocation would make them inert")
    void bothPhasesAreTransactional() throws NoSuchMethodException {
        assertSame(
                true,
                CreatorWithdrawalOps.class
                                .getMethod(
                                        "reserveWithdrawal",
                                        String.class,
                                        String.class,
                                        BigDecimal.class,
                                        String.class,
                                        int.class)
                                .getAnnotation(
                                        org.springframework.transaction.annotation.Transactional.class)
                        != null,
                "reserveWithdrawal must be transactional: the PENDING payout row and the ledger"
                        + " debit have to commit together or not at all");
        assertSame(
                true,
                CreatorWithdrawalOps.class
                                .getMethod(
                                        "recordGatewayResult", String.class, String.class, String.class)
                                .getAnnotation(
                                        org.springframework.transaction.annotation.Transactional.class)
                        != null,
                "recordGatewayResult must be transactional — executeOnce marks the key COMPLETED"
                        + " only after it commits");
    }
}
