package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** W2-1 — CollaborationLifecycleService's guard logic and review-phase derivation. */
@ExtendWith(MockitoExtension.class)
class CollaborationLifecycleServiceTest {

    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DeliverableRepository deliverableRepository;

    private CollaborationLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new CollaborationLifecycleService(collaborationRepository, deliverableRepository);
    }

    private static Collaboration collaborationWithStatus(CollaborationStatus status) {
        Collaboration c = Collaboration.propose(COLLAB_ID, "campaign1", "creator1", null, "INR", null);
        c.transitionTo(status);
        return c;
    }

    private static Deliverable deliverableWithStatus(DeliverableStatus status) {
        return Deliverable.builder()
                .id("del-" + status)
                .collaborationId(COLLAB_ID)
                .creatorProfileId("profile1")
                .type(DeliverableType.INSTAGRAM_REEL)
                .status(status)
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // onContractGenerated -> CONTRACT_PENDING
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("onContractGenerated: TERMS_AGREED -> CONTRACT_PENDING")
    void onContractGeneratedAdvances() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.TERMS_AGREED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onContractGenerated(COLLAB_ID);

        assertEquals(CollaborationStatus.CONTRACT_PENDING, c.getStatus());
        verify(collaborationRepository).save(c);
    }

    @Test
    @DisplayName("onContractGenerated: idempotent no-op if already CONTRACT_PENDING (contract regenerated)")
    void onContractGeneratedIdempotent() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.CONTRACT_PENDING);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onContractGenerated(COLLAB_ID);

        assertEquals(CollaborationStatus.CONTRACT_PENDING, c.getStatus());
        verify(collaborationRepository, never()).save(any());
    }

    @Test
    @DisplayName("onContractGenerated: never overrides a CANCELLED collaboration")
    void onContractGeneratedNeverOverridesCancelled() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onContractGenerated(COLLAB_ID);

        assertEquals(CollaborationStatus.CANCELLED, c.getStatus());
        verify(collaborationRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // onContractFullySigned -> CONTRACTED
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("onContractFullySigned: CONTRACT_PENDING -> CONTRACTED")
    void onContractFullySignedAdvances() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.CONTRACT_PENDING);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onContractFullySigned(COLLAB_ID);

        assertEquals(CollaborationStatus.CONTRACTED, c.getStatus());
        verify(collaborationRepository).save(c);
    }

    @Test
    @DisplayName("onContractFullySigned: refuses out-of-order advance from TERMS_AGREED (no CONTRACT_PENDING yet)")
    void onContractFullySignedRefusesOutOfOrder() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.TERMS_AGREED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onContractFullySigned(COLLAB_ID);

        assertEquals(CollaborationStatus.TERMS_AGREED, c.getStatus());
        verify(collaborationRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // onEscrowFunded -> IN_PROGRESS
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("onEscrowFunded: CONTRACTED -> IN_PROGRESS")
    void onEscrowFundedAdvances() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.CONTRACTED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onEscrowFunded(COLLAB_ID);

        assertEquals(CollaborationStatus.IN_PROGRESS, c.getStatus());
        verify(collaborationRepository).save(c);
    }

    @Test
    @DisplayName("onEscrowFunded: idempotent no-op on a second milestone funding for the same collaboration")
    void onEscrowFundedIdempotentAcrossMultipleMilestones() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.IN_PROGRESS);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onEscrowFunded(COLLAB_ID);

        assertEquals(CollaborationStatus.IN_PROGRESS, c.getStatus());
        verify(collaborationRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // computeReviewStatus — pure function, exercised directly
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("computeReviewStatus: any SUBMITTED/RESUBMITTED deliverable outranks everything -> REVIEW_PENDING")
    void computeReviewStatusReviewPendingWins() {
        List<Deliverable> deliverables =
                List.of(
                        deliverableWithStatus(DeliverableStatus.APPROVED),
                        deliverableWithStatus(DeliverableStatus.REVISION_REQUESTED),
                        deliverableWithStatus(DeliverableStatus.SUBMITTED));

        assertEquals(
                CollaborationStatus.REVIEW_PENDING,
                CollaborationLifecycleService.computeReviewStatus(deliverables));
    }

    @Test
    @DisplayName("computeReviewStatus: no pending review but one REVISION_REQUESTED -> REVISION_REQUESTED")
    void computeReviewStatusRevisionRequested() {
        List<Deliverable> deliverables =
                List.of(
                        deliverableWithStatus(DeliverableStatus.APPROVED),
                        deliverableWithStatus(DeliverableStatus.REVISION_REQUESTED));

        assertEquals(
                CollaborationStatus.REVISION_REQUESTED,
                CollaborationLifecycleService.computeReviewStatus(deliverables));
    }

    @Test
    @DisplayName("computeReviewStatus: every deliverable resolved (approved + rejected mix) -> COMPLETED")
    void computeReviewStatusCompleted() {
        List<Deliverable> deliverables =
                List.of(
                        deliverableWithStatus(DeliverableStatus.APPROVED),
                        deliverableWithStatus(DeliverableStatus.REJECTED),
                        deliverableWithStatus(DeliverableStatus.POSTED));

        assertEquals(
                CollaborationStatus.COMPLETED, CollaborationLifecycleService.computeReviewStatus(deliverables));
    }

    @Test
    @DisplayName("computeReviewStatus: creator still has unstarted work (DRAFT/PENDING) -> IN_PROGRESS")
    void computeReviewStatusInProgress() {
        List<Deliverable> deliverables =
                List.of(
                        deliverableWithStatus(DeliverableStatus.APPROVED),
                        deliverableWithStatus(DeliverableStatus.DRAFT));

        assertEquals(
                CollaborationStatus.IN_PROGRESS, CollaborationLifecycleService.computeReviewStatus(deliverables));
    }

    // ---------------------------------------------------------------------------------------
    // onDeliverableSubmitted / onDeliverableReviewed — repository round-trip
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("onDeliverableSubmitted: IN_PROGRESS collaboration with a SUBMITTED deliverable -> REVIEW_PENDING")
    void onDeliverableSubmittedMovesToReviewPending() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.IN_PROGRESS);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLAB_ID))
                .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.SUBMITTED)));

        service.onDeliverableSubmitted(COLLAB_ID);

        assertEquals(CollaborationStatus.REVIEW_PENDING, c.getStatus());
        verify(collaborationRepository).save(c);
    }

    @Test
    @DisplayName("onDeliverableReviewed: last deliverable approved -> COMPLETED, unblocking reviews (W2-3)")
    void onDeliverableReviewedAllApprovedCompletesCollaboration() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.REVIEW_PENDING);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLAB_ID))
                .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.APPROVED)));

        service.onDeliverableReviewed(COLLAB_ID);

        assertEquals(CollaborationStatus.COMPLETED, c.getStatus());
        verify(collaborationRepository).save(c);
    }

    @Test
    @DisplayName("onDeliverableReviewed: no lifecycle nudge before a contract exists (still IN_NEGOTIATION)")
    void onDeliverableReviewedSkippedBeforeContractPhase() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.IN_NEGOTIATION);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onDeliverableReviewed(COLLAB_ID);

        assertEquals(CollaborationStatus.IN_NEGOTIATION, c.getStatus());
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(any());
        verify(collaborationRepository, never()).save(any());
    }

    @Test
    @DisplayName("onDeliverableReviewed: never overrides a DISPUTED collaboration")
    void onDeliverableReviewedNeverOverridesDisputed() {
        Collaboration c = collaborationWithStatus(CollaborationStatus.DISPUTED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(c));

        service.onDeliverableReviewed(COLLAB_ID);

        assertEquals(CollaborationStatus.DISPUTED, c.getStatus());
        verify(collaborationRepository, never()).save(any());
    }

    @Test
    @DisplayName("unknown collaboration id: no exception, no save")
    void unknownCollaborationIsANoOp() {
        when(collaborationRepository.findById("missing")).thenReturn(Optional.empty());

        service.onEscrowFunded("missing");

        verify(collaborationRepository, never()).save(any());
    }
}
