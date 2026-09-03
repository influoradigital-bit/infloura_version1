package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviewResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0580 — REGRESSION TEST ONLY, deliberately RED. {@code Deliverable} carries no JPA
 * {@code @Version} and {@link BrandDeliverableService#approve} resolves it through a plain {@code
 * findByIdAndWorkspaceId} — no pessimistic lock (see {@code
 * BrandDeliverableService#requireBrandDeliverable}). Two brand members reviewing the same
 * SUBMITTED deliverable in parallel both materialize their own {@code Deliverable} entity
 * instance from that same SUBMITTED row; whichever approves first mutates and saves only ITS OWN
 * instance, so the second member's already-in-hand instance is a stale read that is still
 * SUBMITTED. {@link BrandDeliverableService#canReview} only inspects the status on the instance
 * in hand, so the second {@code approve()} call passes the guard too and — because approval
 * attempts an escrow release (B3) — fires a SECOND {@code tryReleaseOnApproval} for a deliverable
 * that was already approved and paid out once.
 *
 * <p>Simulates the two concurrent reads deterministically (two separate {@code Deliverable}
 * instances built from the identical SUBMITTED row, handed out on successive {@code
 * findByIdAndWorkspaceId} calls) rather than real threads: the race is in what each transaction
 * reads, not in scheduling, and {@link BrandDeliverableServiceTest} already establishes this
 * mock-per-call style for this service.
 */
@ExtendWith(MockitoExtension.class)
class BrandDeliverableConcurrentApprovalTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";
    private static final String MILESTONE_ID = "01HMILESTONE1234567AB";

    @Mock private BrandContextService brandContext;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private AuthPrincipal principal;
    @Mock private EscrowService escrowService;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private com.influora.service.meera.MeeraInteractionLogService meeraInteractionLogService;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private BrandDeliverableService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service =
                new BrandDeliverableService(
                        brandContext,
                        deliverableRepository,
                        r2StorageService,
                        r2Properties,
                        escrowService,
                        collaborationLifecycleService,
                        meeraInteractionLogService,
                        collaborationRepository,
                        applicationHistoryService);
        workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Brand", "acme", "Fashion", "SMB");
    }

    /** Matches BrandDeliverableServiceTest's activeCollaboration() fixture exactly. */
    private static Collaboration activeCollaboration() {
        return Collaboration.invite(COLLAB_ID, "01HCAMPAIGN0000000001", "01HCREATOR00000000001", null, "INR");
    }

    private static Deliverable submittedDeliverableWithMilestone() {
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
        return deliverable;
    }

    @Test
    @DisplayName(
            "F-0580: second approve() against a stale SUBMITTED read must be rejected and must"
                    + " NOT trigger a second escrow release")
    void secondApprovalOnStaleReadIsRejected_noDoubleRelease() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(collaborationRepository.findById(COLLAB_ID))
                .thenReturn(Optional.of(activeCollaboration()));

        // Two brand members' concurrent findByIdAndWorkspaceId reads of the SAME SUBMITTED row,
        // materialized as two independent entity instances -- exactly what no-lock + no-@Version
        // produces. `firstReaderInstance` is the one whose approve() call commits first.
        // `secondReaderInstance` is the stale read: built from the identical SUBMITTED row, but
        // never touched by the first approval, so it is still SUBMITTED when the second member's
        // approve() call resolves it.
        Deliverable firstReaderInstance = submittedDeliverableWithMilestone();
        Deliverable secondReaderInstance = submittedDeliverableWithMilestone();
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(firstReaderInstance))
                .thenReturn(Optional.of(secondReaderInstance));
        when(escrowService.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID))
                .thenReturn(EscrowService.ReleaseOutcome.RELEASED);

        // Member A approves first -- succeeds, escrow releases once.
        ReviewResponse first = service.approve(principal, DELIVERABLE_ID);
        assertEquals(DeliverableStatus.APPROVED, first.status());

        // Member B approves the SAME deliverable off their stale SUBMITTED read. Correct
        // behavior: rejected as already-reviewed, no second release attempted. This is the
        // assertion that is expected to FAIL against today's code, because
        // requireBrandDeliverable takes no lock and Deliverable has no @Version: B's instance
        // still reads SUBMITTED, canReview() passes a second time, and approve() returns
        // normally instead of throwing.
        ApiException secondAttempt =
                assertThrows(ApiException.class, () -> service.approve(principal, DELIVERABLE_ID));
        assertEquals("INVALID_STATE", secondAttempt.getCode());

        // Money-path assertion, independent of the outcome above: a single deliverable approval
        // must never attempt more than one escrow release.
        verify(escrowService, times(1)).tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID);
    }
}
