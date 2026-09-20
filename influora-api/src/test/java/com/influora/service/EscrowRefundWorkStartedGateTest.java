package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.escrow.EscrowBackend;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * EV-015 — a brand OWNER/ADMIN could refund a FUNDED hold to itself after the creator submitted
 * (and the brand approved) the work. Brand-initiated refunds are now allowed only before any
 * submission, or on a CANCELLED collaboration; afterwards the brand gets 409 {@code
 * REFUND_REQUIRES_DISPUTE}. Admin dispute settlement must keep working.
 */
@ExtendWith(MockitoExtension.class)
class EscrowRefundWorkStartedGateTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String MILESTONE_ID = "01HMILESTONE123456789";
    private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletService walletService;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private EscrowBackend escrowBackend;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;

    private EscrowService service;

    @BeforeEach
    void setUp() {
        service =
                new EscrowService(
                        escrowHoldRepository,
                        milestoneRepository,
                        campaignRepository,
                        collaborationRepository,
                        contractRepository,
                        disputeRepository,
                        walletService,
                        brandContext,
                        creatorContext,
                        campaignServiceInvoiceService,
                        deliverableRepository,
                        workspaceRepository,
                        eventPublisher,
                        collaborationLifecycleService,
                        escrowBackend,
                        applicationHistoryService);
    }

    // ---------------------------------------------------------------- refused after submission

    @ParameterizedTest(name = "deliverable {0} blocks the brand refund")
    @EnumSource(
            value = DeliverableStatus.class,
            names = {"PENDING", "DRAFT"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("EV-015: any deliverable at or past submission blocks a brand refund with REFUND_REQUIRES_DISPUTE")
    void submittedOrLaterDeliverableBlocksRefund(DeliverableStatus delivered) {
        EscrowHold hold = collaborationHold();
        stubRefundPath(hold, collaboration(CollaborationStatus.IN_PROGRESS));
        when(deliverableRepository.findByCollaborationIdForUpdate(COLLABORATION_ID))
                .thenReturn(List.of(deliverable("d1", DeliverableStatus.PENDING), deliverable("d2", delivered)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("REFUND_REQUIRES_DISPUTE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(EscrowStatus.FUNDED, hold.getStatus());
        verify(escrowBackend, never()).refund(any());
        verify(escrowHoldRepository, never()).save(any());
    }

    @ParameterizedTest(name = "collaboration {0} blocks the brand refund")
    @EnumSource(
            value = CollaborationStatus.class,
            names = {"REVIEW_PENDING", "REVISION_REQUESTED", "COMPLETED"})
    @DisplayName("EV-015: a collaboration past the pre-work state blocks a brand refund even with no deliverable rows")
    void postWorkCollaborationStatusBlocksRefund(CollaborationStatus status) {
        EscrowHold hold = collaborationHold();
        stubRefundPath(hold, collaboration(status));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("REFUND_REQUIRES_DISPUTE", ex.getCode());
        verify(escrowBackend, never()).refund(any());
    }

    @Test
    @DisplayName(
            "EV-015 exact scenario: creator submitted, brand approved, hold still FUNDED (no auto-release)"
                    + " -> brand refund refused, including through the Idempotency-Key overload")
    void approvedWorkCannotBeRefundedByBrand() {
        EscrowHold hold = collaborationHold();
        stubRefundPath(hold, collaboration(CollaborationStatus.COMPLETED));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID, "idem-refund-1"));

        assertEquals("REFUND_REQUIRES_DISPUTE", ex.getCode());
        assertEquals(EscrowStatus.FUNDED, hold.getStatus());
        verify(escrowBackend, never()).refund(any());
    }

    @Test
    @DisplayName(
            "EV-015: a milestone-backed hold with no collaboration_id resolves the collaboration through its"
                    + " milestone and is gated the same way")
    void milestoneOnlyHoldIsGatedViaMilestone() {
        EscrowHold hold = hold(null, MILESTONE_ID);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone()));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration(CollaborationStatus.IN_PROGRESS)));
        when(deliverableRepository.findByCollaborationIdForUpdate(COLLABORATION_ID))
                .thenReturn(List.of(deliverable("d1", DeliverableStatus.SUBMITTED)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("REFUND_REQUIRES_DISPUTE", ex.getCode());
        verify(escrowBackend, never()).refund(any());
    }

    @Test
    @DisplayName(
            "EV-015 pool rule: a campaign-level pool hold bound to a collaboration (Meera launch) is refused"
                    + " once that collaboration has submitted work")
    void boundPoolHoldIsGatedByItsCollaboration() {
        EscrowHold hold = hold(COLLABORATION_ID, null);
        stubRefundPath(hold, collaboration(CollaborationStatus.IN_PROGRESS));
        when(deliverableRepository.findByCollaborationIdForUpdate(COLLABORATION_ID))
                .thenReturn(List.of(deliverable("d1", DeliverableStatus.APPROVED)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("REFUND_REQUIRES_DISPUTE", ex.getCode());
        verify(escrowBackend, never()).refund(any());
    }

    @Test
    @DisplayName("EV-015: a hold naming a collaboration that cannot be loaded is refused, not refunded blind")
    void missingCollaborationFailsClosed() {
        EscrowHold hold = collaborationHold();
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        verify(escrowBackend, never()).refund(any());
    }

    // ---------------------------------------------------------------- still allowed

    @Test
    @DisplayName(
            "EV-015: pre-submission (IN_PROGRESS, deliverables PENDING/DRAFT) the brand refund still works,"
                    + " with locks taken hold -> collaboration -> deliverables before any money moves")
    void preSubmissionRefundSucceedsUnderLocks() {
        EscrowHold hold = collaborationHold();
        stubRefundPath(hold, collaboration(CollaborationStatus.IN_PROGRESS));
        when(deliverableRepository.findByCollaborationIdForUpdate(COLLABORATION_ID))
                .thenReturn(List.of(deliverable("d1", DeliverableStatus.PENDING), deliverable("d2", DeliverableStatus.DRAFT)));
        when(escrowBackend.refund(any())).thenReturn(new EscrowBackend.RefundOutcome("refund-txn-1"));

        EscrowStatusResponse response = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

        assertEquals(EscrowStatus.REFUNDED, response.status());
        verify(escrowBackend, times(1)).refund(any());
        InOrder order = inOrder(escrowHoldRepository, collaborationRepository, deliverableRepository, escrowBackend);
        order.verify(escrowHoldRepository).findByIdForUpdate(ESCROW_HOLD_ID);
        order.verify(collaborationRepository).findByIdForUpdate(COLLABORATION_ID);
        order.verify(deliverableRepository).findByCollaborationIdForUpdate(COLLABORATION_ID);
        order.verify(escrowBackend).refund(any());
        // The state the gate decides on must come from the locking reads only.
        verify(collaborationRepository, never()).findById(any());
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(any());
    }

    @Test
    @DisplayName(
            "EV-015: a CANCELLED collaboration stays refundable (the refund remedy) and its deliverables are"
                    + " not consulted")
    void cancelledCollaborationStaysRefundable() {
        EscrowHold hold = collaborationHold();
        stubRefundPath(hold, collaboration(CollaborationStatus.CANCELLED));
        when(escrowBackend.refund(any())).thenReturn(new EscrowBackend.RefundOutcome("refund-txn-1"));

        EscrowStatusResponse response = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

        assertEquals(EscrowStatus.REFUNDED, response.status());
        verifyNoInteractions(deliverableRepository);
    }

    @Test
    @DisplayName(
            "EV-015 pool rule: an unbound campaign-level pool hold funds no collaboration, so it stays"
                    + " refundable (refusing it would strand the money)")
    void unboundPoolHoldStaysRefundable() {
        EscrowHold hold = hold(null, null);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(escrowBackend.refund(any())).thenReturn(new EscrowBackend.RefundOutcome("refund-txn-1"));

        EscrowStatusResponse response = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

        assertEquals(EscrowStatus.REFUNDED, response.status());
        verifyNoInteractions(collaborationRepository, deliverableRepository);
    }

    @Test
    @DisplayName(
            "EV-015: admin dispute settlement still refunds the brand on a collaboration whose work was"
                    + " approved -- the brand-refund gate does not apply to it")
    void adminDisputeRefundUnaffected() {
        EscrowHold frozen = collaborationHoldWithoutMilestone();
        frozen.markFrozen();
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration(CollaborationStatus.DISPUTED)));
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLABORATION_ID, EscrowStatus.FROZEN))
                .thenReturn(List.of(frozen));
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(frozen));
        when(escrowBackend.refund(any())).thenReturn(new EscrowBackend.RefundOutcome("dispute-refund-txn"));

        List<EscrowStatusResponse> settled = service.adminRefundForDispute(COLLABORATION_ID);

        assertEquals(1, settled.size());
        assertEquals(EscrowStatus.REFUNDED, settled.get(0).status());
        verifyNoInteractions(deliverableRepository);
    }

    // ---------------------------------------------------------------- fixtures

    private void stubRefundPath(EscrowHold hold, Collaboration collaboration) {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    }

    private static Collaboration collaboration(CollaborationStatus status) {
        Collaboration collaboration =
                Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        collaboration.transitionTo(status);
        return collaboration;
    }

    private static Deliverable deliverable(String id, DeliverableStatus status) {
        return Deliverable.builder()
                .id(id)
                .collaborationId(COLLABORATION_ID)
                .slotIndex(0)
                .title("Reel")
                .status(status)
                .build();
    }

    private static PaymentMilestone milestone() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .contractId("01HCONTRACT123456789")
                        .collaborationId(COLLABORATION_ID)
                        .sequenceNo(1)
                        .amount(new BigDecimal("10000.00"))
                        .status(MilestoneStatus.PENDING)
                        .build();
        milestone.markFunded(ESCROW_HOLD_ID);
        return milestone;
    }

    private static EscrowHold collaborationHold() {
        return hold(COLLABORATION_ID, MILESTONE_ID);
    }

    private static EscrowHold collaborationHoldWithoutMilestone() {
        return hold(COLLABORATION_ID, null);
    }

    private static EscrowHold hold(String collaborationId, String milestoneId) {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .collaborationId(collaborationId)
                .campaignId(CAMPAIGN_ID)
                .milestoneId(milestoneId)
                .amount(new BigDecimal("10000.00"))
                .currency("INR")
                .status(EscrowStatus.FUNDED)
                .idempotencyKey("fund-idem")
                .build();
    }
}
