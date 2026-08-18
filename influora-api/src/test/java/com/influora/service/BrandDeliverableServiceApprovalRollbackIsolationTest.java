package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.ApplicationHistoryEventRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.meera.MeeraInteractionLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Sign-off review defect (BLOCKING, follow-on to {@code ApplicationHistoryServiceRollbackIsolationTest})
 * — real-transaction-manager proof that {@link BrandDeliverableService#approve}'s reorder actually
 * closes the inverse hazard: a history row committing (via {@link ApplicationHistoryService
 * #record}'s {@code REQUIRES_NEW}) for a business fact that the SAME {@code approve()} call then
 * rolls back.
 *
 * <p><b>Why a mocked {@code ApplicationHistoryService} cannot catch this class of bug either.</b>
 * Same reasoning as the sibling test: a mock has no real commit boundary, so it cannot show
 * whether a row genuinely persisted past the surrounding transaction's rollback. This test uses a
 * REAL, AOP-proxied {@link ApplicationHistoryService} bean (via {@code @Import}) and a REAL {@link
 * BrandDeliverableService} bean driving the actual {@code approve()} method — not a hand-rolled
 * stand-in for its logic.
 *
 * <p><b>What is real vs. mocked here.</b> Real: {@link BrandDeliverableService} (the method under
 * test), {@link ApplicationHistoryService} (must be real for its {@code REQUIRES_NEW} to matter),
 * {@link CollaborationLifecycleService} (must ALSO be real — see below), and the JPA
 * repositories/entities each of them actually persists through ({@link Campaign}, {@link
 * Collaboration}, {@link Deliverable}, {@code ApplicationHistoryEvent}). Mocked: {@link
 * BrandContextService} (workspace resolution — no auth chain needed for this test), {@link
 * EscrowService} (deliberately made to throw a non-whitelisted {@link ApiException}, exactly the
 * "unexpected wallet failure" shape {@code testApproveRollsBackWhenEscrowReleaseFailsUnexpectedly}
 * already covers at the mock level — this test proves the REAL commit consequence of that same
 * scenario), {@link R2StorageService}, {@link MeeraInteractionLogService}, and {@link R2Properties}
 * — none of these are reached before the escrow throw in the fixed code, and their real
 * implementations pull in dependency graphs unrelated to what this test is about.
 *
 * <p><b>Why {@link CollaborationLifecycleService} must be real, not mocked (the actual defect this
 * test exists to catch).</b> {@code approve()} writes history from two call sites: the
 * {@code DELIVERABLE_APPROVED} row it writes directly, AND the {@code DELIVER} row that {@code
 * onDeliverableReviewed} -> {@code recomputeReviewState} writes (via the SAME {@link
 * ApplicationHistoryService#record}) whenever this approval is the one that resolves the last
 * outstanding deliverable. The sign-off defect was specifically in the ORDER of {@code
 * onDeliverableReviewed} relative to the escrow attempt — it used to run BEFORE {@code
 * tryReleaseOnApproval}, so a rejected release still let the {@code DELIVER} row commit via {@code
 * REQUIRES_NEW} before the rethrow rolled the rest of the approval back. A mocked {@code
 * CollaborationLifecycleService} never touches {@link ApplicationHistoryService} at all, so it
 * cannot observe that ordering bug regardless of which order {@code approve()} calls things in —
 * this test is set up (single deliverable, collaboration already {@code REVIEW_PENDING}) so this
 * approval is exactly the one that would flip the collaboration to {@code COMPLETED} and fire
 * {@code DELIVER}, if {@code onDeliverableReviewed} were ever reached.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(
        basePackageClasses = {
            Campaign.class,
            Collaboration.class,
            Deliverable.class,
            com.influora.domain.entity.ApplicationHistoryEvent.class
        })
@EnableJpaRepositories(
        basePackageClasses = {
            CampaignRepository.class,
            CollaborationRepository.class,
            DeliverableRepository.class,
            ApplicationHistoryEventRepository.class
        },
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CampaignRepository$|CollaborationRepository$|DeliverableRepository$|ApplicationHistoryEventRepository$).*"))
@Import({
    BrandDeliverableService.class,
    ApplicationHistoryService.class,
    CollaborationLifecycleService.class
})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:brand_deliverable_approve_rollback_isolation_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class BrandDeliverableServiceApprovalRollbackIsolationTest {

    private static final String WORKSPACE_ID = "01HWORKSPACEROLLBACK01";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNROLLBACK001";
    private static final String COLLAB_ID = "01HCOLLABROLLBACK00001";
    private static final String CREATOR_ID = "01HCREATORROLLBACK0001";
    private static final String DELIVERABLE_ID = "01HDELIVERABLEROLLBACK1";
    private static final String MILESTONE_ID = "01HMILESTONEROLLBACK001";

    /** REAL Spring-proxied bean (via {@code @Import}) — the actual method under test. */
    @Autowired private BrandDeliverableService brandDeliverableService;

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CollaborationRepository collaborationRepository;
    @Autowired private DeliverableRepository deliverableRepository;
    @Autowired private ApplicationHistoryEventRepository applicationHistoryEventRepository;

    @MockBean private BrandContextService brandContext;
    @MockBean private EscrowService escrowService;
    @MockBean private R2StorageService r2StorageService;
    @MockBean private R2Properties r2Properties;
    @MockBean private MeeraInteractionLogService meeraInteractionLogService;

    // @DataJpaTest wraps every test method in its OWN ambient transaction, auto-rolled-back only
    // at teardown — with that active, approve()'s own @Transactional (REQUIRED) would just
    // PARTICIPATE in that already-open transaction instead of being the genuine top-level
    // transaction that rolls back SYNCHRONOUSLY when the ApiException propagates. Without this,
    // the assertions below would observe the still-open Hibernate session's in-memory state
    // (Deliverable already flipped to APPROVED) rather than a real, completed rollback — a false
    // negative (verified: the Deliverable-status assertion below failed with exactly that
    // "expected SUBMITTED but was APPROVED" symptom before this annotation was added, the same
    // confound ApplicationHistoryServiceRollbackIsolationTest hit and fixed the same way).
    // NOT_SUPPORTED suspends the ambient wrapping so approve()'s own transaction is the only real
    // one in play, and its rollback is one this test can trust.
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "approve(): when tryReleaseOnApproval throws, NO ApplicationHistoryEvent row for this"
                    + " collaboration survives — the whole approval, history included, genuinely"
                    + " rolled back")
    void approveFailureLeavesNoApplicationHistoryRows() {
        Campaign campaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Rollback Isolation Campaign")
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .createdBy("brand_user_1")
                        .build();
        campaignRepository.save(campaign);

        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_ID, null, "INR");
        // REVIEW_PENDING + a single deliverable that this approval will resolve is exactly the
        // shape that flips the collaboration to COMPLETED and fires the real DELIVER history
        // write inside onDeliverableReviewed -> recomputeReviewState (see class javadoc on why
        // CollaborationLifecycleService must be real for this test to mean anything).
        collaboration.transitionTo(CollaborationStatus.REVIEW_PENDING);
        collaborationRepository.save(collaboration);

        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .milestoneId(MILESTONE_ID)
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Caption",
                null,
                null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        deliverableRepository.save(deliverable);

        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        AuthPrincipal principal = mock(AuthPrincipal.class);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        // The exact "unexpected wallet failure" shape testApproveRollsBackWhenEscrowReleaseFailsUnexpectedly
        // already covers at the mock level — this test proves what REALLY happens to the two
        // history writes when this exact scenario runs against a real commit boundary.
        when(escrowService.tryReleaseOnApproval(anyString(), anyString()))
                .thenThrow(
                        new ApiException(
                                "WALLET_NOT_FOUND", "Unexpected wallet failure", HttpStatus.INTERNAL_SERVER_ERROR));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> brandDeliverableService.approve(principal, DELIVERABLE_ID));
        assertEquals("WALLET_NOT_FOUND", ex.getCode());

        // The business write genuinely rolled back — the Deliverable is still SUBMITTED, not
        // APPROVED. If this assertion ever fails, the transaction isn't rolling back at all and
        // the history assertion below would be meaningless.
        Deliverable afterRollback = deliverableRepository.findById(DELIVERABLE_ID).orElseThrow();
        assertEquals(
                DeliverableStatus.SUBMITTED,
                afterRollback.getStatus(),
                "the Deliverable status must have rolled back with the rest of the transaction");

        // The actual defect this test exists to catch: before the fix, DELIVERABLE_APPROVED (and,
        // when reachable, DELIVER) committed via record()'s REQUIRES_NEW regardless of this
        // rollback, permanently asserting an approval that never happened.
        assertTrue(
                applicationHistoryEventRepository
                        .findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(COLLAB_ID)
                        .isEmpty(),
                "no ApplicationHistoryEvent row may exist for a collaboration whose approval rolled"
                        + " back — an append-only ledger asserting an undone fact is worse than a"
                        + " missing row");
    }
}
