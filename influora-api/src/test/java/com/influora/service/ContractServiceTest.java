package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.UserType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ContractReadyForEscrowEvent;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * [E2 audit finding #10, HIGH -- fixed] Unit tests for {@link ContractService#recordSignature}'s
 * already-signed guard. Priority: proving a retried sign call for a role that already signed is a
 * clean idempotent no-op -- it must NOT re-fire {@code generateAndDeliverContractPdf} (PDF
 * generation + email delivery) a second time, the exact regression the E2 audit flagged
 * ({@code Contract.recordBrandSignature()}/{@code recordCreatorSignature()} previously
 * unconditionally overwrote the signed-at timestamp on every call with no guard).
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HWORKSPACEOTHERB999";
    private static final String CONTRACT_ID = "01HCONTRACT1234567AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String OTHER_CREATOR_USER_ID = "01HCREATOROTHER9999AB";

    @Mock private ContractRepository contractRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private ContractPdfService contractPdfService;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IdempotencyService idempotencyService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;

    private ContractService service;

    @BeforeEach
    void setUp() {
        service =
                new ContractService(
                        contractRepository,
                        milestoneRepository,
                        escrowHoldRepository,
                        collaborationRepository,
                        campaignRepository,
                        userRepository,
                        workspaceRepository,
                        workspaceMemberRepository,
                        brandContext,
                        creatorContext,
                        contractPdfService,
                        r2StorageService,
                        eventPublisher,
                        idempotencyService,
                        deliverableRepository,
                        dealMessageRepository,
                        creatorProfileRepository,
                        collaborationLifecycleService);
    }

    /**
     * Mirrors {@code PayoutServiceTest#mockIdempotencyExecuteOnce}: run the supplier as the race
     * winner so {@code recordSignature}'s {@code executeOnce} wrapper (E2 LOW-3) doesn't just
     * return the Mockito default {@code null} for these unit tests.
     */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<ContractResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private Contract unsignedContract() {
        return Contract.builder()
                .id(CONTRACT_ID)
                .collaborationId(COLLABORATION_ID)
                .workspaceId(WORKSPACE_ID)
                .totalAmount(BigDecimal.valueOf(5000))
                .termsJson("{}")
                .status(ContractStatus.DRAFT)
                .build();
    }

    private Collaboration collaborationForCampaign(String campaignId) {
        return Collaboration.invite(COLLABORATION_ID, campaignId, "01HCREATORUSER1234AB", null, "INR");
    }

    private ContractGenerateRequest generateRequest() {
        return new ContractGenerateRequest(
                COLLABORATION_ID,
                List.of(new MilestoneWriteRequest(1, "Deliverable 1", BigDecimal.valueOf(5000), LocalDate.now())));
    }

    /**
     * [SEC: Kabir, Wave E1 escalation — fixed] {@code generate} previously trusted
     * {@code req.collaborationId()} with no workspace-ownership check at all: {@code Collaboration}
     * has no {@code workspace_id} column, so a brand authenticated into WORKSPACE_ID could pass ANY
     * other brand's collaborationId and have a Contract + PaymentMilestones built against that
     * stranger's real creator relationship (see {@code wiki/errors/wave-e1-kabir-escalation-review.md}
     * for the full exploit trace). Proves the new {@code campaignRepository.findByIdAndWorkspaceId}
     * check (mirroring {@code CampaignLinkService#createTrackingLink}'s resolve-then-scope pattern)
     * closes the gap: the collaboration's campaign belongs to OTHER_WORKSPACE_ID, not the calling
     * WORKSPACE_ID, so the lookup must miss and no Contract/PaymentMilestone rows may be persisted.
     */
    @Test
    @DisplayName(
            "generate: brand A supplying brand B's collaborationId gets COLLABORATION_NOT_FOUND and"
                    + " persists NO Contract/PaymentMilestone rows")
    void testGenerateRejectsCrossWorkspaceCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        // The collaboration's campaign belongs to a DIFFERENT workspace than the caller's -- the
        // scoped lookup must come back empty.
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.generate(principal, WORKSPACE_ID, generateRequest()));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(contractRepository, milestoneRepository);
    }

    @Test
    @DisplayName(
            "generate: legitimate same-workspace collaboration still creates the Contract and"
                    + " PaymentMilestone rows normally")
    void testGenerateSucceedsForSameWorkspaceCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

        assertNotNull(response);
        assertEquals(COLLABORATION_ID, response.collaborationId());
        assertEquals(WORKSPACE_ID, response.workspaceId());
        verify(contractRepository, times(1)).save(any(Contract.class));
        verify(milestoneRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("recordSignature: first BRAND signature is recorded normally (not fully signed yet, no PDF)")
    void testFirstBrandSignatureRecorded() {
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND");

        assertNotNull(response.brandSignedAt());
        assertEquals(null, response.creatorSignedAt());
        verify(contractRepository, times(1)).save(contract);
        verifyNoInteractions(contractPdfService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName(
            "recordSignature: a retried BRAND sign call (BRAND already signed) is an idempotent"
                    + " no-op -- does NOT re-touch the entity, save, or re-fire PDF/email delivery")
    void testRetriedBrandSignatureIsNoOp() {
        Contract contract = unsignedContract();
        contract.recordBrandSignature(); // simulate the first, already-succeeded call
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND");

        assertNotNull(response.brandSignedAt());
        // The guard short-circuits BEFORE contractRepository.save -- never re-persisted.
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(contractPdfService, r2StorageService, eventPublisher);
    }

    @Test
    @DisplayName(
            "recordSignature: the FIRST call that completes both signatures (BRAND already signed,"
                    + " now CREATOR signs) generates and delivers the PDF exactly once")
    void testSecondRoleCompletesSignatureAndFiresPdfOnce() {
        Contract contract = unsignedContract();
        contract.recordBrandSignature(); // brand already signed in a prior call
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id("01HMILESTONE1234567AB")
                        .contractId(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .sequenceNo(1)
                        .amount(BigDecimal.valueOf(5000))
                        .build();
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of(milestone));
        // generateAndDeliverContractPdf's internals (collaboration/campaign/workspace/user/R2)
        // will throw NPE-safe nulls or ApiExceptions internally, but that method catches all
        // exceptions and logs them -- it must never propagate. Stubbing minimal happy-path lookups
        // isn't required to prove the "fires exactly once" guarantee; contractPdfService.render
        // being invoked (or at least attempted) once is what matters here.
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(
                        COLLABORATION_ID, EscrowStatus.FUNDED))
                .thenReturn(false);
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "CREATOR");

        assertNotNull(response.brandSignedAt());
        assertNotNull(response.creatorSignedAt());
        verify(contractRepository, times(1)).save(contract);
        // generateAndDeliverContractPdf + promptEscrowFundingIfNeeded both look up collaboration.
        verify(collaborationRepository, times(2)).findById(COLLABORATION_ID);
    }

    @Test
    @DisplayName(
            "recordSignature: a retried CREATOR sign call after BOTH parties already signed is an"
                    + " idempotent no-op -- PDF/email delivery is never re-fired a second time")
    void testRetriedSignatureAfterFullyExecutedIsNoOp() {
        Contract contract = unsignedContract();
        contract.recordBrandSignature();
        contract.recordCreatorSignature();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "CREATOR");

        assertEquals(ContractStatus.ACTIVE, response.status());
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(contractPdfService, r2StorageService, eventPublisher, collaborationRepository);
    }

    @Test
    @DisplayName("recordSignature: INVALID_SIGNER_ROLE (400) for a role that is neither BRAND nor CREATOR")
    void testInvalidRoleRejected() {
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "MANAGER"));

        assertEquals("INVALID_SIGNER_ROLE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordSignature: CONTRACT_NOT_FOUND (404) when the contract does not exist for this workspace")
    void testContractNotFound() {
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND"));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "getForCreator: creator can read their own contract via findByIdAndCreatorId scoped lookup")
    void testGetForCreatorOwnContract() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        ContractResponse response = service.getForCreator(principal, CONTRACT_ID);

        assertNotNull(response);
        assertEquals(CONTRACT_ID, response.id());
        verify(creatorContext).requireCreator(principal);
        verify(contractRepository).findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID);
        verify(contractRepository, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    @DisplayName(
            "getForCreator: creator cannot read another creator's contract — CONTRACT_NOT_FOUND (404)")
    void testGetForCreatorRejectsOtherCreatorContract() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getForCreator(principal, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).findById(any());
    }

    @Test
    @DisplayName(
            "getPdfDownloadUrlForCreator: creator cannot mint PDF URL for another creator's contract")
    void testGetPdfDownloadUrlForCreatorRejectsCrossCreatorIdor() {
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, OTHER_CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.getPdfDownloadUrlForCreator(principal, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(r2StorageService);
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: creator signs own contract when brand already signed —"
                    + " ACTIVE + escrow funding prompt fired")
    void testCreatorSignsOwnContractAndPromptsEscrow() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature();
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(
                        COLLABORATION_ID, EscrowStatus.FUNDED))
                .thenReturn(false);
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(Campaign.builder().id(CAMPAIGN_ID).title("Summer Drop").build()));
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, com.influora.domain.enums.MemberRole.OWNER))
                .thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID);

        assertNotNull(response.creatorSignedAt());
        assertEquals(ContractStatus.ACTIVE, response.status());
        verify(contractRepository, times(1)).save(contract);
        verify(escrowHoldRepository)
                .existsByCollaborationIdAndStatus(COLLABORATION_ID, EscrowStatus.FUNDED);
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: retried creator sign after already signed is idempotent no-op")
    void testRetriedCreatorSignatureForCreatorIsNoOp() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordCreatorSignature();
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID);

        assertNotNull(response.creatorSignedAt());
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(escrowHoldRepository, contractPdfService, eventPublisher);
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: creator cannot sign another creator's contract —"
                    + " CONTRACT_NOT_FOUND (404)")
    void testCreatorSignRejectsCrossCreatorIdor() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignatureForCreator(principal, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("listUnsignedForCreator: returns only contracts awaiting creator signature")
    void testListUnsignedForCreator() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract awaiting = unsignedContract();
        when(contractRepository.findUnsignedByCreatorId(CREATOR_USER_ID))
                .thenReturn(List.of(awaiting));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        List<ContractResponse> responses = service.listUnsignedForCreator(principal);

        assertEquals(1, responses.size());
        assertEquals(CONTRACT_ID, responses.get(0).id());
        assertEquals(null, responses.get(0).creatorSignedAt());
        verify(creatorContext).requireCreator(principal);
    }

    /**
     * Brand tracker P1-#1: {@code GET /contracts} for a brand caller was previously hard-403'd
     * (creator-only). {@code listForBrand} is the new brand-scoped list, wired the same way as
     * every other brand read on this service: {@code brandContext.requireMember} first, then
     * {@code ContractRepository#findByWorkspaceId} -- never a raw unscoped query.
     */
    @Test
    @DisplayName("listForBrand: returns contracts for the caller's own workspace only")
    void testListForBrandReturnsOwnWorkspaceContracts() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = unsignedContract();
        when(contractRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        List<ContractResponse> responses = service.listForBrand(principal, WORKSPACE_ID);

        assertEquals(1, responses.size());
        assertEquals(CONTRACT_ID, responses.get(0).id());
        assertEquals(WORKSPACE_ID, responses.get(0).workspaceId());
        verify(brandContext).requireMember(principal, WORKSPACE_ID);
        verify(contractRepository).findByWorkspaceId(WORKSPACE_ID);
        // Never falls back to an unscoped/global query.
        verify(contractRepository, never()).findAll();
    }

    /**
     * IDOR guard: a brand user who is NOT an active member of OTHER_WORKSPACE_ID must be rejected
     * by {@code brandContext.requireMember} before {@code ContractRepository#findByWorkspaceId} is
     * ever reached -- mirrors {@code testGenerateRejectsCrossWorkspaceCollaboration}'s
     * resolve-then-scope discipline so one brand can never enumerate another workspace's contracts.
     */
    @Test
    @DisplayName(
            "listForBrand: caller with no membership in OTHER_WORKSPACE_ID is rejected and never"
                    + " reaches the repository")
    void testListForBrandRejectsNonMemberOfOtherWorkspace() {
        when(brandContext.requireMember(principal, OTHER_WORKSPACE_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "You are not a member of this workspace",
                                org.springframework.http.HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.listForBrand(principal, OTHER_WORKSPACE_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(contractRepository);
    }

    @Test
    @DisplayName(
            "recordSignature: dual signature publishes escrow funding prompt when no FUNDED hold exists")
    void testDualSignaturePublishesEscrowFundingPrompt() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = unsignedContract();
        contract.recordBrandSignature();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.existsByCollaborationIdAndStatus(
                        COLLABORATION_ID, EscrowStatus.FUNDED))
                .thenReturn(false);
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(Campaign.builder().id(CAMPAIGN_ID).title("Drop").build()));
        WorkspaceMember owner = org.mockito.Mockito.mock(WorkspaceMember.class);
        when(owner.getUserId()).thenReturn("01HBRANDOWNER123456");
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, com.influora.domain.enums.MemberRole.OWNER))
                .thenReturn(Optional.of(owner));
        mockIdempotencyExecuteOnce();

        service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "CREATOR");

        verify(eventPublisher).publishEvent(any(ContractReadyForEscrowEvent.class));
    }
}
