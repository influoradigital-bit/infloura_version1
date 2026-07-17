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

    private static Wallet walletWithBalance(String walletId, BigDecimal balance) {
        Wallet wallet = Wallet.forUser(walletId, "owner-" + walletId);
        wallet.applyBalanceDelta(balance);
        return wallet;
    }
}
