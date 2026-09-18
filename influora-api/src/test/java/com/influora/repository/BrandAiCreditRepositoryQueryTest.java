package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Kabir red-team follow-up, MUST-FIX #3: {@code refundCredits} must never push {@code
 * creditsRemaining} above {@code monthlyAllotment} -- a monthly reset landing between a turn's
 * send-time charge and its later release (e.g. AICreditService#release, following a provider
 * failure) could otherwise let the unconditional {@code + :amount} add overshoot the workspace's
 * allotment.
 *
 * <p>There is no H2/testcontainers-backed @DataJpaTest harness wired up for this module (see
 * CreatorMetricsRepositoryTest's docstring — this codebase defers real JPQL execution coverage to
 * integration tests against a live DB, and testcontainers-mysql is present in the pom but requires
 * Docker, unavailable in this offline unit-test run). This test instead pins the literal JPQL on
 * {@link BrandAiCreditRepository#refundCredits} via reflection on its {@code @Query} annotation, so
 * a future edit that silently drops the clamp (reverting to a plain {@code + :amount}) fails this
 * test instead of shipping unnoticed.
 */
class BrandAiCreditRepositoryQueryTest {

    @Test
    @DisplayName("refundCredits: JPQL clamps the refunded total to monthlyAllotment")
    void refundCreditsJpqlClampsToMonthlyAllotment() throws NoSuchMethodException {
        Method refundCredits =
                BrandAiCreditRepository.class.getMethod("refundCredits", String.class, int.class);
        Query query = refundCredits.getAnnotation(Query.class);
        assertTrue(query != null, "refundCredits must carry a @Query annotation");

        String jpql = query.value();

        // The clamp: never let creditsRemaining + amount exceed monthlyAllotment.
        assertTrue(
                jpql.contains("c.creditsRemaining + :amount > c.monthlyAllotment"),
                "refundCredits JPQL must compare creditsRemaining + amount against monthlyAllotment: " + jpql);
        assertTrue(
                jpql.contains("THEN c.monthlyAllotment"),
                "refundCredits JPQL must clamp the result to monthlyAllotment when the sum overshoots: " + jpql);
        assertTrue(
                jpql.contains("ELSE c.creditsRemaining + :amount"),
                "refundCredits JPQL must still add the refund normally when under the allotment: " + jpql);
    }

    // -----------------------------------------------------------------------------------------
    // T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18]: same reflection-pinning precedent for
    // the two new atomic queries the grant-path fix (F-0881/F-0883/F-0885) adds. No DB harness
    // here either (see class javadoc) -- these pin the WHERE-clause guards so a future edit that
    // silently drops either one (reverting to the blind full-row save() this round removed) fails
    // this test instead of shipping unnoticed.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("syncPlanAllotment: JPQL never touches creditsRemaining or any daily-action column")
    void syncPlanAllotmentJpqlOnlyTouchesPlanAndMonthlyAllotment() throws NoSuchMethodException {
        Method syncPlanAllotment =
                BrandAiCreditRepository.class.getMethod("syncPlanAllotment", String.class, int.class);
        Query query = syncPlanAllotment.getAnnotation(Query.class);
        assertTrue(query != null, "syncPlanAllotment must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(jpql.contains("c.planAllotment = :planAllotment"), "must write planAllotment: " + jpql);
        assertTrue(
                jpql.contains("c.monthlyAllotment = :planAllotment + c.loyaltyBonus"),
                "must recompute monthlyAllotment server-side against the row's CURRENT loyaltyBonus: " + jpql);
        assertTrue(
                !jpql.contains("creditsRemaining"),
                "syncPlanAllotment must NEVER touch creditsRemaining -- that is the whole point of"
                        + " splitting the plan-sync write off from the grant write (F-0885): " + jpql);
    }

    @Test
    @DisplayName("grantAllotmentIncrease: JPQL guards the grant on creditGrantPeriodEnd, not just a service-layer check")
    void grantAllotmentIncreaseJpqlGuardsOnCreditGrantPeriodEnd() throws NoSuchMethodException {
        Method grantAllotmentIncrease =
                BrandAiCreditRepository.class.getMethod(
                        "grantAllotmentIncrease", String.class, int.class, java.time.Instant.class);
        Query query = grantAllotmentIncrease.getAnnotation(Query.class);
        assertTrue(query != null, "grantAllotmentIncrease must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(
                jpql.contains("c.creditsRemaining = :newAllotment"),
                "must SET creditsRemaining to the full new allotment (the ruling replaces the old"
                        + " top-up-by-the-increase rule): "
                        + jpql);
        assertTrue(
                jpql.contains("c.creditGrantPeriodEnd = :periodEnd"),
                "must record the billing-period marker this grant was applied for: " + jpql);
        assertTrue(
                jpql.contains("c.creditGrantPeriodEnd <> :periodEnd"),
                "the WHERE clause must guard against re-granting for the SAME billing period"
                        + " (F-0883 repeat-grant-on-every-flap): "
                        + jpql);
    }
}
