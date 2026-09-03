package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.FeaturedCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.SavedCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.portfolio.PortfolioService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * T-CREATORCONNECT-0902 Q6.2 (High) regression pin — {@code CreatorDiscoveryService} half.
 *
 * <p>{@link CreatorDiscoveryService#invite} must refuse to create a {@code Collaboration} against
 * a still-{@code DRAFT} campaign: the campaign has not been shown to anyone, and inviting a creator
 * to it both exposes an unpublished campaign and (via {@code COLLABORATION_EXISTS}'s unique-row
 * semantics) makes that draft undeletable. This is the server-side backstop the finding calls for —
 * the FE (campaign-form.tsx) now only sends its post-create invite once the campaign is {@code
 * ACTIVE}, but the server must not trust that. Not covered by {@code CreatorDiscoveryServiceTest}:
 * every existing {@code invite()} test there stubs {@code Campaign} as a bare Mockito mock whose
 * unstubbed {@code getStatus()} returns {@code null} (so the guard's {@code == DRAFT} check is
 * trivially false) — none of them ever set the campaign to {@code DRAFT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorDiscoveryServiceInviteDraftGuardTest {

    private static final String WORKSPACE_ID = "01HWORKSPACEDRAFTGUARD";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNDRAFTGUARD1";
    private static final String CREATOR_PROFILE_ID = "01HCREATORDRAFTGUARD01";
    private static final String CREATOR_USER_ID = "01HCREATORUSERDRAFTGD1";

    @Mock private BrandContextService brandContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private SavedCreatorRepository savedCreatorRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private FeaturedCreatorRepository featuredCreatorRepository;
    @Mock private com.influora.repository.CreatorScoreRepository creatorScoreRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private PortfolioService portfolioService;
    @Mock private com.influora.repository.ContractRepository contractRepository;
    @Mock private com.influora.repository.EscrowHoldRepository escrowHoldRepository;
    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private AuthPrincipal principal;

    private CreatorDiscoveryService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorDiscoveryService(
                        brandContext,
                        creatorProfileRepository,
                        platformStatRepository,
                        savedCreatorRepository,
                        campaignRepository,
                        collaborationRepository,
                        featuredCreatorRepository,
                        creatorScoreRepository,
                        reviewRepository,
                        portfolioService,
                        new CollaborationReviveService(
                                collaborationRepository, contractRepository, escrowHoldRepository, shipmentRepository),
                        dealMessageRepository);
    }

    @Test
    @DisplayName("invite(): a DRAFT campaign is refused with a typed 409, no Collaboration created (Q6.2)")
    void invite_draftCampaign_throwsCampaignNotPublished() {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);

        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));

        Campaign draftCampaign = mock(Campaign.class);
        when(draftCampaign.getId()).thenReturn(CAMPAIGN_ID);
        when(draftCampaign.getStatus()).thenReturn(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(draftCampaign));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi"));

        assertEquals("CAMPAIGN_NOT_PUBLISHED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(collaborationRepository, never()).findByCampaignIdAndCreatorId(any(), any());
    }
}
