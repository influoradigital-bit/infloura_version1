package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.entity.ApplicationHistoryEvent;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Real Hibernate + H2 proof of the timestamp-tie ordering guarantee {@code
 * ApplicationHistoryEventRepository#findByApplicationIdOrderByCreatedAtAscSequenceNoAsc}
 * advertises. Sign-off review defect #3: {@code created_at} is {@code TIMESTAMP(3)} (millisecond
 * precision) and {@code CreatorCampaignService#recordApplicationHistory} writes CAMPAIGN_APPLIED
 * then APPLICATION_RECEIVED back-to-back, so a same-millisecond collision is a real case, not a
 * theoretical one -- with `created_at` alone, their relative order is undefined.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST. A mocked repository can be told to return events
 * in whatever order the test wants, which proves nothing about what the real derived query does
 * on a genuine tie. Only a real database can show whether the {@code sequence_no} tiebreak
 * actually breaks the tie.
 *
 * <p>WHY {@code ddl-auto=none} PLUS A HAND-WRITTEN {@code CREATE TABLE} INSTEAD OF THIS
 * CODEBASE'S USUAL {@code ddl-auto=create-drop}. {@code sequence_no} is deliberately mapped
 * {@code insertable = false, updatable = false} on the entity (see {@link
 * ApplicationHistoryEvent#getSequenceNo()}'s javadoc) -- the real column is a plain SQL {@code
 * AUTO_INCREMENT}, assigned by MySQL on INSERT, not something Hibernate's schema generator is
 * asked to produce from JPA annotations. Letting Hibernate generate the H2 schema from entities
 * alone (this codebase's usual pattern) would create {@code sequence_no} as an ordinary nullable
 * {@code BIGINT} with no auto-increment behavior at all, so the tiebreak this test exists to prove
 * would silently not exist in the test schema. The {@code CREATE TABLE} below is H2's {@code
 * AUTO_INCREMENT}-syntax mirror of the real V69 migration (enum columns loosened to {@code
 * VARCHAR} -- Hibernate writes/reads {@code @Enumerated(EnumType.STRING)} values as plain strings
 * regardless of the underlying column's declared type; the FK constraints are dropped since this
 * test needs no {@code campaigns}/{@code collaborations} parent rows). The hand-written DDL is
 * needed because the entity maps {@code sequence_no} {@code insertable=false, updatable=false},
 * so this test has to seed the column natively into a NOT NULL slot -- NOT because it exercises
 * real auto-increment semantics. It does not: the fixture assigns {@code sequence_no} explicitly
 * (see below), precisely so insertion order and sequence order can be made to disagree. Whether
 * MySQL assigns the column monotonically is an engine guarantee H2 could not prove either way.
 *
 * <p><b>Sign-off review defect (F-0317) -- the fixture used to be unable to falsify its own
 * claim.</b> The original seed inserted {@code FIRST_ID} first (letting {@code AUTO_INCREMENT}
 * hand it the lower {@code sequence_no}) and {@code SECOND_ID} second (the higher one), so
 * physical insertion order and {@code sequence_no} order were identical by construction. H2
 * happens to resolve a {@code created_at} tie with no {@code ORDER BY} tiebreak by returning rows
 * in that same physical order -- so removing {@code SequenceNoAsc} from the query (verified
 * directly: {@code findByApplicationIdOrderByCreatedAtAscSequenceNoAsc} -> {@code
 * findByApplicationIdOrderByCreatedAtAsc}) still produced the "expected" order and this test
 * still passed. A test that cannot fail proves nothing, no matter what its comment claims.
 *
 * <p>Fixed by making the two orders genuinely DISAGREE: {@code sequence_no} is assigned
 * EXPLICITLY in the native {@code INSERT} below (not left to {@code AUTO_INCREMENT}, which this
 * test does not otherwise need -- H2, like MySQL, accepts an explicit value for an
 * auto-increment column), and the row seeded PHYSICALLY FIRST ({@code SECOND_ID}) is given the
 * HIGHER {@code sequence_no}, while the row seeded PHYSICALLY SECOND ({@code FIRST_ID}) is given
 * the LOWER one. The correct answer ({@code FIRST_ID} before {@code SECOND_ID}, per {@code
 * sequence_no} ASC) now CONTRADICTS H2's own insertion-order tie resolution -- so a query that
 * silently dropped the {@code sequence_no} tiebreak would return the wrong order, and this test
 * would fail. Mutation-checked: verified this actually goes red against {@code
 * findByApplicationIdOrderByCreatedAtAsc} (tiebreak removed) and green against {@code
 * findByApplicationIdOrderByCreatedAtAscSequenceNoAsc} (tiebreak present).
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
            "spring.datasource.url=jdbc:h2:mem:application_history_ordering_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ApplicationHistoryEventOrderingTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String APPLICATION_ID = "01HDEAL00000000000001";
    private static final String FIRST_ID = "01HHIST0000000000000001";
    private static final String SECOND_ID = "01HHIST0000000000000002";

    @Autowired private ApplicationHistoryEventRepository repository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void createSchemaAndSeedSameMillisecondEvents() {
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
                                + "UNIQUE (sequence_no))")
                .executeUpdate();

        // Deliberately IDENTICAL created_at on both rows -- the exact collision
        // CreatorCampaignService#recordApplicationHistory produces when its two back-to-back
        // record() calls land in the same millisecond.
        Timestamp sameMillisecond = Timestamp.from(Instant.parse("2026-08-17T12:00:00.123Z"));

        // F-0317 -- seeded PHYSICALLY OUT OF ORDER relative to sequence_no, on purpose: SECOND_ID
        // is inserted FIRST but given the HIGHER sequence_no (2); FIRST_ID is inserted SECOND but
        // given the LOWER sequence_no (1). H2's own insertion-order tie resolution (see class
        // javadoc) would therefore return SECOND_ID before FIRST_ID if the sequence_no tiebreak
        // were ever silently dropped from the query -- the exact wrong order the assertions below
        // exist to catch. Explicit sequence_no values, not AUTO_INCREMENT's own assignment: H2,
        // like MySQL, accepts an explicit value on an auto-increment column.
        insertEvent(SECOND_ID, "APPLICATION_RECEIVED", sameMillisecond, 2);
        insertEvent(FIRST_ID, "CAMPAIGN_APPLIED", sameMillisecond, 1);
        entityManager.clear();
    }

    private void insertEvent(String id, String eventType, Timestamp createdAt, long sequenceNo) {
        entityManager
                .createNativeQuery(
                        "INSERT INTO application_history_events "
                                + "(id, sequence_no, campaign_id, application_id, event_type, event_status,"
                                + " actor_type, actor_id, description, created_at) "
                                + "VALUES (?1, ?2, ?3, ?4, ?5, 'APPLIED', 'CREATOR', ?6, ?7, ?8)")
                .setParameter(1, id)
                .setParameter(2, sequenceNo)
                .setParameter(3, CAMPAIGN_ID)
                .setParameter(4, APPLICATION_ID)
                .setParameter(5, eventType)
                .setParameter(6, "creator_1")
                .setParameter(7, eventType)
                .setParameter(8, createdAt)
                .executeUpdate();
    }

    /** The exit-test requirement: two events in the same millisecond return in a defined order. */
    @Test
    @DisplayName(
            "findByApplicationIdOrderByCreatedAtAscSequenceNoAsc: a same-millisecond created_at tie"
                    + " is broken by sequence_no, even when that contradicts H2's own insertion-order"
                    + " tie resolution")
    void testSameMillisecondTimestampsOrderBySequenceNo() {
        List<ApplicationHistoryEvent> events =
                repository.findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(APPLICATION_ID);

        assertEquals(2, events.size());
        // Both rows share the identical created_at seeded above. FIRST_ID was inserted SECOND
        // (physically) but carries the LOWER sequence_no (1) -- if sequence_no were not the
        // tiebreak (e.g. reverting to `ORDER BY created_at ASC` alone), H2 falls back to its own
        // insertion-order tie resolution and this would return SECOND_ID first instead, failing
        // the assertion below. Verified directly (F-0317 mutation check): swapping the repository
        // query to findByApplicationIdOrderByCreatedAtAsc (no tiebreak) turns this assertion red;
        // restoring SequenceNoAsc turns it green again.
        assertEquals(
                FIRST_ID,
                events.get(0).getHistoryId(),
                "lower sequence_no (1) must sort first, even though it was inserted physically"
                        + " second");
        assertEquals(
                SECOND_ID,
                events.get(1).getHistoryId(),
                "higher sequence_no (2) must sort second, even though it was inserted physically"
                        + " first");
        assertEquals(events.get(0).getCreatedAt(), events.get(1).getCreatedAt(), "both rows share one created_at");
        assertEquals(
                events.get(0).getSequenceNo() + 1,
                events.get(1).getSequenceNo(),
                "the returned rows carry the two seeded sequence_no values in ascending order"
                        + " -- tautological against this fixture's literals 1 and 2, and kept only as a"
                        + " shape check; it does NOT prove AUTO_INCREMENT assignment, which this test"
                        + " never exercises");
    }
}
