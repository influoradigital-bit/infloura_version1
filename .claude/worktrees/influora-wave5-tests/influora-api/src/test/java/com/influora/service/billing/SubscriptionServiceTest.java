package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.meera.AICreditService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MP-1 wiring tests for Task 24's AI-credit reconciliation fix (Priya's Phase 3 sign-off
 * finding) — the highest-value item in this task. Per MP-1 (wiki/tech/security.md), these tests
 * assert the {@code AICreditService#applyPlanAllotment} call actually FIRES from the subscription
 * state transitions that should trigger it, not just that the allotment numbers are correct in
 * isolation (that calculation is already covered by {@code AICreditServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String RAZORPAY_SUB_ID = "sub_test123";
    private static final String RAZORPAY_PLAN_ID = "plan_test_pro";
    private static final String FREE_PLAN_ID = "01HWXYZPLANFREE000000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanService planService;
    @Mock private RazorpayClient razorpayClient;
    @Mock private AICreditService aiCreditService;

    private SubscriptionService subscriptionService;

    private Plan freePlan;
    private Plan proPlan;

    @BeforeEach
    void setUp() {
        subscriptionService =
                new SubscriptionService(
                        subscriptionRepository, planRepository, planService, razorpayClient, aiCreditService);

        freePlan =
                Plan.builder()
                        .id(FREE_PLAN_ID)
                        .code(PlanCode.FREE)
                        .name("Free")
                        .priceInr(0)
                        .aiMonthlyAllotment(100)
                        .active(true)
                        .build();
        proPlan =
                Plan.builder()
                        .id(PRO_PLAN_ID)
                        .code(PlanCode.PRO)
                        .name("Pro")
                        .priceInr(499900)
                        .razorpayPlanId(RAZORPAY_PLAN_ID)
                        .aiMonthlyAllotment(400)
                        .active(true)
                        .build();
    }

    @Test
    @DisplayName("wiring: brand-new subscription row activated straight to Pro reconciles AI-credit allotment to 400 immediately")
    void testNewSubscriptionActivatedReconcilesToProAllotment() {
        // findByWorkspaceId is called TWICE by production code for this path: once to resolve
        // "does a row already exist" (must be empty to hit the new-row branch), and again by
        // reconcileAiCreditAllotment's getActivePlanForWorkspace re-read AFTER the new row is
        // saved (must see the just-created row, exactly like a real repository would within the
        // same transaction). AtomicReference + thenAnswer models that instead of a static stub.
        AtomicReference<Subscription> savedRef = new AtomicReference<>();
        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(
                        inv -> {
                            Subscription s = inv.getArgument(0);
                            savedRef.set(s);
                            return s;
                        });
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID,
                WORKSPACE_ID,
                RAZORPAY_PLAN_ID,
                SubscriptionStatus.ACTIVE,
                Instant.now(),
                Instant.now().plusSeconds(2592000),
                Instant.now());

        // The wiring assertion MP-1 requires: the call actually fires, not just that 400 is the
        // "right" number somewhere in isolation.
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: Free-tier row upgraded to Pro mid-cycle reconciles allotment to 400 on THIS webhook, not deferred to the monthly cron")
    void testMidCycleUpgradeReconcilesImmediately() {
        Subscription existingFreeSub = freeSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingFreeSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID,
                WORKSPACE_ID,
                RAZORPAY_PLAN_ID,
                SubscriptionStatus.ACTIVE,
                Instant.now(),
                Instant.now().plusSeconds(2592000),
                Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: Pro subscription cancelled reconciles allotment back down to Free's 100 immediately, not deferred to the monthly cron")
    void testCancellationReconcilesDownToFreeImmediately() {
        Subscription existingProSub = proSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        // After setStatus(CANCELLED) mutates the row in place, getActivePlanForWorkspace's
        // re-read must see it as no-longer-ACTIVE and fall back to Free — this is the same
        // in-memory row Mockito returns both times, mirroring how the real repository would
        // return the just-flushed row within the same transaction.
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.CANCELLED, null, null, Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 100);
    }

    @Test
    @DisplayName("wiring: Pro subscription halted (dunning-exhausted) reconciles allotment back down to Free's 100 immediately")
    void testHaltedReconcilesDownToFreeImmediately() {
        Subscription existingProSub = proSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.HALTED, null, null, Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 100);
    }

    @Test
    @DisplayName("reconciliation failure is caught, logged, and does NOT roll back or fail the already-applied subscription status write")
    void testReconciliationFailureDoesNotBreakWebhookProcessing() {
        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);
        doThrow(new RuntimeException("simulated credit-service outage"))
                .when(aiCreditService)
                .applyPlanAllotment(eq(WORKSPACE_ID), anyInt());

        assertDoesNotThrow(
                () ->
                        subscriptionService.applySubscriptionWebhookUpdate(
                                RAZORPAY_SUB_ID,
                                WORKSPACE_ID,
                                RAZORPAY_PLAN_ID,
                                SubscriptionStatus.ACTIVE,
                                Instant.now(),
                                Instant.now().plusSeconds(2592000),
                                Instant.now()));

        verify(subscriptionRepository).save(any(Subscription.class));
    }

    @Test
    @DisplayName("stale out-of-order webhook delivery is skipped entirely — no reconciliation call fires for a no-op")
    void testStaleWebhookSkipDoesNotReconcile() {
        Subscription existingProSub = proSubscriptionRow();
        Instant lastApplied = Instant.now();
        existingProSub.markWebhookApplied(lastApplied);

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));

        Instant staleEventAt = lastApplied.minusSeconds(3600);
        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.PAST_DUE, null, null, staleEventAt);

        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName(
            "wiring [SEC: Kabir red-team Phase 4a MEDIUM-2]: applyRenewalSafetyNet advances the"
                    + " period AND syncs Pro allotment AND resets credits in one call")
    void testApplyRenewalSafetyNetSyncsProAllotmentAndResetsCredits() {
        Subscription sub = proSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd);

        // The MP-1 wiring assertion: all three steps actually fire from ONE call, matching what
        // used to be three separately auto-committing calls made directly by the job.
        verify(subscriptionRepository).save(sub);
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
        verify(aiCreditService).resetForNewCycle(WORKSPACE_ID);
    }

    @Test
    @DisplayName("wiring: applyRenewalSafetyNet on a Free-tier workspace resets credits but does NOT call applyPlanAllotment")
    void testApplyRenewalSafetyNetFreeTierSkipsAllotmentSync() {
        Subscription sub = freeSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(FREE_PLAN_ID)).thenReturn(Optional.of(freePlan));

        subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd);

        verify(subscriptionRepository).save(sub);
        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
        verify(aiCreditService).resetForNewCycle(WORKSPACE_ID);
    }

    @Test
    @DisplayName(
            "wiring [SEC: Kabir red-team Phase 4a MEDIUM-2]: a credit-sync failure propagates"
                    + " (NOT swallowed) so @Transactional rolls back the period-advance too")
    void testApplyRenewalSafetyNetPropagatesCreditSyncFailure() {
        Subscription sub = proSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));
        doThrow(new RuntimeException("simulated credit-service outage"))
                .when(aiCreditService)
                .resetForNewCycle(WORKSPACE_ID);

        // Unlike reconcileAiCreditAllotment (deliberately swallows failures — a best-effort side
        // effect of an already-committed webhook write), applyRenewalSafetyNet must NOT swallow:
        // this method IS the atomic unit of work, so the exception must propagate for Spring's
        // @Transactional proxy to roll back the period-advance save() alongside it, and for the
        // job's per-subscription catch to correctly skip the audit-log call for this subscription.
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd));
    }

    /**
     * MP-1 wiring tests for Task 25 ({@code AdminBillingController}'s {@code grantAdminPlan} —
     * the shared primitive behind {@code POST /admin/billing/comp} and {@code
     * POST /admin/billing/override}). Same discipline as the webhook tests above: assert the
     * {@code AICreditService#applyPlanAllotment} call actually fires, and that comp metadata is
     * genuinely persisted, not just that the "right" numbers are computed somewhere in isolation.
     */
    @Test
    @DisplayName("wiring: comp grant to a brand-new workspace creates an ACTIVE, comp-flagged Pro subscription and reconciles AI-credit allotment to 400 immediately")
    void testGrantAdminPlanNewWorkspaceCreatesActiveCompSubscription() {
        String adminId = "01HWXYZADMIN000000000001";
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result =
                subscriptionService.grantAdminPlan(WORKSPACE_ID, proPlan, adminId, "Q3 partnership comp — 90 days", null);

        assertTrue(result.isComp());
        assertEquals("Q3 partnership comp — 90 days", result.getCompReason());
        assertEquals(adminId, result.getCompGrantedBy());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertEquals(PRO_PLAN_ID, result.getPlanId());
        assertNull(result.getRazorpaySubscriptionId());

        verify(subscriptionRepository).save(any(Subscription.class));
        // The MP-1 assertion: the SAME reconciliation call a real Razorpay webhook activation
        // fires, not a parallel/duplicated "give them Pro benefits" code path.
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: comp grant to a workspace with an existing Free row upgrades that SAME row in place and reconciles credits")
    void testGrantAdminPlanUpgradesExistingFreeRowInPlace() {
        Subscription existingFreeSub = freeSubscriptionRow();
        String adminId = "01HWXYZADMIN000000000001";
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingFreeSub));

        Subscription result =
                subscriptionService.grantAdminPlan(
                        WORKSPACE_ID, proPlan, adminId, "Support-team goodwill grant", Instant.now().plusSeconds(86400));

        assertEquals(existingFreeSub, result);
        assertTrue(result.isComp());
        assertEquals(PRO_PLAN_ID, result.getPlanId());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        verify(subscriptionRepository).save(existingFreeSub);
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("a workspace with a REAL Razorpay-backed subscription cannot be comp'd/overridden — ALREADY_PAID_SUBSCRIBER, no save, no reconciliation")
    void testGrantAdminPlanRejectsRealPaidSubscriber() {
        Subscription existingProSub = proSubscriptionRow(); // has razorpaySubscriptionId set
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));

        com.influora.common.ApiException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.influora.common.ApiException.class,
                        () ->
                                subscriptionService.grantAdminPlan(
                                        WORKSPACE_ID, freePlan, "admin1", "accidental downgrade attempt", null));

        org.junit.jupiter.api.Assertions.assertEquals("ALREADY_PAID_SUBSCRIBER", ex.getCode());
        verify(subscriptionRepository, never()).save(any());
        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
    }

    private Subscription freeSubscriptionRow() {
        return Subscription.builder()
                .id("01HWXYZSUB0000000000001")
                .workspaceId(WORKSPACE_ID)
                .planId(FREE_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(Instant.now().minusSeconds(86400))
                .currentPeriodEnd(Instant.now().plusSeconds(2592000))
                .build();
    }

    private Subscription proSubscriptionRow() {
        return Subscription.builder()
                .id("01HWXYZSUB0000000000002")
                .workspaceId(WORKSPACE_ID)
                .planId(PRO_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .razorpaySubscriptionId(RAZORPAY_SUB_ID)
                .currentPeriodStart(Instant.now().minusSeconds(86400))
                .currentPeriodEnd(Instant.now().plusSeconds(2592000))
                .build();
    }
}
