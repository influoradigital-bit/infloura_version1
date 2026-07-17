package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletLedgerServiceTest {

    private static final String DEBIT_WALLET_ID = "01HWXYZDEBIT123456789";
    private static final String CREDIT_WALLET_ID = "01HWXYZCREDIT12345678";

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;

    private WalletLedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new WalletLedgerService(walletRepository, walletTransactionRepository);
    }

    @Test
    @DisplayName("post: rejects insufficient balance after pessimistic debit-wallet lock (M-18-1)")
    void testPostRejectsInsufficientBalanceUnderLock() {
        Wallet debitWallet = walletWithBalance(DEBIT_WALLET_ID, new BigDecimal("4000.00"));
        Wallet creditWallet = walletWithBalance(CREDIT_WALLET_ID, BigDecimal.ZERO);

        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(DEBIT_WALLET_ID)).thenReturn(Optional.of(debitWallet));
        when(walletRepository.findByIdForUpdate(CREDIT_WALLET_ID)).thenReturn(Optional.of(creditWallet));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                ledgerService.post(
                                        DEBIT_WALLET_ID,
                                        CREDIT_WALLET_ID,
                                        new BigDecimal("6000.00"),
                                        "INR",
                                        WalletTransactionType.WITHDRAWAL,
                                        TxnReferenceType.MANUAL,
                                        "ref-1",
                                        "Creator withdrawal",
                                        "idem-1",
                                        null));

        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "post: first brand top-up succeeds even though the platform clearing wallet starts at"
                    + " balance 0 (W1-1 / C2)")
    void testFirstBrandTopUpSucceedsAgainstZeroBalanceClearingWallet() {
        Wallet clearingWallet =
                Wallet.forWorkspace(DEBIT_WALLET_ID, PlatformWalletService.PLATFORM_CLEARING_WALLET_OWNER_ID);
        Wallet brandWallet = walletWithBalance(CREDIT_WALLET_ID, BigDecimal.ZERO);

        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(DEBIT_WALLET_ID)).thenReturn(Optional.of(clearingWallet));
        when(walletRepository.findByIdForUpdate(CREDIT_WALLET_ID)).thenReturn(Optional.of(brandWallet));

        WalletLedgerService.LedgerPostingResult result =
                ledgerService.post(
                        DEBIT_WALLET_ID,
                        CREDIT_WALLET_ID,
                        new BigDecimal("500.00"),
                        "INR",
                        WalletTransactionType.DEPOSIT,
                        TxnReferenceType.DEPOSIT_ORDER,
                        "topup-1",
                        "Wallet top-up",
                        "idem-topup-1",
                        "rzp_pay_1");

        assertEquals(0, new BigDecimal("-500.00").compareTo(clearingWallet.getBalance()));
        assertEquals(0, new BigDecimal("500.00").compareTo(brandWallet.getBalance()));
        assertEquals(0, new BigDecimal("500.00").compareTo(result.debitLeg().getAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(result.creditLeg().getAmount()));
        verify(walletRepository).save(clearingWallet);
        verify(walletRepository).save(brandWallet);
    }

    @Test
    @DisplayName(
            "post: clearing-wallet exemption keeps the ledger balanced — debit delta + credit delta"
                    + " net to zero (W1-1 / C2)")
    void testClearingWalletTopUpKeepsLedgerBalanced() {
        Wallet clearingWallet =
                Wallet.forWorkspace(DEBIT_WALLET_ID, PlatformWalletService.PLATFORM_CLEARING_WALLET_OWNER_ID);
        Wallet brandWallet = walletWithBalance(CREDIT_WALLET_ID, new BigDecimal("100.00"));

        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(DEBIT_WALLET_ID)).thenReturn(Optional.of(clearingWallet));
        when(walletRepository.findByIdForUpdate(CREDIT_WALLET_ID)).thenReturn(Optional.of(brandWallet));

        ledgerService.post(
                DEBIT_WALLET_ID,
                CREDIT_WALLET_ID,
                new BigDecimal("750.00"),
                "INR",
                WalletTransactionType.DEPOSIT,
                TxnReferenceType.DEPOSIT_ORDER,
                "topup-2",
                "Wallet top-up",
                "idem-topup-2",
                "rzp_pay_2");

        // Debit leg balance delta (-750) plus credit leg balance delta (+750) must net to zero —
        // the exemption only lifts the non-negative floor, it never fabricates or destroys money.
        BigDecimal debitDelta = clearingWallet.getBalance().subtract(BigDecimal.ZERO);
        BigDecimal creditDelta = brandWallet.getBalance().subtract(new BigDecimal("100.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(debitDelta.add(creditDelta)));
    }

    @Test
    @DisplayName(
            "post: a BRAND (non-platform) wallet at balance 0 still cannot go negative — the"
                    + " clearing-wallet exemption does not leak (W1-1 / C2)")
    void testBrandWalletExemptionDoesNotLeak() {
        Wallet brandDebitWallet = walletWithBalance(DEBIT_WALLET_ID, BigDecimal.ZERO);
        Wallet creatorCreditWallet = walletWithBalance(CREDIT_WALLET_ID, BigDecimal.ZERO);

        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(DEBIT_WALLET_ID)).thenReturn(Optional.of(brandDebitWallet));
        when(walletRepository.findByIdForUpdate(CREDIT_WALLET_ID)).thenReturn(Optional.of(creatorCreditWallet));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                ledgerService.post(
                                        DEBIT_WALLET_ID,
                                        CREDIT_WALLET_ID,
                                        new BigDecimal("250.00"),
                                        "INR",
                                        WalletTransactionType.ESCROW_HOLD,
                                        TxnReferenceType.MANUAL,
                                        "ref-2",
                                        "Escrow hold",
                                        "idem-2",
                                        null));

        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(walletTransactionRepository, never()).save(any());
    }

    private static Wallet walletWithBalance(String walletId, BigDecimal balance) {
        Wallet wallet = Wallet.forUser(walletId, "owner-" + walletId);
        wallet.applyBalanceDelta(balance);
        return wallet;
    }
}
