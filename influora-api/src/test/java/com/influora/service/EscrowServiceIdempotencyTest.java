package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
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
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * F-0652 — before this fix, {@code IdempotencyService} had zero references anywhere in {@code
 * EscrowService}: a duplicated caller request to release/refund had no request-replay key, only
 * the lower ledger-level key derived from the hold id (stops double money movement inside the
 * ledger, not a duplicated HTTP call from re-running the whole {@code EscrowService} operation
 * against a possibly-stale read). These tests exercise the new {@code idempotencyKey}-accepting
 * overloads of {@link EscrowService#release} and {@link EscrowService#refund} and prove:
 *
 * <ul>
 *   <li>a retried request with the SAME key produces exactly ONE {@link EscrowBackend#release}/
 *       {@link EscrowBackend#refund} call, never two, even when the second call's own hold lookup
 *       is a fresh/independent snapshot that still shows FUNDED (simulating a read that has not
 *       observed the first call's commit yet — the realistic shape of the gap this closes); and
 *   <li>both calls return the identical result.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EscrowServiceIdempotencyTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
  private static final String MILESTONE_ID = "01HMILESTONE123456789";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
  private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
  private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
  private static final String IDEM_KEY = "client-retry-key-1";

  @Mock private EscrowHoldRepository escrowHoldRepository;
  @Mock private PaymentMilestoneRepository milestoneRepository;
  @Mock private CampaignRepository campaignRepository;
  @Mock private CollaborationRepository collaborationRepository;
  @Mock private ContractRepository contractRepository;
  @Mock private DisputeRepository disputeRepository;
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
  @Mock private EscrowBackend escrowBackend;
  @Mock private IdempotencyService idempotencyService;

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
    // idempotencyService is field-injected (@Autowired) in production — see EscrowService's
    // javadoc on that field for why. Wire the mock in directly, the same way this test file's
    // siblings wire releaseGateCutoverInstantRaw.
    ReflectionTestUtils.setField(service, "idempotencyService", idempotencyService);
  }

  @SuppressWarnings("unchecked")
  private static void stubExecuteOnceRunsOnceThenAlreadyCompleted(
      IdempotencyService idempotencyServiceMock, String key, String workspaceId, String scope) {
    when(idempotencyServiceMock.executeOnce(eq(key), eq(workspaceId), eq(scope), any(Supplier.class)))
        .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get())
        .thenThrow(new IdempotencyService.AlreadyCompletedException(key));
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

  @Test
  @DisplayName(
      "F-0652: release(...,idempotencyKey) called twice with the SAME key releases exactly once"
          + " and returns the identical RELEASED result both times, even when the second call's"
          + " own write-path lookup would independently see a fresh FUNDED snapshot")
  void releaseWithSameIdempotencyKeyReleasesOnceAndReplaysResult() {
    stubExecuteOnceRunsOnceThenAlreadyCompleted(idempotencyService, IDEM_KEY, WORKSPACE_ID, "escrow-release");

    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

    PaymentMilestone milestone = fundedMilestone();
    when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(milestone));

    // The write path (releaseInternal, reached only on the FIRST call) locks via
    // findByIdForUpdate. Two DISTINCT FUNDED instances stand in for a second, independent read
    // that has not observed the first call's commit yet — the realistic shape of "a duplicated
    // HTTP call retries the whole operation" (F-0652). The read-only replay path
    // (currentReleaseStatusByMilestone) uses the OTHER, non-locking lookup
    // (findByIdAndWorkspaceId) and must never reach this one a second time — if it did, the
    // second, still-FUNDED snapshot would get released too.
    EscrowHold writeSnapshot1 = fundedHold();
    EscrowHold writeSnapshot2 = fundedHold();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID))
        .thenReturn(Optional.of(writeSnapshot1), Optional.of(writeSnapshot2));

    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);

    when(escrowBackend.release(any())).thenReturn(new EscrowBackend.ReleaseOutcome("release-txn-1"));

    // Read-only replay path: a plain, non-locking read of the now-committed row — stub it to
    // return writeSnapshot1, the instance the first (real) write actually mutated to RELEASED.
    when(escrowHoldRepository.findByIdAndWorkspaceId(ESCROW_HOLD_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(writeSnapshot1));

    EscrowStatusResponse first = service.release(principal, WORKSPACE_ID, MILESTONE_ID, IDEM_KEY);
    EscrowStatusResponse second = service.release(principal, WORKSPACE_ID, MILESTONE_ID, IDEM_KEY);

    assertEquals(EscrowStatus.RELEASED, first.status());
    assertEquals(EscrowStatus.RELEASED, second.status());
    assertEquals(first, second);
    // The actual money movement happened exactly once, not once per HTTP call.
    verify(escrowBackend, times(1)).release(any());
    // writeSnapshot2 — the second, independent FUNDED read — was never acted on: proves the
    // replay short-circuited before the write path ran a second time.
    assertEquals(EscrowStatus.FUNDED, writeSnapshot2.getStatus());
  }

  @Test
  @DisplayName(
      "F-0652: refund(...,idempotencyKey) called twice with the SAME key refunds exactly once and"
          + " returns the identical REFUNDED result both times")
  void refundWithSameIdempotencyKeyRefundsOnceAndReplaysResult() {
    stubExecuteOnceRunsOnceThenAlreadyCompleted(idempotencyService, IDEM_KEY, WORKSPACE_ID, "escrow-refund");

    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

    EscrowHold writeSnapshot1 =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId(CAMPAIGN_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    EscrowHold writeSnapshot2 =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId(CAMPAIGN_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID))
        .thenReturn(Optional.of(writeSnapshot1), Optional.of(writeSnapshot2));
    when(escrowHoldRepository.findByIdAndWorkspaceId(ESCROW_HOLD_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(writeSnapshot1));

    when(escrowBackend.refund(any())).thenReturn(new EscrowBackend.RefundOutcome("refund-txn-1"));

    EscrowStatusResponse first = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID, IDEM_KEY);
    EscrowStatusResponse second = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID, IDEM_KEY);

    assertEquals(EscrowStatus.REFUNDED, first.status());
    assertEquals(EscrowStatus.REFUNDED, second.status());
    assertEquals(first, second);
    verify(escrowBackend, times(1)).refund(any());
    assertEquals(EscrowStatus.FUNDED, writeSnapshot2.getStatus());
  }

  @Test
  @DisplayName(
      "F-0652: a genuinely concurrent duplicate (key still IN_PROGRESS) is rejected with a clean"
          + " 409 rather than being silently retried or double-executed")
  void releaseWithIdempotencyKeyStillInProgressRejectsCleanly() {
    when(idempotencyService.executeOnce(
            eq(IDEM_KEY), eq(WORKSPACE_ID), eq("escrow-release"), any(Supplier.class)))
        .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEM_KEY));
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID, IDEM_KEY));

    assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
    verify(escrowBackend, never()).release(any());
  }
}
