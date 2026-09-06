package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
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
import org.springframework.http.HttpStatus;

/**
 * F-0400 (CampaignService.java assignment task) — VERIFICATION, not a fix.
 *
 * <p>Per the assignment's instruction to read current code first: {@code maxCollaborators}
 * enforcement already exists on this branch, added by a prior session:
 *
 * <ul>
 *   <li><b>Write edge</b> — {@code CampaignService.validateMaxCollaborators} (called from both
 *       {@code create} and {@code update}) rejects a non-positive cap on write, so a stored cap
 *       can no longer be silently disabled by writing {@code 0}.
 *   <li><b>Read/accept edge</b> — {@code DealService.requireWithinCollaboratorCap}, called from
 *       {@code doAccept} right before the {@code TERMS_AGREED} transition, is what actually
 *       rejects a creator once the campaign's committed-collaborator count would exceed {@code
 *       maxCollaborators}. This is the edge that makes the cap real: {@code CampaignService}
 *       itself never adds a collaborator, so it has no count to enforce.
 * </ul>
 *
 * <p>Because both halves already exist, no change was made to {@code CampaignService.java} for
 * F-0400. This class proves the claim instead of just asserting it: it drives {@code
 * DealService.accept} — the real, public accept path — through Mockito exactly like {@code
 * DealServiceBudgetTest} (the sibling gate for the cumulative-budget check) does, and shows the
 * N+1th creator is rejected with {@code MAX_COLLABORATORS_REACHED}/409 while the Nth (at the cap)
 * still succeeds.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceCollaboratorCapVerificationTest {

    private static final String DEAL_ID = "01HDEAL00000000000001";
    private static final String OTHER_DEAL_ID_1 = "01HDEAL00000000000002";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String OTHER_CREATOR_USER_ID_1 = "01HCREATOROTHER123451";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";

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
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private AuthPrincipal brandPrincipal;

    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;

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
                        new CollaborationReviveService(
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        applicationHistoryService);
    }

    /** Campaign capped at exactly 1 collaborator, budgetMax high enough to never trip the budget gate. */
    private static Campaign campaignCappedAt(int maxCollaborators) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Capped Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("1000000"))
                .currency("INR")
                .createdBy(BRAND_USER_ID)
                .maxCollaborators(maxCollaborators)
                .build();
    }

    private void stubBrandWorkspace() {
        Workspace workspace =
                Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandPrincipal.getUserType()).thenReturn(UserType.BRAND);
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
    }

    private void stubIdempotencyExecutesAction() {
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });
    }

    private void stubAcceptTarget(Collaboration collaboration, Campaign campaign) {
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.empty());
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
    }

    @Test
    @DisplayName(
            "[F-0400 verification] accept(): campaign capped at maxCollaborators=1 already has ONE"
                    + " committed creator — accepting a second (the N+1th) is rejected with"
                    + " 409 MAX_COLLABORATORS_REACHED, nothing persisted")
    void testAcceptRejectsNPlusOnethCollaborator() {
        stubBrandWorkspace();
        Campaign campaign = campaignCappedAt(1);

        Collaboration acceptingNow =
                Collaboration.propose(
                        DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, new BigDecimal("5000"), "INR", "Deal");
        stubAcceptTarget(acceptingNow, campaign);

        Collaboration alreadyCommitted =
                Collaboration.propose(
                        OTHER_DEAL_ID_1,
                        CAMPAIGN_ID,
                        OTHER_CREATOR_USER_ID_1,
                        new BigDecimal("5000"),
                        "INR",
                        "Deal");
        alreadyCommitted.transitionTo(CollaborationStatus.TERMS_AGREED);

        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(acceptingNow, alreadyCommitted));
        stubIdempotencyExecutesAction();

        ApiException ex =
                assertThrows(ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals("MAX_COLLABORATORS_REACHED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertNotEquals(CollaborationStatus.TERMS_AGREED, acceptingNow.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName(
            "[F-0400 verification] accept(): campaign capped at maxCollaborators=1 with ZERO"
                    + " committed creators yet — accepting the FIRST (the Nth, at the cap) succeeds,"
                    + " proving the gate isn't simply always-reject")
    void testAcceptAllowsNthCollaboratorAtCap() {
        stubBrandWorkspace();
        Campaign campaign = campaignCappedAt(1);

        Collaboration acceptingNow =
                Collaboration.propose(
                        DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, new BigDecimal("5000"), "INR", "Deal");
        stubAcceptTarget(acceptingNow, campaign);

        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(acceptingNow));
        stubIdempotencyExecutesAction();

        assertDoesNotThrow(() -> service.accept(brandPrincipal, DEAL_ID, null));
        assertEquals(CollaborationStatus.TERMS_AGREED, acceptingNow.getStatus());
    }
}
