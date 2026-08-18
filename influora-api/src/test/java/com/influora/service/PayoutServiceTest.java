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
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.integration.razorpay.RazorpayXClient.PayoutResult;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.WalletLedgerService.LedgerPostingResult;
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
    private static final String RELEASED_TXN_ID = "01HRELEASEDTXN1234567";
    private static final String CREATOR_WALLET_ID = "01HCREATORWALLET12345";
    private static final String CLEARING_WALLET_ID = "01HCLEARINGWALLET1234";
    // 15% creator-side platform commission on a gross milestone amount of 5000 -> net 4250. This
    // is deliberately DIFFERENT from the gross amount so a test that regresses back to paying
    // milestone.getAmount() (gross) fails loudly instead of coincidentally matching.
    private static final BigDecimal GROSS_AMOUNT = BigDecimal.valueOf(5000);
    private static final BigDecimal NET_AMOUNT = BigDecimal.valueOf(4250);

    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private PayoutRepository payoutRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorBankAccountRepository creatorBankAccountRepository;
    @Mock private RazorpayXClient razorpayXClient;
    @Mock private com.influora.service.payout.RazorpayFundAccountService fundAccountService;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private WalletLedgerService walletLedgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private WalletService walletService;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private PayoutService service;

    @BeforeEach
    void setUp() {
        service =
                new PayoutService(
                        milestoneRepository,
                        escrowHoldRepository,
                        collaborationRepository,
                        payoutRepository,
                        creatorProfileRepository,
                        creatorBankAccountRepository,
                        razorpayXClient,
                        fundAccountService,
                        brandContext,
                        idempotencyService,
                        walletTransactionRepository,
                        walletLedgerService,
                        platformWalletService,
                        walletService,
                        applicationHistoryService);
    }

    /**
     * Stubs the release-ledger lookup {@code PayoutService#resolveNetPayoutAmount} depends on --
     * {@link PaymentMilestone#getReleasedTxnId()} must resolve to a {@link WalletTransaction} whose
     * {@code amount} is the NET (post-commission) figure, distinct from the milestone's gross
     * {@code amount}. Every test exercising a successful/replayed payout path needs this stubbed.
     */
    private void mockReleaseLedgerNetAmount() {
        WalletTransaction releaseCredit =
                WalletTransaction.builder()
                        .id(RELEASED_TXN_ID)
                        .walletId(CREATOR_WALLET_ID)
                        .groupId("01HGROUP1234567890AB")
                        .direction(TxnDirection.CREDIT)
                        .type(WalletTransactionType.ESCROW_RELEASE)
                        .amount(NET_AMOUNT)
                        .currency("INR")
                        .balanceAfter(NET_AMOUNT)
                        .referenceType(TxnReferenceType.MILESTONE)
                        .referenceId(MILESTONE_ID)
                        .idempotencyKey("release:" + ESCROW_HOLD_ID + ":C")
                        .build();
        when(walletTransactionRepository.findById(RELEASED_TXN_ID))
                .thenReturn(Optional.of(releaseCredit));
    }

    /** Stubs the wallet-debit leg {@code doQueuePayout} posts before ever calling RazorpayX. */
    private void mockWalletDebit() {
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        WalletTransaction debitLeg =
                WalletTransaction.builder()
                        .id("01HDEBITLEG1234567890")
                        .walletId(CREATOR_WALLET_ID)
                        .groupId("01HGROUP2234567890AB")
                        .direction(TxnDirection.DEBIT)
                        .type(WalletTransactionType.PAYOUT)
                        .amount(NET_AMOUNT)
                        .currency("INR")
                        .balanceAfter(BigDecimal.ZERO)
                        .referenceType(TxnReferenceType.MILESTONE)
                        .referenceId(MILESTONE_ID)
                        .idempotencyKey("payout-debit:" + MILESTONE_ID + ":D")
                        .build();
        WalletTransaction creditLeg =
                WalletTransaction.builder()
                        .id("01HCREDITLEG123456789")
                        .walletId(CLEARING_WALLET_ID)
                        .groupId("01HGROUP2234567890AB")
                        .direction(TxnDirection.CREDIT)
                        .type(WalletTransactionType.PAYOUT)
                        .amount(NET_AMOUNT)
                        .currency("INR")
                        .balanceAfter(NET_AMOUNT)
                        .referenceType(TxnReferenceType.MILESTONE)
                        .referenceId(MILESTONE_ID)
                        .idempotencyKey("payout-debit:" + MILESTONE_ID + ":C")
                        .build();
        when(walletLedgerService.post(
                        eq(CREATOR_WALLET_ID),
                        eq(CLEARING_WALLET_ID),
                        eq(NET_AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.PAYOUT),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        anyString(),
                        eq("payout-debit:" + MILESTONE_ID),
                        eq(null)))
                .thenReturn(new LedgerPostingResult(debitLeg, creditLeg));
    }

    /**
     * B7/C-5 — stubs the fund-account resolution path so tests exercise the same observable
     * outcome as before: one bank account on file, resolved to a fund account id equal to
     * {@code CREATOR_ID}, so existing {@code initiatePayout(eq(CREATOR_ID), ...)} assertions hold.
     */
    private void mockFundAccountResolution() {
        CreatorBankAccount bankAccount =
                CreatorBankAccount.createEncrypted(
                        "01HBANKACCOUNT1234567",
                        CREATOR_ID,
                        "cipher-account",
                        "cipher-ifsc",
                        "BANK",
                        "xxxx1234",
                        true,
                        java.time.Instant.now(),
                        java.time.Instant.now());
        when(creatorBankAccountRepository.findByCreatorUserIdAndPrimaryTrue(CREATOR_ID))
                .thenReturn(Optional.of(bankAccount));
        when(fundAccountService.resolveFundAccountId(CREATOR_ID, bankAccount.getId()))
                .thenReturn(CREATOR_ID);
    }

    /**
     * [P2, SEC: Kabir 1c] Stubs the creator-wallet lookup {@code validateForPayout}'s binding
     * assert needs -- confirms the release-ledger credit's {@code walletId} equals THIS
     * milestone's creator's wallet id. Lighter-weight than {@link #mockWalletDebit()} (which also
     * stubs the clearing wallet + the ledger post itself) for tests that only need to get past
     * validation without exercising the debit/gateway path.
     */
    private void mockCreatorWalletBinding() {
        Wallet creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_ID);
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(creatorWallet);
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
                .amount(GROSS_AMOUNT)
                .currency("INR")
                .build();
    }

    /**
     * Binds {@code milestone} to the funded/released escrow hold AND sets its {@code
     * releasedTxnId} to {@link #RELEASED_TXN_ID} -- the release-ledger pointer {@code
     * PayoutService#resolveNetPayoutAmount} reads to determine the NET payout figure. Callers must
     * also stub {@link #mockReleaseLedgerNetAmount()} so that id actually resolves to {@link
     * #NET_AMOUNT}.
     */
    private void bindReleased(PaymentMilestone milestone) {
        milestone.markFunded(ESCROW_HOLD_ID);
        milestone.markReleased(RELEASED_TXN_ID, "release:" + ESCROW_HOLD_ID);
    }

    private EscrowHold releasedHold() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .amount(GROSS_AMOUNT)
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
        // Bind the milestone to the funded/released escrow hold so getEscrowHoldId() is non-null,
        // AND to its release-ledger credit leg so resolveNetPayoutAmount can find the NET figure.
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID))
                .thenReturn(Optional.empty()) // replay pre-check sees nothing queued yet
                .thenReturn(Optional.of(milestone)); // doQueuePayout's own lookup
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        // NET_AMOUNT (4250), NOT GROSS_AMOUNT (5000) -- this is the money-safety regression guard.
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), eq(NET_AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_abc123", "queued"));
        mockIdempotencyExecuteOnce();
        mockFundAccountResolution();
        mockReleaseLedgerNetAmount();
        mockWalletDebit();

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals("payout_abc123", response.payoutId());
        assertEquals(MILESTONE_ID, response.milestoneId());
        // Money-safety: the payout response reports the NET amount actually paid, never gross.
        assertEquals(0, NET_AMOUNT.compareTo(response.amount()));
        assertEquals("queued", response.status());
        verify(razorpayXClient, times(1))
                .initiatePayout(eq(CREATOR_ID), eq(NET_AMOUNT), eq("INR"), eq(IDEMPOTENCY_KEY));
        // Regression pin: RazorpayX must NEVER be called with the milestone's gross amount.
        verify(razorpayXClient, never()).initiatePayout(any(), eq(GROSS_AMOUNT), any(), any());
        // Double-pay guardrail: the creator's wallet balance is debited the SAME net amount, on the
        // SAME milestone reference, before the money is ever handed to RazorpayX.
        verify(walletLedgerService, times(1))
                .post(
                        eq(CREATOR_WALLET_ID),
                        eq(CLEARING_WALLET_ID),
                        eq(NET_AMOUNT),
                        eq("INR"),
                        eq(WalletTransactionType.PAYOUT),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        anyString(),
                        eq("payout-debit:" + MILESTONE_ID),
                        eq(null));
        // Milestone persisted with the payout's idempotency key -- the local replay guard.
        ArgumentCaptor<PaymentMilestone> captor = ArgumentCaptor.forClass(PaymentMilestone.class);
        verify(milestoneRepository).save(captor.capture());
        assertEquals(IDEMPOTENCY_KEY, captor.getValue().getIdempotencyKey());
    }

    /** Persistent application-history requirement — wiring the 8 remaining event types. */
    @Test
    @DisplayName("queuePayout: records a PAY application-history event, after the gateway confirms")
    void testQueuePayoutRecordsApplicationHistoryEvent() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(principal.getUserId()).thenReturn("brand_user_1");
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), eq(NET_AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_abc123", "queued"));
        mockIdempotencyExecuteOnce();
        mockFundAccountResolution();
        mockReleaseLedgerNetAmount();
        mockWalletDebit();

        service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        verify(applicationHistoryService)
                .record(
                        eq("01HCAMPAIGN123456789A"),
                        eq(COLLABORATION_ID),
                        eq(COLLABORATION_ID),
                        eq(com.influora.domain.enums.ApplicationHistoryEventType.PAY),
                        any(),
                        eq(com.influora.domain.enums.ApplicationHistoryActorType.BRAND),
                        eq("brand_user_1"),
                        anyString(),
                        // Sign-off review follow-on (#3) — metadata is null, no raw milestone id.
                        org.mockito.ArgumentMatchers.isNull(),
                        eq("/creator/chat?deal=" + COLLABORATION_ID),
                        eq(COLLABORATION_ID));
    }

    @Test
    @DisplayName(
            "queuePayout: a retried request (milestone already carries this payout's idempotencyKey)"
                    + " returns the SAME response and NEVER calls RazorpayX a second time")
    void testRetryDoesNotDoubleCallGateway() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        milestone.markPayoutQueued(IDEMPOTENCY_KEY);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        mockReleaseLedgerNetAmount();

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals(MILESTONE_ID, response.milestoneId());
        // Replay reports the NET amount too -- never gross.
        assertEquals(0, NET_AMOUNT.compareTo(response.amount()));
        // The gateway must NEVER be called on a replay -- this is the whole point of the fix.
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
        verify(milestoneRepository, never()).save(any());
        // No wallet debit either -- a replay must never move money a second time.
        verify(walletLedgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "queuePayout: loses the concurrent reservation race but the winner's milestone row is"
                    + " already updated -- returns it gracefully instead of a 500/double gateway call")
    void testConcurrentRaceLoserReturnsWinnerGracefully() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone freshMilestone = releasedMilestone();
        bindReleased(freshMilestone);
        PaymentMilestone wonMilestone = releasedMilestone();
        bindReleased(wonMilestone);
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
        mockReleaseLedgerNetAmount();
        mockCreatorWalletBinding(); // [P2] validateForPayout's binding assert runs before the race

        PayoutResponse response = service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals(MILESTONE_ID, response.milestoneId());
        assertEquals(0, NET_AMOUNT.compareTo(response.amount()));
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        // The race loser never touches the wallet ledger -- only the winner's (already-completed)
        // path could have, and that happened inside a different call.
        verify(walletLedgerService, never()).post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "queuePayout: loses the race and the winner's row is STILL not visible -- throws a"
                    + " retry-safe 409, never a generic 500, and never calls RazorpayX")
    void testConcurrentRaceNoVisibleWinnerThrows409() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone freshMilestone = releasedMilestone();
        bindReleased(freshMilestone);
        // Not markPayoutQueued -- both replayIfPresent checks (pre-check and post-race) see a
        // milestone whose idempotencyKey does not match yet, so neither one queues a response.
        // validateForPayout (E2 HIGH-1 -- runs BEFORE executeOnce) still fully validates the
        // milestone/escrow/collaboration on every call, including resolving the NET payout amount
        // off the release ledger, so those repositories must be stubbed even though this scenario's
        // outcome hinges on the idempotency race, not validation.
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(freshMilestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));
        mockReleaseLedgerNetAmount();
        mockCreatorWalletBinding(); // [P2] validateForPayout's binding assert runs before the race

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
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        mockFundAccountResolution();
        mockReleaseLedgerNetAmount();
        mockWalletDebit();

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

    // ------------------------------------------------------------------
    // [P1, SEC: Kabir, landed-money-path audit 2b] Orphaned-debit fix -- a durable PENDING Payout
    // row must exist BEFORE the wallet debit / RazorpayX call, and be updated (never re-inserted)
    // once the gateway responds.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "queuePayout: persists a PENDING Payout row BEFORE the wallet debit and the RazorpayX"
                    + " call, then updates that SAME row to the gateway's status once it responds")
    void testPersistsPendingPayoutRowBeforeGatewayCall() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), eq(NET_AMOUNT), eq("INR"), anyString()))
                .thenReturn(new PayoutResult("payout_abc123", "queued"));
        mockIdempotencyExecuteOnce();
        mockFundAccountResolution();
        mockReleaseLedgerNetAmount();
        mockWalletDebit();
        // No prior Payout row for this idempotency key -- Mockito's default Optional answer
        // returns Optional.empty() for the unstubbed findByIdempotencyKey call.

        // payoutRepository.save() is called TWICE on the SAME (mutable) Payout object -- once to
        // persist it PENDING, once to persist it gateway-confirmed. An ArgumentCaptor would only
        // ever see the object's FINAL state for both captures (classic Mockito mutable-argument
        // pitfall), so snapshot the immutable String status/id at each actual call instead.
        java.util.List<String> savedStatusesAtCallTime = new java.util.ArrayList<>();
        java.util.List<String> savedRazorpayIdsAtCallTime = new java.util.ArrayList<>();
        java.util.List<String> savedRowIdsAtCallTime = new java.util.ArrayList<>();
        when(payoutRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            com.influora.domain.entity.Payout p = invocation.getArgument(0);
                            savedStatusesAtCallTime.add(p.getStatus());
                            savedRazorpayIdsAtCallTime.add(p.getRazorpayPayoutId());
                            savedRowIdsAtCallTime.add(p.getId());
                            return p;
                        });

        service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID);

        org.mockito.InOrder order =
                org.mockito.Mockito.inOrder(payoutRepository, walletLedgerService, razorpayXClient);
        // The PENDING row is saved BEFORE the debit and BEFORE the gateway call ...
        order.verify(payoutRepository).save(any());
        order.verify(walletLedgerService)
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(razorpayXClient).initiatePayout(any(), any(), any(), anyString());
        // ... and updated (not re-inserted) once the gateway responds.
        order.verify(payoutRepository).save(any());

        assertEquals(2, savedStatusesAtCallTime.size());
        assertEquals(com.influora.domain.entity.Payout.STATUS_PENDING, savedStatusesAtCallTime.get(0));
        assertEquals("queued", savedStatusesAtCallTime.get(1));
        assertEquals("payout_abc123", savedRazorpayIdsAtCallTime.get(1));
        // Both saves are literally the same row (same object/id) -- an update, not a second insert.
        assertEquals(savedRowIdsAtCallTime.get(0), savedRowIdsAtCallTime.get(1));
    }

    @Test
    @DisplayName(
            "queuePayout: RazorpayX failure leaves the Payout row PENDING with the debit already"
                    + " posted -- the exact orphaned-debit state PayoutOrphanedDebitSweepJob sweeps")
    void testGatewayFailureLeavesOrphanedDebitAsPendingRow() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        mockFundAccountResolution();
        mockReleaseLedgerNetAmount();
        mockWalletDebit();
        mockIdempotencyExecuteOnce();
        when(razorpayXClient.initiatePayout(eq(CREATOR_ID), any(), any(), anyString()))
                .thenThrow(new RuntimeException("RazorpayX unreachable"));

        assertThrows(
                RuntimeException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        // The debit still posted (money-safety fix, unaffected) ...
        verify(walletLedgerService, times(1))
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // ... and exactly one Payout row was saved: the PENDING row, never confirmed because the
        // gateway call threw before markGatewayConfirmed. This is the durable orphaned-debit
        // record the sweeper relies on.
        ArgumentCaptor<com.influora.domain.entity.Payout> captor =
                ArgumentCaptor.forClass(com.influora.domain.entity.Payout.class);
        verify(payoutRepository, times(1)).save(captor.capture());
        assertEquals(com.influora.domain.entity.Payout.STATUS_PENDING, captor.getValue().getStatus());
    }

    // ------------------------------------------------------------------
    // [P2, SEC: Kabir, landed-money-path audit 1c] Binding assert -- the loaded release-ledger
    // credit must actually belong to THIS milestone's creator/workspace, not just resolve by id.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "queuePayout: rejects with MILESTONE_RELEASE_LEDGER_MISMATCH if the release credit's"
                    + " walletId does not belong to this milestone's creator (cross-creator binding failure)")
    void testRejectsReleaseCreditForWrongCreatorWallet() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        mockReleaseLedgerNetAmount(); // release credit lives on CREATOR_WALLET_ID
        // But the resolved creator wallet is a DIFFERENT wallet id -- simulating a
        // tampered/misdirected releasedTxnId pointing at another user's release credit.
        Wallet otherUsersWallet = Wallet.forUser("01HOTHERWALLET1234567", "01HOTHERCREATOR123456");
        when(walletService.requireOrCreateUserWallet(CREATOR_ID)).thenReturn(otherUsersWallet);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_RELEASE_LEDGER_MISMATCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "queuePayout: rejects with MILESTONE_RELEASE_LEDGER_MISMATCH if the release credit's"
                    + " referenceId points at a different milestone (cross-milestone binding failure)")
    void testRejectsReleaseCreditForWrongMilestone() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        PaymentMilestone milestone = releasedMilestone();
        bindReleased(milestone);
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(releasedHold()));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));
        WalletTransaction releaseCreditForAnotherMilestone =
                WalletTransaction.builder()
                        .id(RELEASED_TXN_ID)
                        .walletId(CREATOR_WALLET_ID)
                        .groupId("01HGROUP1234567890AB")
                        .direction(TxnDirection.CREDIT)
                        .type(WalletTransactionType.ESCROW_RELEASE)
                        .amount(NET_AMOUNT)
                        .currency("INR")
                        .balanceAfter(NET_AMOUNT)
                        .referenceType(TxnReferenceType.MILESTONE)
                        .referenceId("01HSOMEOTHERMILESTONE") // NOT this test's MILESTONE_ID
                        .idempotencyKey("release:" + ESCROW_HOLD_ID + ":C")
                        .build();
        when(walletTransactionRepository.findById(RELEASED_TXN_ID))
                .thenReturn(Optional.of(releaseCreditForAnotherMilestone));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.queuePayout(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("MILESTONE_RELEASE_LEDGER_MISMATCH", ex.getCode());
        verify(razorpayXClient, never()).initiatePayout(any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(any(), any(), any(), any());
    }
}
