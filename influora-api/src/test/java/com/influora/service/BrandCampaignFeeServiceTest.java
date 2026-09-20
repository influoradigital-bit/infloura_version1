package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.service.billing.SubscriptionService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Task 21 — plan-aware brand-fee resolution. Covers {@code resolveBrandFeeBps(workspaceId)}'s 4
 * scenarios called out in the task handoff: Free/no-subscription, ACTIVE Pro, non-ACTIVE Pro
 * fallback, and exception-during-resolution fallback. {@code SubscriptionService.
 * getActivePlanForWorkspace} already collapses "no subscription", "PAST_DUE/HALTED/CANCELLED", and
 * "deactivated Plan row" cases down to returning the Free {@link Plan} (see that method's own
 * javadoc/tests) — scenario (c) here exercises that collapse from the caller's side by stubbing
 * the mock to return the Free plan directly, which is the observable contract
 * {@code BrandCampaignFeeService} actually depends on.
 */
@ExtendWith(MockitoExtension.class)
class BrandCampaignFeeServiceTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE1234567890";

  @Mock private PlatformFeeConfigRepository configRepository;
  @Mock private WalletLedgerService ledgerService;
  @Mock private PlatformWalletService platformWalletService;
  @Mock private WalletService walletService;
  @Mock private SubscriptionService subscriptionService;
  @Mock private CommissionInvoiceService commissionInvoiceService;
  @Mock private WalletTransactionRepository walletTransactionRepository;

  private BrandCampaignFeeService service;

  @BeforeEach
  void setUp() {
    service =
        new BrandCampaignFeeService(
            configRepository,
            ledgerService,
            platformWalletService,
            walletService,
            subscriptionService,
            commissionInvoiceService,
            walletTransactionRepository);
  }

  private void stubGlobalConfig(int brandFeeBps) {
    PlatformFeeConfig config = mock(PlatformFeeConfig.class);
    when(config.getBrandFeeBps()).thenReturn(brandFeeBps);
    when(configRepository.findById(PlatformFeeConfig.SINGLETON_ID)).thenReturn(Optional.of(config));
  }

  private static Plan planWithCodeAndFee(PlanCode code, Integer feeBps) {
    Plan plan = mock(Plan.class);
    when(plan.getCode()).thenReturn(code);
    // resolveBrandFeeBps short-circuits on `plan.getCode() == PRO` before ever calling
    // getFeeBps() — only stub it for PRO plans so a FREE-plan test doesn't leave an unconsulted
    // (strict-stubbing-flagged) stub behind.
    if (code == PlanCode.PRO) {
      when(plan.getFeeBps()).thenReturn(feeBps);
    }
    return plan;
  }

  @Test
  @DisplayName("(a) Free/no-subscription workspace gets the global 10% rate")
  void freeWorkspaceGetsGlobalTenPercent() {
    // NOTE: the Plan mock must be built as its own statement, never inline inside
    // when(...).thenReturn(...) — Mockito evaluates method arguments before the outer when()'s
    // thenReturn() runs, so stubbing a second mock mid-argument-evaluation leaves the outer when()
    // "unfinished" from Mockito's point of view (UnfinishedStubbingException).
    Plan freePlan = planWithCodeAndFee(PlanCode.FREE, 1000);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(freePlan);
    stubGlobalConfig(1000);

    int bps = service.resolveBrandFeeBps(WORKSPACE_ID);

    assertEquals(1000, bps);
  }

  @Test
  @DisplayName("(b) ACTIVE Pro subscription workspace gets the plan's 7% override")
  void activeProWorkspaceGetsPlanFeeBps() {
    Plan proPlan = planWithCodeAndFee(PlanCode.PRO, 700);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(proPlan);

    int bps = service.resolveBrandFeeBps(WORKSPACE_ID);

    assertEquals(700, bps);
    // Plan-aware path taken — the global singleton is never consulted when Pro resolves cleanly.
    org.mockito.Mockito.verifyNoInteractions(configRepository);
  }

  @Test
  @DisplayName(
      "(c) non-ACTIVE Pro (CANCELLED/PAST_DUE/HALTED) falls back to global 10%, not the Pro rate")
  void nonActiveProFallsBackToGlobalConfig() {
    // SubscriptionService.getActivePlanForWorkspace already resolves a CANCELLED/PAST_DUE/HALTED
    // subscription down to the Free plan (see its own javadoc/tests) — from
    // BrandCampaignFeeService's perspective this is indistinguishable from "was never on Pro",
    // so stubbing the Free plan here is the correct way to exercise this fallback.
    Plan freePlan = planWithCodeAndFee(PlanCode.FREE, 1000);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(freePlan);
    stubGlobalConfig(1000);

    int bps = service.resolveBrandFeeBps(WORKSPACE_ID);

    assertEquals(1000, bps);
  }

  @Test
  @DisplayName("(d) exception during plan resolution falls back to global 10% safely, does not throw")
  void exceptionDuringPlanResolutionFallsBackSafely() {
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
        .thenThrow(new RuntimeException("simulated SubscriptionService outage"));
    stubGlobalConfig(1000);

    int bps = service.resolveBrandFeeBps(WORKSPACE_ID);

    assertEquals(1000, bps);
  }

  @Test
  @DisplayName("a Pro plan row with null feeBps also falls back to global config, never NPEs")
  void proPlanWithNullFeeBpsFallsBackSafely() {
    Plan proPlanNoOverride = planWithCodeAndFee(PlanCode.PRO, null);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(proPlanNoOverride);
    stubGlobalConfig(1000);

    int bps = service.resolveBrandFeeBps(WORKSPACE_ID);

    assertEquals(1000, bps);
  }

  @Test
  @DisplayName("resolveBrandFeeBps always resolves the plan for the given workspaceId, never a different one")
  void resolvesPlanForTheGivenWorkspaceId() {
    Plan proPlan = planWithCodeAndFee(PlanCode.PRO, 700);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(proPlan);

    service.resolveBrandFeeBps(WORKSPACE_ID);

    verify(subscriptionService).getActivePlanForWorkspace(WORKSPACE_ID);
  }

  // ==================================================================================
  // EV-005 / EV-026 / F-0857 / F-0858 — the publish fee is ONE-TIME per campaign.
  //
  // Plan resolution is stubbed to the FREE 10% global rate (bps=1000), so fee = budget * 10%.
  // `alreadyPaidFee` (walletTransactionRepository.sumAmountByReferenceAndTypeAndDirection) is
  // stubbed directly: that repository read IS the production seam the one-time rule keys off.
  // CampaignActivationLedgerIntegrationTest covers the same rule against the real ledger.
  // ==================================================================================

  private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";

  private static Campaign campaignWithBudget(BigDecimal budgetMax) {
    return Campaign.builder()
        .id(CAMPAIGN_ID)
        .workspaceId(WORKSPACE_ID)
        .title("One-time fee test campaign")
        .status(CampaignStatus.PAUSED)
        .budgetMax(budgetMax)
        .build();
  }

  private void stubFreeTenPercentPlan() {
    Plan freePlan = planWithCodeAndFee(PlanCode.FREE, 1000);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(freePlan);
    stubGlobalConfig(1000);
  }

  private void stubAlreadyPaid(BigDecimal amount) {
    when(walletTransactionRepository.sumAmountByReferenceAndTypeAndDirection(
            TxnReferenceType.CAMPAIGN, CAMPAIGN_ID, WalletTransactionType.PLATFORM_FEE, TxnDirection.DEBIT))
        .thenReturn(amount);
  }

  private Wallet stubBrandWallet(BigDecimal balance) {
    Wallet wallet = mock(Wallet.class);
    // lenient: an insufficient-delta-balance case throws before requireBrandWallet() ever reads
    // getId()/getCurrency() -- only getBalance() is consulted on that path.
    org.mockito.Mockito.lenient().when(wallet.getId()).thenReturn("01HWALLETBRAND1234567");
    org.mockito.Mockito.lenient().when(wallet.getCurrency()).thenReturn("INR");
    when(wallet.getBalance()).thenReturn(balance);
    when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);
    return wallet;
  }

  private Wallet stubRevenueWallet() {
    Wallet wallet = mock(Wallet.class);
    when(wallet.getId()).thenReturn("01HWALLETREVENUE123456");
    when(platformWalletService.requireRevenueWallet()).thenReturn(wallet);
    return wallet;
  }

  private WalletLedgerService.LedgerPostingResult fakePosting() {
    WalletTransaction debit = mock(WalletTransaction.class);
    WalletTransaction credit = mock(WalletTransaction.class);
    return new WalletLedgerService.LedgerPostingResult(debit, credit);
  }

  @Test
  @DisplayName(
      "F-0857: resume of an already-paid campaign with wallet balance BELOW the fee -> succeeds,"
          + " posts nothing, balance check never runs")
  void resumeOfAlreadyPaidCampaignSucceedsWithNoBalanceCheck() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(10_000));
    stubFreeTenPercentPlan();
    stubAlreadyPaid(BigDecimal.valueOf(1000));

    BrandCampaignFeeService.FeeChargeResult result = service.chargeOnPublish(campaign, WORKSPACE_ID);

    assertEquals(0, result.feeAmount().compareTo(BigDecimal.ZERO));
    verifyNoInteractions(walletService);
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(platformWalletService);
    verifyNoInteractions(commissionInvoiceService);
  }

  @Test
  @DisplayName(
      "EV-026/F-0858: budget RAISED while paused -> resume posts NO second PLATFORM_FEE (so no fee"
          + " debit can exist without its commission invoice) and never collides on the ledger key")
  void budgetRaisedWhilePausedChargesNothing() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(15_000));
    stubFreeTenPercentPlan();
    stubAlreadyPaid(BigDecimal.valueOf(1000)); // paid on the original 10,000 budget

    BrandCampaignFeeService.FeeChargeResult result = service.chargeOnPublish(campaign, WORKSPACE_ID);

    assertEquals(0, result.feeAmount().compareTo(BigDecimal.ZERO));
    verifyNoInteractions(walletService);
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(platformWalletService);
    verifyNoInteractions(commissionInvoiceService);
  }

  @Test
  @DisplayName("F-0858: budget lowered while paused -> charges nothing and refunds nothing")
  void budgetLoweredWhilePausedChargesNothing() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(4000));
    stubFreeTenPercentPlan();
    stubAlreadyPaid(BigDecimal.valueOf(1000));

    BrandCampaignFeeService.FeeChargeResult result = service.chargeOnPublish(campaign, WORKSPACE_ID);

    assertEquals(0, result.feeAmount().compareTo(BigDecimal.ZERO));
    verifyNoInteractions(walletService);
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(platformWalletService);
    verifyNoInteractions(commissionInvoiceService);
  }

  @Test
  @DisplayName("plan rate RAISED after the fee was paid -> resume still charges nothing")
  void planRateRaisedAfterPaymentChargesNothing() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(10_000));
    stubGlobalConfig(1500); // FREE plan, global rate raised from 10% to 15% since first publish
    Plan freePlan = planWithCodeAndFee(PlanCode.FREE, 1500);
    when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(freePlan);
    stubAlreadyPaid(BigDecimal.valueOf(1000));

    BrandCampaignFeeService.FeeChargeResult result = service.chargeOnPublish(campaign, WORKSPACE_ID);

    assertEquals(0, result.feeAmount().compareTo(BigDecimal.ZERO));
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(commissionInvoiceService);
  }

  @Test
  @DisplayName(
      "first publish charges the full fee ONCE under key brand-fee-publish:<id>, and issues exactly"
          + " one commission invoice for that same posting")
  void firstChargePostsFullFeeOnceWithOneInvoice() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(10_000));
    stubFreeTenPercentPlan();
    stubAlreadyPaid(BigDecimal.ZERO);
    stubBrandWallet(BigDecimal.valueOf(5000));
    stubRevenueWallet();
    WalletLedgerService.LedgerPostingResult posting = fakePosting();
    when(ledgerService.post(
            anyString(),
            anyString(),
            any(BigDecimal.class),
            anyString(),
            eq(WalletTransactionType.PLATFORM_FEE),
            eq(TxnReferenceType.CAMPAIGN),
            eq(CAMPAIGN_ID),
            anyString(),
            eq("brand-fee-publish:" + CAMPAIGN_ID),
            eq(null)))
        .thenReturn(posting);

    BrandCampaignFeeService.FeeChargeResult result = service.chargeOnPublish(campaign, WORKSPACE_ID);

    assertEquals(0, result.feeAmount().compareTo(BigDecimal.valueOf(1000)));
    verify(ledgerService, times(1))
        .post(
            anyString(),
            anyString(),
            eq(BigDecimal.valueOf(1000).setScale(2)),
            anyString(),
            eq(WalletTransactionType.PLATFORM_FEE),
            eq(TxnReferenceType.CAMPAIGN),
            eq(CAMPAIGN_ID),
            anyString(),
            eq("brand-fee-publish:" + CAMPAIGN_ID),
            eq(null));
    verify(commissionInvoiceService, times(1))
        .createBrandLegAtPublish(
            eq(campaign), eq(WORKSPACE_ID), eq(1000), eq(BigDecimal.valueOf(1000).setScale(2)), eq(posting));
  }

  @Test
  @DisplayName(
      "first publish with insufficient balance -> INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH naming the"
          + " shortfall, nothing posted")
  void firstChargeInsufficientBalanceNamesTheShortfall() {
    Campaign campaign = campaignWithBudget(BigDecimal.valueOf(10_000)); // fee = 1000
    stubFreeTenPercentPlan();
    stubAlreadyPaid(BigDecimal.ZERO);
    stubBrandWallet(BigDecimal.valueOf(100)); // short by 900

    ApiException ex =
        assertThrows(ApiException.class, () -> service.chargeOnPublish(campaign, WORKSPACE_ID));

    assertEquals("INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH", ex.getCode());
    assertEquals(true, ex.getMessage().contains("900"));
    verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(commissionInvoiceService);
  }
}
