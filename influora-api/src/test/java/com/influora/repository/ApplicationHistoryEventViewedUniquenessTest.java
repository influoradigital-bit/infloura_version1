package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import jakarta.persistence.EntityManager;
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
 * Real Hibernate + H2 proof of {@code V70__application_history_events_viewed_unique.sql} — the
 * migration closing the concurrent-insert race flagged in the Decision 6 review
 * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md): {@code
 * ApplicationHistoryService#recordViewIfAbsent}'s check-then-insert has no row lock, and Decision
 * 6 grew its call sites from one ({@code DealService#get}) to four ({@code get}/{@code
 * doAccept}/{@code doReject}/{@code doCounter}), all guarded on {@code UserType.BRAND} — so two
 * near-simultaneous brand-authenticated requests for the SAME application (Deal Room open in one
 * tab, Accept clicked from the Bids tab in another) can both pass the exists-check and both
 * insert, leaving two {@code APPLICATION_VIEWED} rows in an append-only ledger the creator reads
 * and is meant to trust as a single, first-write-wins signal.
 *
 * <p><b>What this proves, and what it deliberately does NOT.</b> This proves the SCHEMA-LEVEL
 * claim V70's own migration comment makes: the generated-column UNIQUE index rejects a genuine
 * second {@code APPLICATION_VIEWED} row for the same application, while leaving every recurring
 * event type (CAMPAIGN_APPLIED, APPLICATION_ACCEPTED, APPLICATION_REJECTED, APPLICATION_WITHDRAWN
 * — the exact reason V69 never put a blanket unique constraint on {@code (application_id,
 * event_type)}) completely unaffected. It does NOT re-prove that {@code
 * ApplicationHistoryService#recordViewIfAbsent}'s own {@code REQUIRES_NEW} propagation lets a
 * deferred-flush violation surface to its caller — that exact mechanism, for this same entity
 * type, is already proven by {@code ApplicationHistoryServiceRollbackIsolationTest} (for {@code
 * #record}) and independently re-confirmed by {@code
 * CreatorCampaignServiceApplyHistoryFkRaceTest}; re-deriving it here would duplicate coverage
 * rather than add any. See {@code recordViewIfAbsent}'s own javadoc for why no Java-level {@code
 * try/catch} was added for this — its four call sites' pre-existing {@code catch
 * (RuntimeException e)} already absorbs it.
 *
 * <p>Two inserts are driven directly through the repository, each in its OWN explicit, genuinely
 * committing (or genuinely failing) {@link TransactionTemplate}, rather than through two real
 * concurrent threads. A genuinely interleaved two-thread race is nondeterministic to assert on;
 * what actually determines the OUTCOME of that race is not the timing of the two exists-checks
 * (TOCTOU always exists for check-then-act without a lock — that part needs no proof) but whether
 * the database rejects the SECOND of two {@code APPLICATION_VIEWED} inserts for the same
 * application once the first is visible. That is fully deterministic and is what is exercised
 * here: commit the first insert in its own transaction, then attempt the second in a SEPARATE
 * transaction — mirroring how {@code CreatorCampaignServiceApplyHistoryFkRaceTest} proves its own
 * real-constraint race by forcing the specific ordering that exposes the mechanism, not by
 * spinning up real threads.
 *
 * <p><b>Why {@code @Transactional(propagation = NOT_SUPPORTED)} plus explicit {@code
 * TransactionTemplate}s per operation, mirroring {@code
 * ApplicationHistoryServiceRollbackIsolationTest}/{@code
 * CreatorCampaignServiceApplyHistoryFkRaceTest}.</b> {@code @DataJpaTest} wraps every test method
 * in ONE ambient transaction/session, auto-rolled-back only at teardown. Left active, a
 * constraint violation from the second insert would poison that SAME shared Hibernate session for
 * the REST of the test method — including the follow-up row-count query, which would then fail
 * for an unrelated reason (an invalid-session error, not the assertion this test exists to make).
 * {@code NOT_SUPPORTED} suspends the ambient wrapping so each {@code TransactionTemplate.execute}
 * below is a genuinely separate transaction/session; a failure in one cannot poison another,
 * exactly like a real {@code REQUIRES_NEW} call would not poison its caller.
 *
 * <p><b>Why hand-written DDL, mirroring {@code ApplicationHistoryEventOrderingTest}.</b> Letting
 * Hibernate generate the H2 schema from the entity (this codebase's usual {@code create-drop}
 * pattern) would produce no generated column and no unique index at all — {@link
 * ApplicationHistoryEvent} carries no JPA mapping for either, both being pure SQL-side
 * constructs — so the one thing this test exists to prove would silently not be exercised. The
 * DDL below mirrors V69 (loosened to {@code VARCHAR} enums, same rationale as {@code
 * ApplicationHistoryEventOrderingTest}) plus V70's generated column and unique index, using {@code
 * CASE WHEN ... END} rather than V70's MySQL {@code IF(...)} (semantically identical; H2 accepts
 * this ANSI form) and omitting V70's {@code STORED} keyword (H2's generated-column grammar has no
 * {@code STORED}/{@code VIRTUAL} distinction at all — every H2 generated column behaves as
 * computed; V70 itself is never executed against this test database, Flyway is disabled here,
 * same as every other hand-DDL test in this codebase).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = ApplicationHistoryEvent.class)
