package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.InsufficientFundsException;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.integration.razorpay.RazorpayClient;
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
    @Mock private RazorpayClient razorpayClient;
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
        service =
                new EscrowService(
                        escrowHoldRepository,
                        milestoneRepository,
                        campaignRepository,
                        collaborationRepository,
                        contractRepository,
                        disputeRepository,
                        ledgerService,
                        platformWalletService,
                        platformFeeService,
                        walletService,
                        brandContext,
                        creatorContext,
                        razorpayClient,
                        campaignServiceInvoiceService,
                        deliverableRepository,
                        workspaceRepository,
                        eventPublisher,
                        collaborationLifecycleService);
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
        when(razorpayClient.createOrder(eq(requiredAmount), eq("INR"), any()))
                .thenReturn(new RazorpayClient.OrderResult("order-1", "created"));

        service.initiateFund(
                principal, WORKSPACE_ID, CAMPAIGN_ID, null, requiredAmount, "INR", FUND_IDEMPOTENCY_KEY);

        verify(escrowHoldRepository).save(any(EscrowHold.class));
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
        when(escrowHoldRepository.findByIdempotencyKey(FUND_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet);
        when(razorpayClient.createOrder(eq(requiredAmount), eq("INR"), any()))
                .thenReturn(new RazorpayClient.OrderResult("order-2", "created"));

        service.initiateFund(
                principal,
                WORKSPACE_ID,
                CAMPAIGN_ID,
                MILESTONE_ID,
                requiredAmount,
                "INR",
                FUND_IDEMPOTENCY_KEY);

        verify(escrowHoldRepository).save(any(EscrowHold.class));
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
        when(razorpayClient.createOrder(eq(requiredAmount), eq("INR"), any()))
                .thenReturn(new RazorpayClient.OrderResult("order-3", "created"));

        service.initiateFund(
                principal, WORKSPACE_ID, CAMPAIGN_ID, null, requiredAmount, "INR", FUND_IDEMPOTENCY_KEY);

        verify(escrowHoldRepository).save(any(EscrowHold.class));
        verify(milestoneRepository, never()).findByIdAndWorkspaceId(any(), any());
        verify(contractRepository, never()).findById(any());
    }
}
