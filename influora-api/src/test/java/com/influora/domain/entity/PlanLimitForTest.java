package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.enums.Entitlement;
import com.influora.domain.enums.PlanCode;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link Plan#limitFor} is the new read surface for CAPACITY/METERED {@link Entitlement}s
 * (redesign doc §3.1) — proves it against every entitlement, not just the ones already wired
 * through {@code EntitlementService} (see {@code EntitlementServiceTest} for those end-to-end).
 */
class PlanLimitForTest {

    private static Plan.Builder basePlan() {
        return Plan.builder()
                .id("plan-1")
                .code(PlanCode.PRO)
                .name("Pro")
                .seatLimit(5)
                .creatorAnalyticsMonthlyLimit(null)
                .aiMonthlyAllotment(400)
                .exportEnabled(true)
                .campaignTemplatesEnabled(true)
                .feeBps(700);
    }

    @Test
    @DisplayName("SEATS -> present, equal to the seatLimit column")
    void seats() {
        assertEquals(OptionalInt.of(5), basePlan().build().limitFor(Entitlement.SEATS));
    }

    @Test
    @DisplayName("CREATOR_ANALYTICS_VIEWS -> empty when the column is null (Pro/unlimited)")
    void creatorAnalyticsViews_null_isUnlimited() {
        assertTrue(basePlan().build().limitFor(Entitlement.CREATOR_ANALYTICS_VIEWS).isEmpty());
    }

    @Test
    @DisplayName("CREATOR_ANALYTICS_VIEWS -> present when the column is set (Free)")
    void creatorAnalyticsViews_set() {
        Plan plan = basePlan().creatorAnalyticsMonthlyLimit(1).build();
        assertEquals(OptionalInt.of(1), plan.limitFor(Entitlement.CREATOR_ANALYTICS_VIEWS));
    }

    @Test
    @DisplayName("AI_CREDITS -> present, equal to the aiMonthlyAllotment column, never unlimited")
    void aiCredits() {
        assertEquals(OptionalInt.of(400), basePlan().build().limitFor(Entitlement.AI_CREDITS));
    }

    @Test
    @DisplayName("EXPORT is FLAG-shaped, not limit-shaped -> limitFor throws rather than lying")
    void export_throws() {
        assertThrows(
                UnsupportedOperationException.class, () -> basePlan().build().limitFor(Entitlement.EXPORT));
    }

    @Test
    @DisplayName("CAMPAIGN_TEMPLATES is FLAG-shaped, not limit-shaped -> limitFor throws rather than lying")
    void campaignTemplates_throws() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> basePlan().build().limitFor(Entitlement.CAMPAIGN_TEMPLATES));
    }

    @Test
    @DisplayName("BRAND_FEE_BPS is RATE-shaped, not limit-shaped -> limitFor throws rather than lying")
    void brandFeeBps_throws() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> basePlan().build().limitFor(Entitlement.BRAND_FEE_BPS));
    }

    // ============ equivalence with the hand-rolled lookups the gates used to do ================

    /**
     * Every plan shape that exists or could exist, including the two really seeded by {@code
     * V55__seed_billing_plans.sql} (FREE: ai=100/seat=1/analytics=1; PRO: ai=400/seat=5/
     * analytics=NULL) and the boundary values around them.
     */
    static Stream<Plan> everyPlanShape() {
        return Stream.of(
                basePlan().name("Free").seatLimit(1).creatorAnalyticsMonthlyLimit(1).aiMonthlyAllotment(100).build(),
                basePlan().name("Pro").seatLimit(5).creatorAnalyticsMonthlyLimit(null).aiMonthlyAllotment(400).build(),
                basePlan().seatLimit(0).creatorAnalyticsMonthlyLimit(0).aiMonthlyAllotment(0).build(),
                basePlan().seatLimit(1).creatorAnalyticsMonthlyLimit(null).aiMonthlyAllotment(1).build(),
                basePlan()
                        .seatLimit(Integer.MAX_VALUE)
                        .creatorAnalyticsMonthlyLimit(Integer.MAX_VALUE)
                        .aiMonthlyAllotment(Integer.MAX_VALUE)
                        .build());
    }

    /**
     * The rework's behaviour-preservation claim, asserted rather than assumed: the limit the gates
     * now resolve from the {@link Entitlement} constant is, for every plan shape, byte-identical to
     * the hand-rolled getter lookup the two production call sites used to perform inline
     * ({@code OptionalInt.of(plan.getSeatLimit())} in {@code WorkspaceMemberService},
     * {@code plan.getCreatorAnalyticsMonthlyLimit()} + its {@code == null} unlimited branch in
     * {@code AnalyticsUsageCapInterceptor}).
     */
    @ParameterizedTest
    @MethodSource("everyPlanShape")
    @DisplayName("resolved limit == the hand-rolled getter lookup, for every plan shape")
    void resolvedLimitEqualsHandRolledLookup(Plan plan) {
        assertEquals(OptionalInt.of(plan.getSeatLimit()), Entitlement.SEATS.limitIn(plan));

        OptionalInt handRolledAnalytics =
                plan.getCreatorAnalyticsMonthlyLimit() == null
                        ? OptionalInt.empty()
                        : OptionalInt.of(plan.getCreatorAnalyticsMonthlyLimit());
        assertEquals(handRolledAnalytics, Entitlement.CREATOR_ANALYTICS_VIEWS.limitIn(plan));

        assertEquals(OptionalInt.of(plan.getAiMonthlyAllotment()), Entitlement.AI_CREDITS.limitIn(plan));
    }

    /**
     * {@link Plan#limitFor} is now a thin delegate to {@link Entitlement#limitIn} — proves the two
     * surfaces cannot drift apart, so the convenience surface every NEW caller is pointed at and
     * the resolution the production gates actually run stay the same thing.
     */
    @ParameterizedTest
    @MethodSource("everyPlanShape")
    @DisplayName("Plan.limitFor and Entitlement.limitIn agree on every limit-shaped entitlement")
    void limitForAgreesWithLimitIn(Plan plan) {
        for (Entitlement entitlement :
                new Entitlement[] {
                    Entitlement.SEATS, Entitlement.CREATOR_ANALYTICS_VIEWS, Entitlement.AI_CREDITS
                }) {
            assertEquals(entitlement.limitIn(plan), plan.limitFor(entitlement), entitlement.name());
        }
    }
}
