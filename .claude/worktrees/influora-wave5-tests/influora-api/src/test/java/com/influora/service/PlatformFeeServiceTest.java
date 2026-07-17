package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PlatformFeeConfigRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task #26 V1 — creator-side platform fee at escrow release. */
@ExtendWith(MockitoExtension.class)
class PlatformFeeServiceTest {

  private static final String MILESTONE_ID = "01HMILESTONE123456789";
  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
  private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
  private static final String REVENUE_WALLET_ID = "01HREVENUE12345678901";

  @Mock private PlatformFeeConfigRepository configRepository;
  @Mock private WalletLedgerService ledgerService;
  @Mock private PlatformWalletService platformWalletService;

  private PlatformFeeService service;

  @BeforeEach
  void setUp() {
    service = new PlatformFeeService(configRepository, ledgerService, platformWalletService);
  }

  private void stubConfig(int defaultFeeBps) {
    PlatformFeeConfig config = org.mockito.Mockito.mock(PlatformFeeConfig.class);
    when(config.getDefaultFeeBps()).thenReturn(defaultFeeBps);
    when(configRepository.findById(PlatformFeeConfig.SINGLETON_ID))
        .thenReturn(Optional.of(config));
  }

  @Test
  @DisplayName("deductAtRelease: deducts exactly 15% of gross at release (not funding)")
  void testDeductAtReleaseAppliesFifteenPercent() {
    stubConfig(1500);
    Wallet clearingWallet = wallet(CLEARING_WALLET_ID);
    Wallet revenueWallet = wallet(REVENUE_WALLET_ID);
    when(platformWalletService.requireRevenueWallet()).thenReturn(revenueWallet);

    WalletTransaction debitLeg = txn("fee-debit");
    WalletTransaction creditLeg = txn("fee-credit");
    when(ledgerService.post(
            eq(CLEARING_WALLET_ID),
            eq(REVENUE_WALLET_ID),
            eq(new BigDecimal("1500.00")),
            eq("INR"),
            eq(WalletTransactionType.PLATFORM_FEE),
            eq(TxnReferenceType.MILESTONE),
            eq(MILESTONE_ID),
            any(),
            eq("release-fee:" + ESCROW_HOLD_ID),
            eq(null)))
        .thenReturn(new WalletLedgerService.LedgerPostingResult(debitLeg, creditLeg));

    PlatformFeeService.FeeDeductionResult result =
        service.deductAtRelease(
            clearingWallet,
            MILESTONE_ID,
            CREATOR_USER_ID,
            new BigDecimal("10000.00"),
            "INR",
            ESCROW_HOLD_ID);

    assertEquals(new BigDecimal("10000.00"), result.grossAmount());
    assertEquals(1500, result.feeBpsApplied());
    assertEquals(new BigDecimal("1500.00"), result.platformFee());
    assertEquals(new BigDecimal("8500.00"), result.netAmount());
    assertNotNull(result.feePosting());
  }

