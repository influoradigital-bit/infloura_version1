package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.FeaturedCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.SavedCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorSuggestionRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorDiscoveryServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String USERNAME = "riya_fitness";

    @Mock private BrandContextService brandContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private SavedCreatorRepository savedCreatorRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private FeaturedCreatorRepository featuredCreatorRepository;
    @Mock private CreatorScoreRepository creatorScoreRepository;
    @Mock private ReviewRepository reviewRepository;
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
                        reviewRepository);
    }

    private Workspace stubWorkspace() {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        return workspace;
    }

    private CreatorProfile stubDiscoverableProfile() {
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(profile.getUsername()).thenReturn(USERNAME);
        when(profile.getDisplayName()).thenReturn("Riya Sharma");
        when(profile.getBio()).thenReturn("Fitness creator");
        when(profile.getCity()).thenReturn("Mumbai");
        when(profile.getCategoriesJson()).thenReturn(JsonLists.toJson(List.of("fitness", "health")));
        when(profile.getLanguagesJson()).thenReturn(JsonLists.toJson(List.of("english")));
        when(profile.getContentStylesJson()).thenReturn("[]");
        when(profile.getTotalFollowers()).thenReturn(125_000L);
        when(profile.getEngagementRate()).thenReturn(new BigDecimal("4.20"));
        when(profile.getRateMin()).thenReturn(new BigDecimal("15000"));
        when(profile.getRateMax()).thenReturn(new BigDecimal("25000"));
        when(profile.getCurrency()).thenReturn("INR");
        when(profile.isVerified()).thenReturn(true);
        when(profile.isDiscoverable()).thenReturn(true);
        return profile;
    }

    private void stubHappyPathLookups() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));

        Campaign campaign = mock(Campaign.class);
        when(campaign.getId()).thenReturn(CAMPAIGN_ID);
        when(campaign.getCurrency()).thenReturn("INR");
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(false);
    }

    @Test
    @DisplayName("invite: sequential duplicate throws friendly 409 without save()")
    void testSequentialDuplicateThrowsFriendly409() {
        stubWorkspace();
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));

        Campaign campaign = mock(Campaign.class);
        when(campaign.getId()).thenReturn(CAMPAIGN_ID);
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        when(collaborationRepository.existsByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi"));

        assertEquals("COLLABORATION_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, org.mockito.Mockito.never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("invite: TOCTOU race surfaces friendly 409, not raw 500")
    void testConcurrentRaceLoserGetsFriendly409NotRaw500() {
        stubHappyPathLookups();
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'uq_campaign_creator'"))
                .when(collaborationRepository)
                .save(any(Collaboration.class));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi"));

        assertEquals("COLLABORATION_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("invite: happy path saves exactly once")
    void testHappyPathSavesOnce() {
        stubHappyPathLookups();
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi");

        assertEquals("INVITED", response.status());
        verify(collaborationRepository, org.mockito.Mockito.times(1)).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("getPublicProfile: resolves discoverable creator by username")
    void testGetPublicProfileByUsername() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(profile));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(savedCreatorRepository.findByWorkspaceIdAndCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        when(collaborationRepository.findByCreatorIdAndStatus(CREATOR_USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(List.of());
        CreatorScore score = mock(CreatorScore.class);
        when(score.getQualityScore()).thenReturn(new BigDecimal("8.5"));
        when(score.getFakeFollowerScore()).thenReturn(new BigDecimal("9.2"));
        when(score.getBrandSafetyScore()).thenReturn(new BigDecimal("9.8"));
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(score));

        var response = service.getPublicProfile(principal, USERNAME);

        assertEquals(CREATOR_PROFILE_ID, response.id());
        assertEquals(USERNAME, response.username());
        assertEquals("Riya Sharma", response.displayName());
        assertNotNull(response.scores());
        assertEquals(new BigDecimal("8.5"), response.scores().quality());
    }

    @Test
    @DisplayName("getPublicProfile: non-discoverable username returns 404")
    void testGetPublicProfileNotDiscoverable() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(profile.isDiscoverable()).thenReturn(false);
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(profile));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getPublicProfile(principal, USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    // ------------------------------------------------------------------------------------------
    // [SEC: Wave-1 S4-discovery] A suspended creator (CreatorProfile#suspend(), admin moderation)
    // must never be discoverable/invitable, even though suspend() never flips `discoverable`.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("invite: a suspended-but-still-discoverable creator cannot be invited (404, not silently allowed)")
    void testInviteRejectsSuspendedCreator() {
        stubWorkspace();
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(profile.isDiscoverable()).thenReturn(true);
        when(profile.isSuspended()).thenReturn(true);
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi"));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(collaborationRepository, org.mockito.Mockito.never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("getPublicProfile: a suspended-but-still-discoverable username returns 404")
    void testGetPublicProfileRejectsSuspendedCreator() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(profile.isSuspended()).thenReturn(true);
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(profile));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getPublicProfile(principal, USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName("getSimilar: ranks creators in same niche with match metadata")
    void testGetSimilarCreators() {
        stubWorkspace();
        CreatorProfile source = stubDiscoverableProfile();
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(source));

        CreatorProfile peer = mock(CreatorProfile.class);
        when(peer.getId()).thenReturn("01HPEER0000000000000001");
        when(peer.getUsername()).thenReturn("arjun_fitness");
        when(peer.getDisplayName()).thenReturn("Arjun Mehta");
        when(peer.getAvatarUrl()).thenReturn(null);
        when(peer.getTotalFollowers()).thenReturn(110_000L);
        when(peer.getEngagementRate()).thenReturn(new BigDecimal("4.5"));
        when(peer.getCity()).thenReturn("Mumbai");
        when(peer.getCategoriesJson()).thenReturn(JsonLists.toJson(List.of("fitness")));

        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(peer)));

        var response = service.getSimilar(principal, USERNAME, 6);

        assertEquals(1, response.similar().size());
        assertEquals("arjun_fitness", response.similar().get(0).username());
        assertTrue(response.similar().get(0).matchScore() > 0);
        assertFalse(response.similar().get(0).matchReasons().isEmpty());
    }

    @Test
    @DisplayName("getFeatured: falls back to algorithmic sections when curated rows are empty")
    void testGetFeaturedAlgorithmicFallback() {
        stubWorkspace();
        when(featuredCreatorRepository.findActiveFeatured(eq(null), any(), any(Pageable.class)))
                .thenReturn(List.of());

        CreatorProfile profile = stubDiscoverableProfile();
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(profile)));

        var response = service.getFeatured(principal, null, 5);

        assertFalse(response.featured().isEmpty());
        assertFalse(response.featured().get(0).creators().isEmpty());
    }

    @Test
    @DisplayName("suggest: returns ranked creators for fitness campaign goals")
    void testSuggestCreatorsForFitnessCampaign() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        Page<CreatorProfile> page = new PageImpl<>(List.of(profile));
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(savedCreatorRepository.findByWorkspaceIdAndCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        var response =
                service.suggest(
                        principal,
                        new CreatorSuggestionRequest(
                                "Increase awareness for protein supplement",
                                "Fitness enthusiasts 18-35",
                                200_000,
                                List.of("INSTAGRAM")));

        assertEquals(1, response.suggestions().size());
        assertTrue(response.suggestions().get(0).matchScore() > 0);
        assertFalse(response.suggestions().get(0).reasons().isEmpty());
        assertEquals(USERNAME, response.suggestions().get(0).creator().username());
    }

    @Test
    @DisplayName("search: returns facet metadata alongside creator results")
    void testSearchReturnsFacets() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(profile), Pageable.ofSize(20), 1));
        when(creatorProfileRepository.findAll(any(Specification.class), eq(Pageable.ofSize(5000))))
                .thenReturn(new PageImpl<>(List.of(profile)));
        when(platformStatRepository.findByCreatorProfileIdIn(any())).thenReturn(List.of());
        when(savedCreatorRepository.findByWorkspaceIdAndCreatorProfileIdInAndSavedTrue(any(), any()))
                .thenReturn(List.of());

        var result =
                service.search(
                        principal,
                        "fitness",
                        null,
                        "Mumbai",
                        "fitness",
                        null,
                        "english",
                        10_000L,
                        500_000L,
                        null,
                        null,
                        null,
                        null,
                        true,
                        1,
                        20,
                        "engagement");

        assertEquals(1, result.page().items().size());
        assertNotNull(result.envelope().filters());
        assertTrue(result.envelope().filters().applied().containsKey("q"));
        assertFalse(result.envelope().filters().available().categories().isEmpty());
    }
}
