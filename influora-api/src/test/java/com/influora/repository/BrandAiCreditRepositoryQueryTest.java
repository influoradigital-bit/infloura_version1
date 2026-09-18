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
                BrandAiCreditRepository.class.getMethod(
                        "refundCredits", String.class, int.class, java.time.Instant.class);
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
                BrandAiCreditRepository.class.getMethod(
                        "syncPlanAllotment", String.class, int.class, java.time.Instant.class);
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

    // -----------------------------------------------------------------------------------------
    // T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- wiki/decisions/2026-09-18-ai-credit-clock.md.
    // grantAllotmentIncrease is RETIRED (generalized into refillForBillingPeriod below); its old
    // pinning test is replaced by pins on the four new atomic queries this build adds.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("grantAllotmentIncrease is retired -- refillForBillingPeriod replaces it")
    void grantAllotmentIncreaseNoLongerExists() {
        boolean stillExists =
                java.util.Arrays.stream(BrandAiCreditRepository.class.getMethods())
                        .anyMatch(m -> m.getName().equals("grantAllotmentIncrease"));
        assertTrue(!stillExists, "grantAllotmentIncrease should be retired per RULING-upgrade-grant.md's successor ruling");
    }

    @Test
    @DisplayName(
            "refillForBillingPeriod: JPQL SETs creditsRemaining from the row's OWN monthlyAllotment"
                    + " (never a Java-computed value), guards on creditGrantPeriodEnd, and stamps"
                    + " lastReset")
    void refillForBillingPeriodJpqlShape() throws NoSuchMethodException {
        Method method =
                BrandAiCreditRepository.class.getMethod(
                        "refillForBillingPeriod",
                        String.class,
                        java.time.Instant.class,
                        java.time.LocalDate.class,
                        java.time.Instant.class);
        Query query = method.getAnnotation(Query.class);
        assertTrue(query != null, "refillForBillingPeriod must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(
                jpql.contains("c.creditsRemaining = c.monthlyAllotment"),
                "must SET creditsRemaining from the row's OWN monthlyAllotment (F-0894): " + jpql);
        assertTrue(
                jpql.contains("c.creditGrantPeriodEnd = :periodEnd"),
                "must record the billing-period marker this refill was applied for: " + jpql);
        assertTrue(
                jpql.contains("c.lastReset = :today"),
                "must stamp lastReset so the §3 handover top-up can tell this month's billing"
                        + " refill apart from an earlier one: "
                        + jpql);
        // Repair round LOW [vikram · 2026-09-18]: was `<>` (symmetric -- also re-fires for an
        // OLDER period than the one already granted, H2 probeD). Now `<`, forward-only.
        assertTrue(
                jpql.contains("c.creditGrantPeriodEnd IS NULL OR c.creditGrantPeriodEnd < :periodEnd"),
                "the WHERE clause must guard against re-granting for the SAME OR an OLDER billing"
                        + " period (F-0883 repeat-grant-on-every-flap; repair round LOW probeD): "
                        + jpql);
    }

    @Test
    @DisplayName(
            "topUpOnJoinCalendarClock: JPQL never lowers creditsRemaining and only fires once per"
                    + " UTC calendar month")
    void topUpOnJoinCalendarClockJpqlShape() throws NoSuchMethodException {
        Method method =
                BrandAiCreditRepository.class.getMethod(
                        "topUpOnJoinCalendarClock",
                        String.class,
                        java.time.LocalDate.class,
                        java.time.LocalDate.class,
                        java.time.Instant.class);
        Query query = method.getAnnotation(Query.class);
        assertTrue(query != null, "topUpOnJoinCalendarClock must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(
                jpql.contains("CASE WHEN c.creditsRemaining < c.monthlyAllotment THEN c.monthlyAllotment"),
                "must only RAISE creditsRemaining up to monthlyAllotment, never lower it: " + jpql);
        assertTrue(
                jpql.contains("ELSE c.creditsRemaining END"),
                "must leave creditsRemaining alone when it is already >= monthlyAllotment: " + jpql);
        assertTrue(
                jpql.contains("c.lastReset < :firstOfMonth"),
                "must guard on lastReset so this fires at most once per UTC calendar month: " + jpql);
    }

    @Test
    @DisplayName("calendarReset: JPQL is an unconditional atomic reset, no full-row save()")
    void calendarResetJpqlShape() throws NoSuchMethodException {
        Method method =
                BrandAiCreditRepository.class.getMethod(
                        "calendarReset", String.class, java.time.LocalDate.class, java.time.Instant.class);
        Query query = method.getAnnotation(Query.class);
        assertTrue(query != null, "calendarReset must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(jpql.contains("c.creditsRemaining = c.monthlyAllotment"), "must reset to monthlyAllotment: " + jpql);
        assertTrue(jpql.contains("c.lastReset = :today"), "must stamp lastReset: " + jpql);
    }

    @Test
    @DisplayName(
            "applyEscrowFundedReset: JPQL never writes creditGrantPeriodEnd, lastReset, or"
                    + " lastResetPeriodEnd -- a funded launch is a funding event on neither clock"
                    + " (F-0894)")
    void applyEscrowFundedResetJpqlNeverWritesClockMarkers() throws NoSuchMethodException {
        Method method =
                BrandAiCreditRepository.class.getMethod(
                        "applyEscrowFundedReset",
                        String.class,
                        int.class,
                        java.time.Instant.class,
                        java.time.Instant.class);
        Query query = method.getAnnotation(Query.class);
        assertTrue(query != null, "applyEscrowFundedReset must carry a @Query annotation");

        String jpql = query.value();
        assertTrue(
                !jpql.contains("creditGrantPeriodEnd"),
                "must NEVER write creditGrantPeriodEnd -- a funded launch is on neither clock: " + jpql);
        assertTrue(
                !jpql.contains("c.lastReset ="),
                "must NEVER write lastReset -- a funded launch is on neither clock: " + jpql);
        assertTrue(
                !jpql.contains("lastResetPeriodEnd"),
                "must NEVER write the deprecated lastResetPeriodEnd column: " + jpql);
        assertTrue(
                jpql.contains("c.unlimitedUntil = :unlimitedUntil"), "must open the unlimited window: " + jpql);
    }
}
