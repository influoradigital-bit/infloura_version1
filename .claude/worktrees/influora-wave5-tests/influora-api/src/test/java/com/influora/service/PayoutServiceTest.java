package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.integration.razorpay.RazorpayXClient.PayoutResult;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.PayoutResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [E2 audit finding #9, CRITICAL -- fixed] Unit tests for {@link PayoutService#queuePayout}.
 * Priority: proving a retried payout request can never queue a second RazorpayX payout -- the
 * money-moving guarantee this class previously had ZERO local protection for (dedup was entirely
 * delegated to RazorpayX's own idempotency handling). Same rigor/shape as {@code
 * RedemptionServiceTest}'s idempotency suite (the mandated shared {@code
 * IdempotencyService.executeOnce} pattern).
 *
 * <p>[SEC: Vikram, P5 test-compile fix] Constructor call and mocks below now match {@link
 * PayoutService}'s REAL, current constructor (6 args). This file previously referenced a richer
 * shape -- {@code PayoutRepository}/{@code CreatorProfileRepository}/{@code
 * CreatorBankAccountRepository}/{@code RazorpayFundAccountService}/{@code ObjectMapper}, plus a
 * {@code mockFundAccountResolution()} P2-12 fund-account-lookup stub -- that {@link PayoutService}
 * never actually implements: {@link PayoutService#doQueuePayout} still passes {@code
 * collaboration.getCreatorId()} straight to {@code RazorpayXClient#initiatePayout} as a documented
 * placeholder (see that method's own comment: "the real integration resolves a Razorpay Contact/
 * Fund Account id from the creator's KYC record; that entity does not exist in this slice"). Rather
 * than fabricate a fund-account-resolution feature that isn't there, this file was trimmed to the
 * dependencies {@link PayoutService} genuinely has today; the removed stub's own comment already
 * noted it existed only to keep {@code initiatePayout(eq(CREATOR_ID), ...)} assertions passing
 * under a NEVER-SHIPPED P2-12 behavior change, and those assertions pass unmodified against the
 * current, simpler implementation with no fund-account mocking needed at all.
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String MILESTONE_ID = "01HMILESTONE1234567AB";
    private static final String ESCROW_HOLD_ID = "01HESCROWHOLD1234567A";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_ID = "01HCREATORUSER1234567";
    private static final String IDEMPOTENCY_KEY = "payout:" + MILESTONE_ID;

    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private RazorpayXClient razorpayXClient;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;

    private PayoutService service;

    @BeforeEach
    void setUp() {
        service =
                new PayoutService(
                        milestoneRepository,
                        escrowHoldRepository,
                        collaborationRepository,
                        razorpayXClient,
                        brandContext,
                        idempotencyService);
    }

    /** Mirrors {@code RedemptionServiceTest#mockIdempotencyExecuteOnce}: run the supplier as the race winner. */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<PayoutResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private PaymentMilestone releasedMilestone() {
        return PaymentMilestone.builder()
                .id(MILESTONE_ID)
                .contractId("01HCONTRACT1234567AB")
                .collaborationId(COLLABORATION_ID)
                .sequenceNo(1)
                .amount(BigDecimal.valueOf(5000))
                .currency("INR")
                .build();
    }

    private EscrowHold releasedHold() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .amount(BigDecimal.valueOf(5000))
                .currency("INR")
                .status(EscrowStatus.RELEASED)
                .idempotencyKey("release:" + ESCROW_HOLD_ID)
                .build();
    }

    private Collaboration collaboration() {
        return Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN123456789A", CREATOR_ID, null, "INR");
    }

    // ------------------------------------------------------------------
    // Idempotency [SEC: Kabir] -- the CRITICAL guarantee under test
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "queuePayout: first call with a fresh key hits RazorpayX exactly once and marks the"
                    + " milestone's idempotencyKey so a retry can be detected without re-calling the gateway")
    void testFirstCallInitiatesPayoutExactlyOnce() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        // Bind the milestone to the funded/released escrow hold so getEscrowHoldId() is non-null.
        milestone.markFunded(ESCROW_HOLD_ID);
        when(milestoneRepository.findById(MILESTONE_ID))
                .thenReturn(Optional.empty()) // replay pre-check sees nothing queued yet
                .thenReturn(Optional.of(milestone)); // doQueuePayout's own lookup
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), eq(BigDecimal.valueOf(5000)), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_abc123", "queued"));
        mockIdempotencyExecuteOnce();

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals("payout_abc123", response.payoutId());
        assertEquals(MILESTONE_ID, response.milestoneId());
        assertEquals(0, BigDecimal.valueOf(5000).compareTo(response.amount()));
        assertEquals("queued", response.status());
        verify(razorpayXClient, times(1))
                .initiatePayout(eq(CREATOR_ID), eq(BigDecimal.valueOf(5000)), eq("INR"), eq(IDEMPOTENCY_KEY));
        // Milestone persisted with the payout's idempotency key -- the local replay guard.
        ArgumentCaptor<PaymentMilestone> captor = ArgumentCaptor.forClass(PaymentMilestone.class);
        verify(milestoneRepository).save(captor.capture());
        assertEquals(IDEMPOTENCY_KEY, captor.getValue().getIdempotencyKey());
    }

    @Test
    @DisplayName(
            "queuePayout: a retried request (milestone already carries this payout's idempotencyKey)"
                    + " returns the SAME response and NEVER calls RazorpayX a second time")
    void testRetryDoesNotDoubleCallGateway() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        milestone.markFunded(ESCROW_HOLD_ID);
        milestone.markPayoutQueued(IDEMPOTENCY_KEY);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals(MILESTONE_ID, response.milestoneId());
        assertEquals(0, BigDecimal.valueOf(5000).compareTo(response.amount()));
        // The gateway must NEVER be called on a replay -- this is the whole point of the fix.
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
        verify(milestoneRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "queuePayout: loses the concurrent reservation race but the winner's milestone row is"
                    + " already updated -- returns it gracefully instead of a 500/double gateway call")
    void testConcurrentRaceLoserReturnsWinnerGracefully() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone freshMilestone = releasedMilestone();
        freshMilestone.markFunded(ESCROW_HOLD_ID);
        PaymentMilestone wonMilestone = releasedMilestone();
        wonMilestone.markFunded(ESCROW_HOLD_ID);
        wonMilestone.markPayoutQueued(IDEMPOTENCY_KEY);

        // Three reads of the milestone happen before this test's assertions: (1) queuePayout's
        // pre-check replay (not queued yet), (2) validateForPayout's own lookup (E2 HIGH-1 -- runs
        // BEFORE executeOnce, still sees the not-yet-queued row), (3) the post-race replay after
        // losing executeOnce, which now sees the winner's committed, payout-queued row.
        when(milestoneRepository.findById(MILESTONE_ID))
                .thenReturn(Optional.of(freshMilestone))
                .thenReturn(Optional.of(freshMilestone))
                .thenReturn(Optional.of(wonMilestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals(MILESTONE_ID, response.milestoneId());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "queuePayout: loses the race and the winner's row is STILL not visible -- throws a"
                    + " retry-safe 409, never a generic 500, and never calls RazorpayX")
    void testConcurrentRaceNoVisibleWinnerThrows409() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone freshMilestone = releasedMilestone();
        freshMilestone.markFunded(ESCROW_HOLD_ID);
        // Not markPayoutQueued -- both replayIfPresent checks (pre-check and post-race) see a
        // milestone whose idempotencyKey does not match yet, so neither one queues a response.
        // validateForPayout (E2 HIGH-1 -- runs BEFORE executeOnce) still fully validates the
        // milestone/escrow/collaboration on every call, so those repositories must be stubbed even
        // though this scenario's outcome hinges on the idempotency race, not validation.
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(freshMilestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Pre-existing state-machine guards, still enforced under the new wrapper
    // ------------------------------------------------------------------

    @Test
    @DisplayName("queuePayout: MILESTONE_NOT_RELEASED (409) if the milestone has no escrow hold at all")
    void testMilestoneNotFunded() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone(); // escrowHoldId null (never funded)
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        // [SEC: Kabir, E2 HIGH-1] Validation now runs BEFORE executeOnce -- it must never even be
        // invoked for a validation failure, so no stub for it here (STRICT_STUBS would fail this
        // test if it were stubbed and never called).

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_NOT_RELEASED", ex.getCode());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @Test
    @DisplayName("queuePayout: MILESTONE_NOT_RELEASED (409) if the escrow hold exists but is not RELEASED yet")
    void testEscrowNotYetReleased() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        milestone.markFunded(ESCROW_HOLD_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        EscrowHold fundedNotReleased =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("fund:" + ESCROW_HOLD_ID)
                        .build();
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(fundedNotReleased));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_NOT_RELEASED", ex.getCode());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @Test
    @DisplayName("queuePayout: MILESTONE_NOT_FOUND (404) if the escrow hold belongs to another workspace")
    void testCrossWorkspaceEscrowRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        milestone.markFunded(ESCROW_HOLD_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        EscrowHold otherWorkspaceHold =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId("01HOTHERWORKSPACE1234")
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.RELEASED)
                        .idempotencyKey("release:" + ESCROW_HOLD_ID)
                        .build();
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(otherWorkspaceHold));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_NOT_FOUND", ex.getCode());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "queuePayout: [E2 LOW-2 -- fixed] a hold that is BOTH cross-workspace AND not-yet-RELEASED"
                    + " reports MILESTONE_NOT_FOUND (ownership checked first), never MILESTONE_NOT_RELEASED --"
                    + " closes the cross-workspace state oracle")
    void testOwnershipCheckedBeforeReleaseState() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        milestone.markFunded(ESCROW_HOLD_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        // Hold belongs to ANOTHER workspace AND is not RELEASED yet -- if ordering regressed back
        // to state-before-ownership, this would surface MILESTONE_NOT_RELEASED instead, leaking
        // the hold's real state to a caller who isn't even a member of its workspace.
        EscrowHold otherWorkspaceUnreleasedHold =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId("01HOTHERWORKSPACE1234")
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("fund:" + ESCROW_HOLD_ID)
                        .build();
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID))
                .thenReturn(Optional.of(otherWorkspaceUnreleasedHold));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_NOT_FOUND", ex.getCode());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // FAILED-key recovery [SEC: Kabir, E2 HIGH-1 -- fixed]
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "queuePayout: a transient gateway failure marks the key FAILED via IdempotencyService, but"
                    + " a subsequent legitimate retry (validation now passes/still passes) reaches"
                    + " executeOnce again and can succeed -- the payout is never permanently wedged")
    void testTransientGatewayFailureThenSuccessfulRetryUnwedgesPayout() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        milestone.markFunded(ESCROW_HOLD_ID);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));

        // First attempt: validation passes (asserted implicitly by reaching executeOnce), but the
        // gateway call inside executeOnce fails -- IdempotencyService is responsible for marking
        // FAILED; from PayoutService's point of view it just sees the RuntimeException propagate.
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<PayoutResponse> supplier = invocation.getArgument(3);
                            return supplier.get(); // runs doQueuePayout -- razorpayXClient throws below
                        });
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), any(), any(), anyString()))
                .thenThrow(new RuntimeException("RazorpayX timeout"));

        assertThrows(
                RuntimeException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));
        verify(milestoneRepository, never()).save(any()); // never reached markPayoutQueued

        // Second attempt (the legitimate retry): validation re-runs and passes again (milestone
        // still RELEASED, still not yet queued -- replayIfPresent still sees no queued payout), and
        // this time the gateway succeeds. Because IdempotencyService now reclaims FAILED keys
        // instead of treating them as terminal, this call is expected to reach doQueuePayout again
        // rather than being wedged behind AlreadyInProgressException forever.
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), any(), any(), anyString()))
                .thenReturn(new PayoutResult("payout_retry_ok", "queued"));

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals("payout_retry_ok", response.payoutId());
        verify(razorpayXClient, times(2)).initiatePayout(eq(CREATOR_ID), any(), any(), anyString());
        verify(milestoneRepository, times(1)).save(any());
    }
}
