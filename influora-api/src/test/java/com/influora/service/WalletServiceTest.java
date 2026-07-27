package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.integration.razorpay.RazorpayXClient.PayoutResult;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.service.payout.RazorpayFundAccountService;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawResponse;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import com.influora.web.dto.money.MoneyDtos.WalletSummaryResponse;
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
 * P7: Unit tests for WalletService (16-VIKRAM-REMAINING-TASKS.md).
 * Priority: double-entry invariant, no direct balance mutation.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String WALLET_ID = "01HWXYZWALLET123456789";
    private static final String PLATFORM_WALLET_ID = "01HWXYZPLATFORM1234567";

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String BANK_ACCOUNT_ID = "01HBANKACCOUNT1234567";
    private static final String IDEMPOTENCY_KEY = "client-key-123";
    private static final String SCOPED_KEY = "creator-withdraw:" + USER_ID + ":" + IDEMPOTENCY_KEY;

    @Mock private WalletRepository walletRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private PaymentMilestoneRepository paymentMilestoneRepository;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private CreatorBankAccountRepository creatorBankAccountRepository;
    @Mock private RazorpayXClient razorpayXClient;
    @Mock private RazorpayFundAccountService fundAccountService;
    @Mock private PayoutRepository payoutRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private EscrowHoldRepository escrowHoldRepository;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService =
                new WalletService(
                        walletRepository,
                        ledgerService,
                        walletTransactionRepository,
                        paymentMilestoneRepository,
                        platformWalletService,
                        creatorBankAccountRepository,
                        razorpayXClient,
                        fundAccountService,
                        payoutRepository,
                        idempotencyService,
                        escrowHoldRepository);
    }

    /** Mirrors {@code PayoutServiceTest#mockIdempotencyExecuteOnce}: run the supplier as the race winner. */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<CreatorWithdrawResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private CreatorBankAccount primaryBankAccount() {
        return CreatorBankAccount.createEncrypted(
                BANK_ACCOUNT_ID,
                USER_ID,
                "cipher-account",
                "cipher-ifsc",
                "BANK",
                "xxxx1234",
                true,
                Instant.now().minusSeconds(3600),
                Instant.now());
    }

    // NOTE: fully-qualified com.influora.domain.entity.Wallet is used throughout this section
    // because the private nested `Wallet` test-double class below (createTestWallet) shadows the
    // real entity's simple name for the rest of this file.
    private com.influora.domain.entity.Wallet userWallet(BigDecimal balance) {
        com.influora.domain.entity.Wallet wallet =
                com.influora.domain.entity.Wallet.forUser(WALLET_ID, USER_ID);
        wallet.applyBalanceDelta(balance);
        return wallet;
    }

    @Test
    @DisplayName("getBalance: returns wallet balance for valid workspace")
    void testGetBalanceReturnsWalletBalance() {
        Wallet wallet = createTestWallet(new BigDecimal("10000.00"), new BigDecimal("5000.00"));
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.of(wallet));

        WalletBalanceResponse result = walletService.getBalance(WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(WALLET_ID, result.walletId());
        assertEquals(new BigDecimal("10000.00"), result.balance());
        assertEquals(new BigDecimal("5000.00"), result.escrowBalance());
        assertEquals("INR", result.currency());
    }

    // ------------------------------------------------------------------
    // getSummary -- brand dashboard escrowLocked derive-on-read (Track C escrow-display fix).
    // wallets.escrow_balance is a dead column (never written); escrowLocked must instead reflect
    // the live sum of the workspace's FUNDED escrow holds.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "getSummary: escrowLocked sums only this workspace's FUNDED holds, ignoring the dead"
                    + " wallets.escrow_balance column, other statuses, and other workspaces")
    void testGetSummaryDerivesEscrowLockedFromFundedHoldsOnly() {
        // Dead column deliberately set to a different, non-zero value to prove it is NOT read.
        Wallet wallet = createTestWallet(new BigDecimal("10000.00"), new BigDecimal("999.00"));
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.of(wallet));
        when(paymentMilestoneRepository.sumAmountByWorkspaceAndStatus(
                        eq(WORKSPACE_ID), eq(MilestoneStatus.FUNDED)))
                .thenReturn(BigDecimal.ZERO);
        // Repository-level aggregate already scopes by workspace + FUNDED status; the mock here
        // stands in for "two FUNDED holds (5000 + 15000) for this workspace, RELEASED/other-
        // workspace holds excluded" per the query's WHERE clause.
        when(escrowHoldRepository.sumAmountByWorkspaceIdAndStatus(WORKSPACE_ID, EscrowStatus.FUNDED))
                .thenReturn(new BigDecimal("20000.00"));

        WalletSummaryResponse result = walletService.getSummary(WORKSPACE_ID);

        assertEquals(new BigDecimal("20000.00"), result.escrowLocked());
        verify(escrowHoldRepository)
                .sumAmountByWorkspaceIdAndStatus(WORKSPACE_ID, EscrowStatus.FUNDED);
    }

    @Test
    @DisplayName("getSummary: escrowLocked is a non-null zero when the workspace has no FUNDED holds")
    void testGetSummaryEscrowLockedZeroWhenNoFundedHolds() {
        Wallet wallet = createTestWallet(new BigDecimal("10000.00"), BigDecimal.ZERO);
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.of(wallet));
        when(paymentMilestoneRepository.sumAmountByWorkspaceAndStatus(
                        eq(WORKSPACE_ID), eq(MilestoneStatus.FUNDED)))
                .thenReturn(BigDecimal.ZERO);
        // COALESCE(...,0) at the JPQL layer means a workspace with zero matching rows returns 0,
        // not null -- mirrored here since we're mocking the repository boundary.
        when(escrowHoldRepository.sumAmountByWorkspaceIdAndStatus(WORKSPACE_ID, EscrowStatus.FUNDED))
                .thenReturn(BigDecimal.ZERO);

        WalletSummaryResponse result = walletService.getSummary(WORKSPACE_ID);

        assertEquals(BigDecimal.ZERO, result.escrowLocked());
    }

    // ------------------------------------------------------------------
    // requestCreatorWithdrawal -- B10/C-5 (real disbursement) + M-6 (mandatory, namespaced key)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("requestCreatorWithdrawal: missing Idempotency-Key is rejected before any wallet/gateway work")
    void testWithdrawalRequiresIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> walletService.requestCreatorWithdrawal(USER_ID, new BigDecimal("1000.00"), null));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("requestCreatorWithdrawal: blank Idempotency-Key is rejected the same as missing")
    void testWithdrawalRejectsBlankIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> walletService.requestCreatorWithdrawal(USER_ID, new BigDecimal("1000.00"), "   "));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName(
            "requestCreatorWithdrawal: happy path debits the ledger, resolves a real fund account, calls"
                    + " RazorpayX, and persists a Payout row with no linked milestone")
    void testWithdrawalInitiatesRealDisbursementAndPersistsPayout() {
        com.influora.domain.entity.Wallet wallet = userWallet(new BigDecimal("5000.00"));
        when(walletRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn(0L);
        CreatorBankAccount bankAccount = primaryBankAccount();
        when(creatorBankAccountRepository.findByCreatorUserIdAndPrimaryTrue(USER_ID))
                .thenReturn(Optional.of(bankAccount));
        com.influora.domain.entity.Wallet clearingWallet =
                com.influora.domain.entity.Wallet.forWorkspace(PLATFORM_WALLET_ID, "platform-clearing");
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(fundAccountService.resolveFundAccountId(USER_ID, BANK_ACCOUNT_ID)).thenReturn("fund_acc_1");
        when(razorpayXClient.initiatePayout(
                        eq("fund_acc_1"), eq(new BigDecimal("1000.00")), eq("INR"), eq(SCOPED_KEY)))
                .thenReturn(new PayoutResult("payout_xyz", "queued"));
        mockIdempotencyExecuteOnce();

        CreatorWithdrawResponse response =
                walletService.requestCreatorWithdrawal(USER_ID, new BigDecimal("1000.00"), IDEMPOTENCY_KEY);

        assertEquals("payout_xyz", response.payoutId());
        verify(ledgerService)
                .post(
                        eq(WALLET_ID),
                        eq(PLATFORM_WALLET_ID),
                        eq(new BigDecimal("1000.00")),
                        eq("INR"),
                        eq(WalletTransactionType.WITHDRAWAL),
                        eq(TxnReferenceType.MANUAL),
                        any(),
                        eq("Creator withdrawal"),
                        eq(SCOPED_KEY),
                        eq(null));
        verify(razorpayXClient, times(1))
                .initiatePayout(eq("fund_acc_1"), eq(new BigDecimal("1000.00")), eq("INR"), eq(SCOPED_KEY));

        ArgumentCaptor<Payout> captor = ArgumentCaptor.forClass(Payout.class);
        verify(payoutRepository).save(captor.capture());
        Payout saved = captor.getValue();
        assertNull(saved.getMilestoneId(), "wallet withdrawal is not tied to a milestone");
        assertEquals(USER_ID, saved.getCreatorUserId());
        assertEquals("payout_xyz", saved.getRazorpayPayoutId());
        assertEquals("fund_acc_1", saved.getFundAccountId());
        assertEquals(SCOPED_KEY, saved.getIdempotencyKey());
    }

    @Test
    @DisplayName("requestCreatorWithdrawal: BANK_ACCOUNT_NOT_FOUND (409) when the creator has no primary bank/UPI account")
    void testWithdrawalRequiresPrimaryBankAccount() {
        com.influora.domain.entity.Wallet wallet = userWallet(new BigDecimal("5000.00"));
        when(walletRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn(0L);
        when(creatorBankAccountRepository.findByCreatorUserIdAndPrimaryTrue(USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                walletService.requestCreatorWithdrawal(
                                        USER_ID, new BigDecimal("1000.00"), IDEMPOTENCY_KEY));

        assertEquals("BANK_ACCOUNT_NOT_FOUND", ex.getCode());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName("requestCreatorWithdrawal: INSUFFICIENT_BALANCE (400) when the wallet balance is below the requested amount")
    void testWithdrawalInsufficientBalance() {
        com.influora.domain.entity.Wallet wallet = userWallet(new BigDecimal("100.00"));
        when(walletRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                walletService.requestCreatorWithdrawal(
                                        USER_ID, new BigDecimal("1000.00"), IDEMPOTENCY_KEY));

        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "requestCreatorWithdrawal: a retry (AlreadyCompletedException) replays the durable Payout row"
                    + " instead of calling RazorpayX again")
    void testWithdrawalRetryReplaysPersistedPayout() {
        com.influora.domain.entity.Wallet wallet = userWallet(new BigDecimal("5000.00"));
        when(walletRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn(0L);
        when(creatorBankAccountRepository.findByCreatorUserIdAndPrimaryTrue(USER_ID))
                .thenReturn(Optional.of(primaryBankAccount()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(SCOPED_KEY));
        Payout existing =
                Payout.createQueued(
                        "01HPAYOUT1234567890AB",
                        null,
                        USER_ID,
                        "payout_prior",
                        "fund_acc_1",
                        new BigDecimal("1000.00"),
                        "INR",
                        "queued",
                        SCOPED_KEY,
                        Instant.now());
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.of(existing));

        CreatorWithdrawResponse response =
                walletService.requestCreatorWithdrawal(USER_ID, new BigDecimal("1000.00"), IDEMPOTENCY_KEY);

        assertEquals("payout_prior", response.payoutId());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(payoutRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "requestCreatorWithdrawal: AlreadyInProgress with no durable row visible yet throws a"
                    + " retry-safe 409, never a generic 500")
    void testWithdrawalInProgressNoVisibleRowThrows409() {
        com.influora.domain.entity.Wallet wallet = userWallet(new BigDecimal("5000.00"));
        when(walletRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        eq(WALLET_ID), eq(WalletTransactionType.WITHDRAWAL), any()))
                .thenReturn(0L);
        when(creatorBankAccountRepository.findByCreatorUserIdAndPrimaryTrue(USER_ID))
                .thenReturn(Optional.of(primaryBankAccount()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(SCOPED_KEY));
        when(payoutRepository.findByIdempotencyKey(SCOPED_KEY)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                walletService.requestCreatorWithdrawal(
                                        USER_ID, new BigDecimal("1000.00"), IDEMPOTENCY_KEY));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    /**
     * Rewritten 2026-07-26. This asserted that {@code getBalance} throws WALLET_NOT_FOUND for a
     * missing wallet — behaviour that was deliberately changed: {@code getBalance} now goes
     * through {@link WalletService#getOrCreateWorkspaceWallet}, which lazily creates the row so a
     * fresh brand's dashboard shows a zero balance instead of 404ing. The test failed with an NPE
     * rather than an assertion mismatch only because the unstubbed {@code save(...)} returned null.
     *
     * Both halves of that contract are now pinned, because the split is the point: display reads
     * create, money-moving reads still refuse. Losing the second half would let a missing wallet be
     * silently papered over as "zero balance" on a spend path, i.e. treated as affordable.
     */
    @Test
    @DisplayName("getBalance: lazily creates a zero-balance wallet instead of 404ing")
    void testGetBalanceLazilyCreatesWallet() {
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.empty());
        // Untyped any() rather than any(Wallet.class): JpaRepository declares save as the generic
        // <S extends Wallet> S save(S), and matching the erased bridge against a Class token made
        // Mockito reject the real Wallet with PotentialStubbingProblem. The assertion below still
        // proves a genuine zero-balance Wallet flowed through.
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletBalanceResponse balance = walletService.getBalance(WORKSPACE_ID);

        assertEquals(0, BigDecimal.ZERO.compareTo(balance.balance()));
        verify(walletRepository).save(any());
    }

    @Test
    @DisplayName("requireWorkspaceWallet: still throws WALLET_NOT_FOUND — money paths must not auto-create")
    void testRequireWorkspaceWalletThrowsForMissingWallet() {
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () ->
                walletService.requireWorkspaceWallet(WORKSPACE_ID));

        assertEquals("WALLET_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // A missing wallet means "cannot afford"; creating one here would fabricate affordability.
        // Untyped any() for the same generic-bridge reason as the test above.
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: delegates to ledger service, never mutates balance directly")
    void testDepositDelegatesToLedger() {
        Wallet wallet = createTestWallet(new BigDecimal("10000.00"), BigDecimal.ZERO);
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.of(wallet));

        BigDecimal depositAmount = new BigDecimal("5000.00");
        String idempotencyKey = "deposit_12345";
        String gatewayRef = "rzp_order_12345";

        walletService.deposit(
                WORKSPACE_ID, PLATFORM_WALLET_ID, depositAmount, "INR", idempotencyKey, gatewayRef);

        // Verify ledger service was called (double-entry posting)
        verify(ledgerService).post(
                eq(PLATFORM_WALLET_ID),
                eq(WALLET_ID),
                eq(depositAmount),
                eq("INR"),
                eq(WalletTransactionType.DEPOSIT),
                eq(TxnReferenceType.DEPOSIT_ORDER),
                any(), // Generated reference ID
                eq("Wallet deposit"),
                eq(idempotencyKey),
                eq(gatewayRef));

        // Wallet balance should NOT be directly mutated (ledger is source of truth)
        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    @DisplayName("withdraw: delegates to ledger service, never mutates balance directly")
    void testWithdrawDelegatesToLedger() {
        Wallet wallet = createTestWallet(new BigDecimal("10000.00"), BigDecimal.ZERO);
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.of(wallet));

        BigDecimal withdrawAmount = new BigDecimal("3000.00");
        String idempotencyKey = "withdraw_12345";
        String gatewayRef = "payout_12345";

        walletService.withdraw(
                WORKSPACE_ID, PLATFORM_WALLET_ID, withdrawAmount, "INR", idempotencyKey, gatewayRef);

        // Verify ledger service was called (double-entry posting)
        verify(ledgerService).post(
                eq(WALLET_ID),
                eq(PLATFORM_WALLET_ID),
                eq(withdrawAmount),
                eq("INR"),
                eq(WalletTransactionType.WITHDRAWAL),
                eq(TxnReferenceType.MANUAL),
                any(),
                eq("Wallet withdrawal"),
                eq(idempotencyKey),
                eq(gatewayRef));

        // Wallet balance should NOT be directly mutated
        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    @DisplayName("requireWorkspaceWallet: throws for missing wallet")
    void testRequireWorkspaceWalletThrowsForMissing() {
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () ->
                walletService.requireWorkspaceWallet(WORKSPACE_ID));

        assertEquals("WALLET_NOT_FOUND", ex.getCode());
    }

    private Wallet createTestWallet(BigDecimal balance, BigDecimal escrowBalance) {
        // Using reflection or a builder to create test wallet
        // In a real scenario, Wallet would have a builder or test factory
        return new Wallet() {
            @Override
            public String getId() {
                return WALLET_ID;
            }

            @Override
            public BigDecimal getBalance() {
                return balance;
            }

            @Override
            public BigDecimal getEscrowBalance() {
                return escrowBalance;
            }

            @Override
            public String getCurrency() {
                return "INR";
            }
        };
    }

    /**
     * Abstract Wallet class for test purposes.
     * In the real implementation, this would use the actual Wallet entity.
     */
    private abstract static class Wallet extends com.influora.domain.entity.Wallet {
        // Test subclass that allows us to override methods
    }
}
