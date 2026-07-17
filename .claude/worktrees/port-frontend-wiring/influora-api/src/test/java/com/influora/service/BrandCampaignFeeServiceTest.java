package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Plan;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.enums.PlanCode;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.service.billing.SubscriptionService;
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
            commissionInvoiceService);
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
}
