package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.Entitlement;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.UsageMetric;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.billing.UsageCounterService;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-proves the generalized {@link EntitlementService} primitives (redesign doc §3.2, Phase 1)
 * in isolation, against a REAL {@link Plan} built via its own builder (not a mock) so {@link
 * Plan#limitFor} actually runs — this is the coverage {@code WorkspaceMemberServiceTest} /
 * {@code PlanGateWiringTest} cannot give the new generalized primitive, since those mock {@link
 * Plan} and never call {@code limitFor} at all (see {@code EntitlementService}'s class javadoc).
 */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE00000000A";

    @Mock private SubscriptionService subscriptionService;
    @Mock private UsageCounterService usageCounterService;

    private EntitlementService entitlementService;

    private void setUp() {
        entitlementService = new EntitlementService(subscriptionService, usageCounterService);
    }

    private static Plan planWithSeatLimit(int seatLimit) {
        return Plan.builder()
                .id("plan-1")
                .code(PlanCode.FREE)
                .name("Free")
                .seatLimit(seatLimit)
                .aiMonthlyAllotment(100)
                .build();
    }

    private static Plan planWithAnalyticsLimit(Integer limit) {
        return Plan.builder()
                .id("plan-1")
                .code(PlanCode.FREE)
                .name("Free")
                .seatLimit(1)
                .creatorAnalyticsMonthlyLimit(limit)
                .aiMonthlyAllotment(100)
                .build();
    }

    // ===================== requireCapacity (instance overload -> Plan.limitFor) =====================

    @Test
    @DisplayName("requireCapacity: under the real Plan's limit -> no throw")
    void requireCapacity_underLimit_allows() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(planWithSeatLimit(5));

        entitlementService.requireCapacity(WORKSPACE_ID, Entitlement.SEATS, () -> 3L);
        // no exception -> pass
    }

    @Test
    @DisplayName("requireCapacity: at the real Plan's limit -> 402 UPGRADE_REQUIRED")
    void requireCapacity_atLimit_throws402() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(planWithSeatLimit(5));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> entitlementService.requireCapacity(WORKSPACE_ID, Entitlement.SEATS, () -> 5L));

        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        assertEquals(402, ex.getStatus().value());
    }

    @Test
    @DisplayName("requireCapacity: over the real Plan's limit -> 402 UPGRADE_REQUIRED")
    void requireCapacity_overLimit_throws402() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(planWithSeatLimit(5));

        assertThrows(
                ApiException.class,
                () -> entitlementService.requireCapacity(WORKSPACE_ID, Entitlement.SEATS, () -> 9L));
    }

    // ============ requireCapacity (static overload — Plan in, limit resolved from Entitlement) ====

    @Test
    @DisplayName("requireCapacity (static): unlimited (CREATOR_ANALYTICS_VIEWS w/ null column) never throws")
    void requireCapacityStatic_unlimited_neverThrows() {
        EntitlementService.requireCapacity(
                Entitlement.CREATOR_ANALYTICS_VIEWS, planWithAnalyticsLimit(null), Long.MAX_VALUE, () -> "unused");
    }

    @Test
    @DisplayName("requireCapacity (static): the message supplier is only evaluated on the throwing path")
    void requireCapacityStatic_messageSupplierLazy() {
        java.util.concurrent.atomic.AtomicBoolean evaluated = new java.util.concurrent.atomic.AtomicBoolean(false);
        EntitlementService.requireCapacity(
                Entitlement.SEATS,
                planWithSeatLimit(5),
                1L,
                () -> {
                    evaluated.set(true);
                    return "unused";
                });
        assertFalse(evaluated.get());
    }

    @Test
    @DisplayName(
            "requireCapacity (static): the Entitlement argument — not the caller — picks the column"
                    + " compared against")
    void requireCapacityStatic_entitlementChoosesTheLimit() {
        // One Plan, two different limits on it: seats=2, aiMonthlyAllotment=9. Same count (5),
        // same plan, different Entitlement -> opposite outcomes. This is the assertion the old
        // static overload (which took the limit as a parameter and ignored the Entitlement) could
        // not make.
        Plan plan =
                Plan.builder()
                        .id("plan-1")
                        .code(PlanCode.FREE)
                        .name("Free")
                        .seatLimit(2)
                        .aiMonthlyAllotment(9)
                        .build();

        assertThrows(
                ApiException.class,
                () -> EntitlementService.requireCapacity(Entitlement.SEATS, plan, 5L, () -> "over seats"));
        EntitlementService.requireCapacity(Entitlement.AI_CREDITS, plan, 5L, () -> "unused"); // under 9
    }

    // ===================== consume (instance overload -> Plan.limitFor) =====================

    @Test
    @DisplayName("consume: Free-shaped Plan (real limitFor) delegates to UsageCounterService.recordCreatorLookup with that limit")
    void consume_limited_delegatesWithResolvedLimit() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(planWithAnalyticsLimit(1));
        when(usageCounterService.recordCreatorLookup(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "creator-1", 1))
                .thenReturn(true);

        boolean allowed = entitlementService.consume(WORKSPACE_ID, Entitlement.CREATOR_ANALYTICS_VIEWS, "creator-1");

        assertTrue(allowed);
        verify(usageCounterService)
                .recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "creator-1", 1);
    }

    @Test
    @DisplayName("consume: rejection from the underlying counter propagates (no throw, returns false)")
    void consume_rejected_returnsFalse() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(planWithAnalyticsLimit(1));
        when(usageCounterService.recordCreatorLookup(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "creator-2", 1))
                .thenReturn(false);

        boolean allowed = entitlementService.consume(WORKSPACE_ID, Entitlement.CREATOR_ANALYTICS_VIEWS, "creator-2");

        assertFalse(allowed);
    }

    @Test
    @DisplayName("consume: unlimited (Pro, null limit) never calls recordCreatorLookup, only tracks via incrementUsage")
    void consume_unlimited_tracksOnly() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(planWithAnalyticsLimit(null));

        boolean allowed = entitlementService.consume(WORKSPACE_ID, Entitlement.CREATOR_ANALYTICS_VIEWS, "creator-1");

        assertTrue(allowed);
        verify(usageCounterService, never())
                .recordCreatorLookup(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(usageCounterService).incrementUsage(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, 1);
    }

    @Test
    @DisplayName("consume: AI_CREDITS has no UsageCounterService-backed metric -> rejected loudly, not silently mis-routed")
    void consume_aiCredits_rejectedLoudly() {
        setUp();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(planWithSeatLimit(1));

        assertThrows(
                IllegalArgumentException.class,
                () -> entitlementService.consume(WORKSPACE_ID, Entitlement.AI_CREDITS, "x"));
    }

    // ========= consume (static overload — Plan in, metric AND limit resolved from Entitlement) ===

    @Test
    @DisplayName("consume (static): metric and limit both come from the Entitlement, not from the caller")
    void consumeStatic_resolvesMetricAndLimitFromEntitlement() {
        when(usageCounterService.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 3))
                .thenReturn(true);

        boolean allowed =
                EntitlementService.consume(
                        Entitlement.CREATOR_ANALYTICS_VIEWS,
                        usageCounterService,
                        WORKSPACE_ID,
                        planWithAnalyticsLimit(3),
                        "c1");

        assertTrue(allowed);
        // limit 3 is NOT a literal any caller passed -- it was read off the plan by
        // Entitlement.CREATOR_ANALYTICS_VIEWS.limitIn(plan).
        verify(usageCounterService).recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 3);
    }

    @Test
    @DisplayName("consume (static): unlimited plan takes the observability-only path, never the dedup counter")
    void consumeStatic_unlimited_tracksOnly() {
        boolean allowed =
                EntitlementService.consume(
                        Entitlement.CREATOR_ANALYTICS_VIEWS,
                        usageCounterService,
                        WORKSPACE_ID,
                        planWithAnalyticsLimit(null),
                        "c1");

        assertTrue(allowed);
        verify(usageCounterService, never())
                .recordCreatorLookup(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(usageCounterService).incrementUsage(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, 1);
    }

    @Test
    @DisplayName("consume (static): AI_CREDITS is rejected loudly here too, before any counter is touched")
    void consumeStatic_aiCredits_rejectedLoudly() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EntitlementService.consume(
                                Entitlement.AI_CREDITS,
                                usageCounterService,
                                WORKSPACE_ID,
                                planWithSeatLimit(1),
                                "x"));
        org.mockito.Mockito.verifyNoInteractions(usageCounterService);
    }

    // ===== the old inert static overloads must not come back =====

    @Test
    @DisplayName(
            "no public EntitlementService primitive lets a caller supply the limit or the metric"
                    + " itself — that is what made the Entitlement argument inert")
    void noStaticOverloadTakesACallerSuppliedLimitOrMetric() {
        for (java.lang.reflect.Method m : EntitlementService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            if (!m.getName().equals("requireCapacity") && !m.getName().equals("consume")) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(
                        p == OptionalInt.class || p == UsageMetric.class || p == int.class || p == Integer.class,
                        "EntitlementService."
                                + m.getName()
                                + " takes a caller-supplied "
                                + p.getSimpleName()
                                + ". The limit and the metric must be derived from the Entitlement"
                                + " argument (Entitlement.limitIn / meteredMetricFor), never handed in"
                                + " -- a hand-in parameter is exactly how the Entitlement became a"
                                + " marker the gate could not falsify.");
            }
        }
    }
}
