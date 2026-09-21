package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.UserType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MeeraDraftRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deal.DealDtos.CounterRequest;
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * hirepath (2026-09-21) — the ordered work must survive every route that can put an offer on the
 * table, and must never be rewritten into a different content type on the way.
 *
 * <p><b>The defect this pins.</b> Deliverable slots are created in exactly one place, {@code
 * ContractService#materializeDeliverables}, from the {@code deliverables} metadata on the newest
 * proposal card. Two of the four offer forms sent no deliverables at all (the campaign-page Bids
 * counter and the creator's counter form), and {@code doCounter}'s carry-forward could only help
 * when an earlier proposal card existed — which it does not on an application or an invite. So a
 * brand could counter an application, the creator could accept, a contract could be generated and
 * signed, and the creator would open a deal room with zero submission slots and no control
 * anywhere that could create one. Separately, the forms that DID send deliverables sent labels
 * ("TikTok Video") or short codes ("REEL"), none of which matched {@link DeliverableType}, and the
 * generator's {@code catch} turned every one of them into an {@code INSTAGRAM_REEL}.
 *
 * <p><b>What each test locks down.</b>
 *
 * <ul>
 *   <li>{@link #createProposalCarriesEveryOrderedSlotThroughToMaterialization()} — the POST /deals
 *       route (Discover offer modal) produces the right COUNT and the right TYPES, including a
 *       YouTube order that must not come out as a reel.
 *   <li>{@link #counterWithExplicitSlotsCarriesThemThrough()} — the counter route (deal-room
 *       proposal form, Bids counter, creator counter) carries an explicitly stated order.
 *   <li>{@link #counterWithNoSlotsAndNoEarlierCardIsRefused()} — the exact hole: a counter that
 *       states nothing, on a deal with nothing to inherit, is refused at the offer route.
 *   <li>{@link #counterWithNoSlotsInheritsTheOfferOnTheTable()} — and the convenience that makes
 *       that refusal safe: a price-only counter still re-sends the agreed scope.
 *   <li>{@link #createProposalWithNoSlotsIsRefused()} / {@link #unknownTypeIsRefusedNotRewritten()}
 *       / {@link #displayLabelIsNormalizedOntoTheTypeItNames()} — the two DTO-level refusals and
 *       the one normalisation that is not a guess.
 *   <li>{@link #contractGenerationRefusesAZeroSlotContract()} / {@link
 *       #contractGenerationRefusesAnUnknownLegacyType()} — the last line of defence, for rows
 *       written before the offer-route guards existed.
 * </ul>
 *
 * <p><b>Falsification.</b> Each guard was removed in turn and the suite re-run:
 *
 * <ul>
 *   <li>Deleting the empty-list branch of {@code DealService#requireOrderedDeliverables} reds
 *       {@code counterWithNoSlotsAndNoEarlierCardIsRefused} and {@code
 *       createProposalWithNoSlotsIsRefused}.
 *   <li>Restoring {@code ContractService#parseDeliverableType}'s {@code catch ->
 *       DeliverableType.INSTAGRAM_REEL} fallback reds {@code unknownTypeIsRefusedNotRewritten} and
 *       {@code contractGenerationRefusesAnUnknownLegacyType}.
 *   <li>Restoring {@code materializeDeliverables}'s early {@code return} on an empty slot list
 *       reds {@code contractGenerationRefusesAZeroSlotContract}.
 * </ul>
 *
 * Run: mvn -o -f influora-api/pom.xml test -Dtest=HirePathDeliverablesTest
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HirePathDeliverablesTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";
    private static final String DEAL_ID = "01HDEAL12345678901234";

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
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;
    @Mock private DealOfferHistoryRepository dealOfferHistoryRepository;
    @Mock private MeeraDraftRepository meeraDraftRepository;

    @Mock private AuthPrincipal brandPrincipal;
    @Mock private WorkspaceMember workspaceMember;

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
                        new CollaborationReviveService(
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        applicationHistoryService,
                        // Phase B0 dependencies: this suite never calls risksForCreator (DealRiskService).
                        null,
                        dealOfferHistoryRepository,
                        meeraDraftRepository);

        // B0-43: recordOffer locks and checks the collaboration row on every write path.
        DealOfferLedgerFixture.stubOfferLedgerRowLock(collaborationRepository);

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

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static Campaign activeCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("1000"))
                .budgetMax(new BigDecimal("500000"))
                .currency("INR")
                .createdBy(BRAND_USER_ID)
                .build();
    }

    /** Wiring shared by every POST /deals call in this suite. */
    private void stubCreateProposalPath() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
        when(brandContext.requireMember(brandPrincipal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        when(brandPrincipal.getUserType()).thenReturn(UserType.BRAND);

        Campaign campaign = activeCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        CreatorProfile creatorProfile =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Test Creator");
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(creatorProfile));
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(creatorProfile));
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Wiring shared by every POST /deals/{id}/counter call in this suite. */
    private Collaboration stubCounterPath() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
        when(brandContext.requireMember(brandPrincipal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        when(brandPrincipal.getUserType()).thenReturn(UserType.BRAND);

        // An APPLICATION, which is exactly the case with nothing to carry forward: the creator
        // applied, so there is a Collaboration and a price but never a proposal card.
        Collaboration collaboration =
                Collaboration.apply(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, "I would love to work on this", "INR");
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dealMessageRepository.save(any(DealMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.hasEscrowForCollaboration(anyString(), any())).thenReturn(false);
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID)).thenReturn(List.of());
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(idempotencyService.executeOnce(anyString(), eq(WORKSPACE_ID), eq("deal.counter"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });
        return collaboration;
    }

    /** A proposal card carrying exactly this metadata JSON, as the DB would hand it back. */
    private static DealMessage proposalCardWithMetadata(String metadataJson) {
        return DealMessage.create(
                "01HMSG123456789012345",
                DEAL_ID,
                DealMessageKind.proposal,
                BRAND_USER_ID,
                DealSenderType.brand,
                "Here is the offer",
                metadataJson);
    }

    /** Drives ContractService#generate against {@code card} and returns the materialized rows. */
    private List<Deliverable> generateContractAgainst(Collaboration collaboration, DealMessage card) {
        when(brandContext.requireMember(brandPrincipal, WORKSPACE_ID)).thenReturn(workspaceMember);
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        when(collaborationRepository.findById(collaboration.getId())).thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findByIdForUpdate(collaboration.getId()))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), WORKSPACE_ID))
                .thenReturn(Optional.of(activeCampaign()));
        when(contractRepository.existsByCollaborationIdAndStatusNot(eq(collaboration.getId()), any()))
                .thenReturn(false);
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId()))
                .thenReturn(List.of());
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaboration.getId(), DealMessageKind.proposal))
                .thenReturn(Optional.ofNullable(card));
        when(creatorProfileRepository.findByUserId(collaboration.getCreatorId()))
                .thenReturn(
                        Optional.of(CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator")));
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        contractService.generate(
                brandPrincipal,
                WORKSPACE_ID,
                new ContractGenerateRequest(
                        collaboration.getId(),
                        List.of(new MilestoneWriteRequest(1, "Full payment", new BigDecimal("15000"), null))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Deliverable>> saved = ArgumentCaptor.forClass(List.class);
        verify(deliverableRepository).saveAll(saved.capture());
        return saved.getValue();
    }

    /** "2 x YOUTUBE_VIDEO, 3 x INSTAGRAM_STORY" from a materialized row list, in slot order. */
    private static Map<DeliverableType, Integer> countByType(List<Deliverable> rows) {
        Map<DeliverableType, Integer> counts = new LinkedHashMap<>();
        for (Deliverable row : rows) {
            counts.merge(row.getType(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------------------------------
    // Route 1 — POST /deals (Discover offer modal)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "POST /deals: every ordered slot reaches materialization with its OWN type"
                    + " — a YouTube order does not become a reel")
    void createProposalCarriesEveryOrderedSlotThroughToMaterialization() {
        stubCreateProposalPath();

        ArgumentCaptor<Collaboration> collaborationCaptor = ArgumentCaptor.forClass(Collaboration.class);
        when(collaborationRepository.save(collaborationCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        dealService.createProposal(
                brandPrincipal,
                new CreateDealRequest(
                        CAMPAIGN_ID,
                        CREATOR_PROFILE_ID,
                        new BigDecimal("15000"),
                        List.of(
                                new DeliverableSlot("YOUTUBE_VIDEO", 2),
                                new DeliverableSlot("INSTAGRAM_STORY", 3)),
                        null,
                        null,
                        "Let's work together",
                        null));

        Collaboration collaboration = collaborationCaptor.getValue();
        ArgumentCaptor<DealMessage> cardCaptor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository).save(cardCaptor.capture());

        List<Deliverable> rows = generateContractAgainst(collaboration, cardCaptor.getValue());

        assertEquals(5, rows.size(), "2 YouTube videos + 3 Instagram stories = 5 submission slots");
        assertEquals(
                Map.of(DeliverableType.YOUTUBE_VIDEO, 2, DeliverableType.INSTAGRAM_STORY, 3),
                countByType(rows),
                "the brand ordered YouTube videos and Instagram stories — neither may arrive as a reel");
        assertEquals(
                "YouTube Video #1", rows.get(0).getTitle(), "the slot the creator sees names what was ordered");
    }

    @Test
    @DisplayName("POST /deals: an offer with no deliverables is refused before anything is written")
    void createProposalWithNoSlotsIsRefused() {
        stubCreateProposalPath();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                dealService.createProposal(
                                        brandPrincipal,
                                        new CreateDealRequest(
                                                CAMPAIGN_ID,
                                                CREATOR_PROFILE_ID,
                                                new BigDecimal("15000"),
                                                List.of(),
                                                null,
                                                null,
                                                "Let's work together",
                                                null)));

        assertEquals("DELIVERABLES_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(dealMessageRepository, never()).save(any(DealMessage.class));
    }

    @Test
    @DisplayName("POST /deals: a type that names no DeliverableType is a 400, not a silent reel")
    void unknownTypeIsRefusedNotRewritten() {
        stubCreateProposalPath();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                dealService.createProposal(
                                        brandPrincipal,
                                        new CreateDealRequest(
                                                CAMPAIGN_ID,
                                                CREATOR_PROFILE_ID,
                                                new BigDecimal("15000"),
                                                // The Discover modal's old vocabulary. "REEL" names
                                                // no constant: Instagram or Facebook? Guessing it
                                                // is the defect, so it is refused.
                                                List.of(new DeliverableSlot("REEL", 1)),
                                                null,
                                                null,
                                                null,
                                                null)));

        assertEquals("DELIVERABLE_TYPE_UNKNOWN", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(
                ex.getMessage().contains("INSTAGRAM_REEL"),
                "the refusal has to name the values the brand can actually pick: " + ex.getMessage());
        verify(dealMessageRepository, never()).save(any(DealMessage.class));
    }

    @Test
    @DisplayName(
            "POST /deals: a display label is normalized onto the type it NAMES"
                    + " — \"TikTok Video\" is TIKTOK_VIDEO, never a reel")
    void displayLabelIsNormalizedOntoTheTypeItNames() {
        stubCreateProposalPath();

        ArgumentCaptor<Collaboration> collaborationCaptor = ArgumentCaptor.forClass(Collaboration.class);
        when(collaborationRepository.save(collaborationCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        dealService.createProposal(
                brandPrincipal,
                new CreateDealRequest(
                        CAMPAIGN_ID,
                        CREATOR_PROFILE_ID,
                        new BigDecimal("15000"),
                        List.of(new DeliverableSlot("TikTok Video", 1)),
                        null,
                        null,
                        null,
                        null));

        ArgumentCaptor<DealMessage> cardCaptor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository).save(cardCaptor.capture());
        assertTrue(
                cardCaptor.getValue().getMetadataJson().contains("TIKTOK_VIDEO"),
                "the card must persist the canonical name, not the label: "
                        + cardCaptor.getValue().getMetadataJson());

        List<Deliverable> rows =
                generateContractAgainst(collaborationCaptor.getValue(), cardCaptor.getValue());
        assertEquals(Map.of(DeliverableType.TIKTOK_VIDEO, 1), countByType(rows));
    }

    // ------------------------------------------------------------------------------------------
    // Route 2 — POST /deals/{id}/counter (deal-room proposal form, Bids counter, creator counter)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /deals/{id}/counter: an explicitly ordered list reaches materialization intact")
    void counterWithExplicitSlotsCarriesThemThrough() {
        Collaboration collaboration = stubCounterPath();

        dealService.counter(
                brandPrincipal,
                DEAL_ID,
                new CounterRequest(
                        new BigDecimal("25000"),
                        "Here is what we need",
                        List.of(
                                new DeliverableSlot("YOUTUBE_SHORT", 1),
                                new DeliverableSlot("INSTAGRAM_REEL", 2)),
                        null,
                        null,
                        null, null),
                null);

        ArgumentCaptor<DealMessage> cardCaptor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository).save(cardCaptor.capture());

        List<Deliverable> rows = generateContractAgainst(collaboration, cardCaptor.getValue());
        assertEquals(3, rows.size());
        assertEquals(
                Map.of(DeliverableType.YOUTUBE_SHORT, 1, DeliverableType.INSTAGRAM_REEL, 2),
                countByType(rows));
    }

    @Test
    @DisplayName(
            "POST /deals/{id}/counter: countering an APPLICATION with no deliverables is refused"
                    + " — this is the silent loss that used to reach the contract")
    void counterWithNoSlotsAndNoEarlierCardIsRefused() {
        stubCounterPath();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                dealService.counter(
                                        brandPrincipal,
                                        DEAL_ID,
                                        new CounterRequest(
                                                new BigDecimal("25000"), "Counter offer", null, null, null, null, null),
                                        null));

        assertEquals("DELIVERABLES_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(dealMessageRepository, never()).save(any(DealMessage.class));
    }

    @Test
    @DisplayName(
            "POST /deals/{id}/counter: a price-only counter re-sends the scope already on the table")
    void counterWithNoSlotsInheritsTheOfferOnTheTable() {
        Collaboration collaboration = stubCounterPath();

        DealMessage standingOffer =
                proposalCardWithMetadata(
                        "{\"amount\":30000,\"status\":\"pending\",\"deliverables\":"
                                + "[{\"type\":\"YOUTUBE_VIDEO\",\"qty\":2}]}");
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.of(standingOffer));

        dealService.counter(
                brandPrincipal,
                DEAL_ID,
                new CounterRequest(new BigDecimal("25000"), "Same work, lower price", null, null, null, null, null),
                null);

        // Two saves: the superseded card (settled to "countered") and the new offer. The new one
        // is the last, and it must carry the SAME order rather than an empty list.
        ArgumentCaptor<DealMessage> cardCaptor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository, org.mockito.Mockito.atLeastOnce()).save(cardCaptor.capture());
        List<DealMessage> saves = new ArrayList<>(cardCaptor.getAllValues());
        DealMessage newOffer = saves.get(saves.size() - 1);

        assertTrue(
                newOffer.getMetadataJson().contains("YOUTUBE_VIDEO"),
                "the inherited order must survive a price-only counter: " + newOffer.getMetadataJson());

        List<Deliverable> rows = generateContractAgainst(collaboration, newOffer);
        assertEquals(Map.of(DeliverableType.YOUTUBE_VIDEO, 2), countByType(rows));
    }

    // ------------------------------------------------------------------------------------------
    // Last line of defence — contract generation itself
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /contracts: a contract that would order nothing is refused, not written empty")
    void contractGenerationRefusesAZeroSlotContract() {
        Collaboration collaboration =
                Collaboration.apply(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, "note", "INR");
        DealMessage cardWithoutSlots =
                proposalCardWithMetadata("{\"amount\":30000,\"status\":\"accepted\"}");

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> generateContractAgainst(collaboration, cardWithoutSlots));

        assertEquals("DELIVERABLES_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(deliverableRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName(
            "POST /contracts: a legacy card whose type names nothing is refused"
                    + " — it is NOT quietly materialized as an Instagram Reel")
    void contractGenerationRefusesAnUnknownLegacyType() {
        Collaboration collaboration =
                Collaboration.apply(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, "note", "INR");
        DealMessage legacyCard =
                proposalCardWithMetadata(
                        "{\"amount\":30000,\"status\":\"accepted\",\"deliverables\":"
                                + "[{\"type\":\"Blog Post\",\"qty\":1}]}");

        ApiException ex =
                assertThrows(ApiException.class, () -> generateContractAgainst(collaboration, legacyCard));

        assertEquals("DELIVERABLE_TYPE_UNKNOWN", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(deliverableRepository, never()).saveAll(any());
    }
}
