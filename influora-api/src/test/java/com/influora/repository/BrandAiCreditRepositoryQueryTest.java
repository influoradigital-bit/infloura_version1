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
}
