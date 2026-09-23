package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.influora.domain.entity.CreatorChallenge;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real Hibernate + H2 proof of {@code V20260923100000__creator_challenges.sql}'s {@code UNIQUE
 * (active_key)} index -- CHALLENGE-SPEC.md Backend &sect;8's named exit-test requirement. Mirrors
 * {@code ApplicationHistoryEventViewedUniquenessTest}'s shape and reasoning (that class's javadoc
 * has the full rationale for every choice repeated below): hand-rolled DDL because {@link
 * CreatorChallenge} carries no {@code unique = true} JPA mapping for {@code active_key} (the
 * constraint is deliberately DB-side only, same as V70's generated column), two genuinely
 * committing transactions via {@link TransactionTemplate} rather than real threads (the race's
 * OUTCOME is fully deterministic -- whether the database rejects a second insert once the first is
 * visible -- so two real concurrent threads would only add nondeterminism to assert on), and
 * {@code @Transactional(propagation = NOT_SUPPORTED)} so a constraint violation from one operation
 * cannot poison {@code @DataJpaTest}'s single ambient session for the rest of the method.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorChallenge.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorChallengeRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorChallengeRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_challenge_active_key_uniqueness_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorChallengeActiveKeyUniquenessTest {

    @Autowired private CreatorChallengeRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private void createSchema() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                entityManager.createNativeQuery("DROP TABLE IF EXISTS creator_challenges").executeUpdate());
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                entityManager
                                        .createNativeQuery(
                                                "CREATE TABLE creator_challenges ("
                                                        + "id VARCHAR(26) PRIMARY KEY,"
                                                        + "creator_user_id VARCHAR(26) NOT NULL,"
                                                        + "creator_profile_id VARCHAR(26) NOT NULL,"
                                                        + "started_on DATE NOT NULL,"
                                                        + "status VARCHAR(12) NOT NULL,"
                                                        + "active_key VARCHAR(64),"
                                                        + "created_at TIMESTAMP NOT NULL,"
                                                        + "ended_at TIMESTAMP,"
                                                        // Mirrors V20260923100000's UNIQUE KEY uk_creator_challenges_active_key.
                                                        + "UNIQUE (active_key))")
                                        .executeUpdate());
    }

    private void commit(CreatorChallenge challenge) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> repository.saveAndFlush(challenge));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "a second ACTIVE challenge for the SAME creator violates the real UNIQUE(active_key)"
                    + " constraint")
    void secondActiveChallengeForSameCreatorIsRejected() {
        createSchema();
        String creatorUserId = "01CREATORACTIVEDUP0001";

        commit(CreatorChallenge.start("01FIRSTCHALLENGE00001", creatorUserId, "01PROFILEDUP00000001", LocalDate.of(2026, 9, 1)));

        CreatorChallenge second =
                CreatorChallenge.start(
                        "01SECONDCHALLENGE0001", creatorUserId, "01PROFILEDUP00000001", LocalDate.of(2026, 9, 10));
        boolean rejected;
        try {
            commit(second);
            rejected = false;
        } catch (RuntimeException constraintViolation) {
            rejected = true;
            String chain = describeCauseChain(constraintViolation);
            assertTrue(
                    chain.toLowerCase(java.util.Locale.ROOT).contains("active_key")
                            || chain.toLowerCase(java.util.Locale.ROOT).contains("constraint")
                            || chain.toLowerCase(java.util.Locale.ROOT).contains("unique"),
                    "expected the real UNIQUE(active_key) constraint to be the cause, got: " + chain);
        }

        if (!rejected) {
            fail("a second ACTIVE challenge for the same creator must be rejected by the real constraint");
        }
        assertEquals(1, repository.count(), "only the first (winning) insert may have survived");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("two DIFFERENT creators each get their own ACTIVE row -- not blocked by the constraint")
    void differentCreatorsEachHaveTheirOwnActiveChallenge() {
        createSchema();
        commit(CreatorChallenge.start("01CREATORAACTIVE0001", "01CREATORA0000000001", "01PROFILEA00000001", LocalDate.of(2026, 9, 1)));
        commit(CreatorChallenge.start("01CREATORBACTIVE0001", "01CREATORB0000000001", "01PROFILEB00000001", LocalDate.of(2026, 9, 1)));

        assertEquals(2, repository.count());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "ending a challenge clears active_key to NULL, so starting a new one for the same creator"
                    + " succeeds -- NULLs never collide under UNIQUE")
    void endedChallengeDoesNotBlockANewOneForTheSameCreator() {
        createSchema();
        String creatorUserId = "01CREATORCENDED000001";

        CreatorChallenge ended =
                CreatorChallenge.start("01ENDEDCHALLENGE00001", creatorUserId, "01PROFILEC00000001", LocalDate.of(2026, 9, 1));
        ended.markEnded();
        commit(ended);

        CreatorChallenge newActive =
                CreatorChallenge.start("01NEWACTIVECHALLENGE1", creatorUserId, "01PROFILEC00000001", LocalDate.of(2026, 9, 10));
        commit(newActive);

        assertEquals(2, repository.count());
    }

    private static String describeCauseChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
