package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock private WalletRepository walletRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private PaymentMilestoneRepository paymentMilestoneRepository;
    @Mock private PlatformWalletService platformWalletService;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService =
                new WalletService(
                        walletRepository,
                        ledgerService,
                        walletTransactionRepository,
                        paymentMilestoneRepository,
                        platformWalletService);
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

    @Test
    @DisplayName("getBalance: throws WALLET_NOT_FOUND for missing workspace")
    void testGetBalanceThrowsForMissingWallet() {
        when(walletRepository.findByOwnerId(WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () ->
                walletService.getBalance(WORKSPACE_ID));

        assertEquals("WALLET_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
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
