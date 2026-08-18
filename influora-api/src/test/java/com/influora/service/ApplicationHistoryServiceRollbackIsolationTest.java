package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.ApplicationHistoryEventRepository;
import com.influora.repository.CollaborationRepository;
import java.util.Optional;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-off review defect #1 (BLOCKING) — real-transaction-manager proof that {@link
 * ApplicationHistoryService#record} cannot roll back the caller's business write, even when
 * {@code record}'s own failure surfaces only at flush/commit time (not at the {@code save()} call
 * itself).
 *
 * <p><b>Why a mocked {@code ApplicationHistoryService} cannot catch this class of bug.</b> Every
 * other test in this codebase that exercises a call site's try/catch (e.g. {@code
 * CreatorCampaignServiceTest#testApplySurvivesApplicationHistoryFailure}) mocks {@code
 * ApplicationHistoryService} and has it throw synchronously from {@code record(...)}. That proves
 * the catch block exists and runs — it proves NOTHING about whether {@code record}'s real {@code
 * @Transactional} annotation lets the failure escape the ambient transaction unmarked. A mock has
 * no {@code TransactionInterceptor}, no {@code globalRollbackOnParticipationFailure}, and no
 * deferred {@code em.merge()} flush — the exact three things this bug lived in. Only a real
 * {@link org.springframework.transaction.PlatformTransactionManager}, wrapping a REAL {@code
 * ApplicationHistoryService} Spring bean (so its {@code @Transactional} annotation is actually
 * intercepted by AOP), can exercise this.
 *
 * <p><b>How the failure is forced.</b> {@code description} is mapped {@code @Column(nullable =
 * false)} but carries no Bean Validation annotation, so passing {@code null} is NOT rejected in
 * Java when {@link ApplicationHistoryService#record} builds and merges the entity — the {@code
 * NOT NULL} violation is a database-level constraint, and because {@code
 * ApplicationHistoryEvent}'s {@code @Id} is pre-assigned (a ULID, never {@code
 * GenerationType.IDENTITY}), Spring Data routes the save through {@code em.merge()}, which
 * defers the actual {@code INSERT} to flush. This reproduces, honestly, the exact "deferred flush
 * surfaces outside the try/catch" hazard flagged in review — not a synchronous mock throw.
 *
 * <p><b>What this test proves about the FIX ({@code REQUIRES_NEW}), and what it would have shown
 * before it.</b> With {@code record} annotated {@code REQUIRES_NEW}, its commit — flush included —
 * happens INSIDE the {@code record(...)} method call, from the ambient transaction's point of
 * view; the {@code DataIntegrityViolationException} therefore surfaces synchronously to this
 * test's try/catch (the same shape every production call site already uses), the OUTER
 * transaction (driven here by {@link TransactionTemplate}, standing in for whatever {@code
 * @Transactional} business method actually calls {@code record}) was never touched by the inner
 * failure, and the {@link Collaboration} row committed by that outer transaction survives.
 *
 * <p><b>Verified manually, both directions, during this fix — the real failure mode observed
 * with the propagation reverted to plain {@code @Transactional} (REQUIRED) was NOT an {@code
 * UnexpectedRollbackException} on a later commit attempt.</b> With REQUIRED, {@code record}
 * merely participates — it never owns a commit of its own — so {@code em.merge()}'s deferred
 * {@code INSERT} rides along inside the ONE physical commit, which is {@link TransactionTemplate}
 * calling {@code AbstractPlatformTransactionManager.processCommit}. That single commit is where
 * Hibernate's pre-commit flush runs, and it is there that the {@code NOT NULL(description)}
 * violation actually fires — so the resulting {@code
 * org.springframework.dao.DataIntegrityViolationException} propagates directly out of {@code
 * TransactionTemplate.execute(...)} itself, past this test's own try/catch (which only wraps the
 * inner {@code record(...)} call, exactly like every production call site), and the test fails
 * with an uncaught exception rather than a false-negative silent pass. This is the literal,
 * observed shape of "a constraint failure surfaces OUTSIDE your try/catch entirely" — proof, not
 * a restated claim. With the {@code REQUIRES_NEW} fix restored, the same scenario instead throws
 * {@code DataIntegrityViolationException} synchronously FROM the {@code record(...)} call (caught
 * by this test, and by every production try/catch), and the {@link Collaboration} row survives —
 * this is the passing state asserted below.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {Collaboration.class, com.influora.domain.entity.ApplicationHistoryEvent.class})
