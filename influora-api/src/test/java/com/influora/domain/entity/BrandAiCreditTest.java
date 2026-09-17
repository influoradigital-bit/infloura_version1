package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-3 [vikram · 2026-09-17]: unit tests for {@link BrandAiCredit}'s derived {@code
 * monthlyAllotment} (= {@code planAllotment + loyaltyBonus}) and the {@code Builder}'s
 * back-compat {@code .monthlyAllotment(int)} alias, now that the column is no longer directly
 * settable.
 *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3
 */
class BrandAiCreditTest {

    private static BrandAiCredit.Builder baseBuilder() {
        return BrandAiCredit.builder()
                .workspaceId("01HWORKSPACE12345678A")
                .cycleStart(LocalDate.now())
                .lastReset(LocalDate.now());
    }

    @Test
    @DisplayName("setPlanAllotment recomputes monthlyAllotment without touching loyaltyBonus")
    void testSetPlanAllotmentRecomputesMonthlyAllotment() {
        BrandAiCredit credit = baseBuilder().monthlyAllotment(100).build();

        credit.setPlanAllotment(400);

        assertEquals(400, credit.getPlanAllotment());
        assertEquals(0, credit.getLoyaltyBonus());
        assertEquals(400, credit.getMonthlyAllotment());
    }

    @Test
    @DisplayName("setLoyaltyBonus recomputes monthlyAllotment without touching planAllotment")
    void testSetLoyaltyBonusRecomputesMonthlyAllotment() {
        BrandAiCredit credit = baseBuilder().monthlyAllotment(400).build();

        credit.setLoyaltyBonus(50);

        assertEquals(400, credit.getPlanAllotment());
        assertEquals(50, credit.getLoyaltyBonus());
        assertEquals(450, credit.getMonthlyAllotment());
    }

    @Test
    @DisplayName("setPlanAllotment after setLoyaltyBonus keeps the bonus (order-independent, F-3's whole point)")
    void testPlanAllotmentChangeAfterLoyaltyBonusKeepsBonus() {
        BrandAiCredit credit = baseBuilder().monthlyAllotment(100).build();

        credit.setLoyaltyBonus(50); // 150 (Free + bonus)
        assertEquals(150, credit.getMonthlyAllotment());

        credit.setPlanAllotment(400); // upgrade to Pro -- bonus must survive
        assertEquals(450, credit.getMonthlyAllotment());
        assertEquals(50, credit.getLoyaltyBonus());

        credit.setPlanAllotment(100); // downgrade back to Free -- bonus must still survive
        assertEquals(150, credit.getMonthlyAllotment());
        assertEquals(50, credit.getLoyaltyBonus());
    }

    @Test
    @DisplayName("Builder.monthlyAllotment(int) is a back-compat alias for planAllotment, loyaltyBonus defaults to 0")
    void testBuilderMonthlyAllotmentAliasesToPlanAllotment() {
        BrandAiCredit credit = baseBuilder().monthlyAllotment(100).build();

        assertEquals(100, credit.getPlanAllotment());
        assertEquals(0, credit.getLoyaltyBonus());
        assertEquals(100, credit.getMonthlyAllotment());
    }

    @Test
    @DisplayName("Builder.planAllotment(int) + Builder.loyaltyBonus(int) compose at build() time")
    void testBuilderPlanAllotmentAndLoyaltyBonusCompose() {
        BrandAiCredit credit = baseBuilder().planAllotment(400).loyaltyBonus(50).build();

        assertEquals(450, credit.getMonthlyAllotment());
    }
}
