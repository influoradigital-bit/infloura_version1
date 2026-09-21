package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.ApplicationHistoryEventRepository;
import com.influora.repository.CollaborationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction contract of {@link ApplicationHistoryService}, against a REAL {@link
 * PlatformTransactionManager} and a real transactional proxy on {@link ApplicationHistoryWriter}.
 *
 * <p><b>Why a mocked service proves none of this.</b> Every other test that touches history mocks
 * {@code ApplicationHistoryService}. A mock has no {@code TransactionInterceptor}, no
 * synchronization registry and no deferred {@code em.merge()} flush — the exact three things this
 * defect lived in. Only the real bean, driven by a real transaction manager, can show WHEN the
 * INSERT happens relative to the caller's commit, which is the entire fix.
 *
 * <h2>The invariant these tests exist to hold</h2>
 *
 * <p>The row must NOT be inserted while the caller's transaction is still open. That is the whole
 * defect: {@code application_history_events.application_id} has an InnoDB FK to {@code
 * collaborations(id)} ({@code fk_app_history_application}, V69:64), an FK check takes {@code
 * S,REC_NOT_GAP} on the parent row, and every real caller already holds that row {@code X} (an
 * {@code UPDATE collaborations} from a status transition, or {@code
 * EscrowService#lockCollaborationEscrowForApproval}'s {@code SELECT ... FOR UPDATE}). An insert on
 * a SECOND connection therefore waited on its own caller until {@code innodb_lock_wait_timeout}.
 *
 * <p>H2 cannot reproduce an InnoDB FK lock wait, and a test that tried would be theatre. What it
 * CAN prove, exactly and cheaply, is the ordering that makes the wait impossible — nothing is
 * written until the caller has committed and released whatever it held. {@link
 * #nothingIsWrittenWhileTheCallersTransactionIsStillOpen} is that gate, and it is genuinely
 * falsifiable: restore {@code @Transactional(REQUIRES_NEW)} on {@code record} and the inner
 * transaction commits immediately, so the in-transaction count below reads 1 under H2's
 * READ_COMMITTED and the assertion goes red.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(
        basePackageClasses = {
            Collaboration.class,
            com.influora.domain.entity.ApplicationHistoryEvent.class
        })
@EnableJpaRepositories(
        basePackageClasses = {CollaborationRepository.class, ApplicationHistoryEventRepository.class},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CollaborationRepository$|ApplicationHistoryEventRepository$).*"))
@Import({ApplicationHistoryService.class, ApplicationHistoryWriter.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:application_history_after_commit_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ApplicationHistoryAfterCommitTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGNAFTERCMT01";
    private static final String CREATOR_ID = "01HCREATORAFTERCMT001";

    /** REAL Spring-proxied beans — a manually-{@code new}'d pair would bypass AOP entirely. */
    @Autowired private ApplicationHistoryService applicationHistoryService;

    @Autowired private ApplicationHistoryEventRepository historyRepository;
    @Autowired private CollaborationRepository collaborationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * {@code @DataJpaTest} wraps every method in its own ambient transaction, auto-rolled-back at
     * teardown — with that active, the {@link TransactionTemplate} below would merely PARTICIPATE
     * (REQUIRED joins) and never really commit, so every assertion here would be vacuous.
     * {@code NOT_SUPPORTED} suspends it so the template's transaction is the only real boundary in
     * play and its commit is a genuine commit. Same reasoning, and the same annotation, as {@code
     * ApplicationHistoryServiceRollbackIsolationTest}.
     */
    /**
     * Every test here suspends the {@code @DataJpaTest} ambient transaction (NOT_SUPPORTED) so
     * the TransactionTemplate below genuinely COMMITS — which also means nothing is rolled back
     * at teardown and rows leak into the next method. Truncating up front is what keeps each
     * "exactly once" / "exactly zero" count an assertion about THIS test rather than about the
     * order JUnit happened to run them in.
     */
    @BeforeEach
    void truncate() {
        historyRepository.deleteAll();
        collaborationRepository.deleteAll();
    }

    private TransactionTemplate businessTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void recordApproval(String collaborationId) {
        applicationHistoryService.record(
                CAMPAIGN_ID,
                collaborationId,
                collaborationId,
                ApplicationHistoryEventType.DELIVERABLE_APPROVED,
                CollaborationStatus.REVIEW_PENDING,
                ApplicationHistoryActorType.BRAND,
                "01HBRANDUSERAFTERCMT1",
                "Brand approved a deliverable",
                null,
                "/creator/chat?deal=" + collaborationId,
                collaborationId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "THE FIX: no history row exists while the caller's transaction is still open — it is"
                    + " inserted only after that transaction commits and releases its locks")
    void nothingIsWrittenWhileTheCallersTransactionIsStillOpen() {
        String collabId = "01HAFTERCMTORDERING001";

        businessTransaction()
                .execute(
                        status -> {
                            collaborationRepository.save(
                                    Collaboration.apply(collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                            // In production this transaction is ALSO holding the collaborations row
                            // under X (FOR UPDATE / UPDATE). An insert carrying an FK to that row,
                            // issued now on any other connection, would block on it until
                            // innodb_lock_wait_timeout.
                            recordApproval(collabId);

                            assertEquals(
                                    0L,
                                    historyRepository.count(),
                                    "record() must not have inserted anything yet — while this"
                                            + " transaction is open its FK parent row is still"
                                            + " locked, and an insert from another transaction"
                                            + " would wait ~50s on its own caller and then lose the"
                                            + " row. If this reads 1, record() is writing"
                                            + " in its own REQUIRES_NEW transaction again.");
                            return null;
                        });

        assertEquals(
                1L,
                historyRepository.count(),
                "the row must exist once the business transaction has committed — deferring the"
                        + " write must not mean dropping it");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "a rolled-back business transaction writes NO history row — an append-only timeline"
                    + " must never assert a fact that was undone")
    void rolledBackBusinessTransactionWritesNoHistoryRow() {
        String collabId = "01HAFTERCMTROLLBACK001";

        assertThrows(
                IllegalStateException.class,
                () ->
                        businessTransaction()
                                .execute(
                                        status -> {
                                            collaborationRepository.save(
                                                    Collaboration.apply(
                                                            collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                                            recordApproval(collabId);
                                            // Stands in for BrandDeliverableService#approve's rethrow
                                            // when the escrow release attempt fails.
                                            throw new IllegalStateException("escrow release failed");
                                        }));

        assertEquals(
                0L,
                historyRepository.count(),
                "the approval did not happen, so DELIVERABLE_APPROVED must not be on the timeline."
                        + " Under the old REQUIRES_NEW shape this row committed independently and"
                        + " survived the rollback — and append-only history has no compensating"
                        + " write to take it back (F-history-approve-rollback)");
        assertTrue(
                collaborationRepository.findById(collabId).isEmpty(),
                "sanity: the business write rolled back too");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("every audited action writes its row exactly once — three actions, three rows")
    void eachAuditedActionWritesExactlyOneRow() {
        String collabId = "01HAFTERCMTEXACTONCE01";

        businessTransaction()
                .execute(
                        status -> {
                            collaborationRepository.save(
                                    Collaboration.apply(collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                            applicationHistoryService.record(
                                    CAMPAIGN_ID,
                                    collabId,
                                    collabId,
                                    ApplicationHistoryEventType.FUND_ESCROW,
                                    CollaborationStatus.CONTRACTED,
                                    ApplicationHistoryActorType.SYSTEM,
                                    "system",
                                    "Funds secured — work can begin",
                                    null,
                                    null,
                                    null);
                            recordApproval(collabId);
                            applicationHistoryService.record(
                                    CAMPAIGN_ID,
                                    collabId,
                                    collabId,
                                    ApplicationHistoryEventType.DELIVER,
                                    CollaborationStatus.COMPLETED,
                                    ApplicationHistoryActorType.SYSTEM,
                                    "system",
                                    "Every deliverable has been resolved — delivery is complete",
                                    null,
                                    null,
                                    null);
                            return null;
                        });

        assertEquals(
                3L,
                historyRepository.count(),
                "three recorded actions must produce exactly three rows — no drop, no duplicate");
        assertEquals(
                1L,
                historyRepository.findAll().stream()
                        .filter(e -> e.getEventType() == ApplicationHistoryEventType.DELIVERABLE_APPROVED)
                        .count(),
                "DELIVERABLE_APPROVED exactly once");
        assertEquals(
                1L,
                historyRepository.findAll().stream()
                        .filter(e -> e.getEventType() == ApplicationHistoryEventType.FUND_ESCROW)
                        .count(),
                "FUND_ESCROW exactly once");
    }

    /**
     * The failure mode {@code AfterCommit} must absorb: the history write fails at ITS OWN
     * flush/commit (deferred {@code em.merge()}, because the {@code @Id} is a pre-assigned ULID),
     * which is outside any {@code try/catch} a call site could write around {@code save()}. The
     * business write already committed and must be untouched, and nothing may propagate into
     * {@code AbstractPlatformTransactionManager.processCommit} — which does NOT catch
     * synchronization callbacks, so an escape here would be a 500 handed to a caller whose money
     * already moved.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "a history write that fails at commit time cannot fail, or roll back, the business"
                    + " transaction it belongs to")
    void historyWriteFailureNeverTouchesTheBusinessTransaction() {
        String collabId = "01HAFTERCMTWRITEFAIL01";

        businessTransaction()
                .execute(
                        status -> {
                            collaborationRepository.save(
                                    Collaboration.apply(collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                            applicationHistoryService.record(
                                    CAMPAIGN_ID,
                                    collabId,
                                    collabId,
                                    ApplicationHistoryEventType.DELIVERABLE_APPROVED,
                                    CollaborationStatus.REVIEW_PENDING,
                                    ApplicationHistoryActorType.BRAND,
                                    "01HBRANDUSERAFTERCMT1",
                                    null, // NOT NULL(description) — fires at the writer's own flush
                                    null,
                                    null,
                                    null);
                            return null;
                        });

        assertTrue(
                collaborationRepository.findById(collabId).isPresent(),
                "the business write must survive a history-write failure");
        assertEquals(
                0L, historyRepository.count(), "sanity: the failing row really did not get written");
    }

    /**
     * The trap that ruled out {@code @TransactionalEventListener(AFTER_COMMIT)} as the mechanism.
     * {@code CreatorCampaignService#onApplicationHistoryRecorded} is itself an AFTER_COMMIT
     * listener and calls {@code record(...)} from inside it. Spring's {@code triggerAfterCommit}
     * iterates a SNAPSHOT of the registered synchronizations, so anything registered from within an
     * {@code afterCommit} callback never gets its own {@code afterCommit} — and the AFTER_COMMIT
     * flavour of {@code TransactionalApplicationListenerSynchronization} does nothing in {@code
     * afterCompletion}. Both of that call site's rows would be silently dropped. {@code
     * AfterCommit}'s synchronization implements both callbacks behind a latch, and {@code
     * triggerAfterCompletion} re-snapshots, so a late registration still runs exactly once.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "a history write requested from inside another afterCommit callback still runs, exactly"
                    + " once")
    void historyWriteRegisteredFromInsideAfterCommitStillRuns() {
        String collabId = "01HAFTERCMTNESTED00001";

        businessTransaction()
                .execute(
                        status -> {
                            collaborationRepository.save(
                                    Collaboration.apply(collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                            // Exactly what CreatorCampaignService's AFTER_COMMIT
                                            // listener does today.
                                            recordApproval(collabId);
                                        }
                                    });
                            return null;
                        });

        assertEquals(
                1L,
                historyRepository.count(),
                "a record() issued from inside an afterCommit callback must still be written exactly"
                        + " once — if this reads 0, the deferral silently drops every row"
                        + " CreatorCampaignService's AFTER_COMMIT listener records");
    }

    /**
     * The brand-side accept path records a first view and then the decision in the same
     * transaction ({@code DealService#doAccept}: {@code recordViewIfAbsent} then {@code record}).
     * Both are deferred; they must still land in order, and the view must still be first-write-wins
     * across separate transactions even though its existence check now runs after the commit.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "view-then-accept in one transaction writes both rows after commit; a second view is"
                    + " deduplicated")
    void viewThenAcceptWritesBothRowsAndTheViewStaysFirstWriteWins() {
        String collabId = "01HAFTERCMTVIEWACCEPT1";

        businessTransaction()
                .execute(
                        status -> {
                            collaborationRepository.save(
                                    Collaboration.apply(collabId, CAMPAIGN_ID, CREATOR_ID, null, "INR"));
                            applicationHistoryService.recordViewIfAbsent(
                                    CAMPAIGN_ID,
                                    collabId,
                                    "01HBRANDUSERAFTERCMT1",
                                    "Brand viewed the application",
                                    CollaborationStatus.APPLIED);
                            applicationHistoryService.record(
                                    CAMPAIGN_ID,
                                    collabId,
                                    null,
                                    ApplicationHistoryEventType.APPLICATION_ACCEPTED,
                                    CollaborationStatus.TERMS_AGREED,
                                    ApplicationHistoryActorType.BRAND,
                                    "01HBRANDUSERAFTERCMT1",
                                    "Brand accepted the proposal",
                                    null,
                                    null,
                                    collabId);
                            assertEquals(0L, historyRepository.count(), "nothing before the commit");
                            return null;
                        });

        businessTransaction()
                .execute(
                        status -> {
                            applicationHistoryService.recordViewIfAbsent(
                                    CAMPAIGN_ID,
                                    collabId,
                                    "01HBRANDUSERAFTERCMT1",
                                    "Brand viewed the application",
                                    CollaborationStatus.TERMS_AGREED);
                            return null;
                        });

        assertEquals(2L, historyRepository.count(), "one view and one accept, no duplicate view");
        assertEquals(
                1L,
                historyRepository.findAll().stream()
                        .filter(e -> e.getEventType() == ApplicationHistoryEventType.APPLICATION_VIEWED)
                        .count());
        assertEquals(
                1L,
                historyRepository.findAll().stream()
                        .filter(e -> e.getEventType() == ApplicationHistoryEventType.APPLICATION_ACCEPTED)
                        .count());
    }
}
