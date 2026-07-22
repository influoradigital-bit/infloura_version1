package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Retention purge (Priya's PARTIAL-2 ruling, wiki/build/partials-resolution-plan.md; Kabir L1,
 * wiki/build/phase2-kabir-security.md): unit tests for {@link
 * MeeraInteractionLogRepository#deleteByCreatedAtBefore}.
 *
 * <p>There is no H2/testcontainers-backed {@code @DataJpaTest} harness wired up for this module
 * (see {@code CreatorMetricsRepositoryTest}'s docstring / {@code BrandAiCreditRepositoryQueryTest}
 * — this codebase defers real JPQL execution coverage to integration tests against a live DB, and
 * testcontainers-mysql requires Docker, unavailable in this offline unit-test run). Following
 * {@code BrandAiCreditRepositoryQueryTest}'s established convention exactly, this test pins the
 * literal JPQL and its bulk-delete annotations via reflection, so a future edit that silently
 * widens/narrows the age predicate (e.g. dropping the {@code <}, flipping it to {@code <=}, or
 * deleting on the wrong column) fails this test instead of shipping unnoticed.
 */
class MeeraInteractionLogRepositoryQueryTest {

    @Test
    @DisplayName("deleteByCreatedAtBefore: JPQL deletes strictly by created_at age, no other predicate")
    void deleteByCreatedAtBeforeJpqlIsAgeOnly() throws NoSuchMethodException {
        Method deleteByCreatedAtBefore =
                MeeraInteractionLogRepository.class.getMethod(
                        "deleteByCreatedAtBefore", Instant.class);

        Query query = deleteByCreatedAtBefore.getAnnotation(Query.class);
        assertNotNull(query, "deleteByCreatedAtBefore must carry a @Query annotation");

        String jpql = query.value();

        // Bulk age-based delete, not load-then-delete: a DELETE statement, not a SELECT.
        assertTrue(
                jpql.trim().toUpperCase().startsWith("DELETE FROM MEERAINTERACTIONLOG"),
                "deleteByCreatedAtBefore must be a bulk DELETE against MeeraInteractionLog: " + jpql);

        // Strictly age-based: created_at compared to the cutoff parameter with "<" (rows AT the
        // cutoff instant are retained, matching "older than", not "at-or-older-than").
        assertTrue(
                jpql.contains("l.createdAt < :cutoff"),
                "deleteByCreatedAtBefore must delete rows where createdAt is strictly before :cutoff: "
                        + jpql);

        // Info-barrier guard (Kabir check 3): the purge must never key off workspace_id,
        // campaign_id, or any other column -- an age-only predicate is what keeps this job from
        // ever needing to read cross-party data.
        assertTrue(
                !jpql.contains("workspaceId") && !jpql.contains("campaignId"),
                "deleteByCreatedAtBefore must be age-only -- no workspace/campaign predicate: " + jpql);

        Modifying modifying = deleteByCreatedAtBefore.getAnnotation(Modifying.class);
        assertNotNull(modifying, "deleteByCreatedAtBefore must be @Modifying (it's a write, not a read)");
        assertTrue(
                modifying.clearAutomatically(),
                "deleteByCreatedAtBefore should clear the persistence context after the bulk delete"
                        + " (same convention as RefreshTokenRepository#revokeAllForUser)");

        assertEquals(
                int.class,
                deleteByCreatedAtBefore.getReturnType(),
                "deleteByCreatedAtBefore must return the deleted row count (Spring Data bulk-@Modifying"
                        + " convention)");
    }
}
