package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T-CREATORCONNECT-0902 Q6.2 (High) regression pin — {@code DealService} half.
 *
 * <p>{@link DealService#list} must never surface a collaboration whose campaign is still {@code
 * DRAFT} to the CREATOR side, even for a row that predates {@code CreatorDiscoveryService#invite}'s
 * own DRAFT guard (see that guard's own Mockito pin in {@code
 * CreatorDiscoveryServiceInviteDraftGuardTest}) or that was created some other way — {@link
 * DealService#excludeDraftCampaignCollaborations} is the backstop. Deliberately sets up ONLY a
 * DRAFT-linked collaboration so the filtered list is empty and {@code toDealResponse}'s heavy
 * mapping (deliverables/contracts/escrow/messages) is never reached — asserted explicitly below via
 * {@code verify(deliverableRepository, never())...} so this test cannot pass by accident if the
 * exclusion silently stopped short-circuiting.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceCreatorDraftExclusionTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGNDRAFTEXCL01";
    private static final String COLLAB_ID = "01HCOLLABDRAFTEXCL0001";
    private static final String CREATOR_PROFILE_ID = "01HCREATORDRAFTEXCL001";
    private static final String CREATOR_USER_ID = "01HCREATORUSERDRAFTEX1";

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
    @Mock private CollaborationReviveService collaborationReviveService;
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private AuthPrincipal creatorPrincipal;

    private DealService service;

    @BeforeEach
    void setUp() {
        service =
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
                        collaborationReviveService,
                        applicationHistoryService);
    }

    @Test
    @DisplayName(
            "list(): a creator's collaboration tied to a still-DRAFT campaign is dropped entirely —"
                    + " never reaches deal-response mapping (Q6.2)")
    void list_creatorRole_dropsCollaborationTiedToDraftCampaign() {
        when(creatorPrincipal.getUserType()).thenReturn(UserType.CREATOR);
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator");
        when(creatorContext.requireCreatorProfile(creatorPrincipal)).thenReturn(profile);

        Collaboration draftLinked =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, "hello", "INR");
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID)).thenReturn(List.of(draftLinked));

        Campaign draftCampaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId("01HWORKSPACEDRAFTEXCL1")
                        .title("Unpublished Campaign")
                        .status(CampaignStatus.DRAFT)
                        .currency("INR")
                        .createdBy("brand_user_1")
                        .build();
        when(campaignRepository.findAllById(Set.of(CAMPAIGN_ID))).thenReturn(List.of(draftCampaign));

        List<DealResponse> result = service.list(creatorPrincipal, null);

        assertTrue(
                result.isEmpty(),
                "a collaboration whose campaign is still DRAFT must never reach the creator's deal"
                        + " list");
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(anyString());
        verify(contractRepository, never()).findByCollaborationIdOrderByVersionDescCreatedAtDesc(anyString());
    }
}
