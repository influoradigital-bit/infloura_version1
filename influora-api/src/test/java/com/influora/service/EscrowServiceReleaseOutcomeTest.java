package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
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
import com.influora.service.escrow.LedgerEscrowBackend;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * F-0406 / F-0489 — targeted coverage for {@code EscrowService#tryReleaseOnApproval}'s held-vs-loud
 * reclassification and {@code EscrowService#releaseByHoldId}'s end-to-end correctness.
 *
 * <p>F-0406: {@code tryReleaseOnApproval} used to swallow every "release did not happen" reason
 * into a silent {@code ReleaseOutcome.held(...)}, including reasons that are actual data-integrity
 * bugs (a dangling milestone reference, a missing collaboration) rather than legitimate "not yet
 * eligible, try again later" states. {@code BrandDeliverableService#approve} (not owned by this
 * file/task) still marked the deliverable APPROVED in every one of those cases, so a brand could
 * see "approved" while no money moved and nothing would ever surface that fact. These tests prove
 * the reclassified branches now propagate a thrown {@link ApiException} (which — per {@code
 * tryReleaseOnApproval}'s own javadoc — rolls back the whole approve() transaction upstream)
 * while the genuinely idempotent/expected branches keep no-op'ing safely.
 *
 * <p>F-0489: confirms {@code releaseByHoldId} — the escrowHoldId release branch POST
 * /wallet/escrow/release is supposed to reach for a milestone-less (Meera campaign-level) hold —
 * is correct and fully callable at the service level. The frontend wrapper hardcoding
 * {@code milestoneId} in its request body (src/lib/api.ts) is a pure client-side gap: this branch
 * itself works end-to-end today.
 */
@ExtendWith(MockitoExtension.class)
class EscrowServiceReleaseOutcomeTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
  private static final String MILESTONE_ID = "01HMILESTONE123456789";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
  private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
  private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
  private static final String PAYEE_WALLET_ID = "01HPAYEE123456789012";
  private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";

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
    // Same wiring as EscrowServiceReleaseTest — put every milestone built here on the
    // "post-cutover, gate applies" side, then stub the deliverable list as satisfying wherever
    // the B5 gate would otherwise interfere with the branch actually under test.
    ReflectionTestUtils.setField(service, "releaseGateCutoverInstantRaw", "2020-01-01T00:00:00Z");
    ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");
  }

  private PaymentMilestone fundedMilestone() {
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

  private EscrowHold fundedHold() {
    return EscrowHold.builder()
        .id(ESCROW_HOLD_ID)
        .workspaceId(WORKSPACE_ID)
        .campaignId(CAMPAIGN_ID)
        .milestoneId(MILESTONE_ID)
        .amount(new BigDecimal("10000.00"))
        .currency("INR")
        .status(EscrowStatus.FUNDED)
        .idempotencyKey("fund-idem")
        .build();
  }

  // ------------------------------------------------------------------------------------------
  // F-0406 — branches that must now FAIL LOUDLY instead of silently no-op'ing.
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "F-0406: tryReleaseOnApproval FAILS LOUDLY (throws, does not return a held outcome) when"
          + " milestoneId is non-blank but resolves to no milestone row anywhere — a dangling"
          + " reference can never self-resolve on retry the way an unfunded milestone can")
  void tryReleaseOnApprovalThrowsOnDanglingMilestoneId() {
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.empty());

    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID));

    assertEquals("MILESTONE_NOT_FOUND", ex.getCode());
    // No money-movement attempt and no release-eligible path reached.
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "F-0406: tryReleaseOnApproval FAILS LOUDLY when the milestone's collaboration is missing —"
          + " PaymentMilestone.collaborationId is NOT NULL, so a miss here is corrupted data, not"
          + " a timing issue that resolves on retry")
  void tryReleaseOnApprovalThrowsOnMissingCollaboration() {
    PaymentMilestone milestone = fundedMilestone();
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(fundedHold()));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());

    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID));

    assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(escrowHoldRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "F-0406: tryReleaseOnApproval FAILS LOUDLY when the resolved hold belongs to a different"
          + " workspace than the caller's — releaseInternal's own comment on this check calls it a"
          + " data inconsistency, not an expected skip")
  void tryReleaseOnApprovalThrowsOnCrossTenantHold() {
    PaymentMilestone milestone = fundedMilestone();
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));
    EscrowHold crossTenantHold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId("01HOTHERWORKSPACE1234")
            .campaignId(CAMPAIGN_ID)
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(crossTenantHold));

    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID));

    assertEquals("ESCROW_NOT_FOUND", ex.getCode());
  }

  // ------------------------------------------------------------------------------------------
  // F-0406 — genuinely expected branches must KEEP no-op'ing safely (do NOT blanket-throw).
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "F-0406 regression: tryReleaseOnApproval still returns a silent held outcome (no throw) when"
          + " escrow simply is not funded yet — a brand may approve creative before funding, and"
          + " approve() must keep succeeding for this ordinary case")
  void tryReleaseOnApprovalStillHeldWhenNotYetFunded() {
    PaymentMilestone unfunded =
        PaymentMilestone.builder()
            .id(MILESTONE_ID)
            .contractId("01HCONTRACT123456789")
            .collaborationId(COLLABORATION_ID)
            .sequenceNo(1)
            .amount(new BigDecimal("10000.00"))
            .status(MilestoneStatus.PENDING)
            .build();
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(unfunded));

    EscrowService.ReleaseOutcome outcome = service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID);

    assertEquals(false, outcome.released());
    assertEquals("MILESTONE_NOT_FUNDED", outcome.heldReason());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "F-0406 regression: an idempotent re-release (hold already RELEASED) still no-ops safely"
          + " through tryReleaseOnApproval instead of throwing or double-paying the creator")
  void tryReleaseOnApprovalIdempotentOnAlreadyReleasedHold() {
    PaymentMilestone milestone = fundedMilestone();
    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));

    EscrowHold releasedHold = fundedHold();
    releasedHold.markReleased("prior-release-txn");
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold));

    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    EscrowService.ReleaseOutcome outcome = service.tryReleaseOnApproval(WORKSPACE_ID, MILESTONE_ID);

    // releaseInternal's idempotent branch returns normally (no exception either way), so
    // tryReleaseOnApproval reports RELEASED — but no second money movement was attempted.
    assertTrue(outcome.released());
    verify(ledgerService, never())
        .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(escrowHoldRepository, never()).save(any());
    verify(milestoneRepository, never()).save(any());
  }

  // ------------------------------------------------------------------------------------------
  // F-0489 — the escrowHoldId release branch (POST /wallet/escrow/release {escrowHoldId}) is
  // correct and fully callable at the service level. Backend confirmed working; the frontend
  // api wrapper hardcoding milestoneId (src/lib/api.ts, out of this task's scope) is the only gap.
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "F-0489: releaseByHoldId releases a milestone-less (campaign-level, e.g. Meera-funded) hold"
          + " end-to-end — proves the backend half of the escrowHoldId branch is genuinely usable,"
          + " not merely present in source")
  void releaseByHoldIdReleasesMilestonelessHoldEndToEnd() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId(CAMPAIGN_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    hold.bindCollaboration(COLLABORATION_ID); // e.g. Meera-launched campaign-level fund
    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");

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

    // Authz enforced (same OWNER/ADMIN gate as the milestone path).
    verify(brandContext)
        .requireRole(member, com.influora.domain.enums.MemberRole.OWNER, com.influora.domain.enums.MemberRole.ADMIN);
    // Money actually moved, hold transitioned, notification fired — a real end-to-end release,
    // not just a code path that compiles.
    assertEquals(EscrowStatus.RELEASED, response.status());
    assertEquals(EscrowStatus.RELEASED, hold.getStatus());
    verify(escrowHoldRepository).save(hold);
    verify(eventPublisher).publishEvent(any(PayoutReleasedEvent.class));
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
