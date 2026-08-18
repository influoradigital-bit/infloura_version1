package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.DealMessageKind;
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
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * CR-51 step 1 — revert-proof regression test for the deliverable-materialization key mismatch.
 *
 * <p>{@code DealService#persistProposalMessage} has always written the agreed slots under
 * metadata key {@code "deliverables"} (see the {@code metadata.put("deliverables", deliverables)}
 * call). {@code ContractService#latestAgreedDeliverableSlots} read them back under {@code
 * "deliverableSlots"} — a key nothing ever wrote — so {@code materializeDeliverables} always saw
 * an empty slot list and never persisted a single {@link Deliverable} row for any contract ever
 * generated. This drives the exact chain Priya's plan (CR-51) prescribes: build a real proposal
 * through {@link DealService}, generate a contract through {@link ContractService} against that
 * SAME persisted proposal metadata, and assert the materialized row count is non-zero.
 *
 * <p>Revert-proof: reverting {@code ContractService}'s read key back to {@code "deliverableSlots"}
 * makes {@link #testProposalDeliverablesMaterializeAsContractDeliverables} fail (zero rows
 * materialized) without touching this file.
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceDeliverableMaterializationTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";

    // Shared across both services under test, exactly like the live DealService/ContractService
    // beans share one Spring-managed repository instance for each of these.
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;

    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private ContractPdfService contractPdfService;
    @Mock private R2StorageService r2StorageService;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;

    @Mock private AuthPrincipal brandPrincipal;
    @Mock private WorkspaceMember workspaceMember;

    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private DealService dealService;
    private ContractService contractService;

    @BeforeEach
    void setUp() {
        dealService =
                new DealService(
                        collaborationRepository,
                        dealMessageRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        contractRepository,
                        escrowHoldRepository,
                        deliverableRepository,
                        creatorContext,
                        brandContext,
                        idempotencyService,
                        eventPublisher,
                        messageStreamRegistry,
                        // F-0225 — this suite never exercises the revive path; wired only to satisfy
                        // the constructor, with the same repositories the harness already mocks.
                        new CollaborationReviveService(
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        applicationHistoryService);

        contractService =
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
                        collaborationLifecycleService,
                        applicationHistoryService);
    }

    private static Campaign activeCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .createdBy(BRAND_USER_ID)
                .build();
    }

    /**
     * Builds a real proposal via {@link DealService#createProposal} with a structured
     * {@code deliverables:[{type:REEL,qty:1}]} slot, generates a contract via {@link
     * ContractService#generate} against that SAME persisted proposal, and asserts at least one
     * {@link Deliverable} row was materialized.
     */
    @Test
    @DisplayName(
            "CR-51: a proposal's deliverables:[{type:REEL,qty:1}] materializes >=1 Deliverable row"
                    + " on contract generation")
    void testProposalDeliverablesMaterializeAsContractDeliverables() {
        // --- createProposal (DealService) -----------------------------------------------------
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
        when(brandContext.requireMember(brandPrincipal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);

        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        CreatorProfile creatorProfile =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Test Creator");
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile));
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(Optional.of(creatorProfile));

        // F-0225 — createProposal's duplicate guard moved to CollaborationReviveService, which
        // resolves the prior row rather than asking whether one exists. Empty = no prior deal.
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Collaboration> collaborationCaptor = ArgumentCaptor.forClass(Collaboration.class);
        when(collaborationRepository.save(collaborationCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateDealRequest proposalRequest =
                new CreateDealRequest(
                        CAMPAIGN_ID,
                        CREATOR_PROFILE_ID,
                        new BigDecimal("15000"),
                        List.of(new DeliverableSlot("REEL", 1)),
                        null,
                        null,
                        "Let's work together");

        dealService.createProposal(brandPrincipal, proposalRequest);

        Collaboration collaboration = collaborationCaptor.getValue();

        ArgumentCaptor<DealMessage> proposalMessageCaptor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository).save(proposalMessageCaptor.capture());
        DealMessage persistedProposal = proposalMessageCaptor.getValue();

        // Sanity check on the writer side: the persisted metadata really does carry the
        // deliverables under the "deliverables" key (DealService.persistProposalMessage), the
        // same shape Priya's plan verified live against 47ad258.
        assertEquals(DealMessageKind.proposal, persistedProposal.getKind());
        String metadataJson = persistedProposal.getMetadataJson();
        assertTrue(
                metadataJson != null && metadataJson.contains("\"deliverables\"") && metadataJson.contains("REEL"),
                "expected proposal metadata to carry the deliverables under the \"deliverables\" key: "
                        + metadataJson);

        // --- generate (ContractService) --------------------------------------------------------
        when(collaborationRepository.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(collaboration.getId()))
                .thenReturn(Optional.of(collaboration));
        when(contractRepository.existsByCollaborationIdAndStatusNot(eq(collaboration.getId()), any()))
                .thenReturn(false);
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId()))
                .thenReturn(List.of());
        // This is the exact read the DealService write above must line up with — the whole point
        // of this test.
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaboration.getId(), DealMessageKind.proposal))
                .thenReturn(Optional.of(persistedProposal));
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        ContractGenerateRequest generateRequest =
                new ContractGenerateRequest(
                        collaboration.getId(),
                        List.of(new MilestoneWriteRequest(1, "Full payment", new BigDecimal("15000"), null)));

        contractService.generate(brandPrincipal, WORKSPACE_ID, generateRequest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Deliverable>> savedDeliverables = ArgumentCaptor.forClass(List.class);
        verify(deliverableRepository).saveAll(savedDeliverables.capture());
        List<Deliverable> materialized = savedDeliverables.getValue();

        assertFalse(
                materialized.isEmpty(),
                "expected >=1 Deliverable row materialized from the proposal's REEL slot — got zero,"
                        + " which is exactly the CR-51 bug (reader/writer metadata key mismatch)");
        assertEquals(collaboration.getId(), materialized.get(0).getCollaborationId());
        assertEquals(CREATOR_PROFILE_ID, materialized.get(0).getCreatorProfileId());
    }
}
