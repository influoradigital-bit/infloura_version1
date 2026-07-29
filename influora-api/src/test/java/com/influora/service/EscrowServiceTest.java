package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.InsufficientFundsException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.domain.entity.Contract;
import com.influora.domain.enums.ContractStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.EscrowService.PagedEscrowHolds;
import com.influora.service.escrow.EscrowBackend;
import com.influora.service.escrow.LedgerEscrowBackend;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Task #10 — creator-scoped escrow read isolation (deal room payments tab). */
@ExtendWith(MockitoExtension.class)
class EscrowServiceTest {

    private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String OTHER_CREATOR_USER_ID = "01HCREATOROTHER9999AB";

    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";

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
    @Mock private WorkspaceMember workspaceMember;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;

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
                        escrowBackend);
    }

    private EscrowHold fundedHold() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .collaborationId(COLLAB_ID)
                .campaignId("01HCAMPAIGN1234567AB")
                .amount(BigDecimal.valueOf(5000))
                .currency("INR")
                .status(EscrowStatus.FUNDED)
                .idempotencyKey("idem-1")
                .build();
    }

    @Test
    @DisplayName("getStatusForCreator: creator can read escrow for their own collaboration")
    void testGetStatusForCreatorOwnHold() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        EscrowHold hold = fundedHold();
        when(escrowHoldRepository.findByIdAndCreatorId(ESCROW_HOLD_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(hold));

        EscrowStatusResponse response = service.getStatusForCreator(principal, ESCROW_HOLD_ID);

        assertNotNull(response);
        assertEquals(ESCROW_HOLD_ID, response.escrowHoldId());
        assertEquals(EscrowStatus.FUNDED, response.status());
        verify(creatorContext).requireCreator(principal);
        verify(escrowHoldRepository, never()).findByIdAndWorkspaceId(ESCROW_HOLD_ID, WORKSPACE_ID);
    }

    @Test
    @DisplayName("getStatusForCreator: creator cannot read another creator's escrow hold")
    void testGetStatusForCreatorRejectsCrossCreatorIdor() {
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_USER_ID);
        when(escrowHoldRepository.findByIdAndCreatorId(ESCROW_HOLD_ID, OTHER_CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.getStatusForCreator(principal, ESCROW_HOLD_ID));

        assertEquals("ESCROW_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // ------------------------------------------------------------------------------------------
    // listForWorkspace — GET /wallet/escrow (Vikram, N4)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("listForWorkspace: returns brand-scoped escrow holds with the status-response shape and page meta")
    void listForWorkspaceReturnsPagedHolds() {
        EscrowHold hold = fundedHold();
        Page<EscrowHold> page =
                new PageImpl<>(
                        List.of(hold),
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
                        1);
        when(escrowHoldRepository.findByWorkspaceIdOrderByCreatedAtDesc(eq(WORKSPACE_ID), any()))
                .thenReturn(page);

        PagedEscrowHolds result = service.listForWorkspace(principal, WORKSPACE_ID, 1, 20);

        assertEquals(1, result.items().size());
        assertEquals(ESCROW_HOLD_ID, result.items().get(0).escrowHoldId());
        assertEquals(EscrowStatus.FUNDED, result.items().get(0).status());
        assertEquals(1, result.meta().page());
        assertEquals(20, result.meta().limit());
        assertEquals(1, result.meta().total());
        assertEquals(false, result.meta().hasMore());
        verify(brandContext).requireMember(principal, WORKSPACE_ID);
    }

    @Test
    @DisplayName("listForWorkspace: clamps page < 1 and limit > 100 before querying")
    void listForWorkspaceClampsPageAndLimit() {
        Page<EscrowHold> emptyPage =
                new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(escrowHoldRepository.findByWorkspaceIdOrderByCreatedAtDesc(eq(WORKSPACE_ID), any()))
                .thenReturn(emptyPage);

        PagedEscrowHolds result = service.listForWorkspace(principal, WORKSPACE_ID, 0, 500);

        assertEquals(1, result.meta().page());
        assertEquals(100, result.meta().limit());
    }

    @Test
    @DisplayName("freezeUnreleasedForDispute: marks FUNDED holds FROZEN for collaboration")
    void freezeUnreleasedForDisputeMarksFundedHolds() {
        EscrowHold hold = fundedHold();
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLAB_ID, EscrowStatus.FUNDED))
                .thenReturn(List.of(hold));
        when(milestoneRepository.findByCollaborationId(COLLAB_ID)).thenReturn(List.of());
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

        int frozen = service.freezeUnreleasedForDispute(COLLAB_ID);

        assertEquals(1, frozen);
        assertEquals(EscrowStatus.FROZEN, hold.getStatus());
        verify(escrowHoldRepository).findByIdForUpdate(ESCROW_HOLD_ID);
        verify(escrowHoldRepository).save(hold);
    }

    // ------------------------------------------------------------------------------------------
    // [FIX: escrow-frozen-hold-fix-spec] Reproduction test for the defect the spec fixes: a hold
    // funded through the ORDINARY brand escrow flow carries collaboration_id == NULL (the only
    // caller of EscrowHold#bindCollaboration in the main tree is ConfirmLaunchExecutor's AI-launch
    // path) and is reachable only via its milestone's collaboration_id. Before Fix 1,
    // requireFrozenHoldsForCollaboration (used by adminReleaseForDispute/adminRefundForDispute/
    // adminSplitForDispute) had no milestone fallback — unlike its sibling
    // findFundedHoldsForCollaboration — so it iterated an EMPTY list for exactly this hold shape,
    // and DisputeService.resolveDispute marked the dispute resolved anyway. Money frozen forever,
    // books say otherwise.
    //
    // THIS TEST MUST FAIL IF FIX 1 IS REVERTED: reverting requireFrozenHoldsForCollaboration to
    // its pre-fix body (a bare escrowHoldRepository.findByCollaborationIdAndStatus(collaborationId,
    // FROZEN), no milestone-fallback loop) leaves the direct-query stub below — which deliberately
    // returns empty, exactly as a real database would for a hold whose own collaboration_id column
    // is null — as the ONLY source of holds. adminReleaseForDispute then iterates zero holds,
    // `settlements` comes back empty, and `assertEquals(1, settlements.size())` below fails.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "REPRODUCTION (escrow-frozen-hold-fix-spec): a hold funded the ordinary way (null"
                    + " collaboration_id, linked only via its milestone) is frozen on dispute-open and"
                    + " actually settled on dispute-resolve — must fail if Fix 1 is reverted")
    void adminReleaseForDisputeSettlesOrdinaryFlowHoldWithNullCollaborationId() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .contractId("01HCONTRACT1234567AB")
                        .collaborationId(COLLAB_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .build();

        // The ordinary brand escrow flow (POST /wallet/escrow/fund), pre-Fix-2 shape: no
        // .collaborationId(...) call on the builder at all — reproduces the null column.
        EscrowHold hold =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId("01HCAMPAIGN1234567AB")
                        .milestoneId(MILESTONE_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("idem-ordinary-flow")
                        .build();
        assertNull(hold.getCollaborationId());
        milestone.markFunded(hold.getId());

        // The direct collaboration_id-column queries the pre-fix code relied on exclusively —
        // always empty for this hold, exactly as a real database would return, because the column
        // is null. Fix 1's milestone-fallback loop is the only way this hold is ever found below.
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLAB_ID, EscrowStatus.FUNDED))
                .thenReturn(List.of());
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLAB_ID, EscrowStatus.FROZEN))
                .thenReturn(List.of());
        when(milestoneRepository.findByCollaborationId(COLLAB_ID)).thenReturn(List.of(milestone));
        when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

        // -- Dispute OPENS: freezeUnreleasedForDispute finds the hold via the (already
        // fallback-aware, pre-existing) findFundedHoldsForCollaboration and freezes it.
        int frozen = service.freezeUnreleasedForDispute(COLLAB_ID);
        assertEquals(1, frozen);
        assertEquals(EscrowStatus.FROZEN, hold.getStatus());

        // -- Dispute RESOLVES (RESOLVED_CREATOR): adminReleaseForDispute must find and settle the
        // SAME hold via requireFrozenHoldsForCollaboration — this is the call Fix 1 repairs.
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));

        Wallet clearingWallet = Wallet.forWorkspace("01HCLEARING1234567890", "platform-clearing");
        Wallet payeeWallet = Wallet.forUser("01HPAYEEWALLET1234AB", CREATOR_USER_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
        when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new PlatformFeeService.FeeDeductionResult(
                                BigDecimal.valueOf(5000),
                                1500,
                                BigDecimal.valueOf(750),
                                BigDecimal.valueOf(4250),
                                null));
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(
                                WalletTransaction.builder().id("wtx-dispute-debit").build(),
                                WalletTransaction.builder().id("wtx-dispute-credit").build()));

        List<EscrowStatusResponse> settlements = service.adminReleaseForDispute(COLLAB_ID);

        // This is the assertion that fails if Fix 1 is reverted (see class comment above).
        assertEquals(1, settlements.size());
        assertEquals(EscrowStatus.RELEASED, hold.getStatus());
        // Saved twice total: once by freezeUnreleasedForDispute (FUNDED -> FROZEN), once more by
        // adminReleaseForDispute (FROZEN -> RELEASED) — both real saves, proving the hold was
        // found and mutated at each stage rather than skipped.
        verify(escrowHoldRepository, times(2)).save(hold);
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: Vikram, P4 defensive fix] adminSplitForDispute -- creatorSplitPercent validation
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("adminSplitForDispute: rejects a null creatorSplitPercent before touching escrow/wallets")
    void adminSplitForDisputeRejectsNullPercent() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.adminSplitForDispute(COLLAB_ID, null));

        assertEquals("CREATOR_SPLIT_PERCENT_INVALID", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(collaborationRepository, never()).findById(COLLAB_ID);
        verify(platformWalletService, never()).requireClearingWallet();
    }

    @Test
    @DisplayName("adminSplitForDispute: rejects a negative creatorSplitPercent")
    void adminSplitForDisputeRejectsNegativePercent() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSplitForDispute(COLLAB_ID, BigDecimal.valueOf(-1)));

        assertEquals("CREATOR_SPLIT_PERCENT_INVALID", ex.getCode());
        verify(collaborationRepository, never()).findById(COLLAB_ID);
    }

    @Test
    @DisplayName("adminSplitForDispute: rejects a creatorSplitPercent above 100")
    void adminSplitForDisputeRejectsOver100Percent() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSplitForDispute(COLLAB_ID, BigDecimal.valueOf(100.01)));

        assertEquals("CREATOR_SPLIT_PERCENT_INVALID", ex.getCode());
        verify(collaborationRepository, never()).findById(COLLAB_ID);
    }

    @Test
    @DisplayName("adminSplitForDispute: accepts the boundary values 0 and 100 (validation does not over-reject)")
    void adminSplitForDisputeAcceptsBoundaryValues() {
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        // Boundary values pass validation and proceed to the next real check (collaboration lookup)
        // rather than being rejected as out-of-range -- proves the fix isn't off-by-one.
        ApiException ex0 =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSplitForDispute(COLLAB_ID, BigDecimal.ZERO));
        assertEquals("COLLABORATION_NOT_FOUND", ex0.getCode());

        ApiException ex100 =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSplitForDispute(COLLAB_ID, BigDecimal.valueOf(100)));
        assertEquals("COLLABORATION_NOT_FOUND", ex100.getCode());
    }

    // ------------------------------------------------------------------------------------------
    // [Kavya QA, adjacent to CR-36] adminSplitForDispute: a 0% creatorSplitPercent settlement must
    // end the hold REFUNDED with the REAL refund txn id, not RELEASED with a synthetic
    // "dispute-split:<id>" string that corresponds to no ledger transaction at all. RELEASED means
    // "the creator was paid" everywhere else this status is read (creator-facing payment state,
    // reporting, released-volume analytics).
    // ------------------------------------------------------------------------------------------

    private EscrowHold frozenHoldForSplit() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .collaborationId(COLLAB_ID)
                .campaignId("01HCAMPAIGN1234567AB")
                .amount(BigDecimal.valueOf(5000))
                .currency("INR")
                .status(EscrowStatus.FROZEN)
                .idempotencyKey("idem-split-1")
                .build();
    }

    private void stubSplitLookup(EscrowHold hold, Collaboration collaboration) {
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLAB_ID, EscrowStatus.FROZEN))
                .thenReturn(List.of(hold));
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    }

    @Test
    @DisplayName(
            "adminSplitForDispute: a 0% creatorSplitPercent settlement ends REFUNDED with the real"
                    + " refund txn id -- NOT RELEASED with a synthetic id, since no ESCROW_RELEASE"
                    + " ever posts when the creator's share is zero")
    void adminSplitForDisputeZeroPercentEndsRefundedNotReleased() {
        EscrowHold hold = frozenHoldForSplit();
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        stubSplitLookup(hold, collaboration);
        Wallet clearingWallet = Wallet.forWorkspace("01HCLEARING1234567890", "platform-clearing");
        Wallet brandWallet = Wallet.forWorkspace(WORKSPACE_ID, "brand");
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(
                                WalletTransaction.builder().id("wtx-split-refund-debit").build(),
                                WalletTransaction.builder().id("wtx-split-refund-credit").build()));

        List<EscrowStatusResponse> results = service.adminSplitForDispute(COLLAB_ID, BigDecimal.ZERO);

        assertEquals(1, results.size());
        assertEquals(EscrowStatus.REFUNDED, hold.getStatus());
        assertEquals("wtx-split-refund-credit", hold.getReleaseTxnId());
        // No release leg was ever attempted -- the creator's share was zero.
        verify(platformFeeService, never()).deductAtRelease(any(), any(), any(), any(), any(), any());
        verify(walletService, never()).requireOrCreateUserWallet(any());
    }

    @Test
    @DisplayName(
            "adminSplitForDispute: a normal (non-zero, non-100) split still ends RELEASED with the"
                    + " real release txn id -- proves the 0% fix is narrow, not blanket")
    void adminSplitForDisputeNormalSplitStillEndsReleased() {
        EscrowHold hold = frozenHoldForSplit();
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        stubSplitLookup(hold, collaboration);
        Wallet brandWallet = Wallet.forWorkspace(WORKSPACE_ID, "brand");
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
        stubSuccessfulReleaseLedgerCalls(CREATOR_USER_ID);

        List<EscrowStatusResponse> results =
                service.adminSplitForDispute(COLLAB_ID, BigDecimal.valueOf(60));

        assertEquals(1, results.size());
        assertEquals(EscrowStatus.RELEASED, hold.getStatus());
        assertEquals("wtx-release-credit", hold.getReleaseTxnId());
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: Wave-1 S5] deriveFundAmount -- milestone lookup must be workspace-scoped, not a bare
    // findById, or a caller could pass another workspace's milestoneId and learn its amount.
    // ------------------------------------------------------------------------------------------

    private static final String MILESTONE_ID = "01HMILESTONE1234567AB";

    @Test
    @DisplayName("deriveFundAmount: resolves the amount for a milestone in the caller's OWN workspace")
    void deriveFundAmountResolvesOwnWorkspaceMilestone() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .collaborationId(COLLAB_ID)
                        .amount(BigDecimal.valueOf(7500))
                        .build();
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));

        BigDecimal amount = service.deriveFundAmount(WORKSPACE_ID, "campaign-1", MILESTONE_ID);

        assertEquals(BigDecimal.valueOf(7500), amount);
        verify(milestoneRepository).findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID);
        verify(milestoneRepository, never()).findById(MILESTONE_ID);
    }

    @Test
    @DisplayName("deriveFundAmount: 404s a milestoneId belonging to ANOTHER workspace (cross-tenant IDOR closed)")
    void deriveFundAmountRejectsCrossTenantMilestone() {
        // The workspace-scoped lookup itself is responsible for excluding another tenant's row --
        // simulate that by returning empty, exactly what findByIdAndWorkspaceId does when the
        // milestone exists but belongs to a different workspaceId.
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.deriveFundAmount(WORKSPACE_ID, "campaign-1", MILESTONE_ID));

        assertEquals("MILESTONE_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // Must never fall back to an unscoped lookup that would find the other tenant's row.
        verify(milestoneRepository, never()).findById(MILESTONE_ID);
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: MF-1 follow-up, 2026-07-21] initiateFund -- INSUFFICIENT_FUNDS 402 carries the exact
    // server-computed requiredAmount/walletBalance/shortfallAmount/currency (Ash → Vikram handoff,
    // closes the "no test asserts the new 402 body" gap ahead of Kabir's Option 1 money-path gate).
    // ------------------------------------------------------------------------------------------

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String FUND_IDEMPOTENCY_KEY = "idem-fund-1";

    /** Anonymous-subclass wallet stub -- same pattern as WalletServiceTest#createTestWallet
     * (Wallet has no public setters/builder; its factory methods always zero the balance). */
    private static Wallet walletWithBalance(BigDecimal balance) {
        return new Wallet() {
            @Override
            public BigDecimal getBalance() {
                return balance;
            }

            @Override
            public String getCurrency() {
                return "INR";
            }
        };
    }

    /**
     * [FIX: double-charge, 2026-07-26] initiateFund now funds immediately from the wallet (no
     * Razorpay order) once the balance check passes — stubs the {@code applyFunding} dependencies
     * (clearing wallet + ledger post) that every successful initiateFund call now reaches.
     */
    private void stubWalletFunding() {
        when(platformWalletService.requireClearingWallet())
                .thenReturn(
                        new Wallet() {
                            @Override
                            public String getId() {
                                return "01HCLEARING1234567890";
                            }

                            @Override
                            public String getCurrency() {
                                return "INR";
                            }
                        });
        WalletTransaction debitLeg = WalletTransaction.builder().id("wtx-debit-1").build();
        WalletTransaction creditLeg = WalletTransaction.builder().id("wtx-credit-1").build();
        when(ledgerService.post(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new WalletLedgerService.LedgerPostingResult(debitLeg, creditLeg));
    }

    @Test
    @DisplayName(
            "initiateFund: throws InsufficientFundsException with the exact server-computed"
                    + " requiredAmount/walletBalance/shortfallAmount/currency when wallet balance <"
                    + " required amount")
    void initiateFundThrowsInsufficientFundsWithExactServerFigures() {
        BigDecimal requiredAmount = BigDecimal.valueOf(50000);
        BigDecimal walletBalance = BigDecimal.valueOf(20000);
        Wallet wallet = walletWithBalance(walletBalance);

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(escrowHoldRepository.findByIdempotencyKey(FUND_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);

        InsufficientFundsException ex =
                assertThrows(
                        InsufficientFundsException.class,
                        () ->
                                service.initiateFund(
                                        principal,
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        null,
                                        requiredAmount,
                                        "INR",
                                        FUND_IDEMPOTENCY_KEY));

        assertEquals("INSUFFICIENT_FUNDS", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        // Concrete shortfall example, per handoff: required 50000, balance 20000 -> shortfall 30000.
        assertEquals(0, requiredAmount.compareTo(ex.getRequiredAmount()));
        assertEquals(0, walletBalance.compareTo(ex.getWalletBalance()));
        assertEquals(0, BigDecimal.valueOf(30000).compareTo(ex.getShortfallAmount()));
        assertEquals("INR", ex.getCurrency());
        // The exact figures are the server-derived amount and the same balance read that gates the
        // charge -- shortfall must equal required - balance, never re-fetched or estimated.
        assertEquals(
                0,
                requiredAmount.subtract(walletBalance).compareTo(ex.getShortfallAmount()));

        verify(brandContext).requireRole(workspaceMember, MemberRole.OWNER, MemberRole.ADMIN);
        verify(escrowHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiateFund: wallet balance exactly equal to the required amount does NOT throw INSUFFICIENT_FUNDS")
    void initiateFundBoundaryBalanceEqualsAmountDoesNotThrowInsufficientFunds() {
        BigDecimal requiredAmount = BigDecimal.valueOf(50000);
        Wallet wallet = walletWithBalance(requiredAmount);

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(escrowHoldRepository.findByIdempotencyKey(FUND_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);
        stubWalletFunding();

        service.initiateFund(
                principal, WORKSPACE_ID, CAMPAIGN_ID, null, requiredAmount, "INR", FUND_IDEMPOTENCY_KEY);

        // Saved twice: once creating the PENDING hold, once more after applyFunding marks it
        // FUNDED — both in the same call now that funding happens immediately, no gateway round trip.
        verify(escrowHoldRepository, times(2)).save(any(EscrowHold.class));
    }

    // ------------------------------------------------------------------------------------------
    // [BE-2: Vikram, contract-flow-architecture-2026-07-23 §6.5] initiateFund must refuse to fund
    // a milestone whose governing contract is not yet ACTIVE (both brandSignedAt and
    // creatorSignedAt set). Previously this method had no contract-awareness at all.
    // ------------------------------------------------------------------------------------------

    private static final String CONTRACT_ID = "01HCONTRACT1234567AB";

    private PaymentMilestone milestoneForContract(String contractId) {
        return PaymentMilestone.builder()
                .id(MILESTONE_ID)
                .contractId(contractId)
                .collaborationId(COLLAB_ID)
                .amount(BigDecimal.valueOf(5000))
                .build();
    }

    @Test
    @DisplayName(
            "initiateFund: rejects funding with CONTRACT_NOT_ACTIVE (409) when the milestone's"
                    + " contract is DRAFT (neither party has signed) -- no hold is created")
    void initiateFundRejectsWhenContractNotSigned() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestoneForContract(CONTRACT_ID)));
        Contract unsignedContract =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.DRAFT)
                        .build();
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(unsignedContract));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.initiateFund(
                                        principal,
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        MILESTONE_ID,
                                        BigDecimal.valueOf(5000),
                                        "INR",
                                        FUND_IDEMPOTENCY_KEY));

        assertEquals("CONTRACT_NOT_ACTIVE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(escrowHoldRepository, never()).save(any());
        verify(walletService, never()).requireWorkspaceWallet(any());
    }

    @Test
    @DisplayName(
            "initiateFund: rejects funding with CONTRACT_NOT_ACTIVE (409) when only the BRAND has"
                    + " signed (creatorSignedAt still null) -- half-signed is not enough")
    void initiateFundRejectsWhenOnlyBrandSigned() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestoneForContract(CONTRACT_ID)));
        Contract halfSigned =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .build();
        halfSigned.recordBrandSignature();
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(halfSigned));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.initiateFund(
                                        principal,
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        MILESTONE_ID,
                                        BigDecimal.valueOf(5000),
                                        "INR",
                                        FUND_IDEMPOTENCY_KEY));

        assertEquals("CONTRACT_NOT_ACTIVE", ex.getCode());
        verify(escrowHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "initiateFund: proceeds past the contract gate once the contract is ACTIVE (both"
                    + " brandSignedAt and creatorSignedAt set) -- reaches the wallet/hold creation path")
    void initiateFundProceedsWhenContractActive() {
        BigDecimal requiredAmount = BigDecimal.valueOf(5000);
        Wallet wallet = walletWithBalance(requiredAmount);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestoneForContract(CONTRACT_ID)));
        Contract fullySigned =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(requiredAmount)
                        .build();
        fullySigned.recordBrandSignature();
        fullySigned.recordCreatorSignature();
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(fullySigned));
        // [CR-22a] assertContractActiveForMilestone now also locks + status-checks the
        // collaboration behind the milestone — must resolve to a non-CANCELLED row.
        when(collaborationRepository.findByIdForUpdate(COLLAB_ID))
                .thenReturn(
                        Optional.of(
                                Collaboration.invite(
                                        COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));
        when(escrowHoldRepository.findByIdempotencyKey(FUND_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);
        stubWalletFunding();

        service.initiateFund(
                principal,
                WORKSPACE_ID,
                CAMPAIGN_ID,
                MILESTONE_ID,
                requiredAmount,
                "INR",
                FUND_IDEMPOTENCY_KEY);

        // Saved twice: once creating the PENDING hold, once more after applyFunding marks it
        // FUNDED — both in the same call now that funding happens immediately, no gateway round trip.
        verify(escrowHoldRepository, times(2)).save(any(EscrowHold.class));
    }

    /**
     * CR-22a, Kabir finding #1 — previously {@code assertContractActiveForMilestone} only ever
     * checked the CONTRACT's two signature timestamps, so escrow could be funded for the FIRST
     * time on a CANCELLED collaboration. Contract is fully signed here (so the pre-existing
     * CONTRACT_NOT_ACTIVE gate above would NOT catch this) — the CollaborationStatus check is the
     * only thing standing between this call and a real wallet debit.
     */
    @Test
    @DisplayName(
            "initiateFund: rejects funding with 409 COLLABORATION_CANCELLED when the milestone's"
                    + " collaboration was cancelled, even though the contract is fully signed")
    void initiateFundRejectsCancelledCollaborationEvenWhenContractActive() {
        BigDecimal requiredAmount = BigDecimal.valueOf(5000);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestoneForContract(CONTRACT_ID)));
        Contract fullySigned =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(requiredAmount)
                        .build();
        fullySigned.recordBrandSignature();
        fullySigned.recordCreatorSignature();
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(fullySigned));
        Collaboration cancelled =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        cancelled.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findByIdForUpdate(COLLAB_ID))
                .thenReturn(Optional.of(cancelled));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.initiateFund(
                                        principal,
                                        WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        MILESTONE_ID,
                                        requiredAmount,
                                        "INR",
                                        FUND_IDEMPOTENCY_KEY));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(escrowHoldRepository, never()).save(any());
        verify(walletService, never()).requireWorkspaceWallet(any());
    }

    @Test
    @DisplayName(
            "initiateFund: campaign-level funding (no milestoneId) skips the contract gate entirely"
                    + " -- no contract/milestone lookup, existing behavior preserved")
    void initiateFundSkipsContractGateWhenNoMilestoneId() {
        BigDecimal requiredAmount = BigDecimal.valueOf(5000);
        Wallet wallet = walletWithBalance(requiredAmount);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(escrowHoldRepository.findByIdempotencyKey(FUND_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);
        stubWalletFunding();

        service.initiateFund(
                principal, WORKSPACE_ID, CAMPAIGN_ID, null, requiredAmount, "INR", FUND_IDEMPOTENCY_KEY);

        // Saved twice: once creating the PENDING hold, once more after applyFunding marks it
        // FUNDED — both in the same call now that funding happens immediately, no gateway round trip.
        verify(escrowHoldRepository, times(2)).save(any(EscrowHold.class));
        verify(milestoneRepository, never()).findByIdAndWorkspaceId(any(), any());
        verify(contractRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------------------------------
    // [CR-36 residual, wiki/tech/escrow-cancelled-gate-spec.md] release() must refuse to pay a
    // creator on a CANCELLED collaboration. refund() must NOT be blocked by the same status — that
    // would strand every rupee held against a cancelled collaboration with no path to return it,
    // the CR-35 class of bug. See EscrowService#assertReleaseNotBlockedByCancellation's javadoc.
    // ------------------------------------------------------------------------------------------

    private PaymentMilestone releasableMilestone() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .contractId(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .build();
        milestone.markFunded(ESCROW_HOLD_ID);
        return milestone;
    }

    private void stubSuccessfulReleaseLedgerCalls(String payeeUserId) {
        Wallet clearingWallet = Wallet.forWorkspace("01HCLEARING1234567890", "platform-clearing");
        Wallet payeeWallet = Wallet.forUser("01HPAYEEWALLET1234AB", payeeUserId);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(payeeUserId)).thenReturn(payeeWallet);
        when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new PlatformFeeService.FeeDeductionResult(
                                BigDecimal.valueOf(5000),
                                1500,
                                BigDecimal.valueOf(750),
                                BigDecimal.valueOf(4250),
                                null));
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(
                                WalletTransaction.builder().id("wtx-release-debit").build(),
                                WalletTransaction.builder().id("wtx-release-credit").build()));
    }

    @Test
    @DisplayName(
            "[CR-36 residual] release: refuses to pay the creator with 409 COLLABORATION_CANCELLED"
                    + " when the milestone's collaboration was cancelled -- no money moves")
    void releaseRejectsCancelledCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        PaymentMilestone milestone = releasableMilestone();
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        // [CR-47] The hold now loads (and its ownership is verified) BEFORE the CANCELLED guard runs,
        // so a legitimately-owned FUNDED hold must be stubbed for the guard to be the thing that fires
        // (rather than an ownership rejection). The hold belongs to the caller's own WORKSPACE_ID.
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID))
                .thenReturn(Optional.of(fundedHold()));
        Collaboration cancelled =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        cancelled.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(cancelled));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        // The guard still fires before any money moves: the hold is loaded for the ownership check,
        // but no ledger posting is ever attempted on a cancelled deal.
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "[CR-47] release: a foreign-workspace hold is rejected with 404 ESCROW_NOT_FOUND BEFORE"
                    + " any collaboration state is read -- ownership gates the CANCELLED/DISPUTED"
                    + " guards, closing the cross-tenant status-enumeration oracle")
    void releaseChecksTenantOwnershipBeforeReadingCollaborationState() {
        // Caller is a legitimate OWNER/ADMIN of WORKSPACE_ID (workspace A)...
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        PaymentMilestone milestone = releasableMilestone();
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        // ...but the hold behind this milestone belongs to a DIFFERENT workspace (workspace B).
        EscrowHold foreignHold =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId("01HOTHERWORKSPACE99AB")
                        .collaborationId(COLLAB_ID)
                        .campaignId(CAMPAIGN_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("idem-foreign")
                        .build();
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID))
                .thenReturn(Optional.of(foreignHold));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID));

        // Same code a non-existent hold yields — the caller cannot tell "not yours" from "no such
        // hold", and gets NO signal about workspace B's deal state.
        assertEquals("ESCROW_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // The oracle is closed: workspace B's collaboration was NEVER read, so its CANCELLED/DISPUTED
        // status cannot leak through a distinct error code. Reverting the ownership-first reorder
        // makes this fail — the state guards would run and findById(COLLAB_ID) would be called.
        verify(collaborationRepository, never()).findById(any());
        verify(ledgerService, never())
                .post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "[CR-36 residual regression guard] refund: still succeeds on a CANCELLED collaboration --"
                    + " refund is the remedy for a cancelled deal, not an abuse of one, and must stay"
                    + " exempt from the release-only CANCELLED guard")
    void refundStillSucceedsOnCancelledCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        EscrowHold hold = fundedHold(); // carries collaborationId(COLLAB_ID)
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        Collaboration cancelled =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        cancelled.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(cancelled));
        Wallet clearingWallet = Wallet.forWorkspace("01HCLEARING1234567890", "platform-clearing");
        Wallet brandWallet = Wallet.forWorkspace(WORKSPACE_ID, "brand");
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(
                                WalletTransaction.builder().id("wtx-refund-debit").build(),
                                WalletTransaction.builder().id("wtx-refund-credit").build()));

        EscrowStatusResponse response = service.refund(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

        assertEquals(EscrowStatus.REFUNDED, response.status());
        verify(escrowHoldRepository).save(hold);
    }

    @Test
    @DisplayName(
            "[CR-36 residual] release: still succeeds on a healthy (non-cancelled, non-disputed)"
                    + " collaboration -- proves the new guard is narrow, not blanket")
    void releaseSucceedsOnHealthyCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        PaymentMilestone milestone = releasableMilestone();
        when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
        Collaboration healthy = Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(healthy));
        EscrowHold hold = fundedHold();
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        stubSuccessfulReleaseLedgerCalls(CREATOR_USER_ID);

        EscrowStatusResponse response = service.release(principal, WORKSPACE_ID, MILESTONE_ID);

        assertEquals(EscrowStatus.RELEASED, response.status());
        verify(escrowHoldRepository).save(hold);
    }

    @Test
    @DisplayName(
            "[Kabir gate CR-35 HIGH-1] a hold that left FROZEN between the unlocked read and the row"
                    + " lock is SKIPPED, not paid out a second time")
    void adminReleaseForDisputeSkipsHoldThatLeftFrozenBeforeTheLock() {
        // The TOCTOU this guards. `resolveHoldsForCollaboration` filters status on an UNLOCKED read;
        // by the time `findByIdForUpdate` returns, a concurrent transaction may already have settled
        // the hold. Two objects with the SAME id and different statuses is exactly that window:
        //   - `snapshot` is what the unlocked query saw (still FROZEN)
        //   - `lockedTruth` is what the row actually contains once locked (already RELEASED)
        //
        // Before this guard the code appended `snapshot` and paid it out again. That is not caught
        // by idempotency: with two active disputes the keys differ (`dispute-refund:<id>` vs
        // `dispute-release:<id>`), `markReleased`/`markRefunded` are unguarded setters, and
        // `WalletLedgerService.post` exempts the clearing wallet from the balance check.
        //
        // This window was unreachable before CR-35 — the lookup returned [] for every ordinary-flow
        // hold — which is why the guard ships in the same commit as the fix that made it reachable.
        EscrowHold snapshot =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId("01HCAMPAIGN1234567AB")
                        .collaborationId(COLLAB_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.FROZEN)
                        .idempotencyKey("idem-toctou-snapshot")
                        .build();

        EscrowHold lockedTruth =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId("01HCAMPAIGN1234567AB")
                        .collaborationId(COLLAB_ID)
                        .amount(BigDecimal.valueOf(5000))
                        .currency("INR")
                        .status(EscrowStatus.RELEASED)
                        .idempotencyKey("idem-toctou-locked")
                        .build();

        when(escrowHoldRepository.findByCollaborationIdAndStatus(COLLAB_ID, EscrowStatus.FROZEN))
                .thenReturn(List.of(snapshot));
        when(milestoneRepository.findByCollaborationId(COLLAB_ID)).thenReturn(List.of());
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID))
                .thenReturn(Optional.of(lockedTruth));

        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));

        List<EscrowStatusResponse> settled = service.adminReleaseForDispute(COLLAB_ID);

        // Skipped, not settled. Reverting the post-lock status re-check makes this return 1 and pay
        // an already-released hold a second time.
        assertEquals(0, settled.size()); // an already-released hold must not be settled again
        // And nothing moved: no ledger posting, no second release on the hold.
        verify(escrowHoldRepository, never()).save(any(EscrowHold.class));
        assertEquals(EscrowStatus.RELEASED, lockedTruth.getStatus());
    }
}