@EnableJpaRepositories(
        basePackageClasses = ApplicationHistoryEventRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!ApplicationHistoryEventRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:application_history_viewed_uniqueness_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ApplicationHistoryEventViewedUniquenessTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String APPLICATION_A = "01HDEALVIEWEDUNIQUEA01";
    private static final String APPLICATION_B = "01HDEALVIEWEDUNIQUEB01";

    @Autowired private ApplicationHistoryEventRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private void createSchemaMirroringV69PlusV70() {
        // The two test methods below each genuinely COMMIT (see class javadoc on why NOT_SUPPORTED
        // + explicit TransactionTemplates are used), and this H2 URL carries DB_CLOSE_DELAY=-1, so
        // the schema from whichever test method ran first would otherwise still exist when the
        // second one starts within the same class run.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                entityManager
                                        .createNativeQuery("DROP TABLE IF EXISTS application_history_events")
                                        .executeUpdate());
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                entityManager
                                        .createNativeQuery(
                                                "CREATE TABLE application_history_events ("
                                                        + "id VARCHAR(26) PRIMARY KEY,"
                                                        + "sequence_no BIGINT NOT NULL AUTO_INCREMENT,"
                                                        + "campaign_id VARCHAR(26) NOT NULL,"
                                                        + "application_id VARCHAR(26) NOT NULL,"
                                                        + "deal_room_id VARCHAR(26),"
                                                        + "event_type VARCHAR(32) NOT NULL,"
                                                        + "event_status VARCHAR(32) NOT NULL,"
                                                        + "actor_type VARCHAR(16) NOT NULL,"
                                                        + "actor_id VARCHAR(26),"
                                                        + "description CLOB NOT NULL,"
                                                        + "metadata CLOB,"
                                                        + "target_route VARCHAR(255),"
                                                        + "target_id VARCHAR(26),"
                                                        + "created_at TIMESTAMP(3) NOT NULL,"
                                                        // V70's generated column, portable ANSI form (see class javadoc).
                                                        + "viewed_dedup_key VARCHAR(26)"
                                                        + "   GENERATED ALWAYS AS ("
                                                        + "     CASE WHEN event_type = 'APPLICATION_VIEWED'"
                                                        + "          THEN application_id ELSE NULL END),"
                                                        + "UNIQUE (sequence_no),"
                                                        + "UNIQUE (viewed_dedup_key))")
                                        .executeUpdate());
    }

    private ApplicationHistoryEvent viewedEvent(String id, String applicationId) {
        return ApplicationHistoryEvent.create(
                id,
                CAMPAIGN_ID,
                applicationId,
                null,
                ApplicationHistoryEventType.APPLICATION_VIEWED,
                CollaborationStatus.APPLIED,
                ApplicationHistoryActorType.BRAND,
                "brand_1",
                "Brand reviewed the application",
                null,
                null,
                null);
    }

    private ApplicationHistoryEvent recurringEvent(
            String id, String applicationId, ApplicationHistoryEventType type) {
        return ApplicationHistoryEvent.create(
                id,
                CAMPAIGN_ID,
                applicationId,
                null,
                type,
                CollaborationStatus.APPLIED,
                ApplicationHistoryActorType.CREATOR,
                "creator_1",
                "recurring event fixture",
                null,
                null,
                null);
    }

    /**
     * The exit-test requirement: the race Decision 6 review flagged. First insert commits in its
     * own transaction (mirrors the concurrent winner already succeeding); the second, for the SAME
     * application and the SAME event type, in a SEPARATE transaction, is the concurrent loser's
     * insert — it must fail against the real constraint, not silently create a duplicate row.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "V70: a second APPLICATION_VIEWED row for the same application violates the real UNIQUE"
                    + " constraint")
    void testSecondViewedRowForSameApplicationViolatesConstraint() {
        createSchemaMirroringV69PlusV70();

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> repository.saveAndFlush(viewedEvent("01HFIRSTVIEWEDROW00001", APPLICATION_A)));

        boolean secondInsertRejected;
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status ->
                                    repository.saveAndFlush(
                                            viewedEvent("01HSECONDVIEWEDROW0001", APPLICATION_A)));
            secondInsertRejected = false;
        } catch (RuntimeException constraintViolation) {
            secondInsertRejected = true;
            String chain = describeCauseChain(constraintViolation);
            assertTrue(
                    chain.toLowerCase(java.util.Locale.ROOT).contains("viewed_dedup_key")
                            || chain.toLowerCase(java.util.Locale.ROOT).contains("constraint")
                            || chain.toLowerCase(java.util.Locale.ROOT).contains("unique"),
                    "expected the real UNIQUE(viewed_dedup_key) constraint to be the cause, got: "
                            + chain);
        }

        if (!secondInsertRejected) {
            fail(
                    "a second APPLICATION_VIEWED insert for the same application must be rejected"
                            + " by V70's real constraint, not silently succeed");
        }

        assertEquals(
                1,
                repository.findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(APPLICATION_A).size(),
                "exactly one APPLICATION_VIEWED row must survive — the loser's insert must not have"
                        + " partially applied, and its own failed transaction must not have touched"
                        + " the winner's already-committed row");
    }

    /**
     * The non-regression half of the same exit-test requirement: V70 must NOT reintroduce the
     * exact bug V69 was written to avoid (a blanket unique constraint that would 500 every
     * withdraw-then-reapply cycle, F-0225). Two CAMPAIGN_APPLIED rows for the same application —
     * the shape a genuine re-application produces — must both persist cleanly.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "V70: two CAMPAIGN_APPLIED rows for the same application (withdraw-then-reapply, F-0225)"
                    + " are NOT blocked by the new constraint")
    void testRecurringEventTypeStillPermitsMultipleRowsForSameApplication() {
        createSchemaMirroringV69PlusV70();

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                repository.saveAndFlush(
                                        recurringEvent(
                                                "01HFIRSTAPPLYROW000001",
                                                APPLICATION_B,
                                                ApplicationHistoryEventType.CAMPAIGN_APPLIED)));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                repository.saveAndFlush(
                                        recurringEvent(
                                                "01HSECONDAPPLYROW00001",
                                                APPLICATION_B,
                                                ApplicationHistoryEventType.CAMPAIGN_APPLIED)));

        assertEquals(
                2,
                repository.findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(APPLICATION_B).size(),
                "both CAMPAIGN_APPLIED rows must survive — V70 must be scoped to APPLICATION_VIEWED"
                        + " only, never to the raw (application_id, event_type) pair");
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
