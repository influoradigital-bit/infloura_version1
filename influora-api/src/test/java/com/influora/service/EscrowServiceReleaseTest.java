package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.ReleaseCondition;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
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
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** Task #26 V1 — escrow release path wires platform fee before creator credit. */
@ExtendWith(MockitoExtension.class)
class EscrowServiceReleaseTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
  private static final String MILESTONE_ID = "01HMILESTONE123456789";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
  private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
  private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
  private static final String PAYEE_WALLET_ID = "01HPAYEE123456789012";

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

  private EscrowService service;

  @BeforeEach
  void setUp() {
    EscrowBackend escrowBackend =
        new LedgerEscrowBackend(ledgerService, platformWalletService, platformFeeService, walletService);
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
    // [CR-51 step 2] This whole test class exercises the B5 release_condition gate
    // (assertReleaseConditionSatisfied), which now only applies to milestones created after a
    // configured cutover (see EscrowService javadoc on that method) — legacy pre-cutover
    // milestones fail open unconditionally. Every milestone built in this file is a fresh
    // PaymentMilestone.builder().build() (createdAt = Instant.now()), so a cutover safely in the
    // past puts all of them on the "post-cutover, gate applies" side, preserving this class's
    // original intent (these tests exist specifically to prove the gate DOES fire) without a
    // per-test cutover dance. A real deploy sets this via
    // influora.escrow.release-gate.cutover-instant; here it's wired directly since these mocks
    // are constructed with `new EscrowService(...)`, not a Spring context that would resolve the
    // @Value default.
    ReflectionTestUtils.setField(service, "releaseGateCutoverInstantRaw", "2020-01-01T00:00:00Z");
    ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");
  }

  @Test
  @DisplayName("release: deducts platform fee then credits creator net amount")
  void testReleaseDeductsFeeBeforeCreatorCredit() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

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

    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();

    // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    // [CR-51 step 3] This test is about fee/credit accounting, not the release_condition gate —
    // but the gate now BLOCKS a zero-deliverable post-cutover release (Swapnil: forbid) instead of
    // failing open, so a satisfying deliverable must be stubbed for release() to reach the
    // fee-deduction code this test actually asserts on. milestone has no explicit
    // releaseCondition -> gate defaults to ON_POSTED.
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.POSTED)));

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);

    PlatformFeeService.FeeDeductionResult feeDeduction =
        new PlatformFeeService.FeeDeductionResult(
            new BigDecimal("10000.00"),
            1500,
            new BigDecimal("1500.00"),
            new BigDecimal("8500.00"),
            null);
    when(platformFeeService.deductAtRelease(
            eq(clearingWallet),
            eq(MILESTONE_ID),
            eq(CREATOR_USER_ID),
            eq(new BigDecimal("10000.00")),
            eq("INR"),
            eq(ESCROW_HOLD_ID)))
        .thenReturn(feeDeduction);

    WalletTransaction releaseCredit = ledgerTxn("release-credit");
    when(ledgerService.post(
            eq(CLEARING_WALLET_ID),
            eq(PAYEE_WALLET_ID),
            eq(new BigDecimal("8500.00")),
            eq("INR"),
            eq(WalletTransactionType.ESCROW_RELEASE),
            eq(TxnReferenceType.MILESTONE),
            eq(MILESTONE_ID),
            any(),
            eq("release:" + ESCROW_HOLD_ID),
            eq(null)))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(ledgerTxn("release-debit"), releaseCredit));

    service.release(principal, WORKSPACE_ID, MILESTONE_ID);

    verify(platformFeeService)
        .deductAtRelease(
            clearingWallet,
            MILESTONE_ID,
            CREATOR_USER_ID,
            new BigDecimal("10000.00"),
            "INR",
            ESCROW_HOLD_ID);
    verify(ledgerService)
        .post(
            CLEARING_WALLET_ID,
            PAYEE_WALLET_ID,
            new BigDecimal("8500.00"),
            "INR",
            WalletTransactionType.ESCROW_RELEASE,
            TxnReferenceType.MILESTONE,
            MILESTONE_ID,
            "Milestone release for contract " + milestone.getContractId(),
            "release:" + ESCROW_HOLD_ID,
            null);
    verify(milestoneRepository).save(milestone);
    assertEquals(MilestoneStatus.RELEASED, milestone.getStatus());
  }

  // ----------------------------------------------------------------------------------------
  // [B5] release_condition gate — EscrowService#release must block unless the deliverable(s)
  // linked to the milestone satisfy release_condition.
  // ----------------------------------------------------------------------------------------

  private PaymentMilestone fundedMilestoneWithCondition(ReleaseCondition condition) {
    PaymentMilestone milestone =
        PaymentMilestone.builder()
            .id(MILESTONE_ID)
            .contractId("01HCONTRACT123456789")
            .collaborationId(COLLABORATION_ID)
            .sequenceNo(1)
            .amount(new BigDecimal("10000.00"))
            .status(MilestoneStatus.FUNDED)
            .releaseCondition(condition)
            .build();
    milestone.markFunded(ESCROW_HOLD_ID);
    return milestone;
  }

  private static Deliverable deliverableWithStatus(DeliverableStatus status) {
    return Deliverable.builder()
        .id("01HDELIVERABLE12345A")
        .collaborationId(COLLABORATION_ID)
        .creatorProfileId("profile-1")
        .milestoneId(MILESTONE_ID)
        .slotIndex(1)
        .type(DeliverableType.INSTAGRAM_REEL)
        .title("Reel 1")
        .status(status)
        .build();
  }

  private void stubCommonReleaseCollaborators(PaymentMilestone milestone, Collaboration collaboration) {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    // [CR-48] releaseInternal now resolves the milestone via the workspace-scoped lookup.
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
  }

  @Test
  @DisplayName("release: BLOCKED when release_condition (ON_POSTED) is not satisfied by the linked deliverable")
  void testReleaseBlockedWhenReleaseConditionNotSatisfied() {
    PaymentMilestone milestone = fundedMilestoneWithCondition(ReleaseCondition.ON_POSTED);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
    stubCommonReleaseCollaborators(milestone, collaboration);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

    // Deliverable exists but is only APPROVED — ON_POSTED requires POSTED/METRICS_REPORTED/VERIFIED.
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.APPROVED)));

    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID));

    assertEquals("RELEASE_CONDITION_NOT_MET", ex.getCode());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(milestoneRepository, never()).save(any());
    verify(escrowHoldRepository, never()).save(any());
  }

  @Test
  @DisplayName("release: proceeds when release_condition (ON_POSTED) IS satisfied by the linked deliverable")
  void testReleaseProceedsWhenReleaseConditionSatisfied() {
    PaymentMilestone milestone = fundedMilestoneWithCondition(ReleaseCondition.ON_POSTED);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
    stubCommonReleaseCollaborators(milestone, collaboration);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.POSTED)));

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
    when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlatformFeeService.FeeDeductionResult(
                new BigDecimal("10000.00"), 1500, new BigDecimal("1500.00"), new BigDecimal("8500.00"), null));
    when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(
                ledgerTxn("release-debit"), ledgerTxn("release-credit")));

    service.release(principal, WORKSPACE_ID, MILESTONE_ID);

    assertEquals(MilestoneStatus.RELEASED, milestone.getStatus());
    verify(ledgerService).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    // [B3] a real release must notify — PayoutReleasedEvent published exactly once.
    verify(eventPublisher).publishEvent(any(PayoutReleasedEvent.class));
  }

  // ----------------------------------------------------------------------------------------
  // [B8] invoice creation failure must NEVER roll back an already-completed escrow release.
  // ----------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "release: a CampaignServiceInvoiceService failure (e.g. CREATOR_PROFILE_NOT_FOUND) does NOT"
          + " roll back the already-completed release/ledger posting")
  void testReleaseSurvivesInvoiceCreationFailure() {
    PaymentMilestone milestone = fundedMilestoneWithCondition(ReleaseCondition.ON_POSTED);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
    stubCommonReleaseCollaborators(milestone, collaboration);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    // [CR-51 step 3] This test is about invoice-failure isolation, not the release_condition gate
    // — a zero-deliverable post-cutover release is now BLOCKED (Swapnil: forbid) rather than
    // fail-open, so a satisfying deliverable must be stubbed for release() to reach the code this
    // test actually asserts on.
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.POSTED)));

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
    when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlatformFeeService.FeeDeductionResult(
                new BigDecimal("10000.00"), 1500, new BigDecimal("1500.00"), new BigDecimal("8500.00"), null));
    when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(
                ledgerTxn("release-debit"), ledgerTxn("release-credit")));

    // The exact failure the audit flagged: invoice creation blows up with an ApiException.
    when(campaignServiceInvoiceService.createAtRelease(any(), any(), any()))
        .thenThrow(
            new ApiException(
                "CREATOR_PROFILE_NOT_FOUND",
                "Creator profile not found",
                org.springframework.http.HttpStatus.CONFLICT));

    // Must NOT throw — the release itself must complete successfully despite the invoice failure.
    service.release(principal, WORKSPACE_ID, MILESTONE_ID);

    assertEquals(MilestoneStatus.RELEASED, milestone.getStatus());
    assertEquals(EscrowStatus.RELEASED, hold.getStatus());
    verify(ledgerService).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(milestoneRepository).save(milestone);
    verify(escrowHoldRepository).save(hold);
    // Notification still fires — the release genuinely succeeded.
    verify(eventPublisher).publishEvent(any(PayoutReleasedEvent.class));
  }

  // ----------------------------------------------------------------------------------------
  // [B3] tryReleaseOnApproval — best-effort release attempt used by BrandDeliverableService#approve.
  // ----------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "tryReleaseOnApproval: returns false (does not throw) when release_condition is not yet met")
  void testTryReleaseOnApprovalReturnsFalseWhenConditionNotMet() {
    PaymentMilestone milestone = fundedMilestoneWithCondition(ReleaseCondition.ON_POSTED);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
    // tryReleaseOnApproval's own pre-check uses the unscoped lookup (internal call, workspaceId
    // already trusted from the approval flow -- see EscrowService#tryReleaseOnApproval javadoc)...
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    // [CR-48] ...but the releaseInternal call it makes now resolves the milestone via the
    // workspace-scoped lookup, so that must be stubbed too or this test would short-circuit on
    // MILESTONE_NOT_FOUND instead of actually reaching (and testing) the release_condition gate.
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    // Deliverable is only APPROVED (brand just approved it) — not yet POSTED.
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.APPROVED)));

    boolean released = service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID).released();

    assertFalse(released);
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    // No brand-role check for this internal path — approval already established workspace trust.
    verify(brandContext, never()).requireRole(any(), any());
  }

  @Test
  @DisplayName("tryReleaseOnApproval: releases (marks milestone RELEASED) when release_condition IS met")
  void testTryReleaseOnApprovalReleasesWhenConditionMet() {
    PaymentMilestone milestone = fundedMilestoneWithCondition(ReleaseCondition.ON_APPROVAL);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    // [CR-48] releaseInternal (called from tryReleaseOnApproval) now resolves the milestone via
    // the workspace-scoped lookup -- see the sibling test above for why both stubs are needed.
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    // ON_APPROVAL is satisfied the instant the deliverable reaches APPROVED.
    when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
        .thenReturn(List.of(deliverableWithStatus(DeliverableStatus.APPROVED)));

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
    when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlatformFeeService.FeeDeductionResult(
                new BigDecimal("10000.00"), 1500, new BigDecimal("1500.00"), new BigDecimal("8500.00"), null));
    when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(
                ledgerTxn("release-debit"), ledgerTxn("release-credit")));

    boolean released = service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID).released();

    assertTrue(released);
    assertEquals(MilestoneStatus.RELEASED, milestone.getStatus());
    verify(ledgerService).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("tryReleaseOnApproval: no-op (false, no exception) when the deliverable has no linked milestone")
  void testTryReleaseOnApprovalNoMilestone() {
    boolean released = service.tryReleaseOnApproval(WORKSPACE_ID, null).released();

    assertFalse(released);
    verify(milestoneRepository, never()).findById(any());
  }

  @Test
  @DisplayName("confirmFunded: does not deduct platform fee at funding time")
  void testConfirmFundedDoesNotDeductPlatformFee() {
    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.PENDING)
            .idempotencyKey("fund-idem")
            .build();

    when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    Wallet brandWallet = Wallet.forWorkspace("01HBRANDWALLET123456", WORKSPACE_ID);
    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(ledgerTxn("fund-debit"), ledgerTxn("fund-credit")));

    service.confirmFunded(ESCROW_HOLD_ID, "gw-ref", 1_000_000L, "INR");

    verify(platformFeeService, never())
        .deductAtRelease(any(), any(), any(), any(), any(), any());
  }

  // ----------------------------------------------------------------------------------------
  // [P-1' fix, BrandF.md §47a] releaseByHoldId — the new release path for holds with NO
  // PaymentMilestone (e.g. escrow Meera funds at the campaign level before any contract exists).
  // ----------------------------------------------------------------------------------------

  private EscrowHold milestonelessFundedHold(String collaborationId) {
    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    if (collaborationId != null) {
      hold.bindCollaboration(collaborationId);
    }
    return hold;
  }

  @Test
  @DisplayName(
      "releaseByHoldId: releases a milestone-less hold (Meera campaign-level escrow) once it is"
          + " bound to a collaboration — the path that did not exist before this fix")
  void testReleaseByHoldIdReleasesMilestonelessHold() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    EscrowHold hold = milestonelessFundedHold(COLLABORATION_ID);
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
    when(platformFeeService.deductAtRelease(
            eq(clearingWallet),
            eq(ESCROW_HOLD_ID), // referenceIdFor(hold) == hold.getId() when milestoneId is null
            eq(CREATOR_USER_ID),
            eq(new BigDecimal("10000.00")),
            eq("INR"),
            eq(ESCROW_HOLD_ID)))
        .thenReturn(
            new PlatformFeeService.FeeDeductionResult(
                new BigDecimal("10000.00"), 1500, new BigDecimal("1500.00"), new BigDecimal("8500.00"), null));
    when(ledgerService.post(
            eq(CLEARING_WALLET_ID),
            eq(PAYEE_WALLET_ID),
            eq(new BigDecimal("8500.00")),
            eq("INR"),
            eq(WalletTransactionType.ESCROW_RELEASE),
            eq(TxnReferenceType.ESCROW_HOLD),
            eq(ESCROW_HOLD_ID),
            any(),
            eq("release:" + ESCROW_HOLD_ID),
            eq(null)))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(
                ledgerTxn("release-debit"), ledgerTxn("release-credit")));

    EscrowStatusResponse response = service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

    assertEquals(EscrowStatus.RELEASED, response.status());
    assertEquals(EscrowStatus.RELEASED, hold.getStatus());
    verify(brandContext).requireRole(member, com.influora.domain.enums.MemberRole.OWNER, com.influora.domain.enums.MemberRole.ADMIN);
    verify(escrowHoldRepository).save(hold);
    verify(eventPublisher).publishEvent(any(PayoutReleasedEvent.class));
  }

  @Test
  @DisplayName("releaseByHoldId: idempotent no-op when the hold is already RELEASED")
  void testReleaseByHoldIdIdempotentOnAlreadyReleased() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    EscrowHold hold = milestonelessFundedHold(COLLABORATION_ID);
    hold.markReleased("prior-release-txn");
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    EscrowStatusResponse response = service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

    assertEquals(EscrowStatus.RELEASED, response.status());
    verify(escrowHoldRepository, never()).save(any());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "releaseByHoldId: rejects a hold that HAS a milestone — must go through release(milestoneId)"
          + " so the B5 release_condition gate can never be bypassed by addressing the hold directly")
  void testReleaseByHoldIdRejectsMilestoneBackedHold() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

    assertEquals("ESCROW_HOLD_HAS_MILESTONE", ex.getCode());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(escrowHoldRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "releaseByHoldId: rejects a milestone-less hold that is not yet bound to any collaboration"
          + " (no payee to resolve) instead of silently no-op'ing")
  void testReleaseByHoldIdRejectsUnlinkedHold() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    EscrowHold hold = milestonelessFundedHold(null);
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

    assertEquals("ESCROW_HOLD_NOT_LINKED", ex.getCode());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("releaseByHoldId: 404s (not a cross-tenant leak) when the hold belongs to another workspace")
  void testReleaseByHoldIdRejectsCrossTenantHold() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId("01HOTHERWORKSPACE1234")
            .campaignId("01HCAMPAIGN1234567AB")
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID));

    assertEquals("ESCROW_NOT_FOUND", ex.getCode());
  }

  private static WalletTransaction ledgerTxn(String id) {
    return WalletTransaction.builder()
        .id(id)
        .walletId(CLEARING_WALLET_ID)
        .groupId("grp")
        .direction(TxnDirection.CREDIT)
        .type(WalletTransactionType.ESCROW_RELEASE)
        .amount(BigDecimal.ONE)
        .currency("INR")
        .balanceAfter(BigDecimal.ZERO)
        .build();
  }
}