  @Test
  @DisplayName("deductAtRelease: fee posts to platform revenue wallet via ledger")
  void testDeductAtReleaseFeeLandsInPlatformLedger() {
    stubConfig(1500);
    Wallet clearingWallet = wallet(CLEARING_WALLET_ID);
    Wallet revenueWallet = wallet(REVENUE_WALLET_ID);
    when(platformWalletService.requireRevenueWallet()).thenReturn(revenueWallet);
    when(ledgerService.post(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(txn("d"), txn("c")));

    service.deductAtRelease(
        clearingWallet,
        MILESTONE_ID,
        CREATOR_USER_ID,
        new BigDecimal("2000.00"),
        "INR",
        ESCROW_HOLD_ID);

    ArgumentCaptor<WalletTransactionType> typeCaptor =
        ArgumentCaptor.forClass(WalletTransactionType.class);
    verify(ledgerService)
        .post(
            eq(CLEARING_WALLET_ID),
            eq(REVENUE_WALLET_ID),
            eq(new BigDecimal("300.00")),
            eq("INR"),
            typeCaptor.capture(),
            eq(TxnReferenceType.MILESTONE),
            eq(MILESTONE_ID),
            any(),
            eq("release-fee:" + ESCROW_HOLD_ID),
            eq(null));
    assertEquals(WalletTransactionType.PLATFORM_FEE, typeCaptor.getValue());
    verify(platformWalletService).requireRevenueWallet();
  }

  @Test
  @DisplayName("deductAtRelease: never mutates wallet balance outside WalletLedgerService.post")
  void testDeductAtReleaseNoDirectBalanceMutation() {
    stubConfig(1500);
    Wallet clearingWallet = wallet(CLEARING_WALLET_ID);
    Wallet revenueWallet = wallet(REVENUE_WALLET_ID);
    when(platformWalletService.requireRevenueWallet()).thenReturn(revenueWallet);
    when(ledgerService.post(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(txn("d"), txn("c")));

    BigDecimal balanceBefore = clearingWallet.getBalance();
    service.deductAtRelease(
        clearingWallet,
        MILESTONE_ID,
        CREATOR_USER_ID,
        new BigDecimal("1000.00"),
        "INR",
        ESCROW_HOLD_ID);

    assertEquals(balanceBefore, clearingWallet.getBalance());
    verify(ledgerService).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("deductAtRelease: fee bps read from DB config, not hardcoded")
  void testDeductAtReleaseReadsFeeFromDbConfig() {
    stubConfig(1200);
    Wallet clearingWallet = wallet(CLEARING_WALLET_ID);
    Wallet revenueWallet = wallet(REVENUE_WALLET_ID);
    when(platformWalletService.requireRevenueWallet()).thenReturn(revenueWallet);
    when(ledgerService.post(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(txn("d"), txn("c")));

    PlatformFeeService.FeeDeductionResult result =
        service.deductAtRelease(
            clearingWallet,
            MILESTONE_ID,
            CREATOR_USER_ID,
            new BigDecimal("10000.00"),
            "INR",
            ESCROW_HOLD_ID);

    assertEquals(1200, result.feeBpsApplied());
    assertEquals(new BigDecimal("1200.00"), result.platformFee());
    assertEquals(new BigDecimal("8800.00"), result.netAmount());
    verify(configRepository).findById(PlatformFeeConfig.SINGLETON_ID);
  }

  @Test
  @DisplayName("deductAtRelease: zero fee skips ledger posting when config is 0 bps")
  void testDeductAtReleaseSkipsPostingWhenFeeIsZero() {
    stubConfig(0);
    Wallet clearingWallet = wallet(CLEARING_WALLET_ID);

    PlatformFeeService.FeeDeductionResult result =
        service.deductAtRelease(
            clearingWallet,
            MILESTONE_ID,
            CREATOR_USER_ID,
            new BigDecimal("5000.00"),
            "INR",
            ESCROW_HOLD_ID);

    assertEquals(BigDecimal.ZERO.setScale(2), result.platformFee());
    assertEquals(new BigDecimal("5000.00"), result.netAmount());
    assertNull(result.feePosting());
    verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(platformWalletService, never()).requireRevenueWallet();
  }

  @Test
  @DisplayName("split: computes fee and net from bps without side effects")
  void testSplitMath() {
    PlatformFeeService.FeeSplit split =
        service.split(new BigDecimal("10000.00"), 1500);

    assertEquals(new BigDecimal("1500.00"), split.platformFee());
    assertEquals(new BigDecimal("8500.00"), split.netAmount());
  }

  private static Wallet wallet(String id) {
    return Wallet.forWorkspace(id, "owner-" + id);
  }

  private static WalletTransaction txn(String id) {
    return WalletTransaction.builder()
        .id(id)
        .walletId(CLEARING_WALLET_ID)
        .groupId("grp-1")
        .direction(TxnDirection.DEBIT)
        .type(WalletTransactionType.PLATFORM_FEE)
        .amount(new BigDecimal("1.00"))
        .currency("INR")
        .balanceAfter(BigDecimal.ZERO)
        .build();
  }
}