@EnableJpaRepositories(
        basePackageClasses = {CollaborationRepository.class, ApplicationHistoryEventRepository.class},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CollaborationRepository$|ApplicationHistoryEventRepository$).*"))
@Import(ApplicationHistoryService.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:application_history_rollback_isolation_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ApplicationHistoryServiceRollbackIsolationTest {

    private static final String COLLAB_ID = "01HROLLBACKISOTEST0001";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNROLLBACK001";
    private static final String CREATOR_ID = "01HCREATORROLLBACK0001";

    /** REAL Spring-proxied bean (via {@code @Import}) — a manually-{@code new}'d instance would
     * bypass AOP entirely and prove nothing about the {@code @Transactional} annotation. */
    @Autowired private ApplicationHistoryService applicationHistoryService;

    @Autowired private CollaborationRepository collaborationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // @DataJpaTest wraps every test method in its OWN ambient transaction, auto-rolled-back at
    // teardown regardless of outcome — with that active, TransactionTemplate.execute() below
    // would just PARTICIPATE in that already-open transaction (REQUIRED joins) instead of opening
    // and genuinely COMMITTING its own, so nothing would ever really commit mid-test and this
    // test would pass unconditionally regardless of whether the fix is present (verified: it did,
    // before this annotation was added). NOT_SUPPORTED suspends that ambient wrapping for this
    // method so TransactionTemplate's transaction is the ONLY real transaction boundary in play,
    // and its commit is a genuine commit this assertion can trust.
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "record()'s own failure — surfacing only at flush/commit, not at the save() call — must"
                    + " never roll back the caller's already-succeeded business write")
    void historyWriteFailureAtCommitTimeNeverRollsBackCallerTransaction() {
        TransactionTemplate outerBusinessTransaction = new TransactionTemplate(transactionManager);

        outerBusinessTransaction.execute(
                status -> {
                    // The business write — stands in for CreatorCampaignService.apply()'s
                    // collaborationRepository.save(collaboration), or any of the eight other
                    // production call sites' own business write.
                    Collaboration collaboration =
                            Collaboration.apply(COLLAB_ID, CAMPAIGN_ID, CREATOR_ID, null, "INR");
                    collaborationRepository.save(collaboration);

                    // The history write — deliberately forced to fail at flush/commit time (see
                    // class javadoc for why description=null reproduces the deferred-flush hazard
                    // honestly rather than a synchronous mock throw). Wrapped in the EXACT try/catch
                    // shape every production call site already uses.
                    try {
                        applicationHistoryService.record(
                                CAMPAIGN_ID,
                                COLLAB_ID,
                                null,
                                ApplicationHistoryEventType.CAMPAIGN_APPLIED,
                                CollaborationStatus.APPLIED,
                                ApplicationHistoryActorType.CREATOR,
                                CREATOR_ID,
                                null, // forces the NOT NULL(description) violation at flush/commit
                                null,
                                null,
                                null);
                    } catch (RuntimeException e) {
                        // Best-effort — the business write above already succeeded and must survive.
                    }
                    return null;
                });

        assertTrue(
                collaborationRepository.findById(COLLAB_ID).isPresent(),
                "the business write must survive a history-recording failure, even one that only"
                        + " surfaces at flush/commit time inside record()'s own REQUIRES_NEW"
                        + " transaction — if this fails, record() is participating in the caller's"
                        + " transaction again instead of running in its own");
    }
}
