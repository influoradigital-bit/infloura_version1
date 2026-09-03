package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * T-ADMINMAIL-0903 REVIEW-R1.md round 2, C2: {@code findPendingForSend} must always sort every
 * currently-pending transactional-priority row (login OTP, password reset) ahead of any other
 * {@code templateKey}, regardless of {@code createdAt} — a {@code CASE WHEN ... THEN 0 ELSE 1 END}
 * before the {@code createdAt ASC} tiebreak, not a plain {@code createdAt ASC} across every key.
 *
 * <p>Same discipline as {@code BrandAiCreditRepositoryQueryTest}: there is no H2/testcontainers
 * @DataJpaTest harness wired up for this module (offline unit-test run, no Docker), so this pins
 * the literal JPQL via reflection on the {@code @Query} annotation rather than executing it — a
 * future edit that quietly drops the CASE-based priority (reverting to plain {@code createdAt ASC})
 * fails this test instead of shipping unnoticed and re-exposing OTP/password-reset to queuing
 * behind an admin.custom marketing blast.
 */
class EmailOutboxRepositoryQueryTest {

    @Test
    @DisplayName("findPendingForSend: JPQL sorts templateKeys in :priorityKeys ahead of createdAt")
    void findPendingForSendJpqlPrioritizesTransactionalKeys() throws NoSuchMethodException {
        Method findPendingForSend =
                EmailOutboxRepository.class.getMethod(
                        "findPendingForSend",
                        com.influora.domain.enums.EmailOutboxStatus.class,
                        java.time.Instant.class,
                        java.util.Collection.class,
                        org.springframework.data.domain.Pageable.class);
        Query query = findPendingForSend.getAnnotation(Query.class);
        assertTrue(query != null, "findPendingForSend must carry a @Query annotation");

        String jpql = query.value();

        assertTrue(
                jpql.contains("CASE WHEN e.templateKey IN :priorityKeys THEN 0 ELSE 1 END"),
                "findPendingForSend JPQL must rank :priorityKeys templateKeys ahead of everything"
                        + " else via a CASE expression: "
                        + jpql);
        assertTrue(
                jpql.contains("ORDER BY CASE WHEN e.templateKey IN :priorityKeys THEN 0 ELSE 1 END, e.createdAt ASC"),
                "the priority CASE must be the PRIMARY sort key, with createdAt ASC only as the"
                        + " tiebreak within each priority tier: "
                        + jpql);
    }
}
