package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.escrow.EscrowBackend;
import com.influora.service.escrow.LedgerEscrowBackend;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * H-T34-1 — dispute-open vs escrow release/refund race closure. Proves freeze-before-save
 * ordering and money-path dispute guards under simulated concurrent callers.
 */
@ExtendWith(MockitoExtension.class)
class DisputeEscrowConcurrencyTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
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
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private PlatformFeeService platformFeeService;
    @Mock private WalletService walletService;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private EscrowService escrowService;

    @BeforeEach
    void setUp() {
        EscrowBackend escrowBackend =
                new LedgerEscrowBackend(ledgerService, platformWalletService, platformFeeService, walletService);
        escrowService =
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

    @Test
    @DisplayName("H-T34-1: release rejects when hold already FROZEN by concurrent dispute open")
    void releaseRejectedWhenHoldFrozen() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(
                        COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        EscrowHold hold = fundedHold();
        hold.markFrozen();

        // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("INVALID_ESCROW_STATE", ex.getCode());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("H-T34-1: release rejects when active dispute exists on collaboration")
    void releaseRejectedWhenActiveDispute() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(
                        COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

        // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(true);
        // [CR-47] ownership/hold-load now precedes the DISPUTED guard in releaseInternal, so an owned
        // FUNDED hold must be stubbed for the guard (not an ESCROW_NOT_FOUND ownership miss) to fire.
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(fundedHold()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("ESCROW_BLOCKED_BY_DISPUTE", ex.getCode());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("H-T34-1: release rejects when collaboration status is DISPUTED")
    void releaseRejectedWhenCollaborationDisputed() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(
                        COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        collaboration.transitionTo(CollaborationStatus.DISPUTED);

        // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        // [CR-47] ownership/hold-load precedes the DISPUTED guard — stub an owned FUNDED hold so the
        // guard is what fires, not an ESCROW_NOT_FOUND ownership miss.
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(fundedHold()));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("ESCROW_BLOCKED_BY_DISPUTE", ex.getCode());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("H-T34-1: refund rejects when active dispute exists on collaboration")
    void refundRejectedWhenActiveDispute() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

        EscrowHold hold = fundedHold();
        Collaboration collaboration =
                Collaboration.invite(
                        COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(eq(COLLABORATION_ID), any()))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> escrowService.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

        assertEquals("ESCROW_BLOCKED_BY_DISPUTE", ex.getCode());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("H-T34-1: concurrent freeze + release — freeze wins, release never posts ledger")
    void concurrentFreezeAndRelease_freezeWins() throws Exception {
        EscrowHold hold = fundedHold();
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLABORATION_ID, EscrowStatus.FUNDED))
                .thenReturn(List.of(hold));
        when(milestoneRepository.findByCollaborationId(COLLABORATION_ID)).thenReturn(List.of());
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenAnswer(inv -> Optional.of(hold));

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(
                        COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch freezeDone = new CountDownLatch(1);
        AtomicReference<Throwable> releaseError = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> releaseFuture =
                    pool.submit(
                            () -> {
                                releaseStarted.countDown();
                                try {
                                    freezeDone.await(5, TimeUnit.SECONDS);
                                    escrowService.release(principal, WORKSPACE_ID, MILESTONE_ID);
                                } catch (Throwable t) {
                                    releaseError.set(t);
                                }
                            });

            Future<Integer> freezeFuture =
                    pool.submit(
                            () -> {
                                releaseStarted.await(5, TimeUnit.SECONDS);
                                int frozen = escrowService.freezeUnreleasedForDispute(COLLABORATION_ID);
                                freezeDone.countDown();
                                return frozen;
                            });

            assertEquals(1, freezeFuture.get(10, TimeUnit.SECONDS));
            releaseFuture.get(10, TimeUnit.SECONDS);

            assertEquals(EscrowStatus.FROZEN, hold.getStatus());
            Throwable err = releaseError.get();
            if (err instanceof ApiException apiEx) {
                assertEquals("INVALID_ESCROW_STATE", apiEx.getCode());
            } else if (err != null) {
                throw new AssertionError("Unexpected release failure", err);
            }
            verify(ledgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    private static PaymentMilestone fundedMilestone() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .contractId("01HCONTRACT123456789")
                        .collaborationId(COLLABORATION_ID)
                        .sequenceNo(1)
                        .amount(new BigDecimal("10000.00"))
                        .status(MilestoneStatus.FUNDED)
                        .build();
        milestone.markFunded(ESCROW_HOLD_ID);
        return milestone;
    }

    private static EscrowHold fundedHold() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .collaborationId(COLLABORATION_ID)
                .campaignId("01HCAMPAIGN1234567AB")
                .milestoneId(MILESTONE_ID)
                .amount(new BigDecimal("10000.00"))
                .currency("INR")
                .status(EscrowStatus.FUNDED)
                .idempotencyKey("fund-idem")
                .build();
    }
}
