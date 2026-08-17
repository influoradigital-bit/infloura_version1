package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.influora.service.portfolio.PortfolioService;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
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
    @Mock private PortfolioService portfolioService;
    // F-0225 — real revive service, mocked repositories. See CreatorCampaignServiceTest.
    @Mock private com.influora.repository.ContractRepository contractRepository;
    @Mock private com.influora.repository.EscrowHoldRepository escrowHoldRepository;
    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;
    @Mock private com.influora.repository.DealMessageRepository dealMessageRepository;
    @Mock private AuthPrincipal principal;

    private CreatorDiscoveryService service;

    @BeforeEach
    void setUp() {
        // M-2: explicit stub (belt-and-suspenders — Mockito's List-returning default is already
        // an empty list, not null, so this isn't guarding an NPE) so any test path reaching
        // toResponseForWorkspace (single-profile read) is unambiguous about what it returns.
        when(portfolioService.getVisiblePinnedPosts(any())).thenReturn(List.of());
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
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        dealMessageRepository);
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

        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("F-0291: inviting writes the event AND the brand's note onto the deal timeline")
    void testInviteRecordsTimeline() {
        stubHappyPathLookups();
        when(principal.getUserId()).thenReturn("01HBRANDUSER123456789");
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(i -> i.getArgument(0));

        service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "We would love to work with you");

        org.mockito.ArgumentCaptor<com.influora.domain.entity.DealMessage> saved =
                org.mockito.ArgumentCaptor.forClass(com.influora.domain.entity.DealMessage.class);
        verify(dealMessageRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        var rows = saved.getAllValues();
        // The EVENT — without it the invited creator opens an empty room.
        assertEquals(com.influora.domain.enums.DealMessageKind.system, rows.get(0).getKind());
        // The brand's own words, attributed to the BRAND so they sit on the brand's side.
        assertEquals(com.influora.domain.enums.DealMessageKind.text, rows.get(1).getKind());
        assertEquals(com.influora.domain.enums.DealSenderType.brand, rows.get(1).getSenderType());
        assertEquals("We would love to work with you", rows.get(1).getContent());
    }

    @Test
    @DisplayName("F-0291: an invite with no note records the event and invents no brand message")
    void testInviteWithoutNoteRecordsEventOnly() {
        stubHappyPathLookups();
        when(principal.getUserId()).thenReturn("01HBRANDUSER123456789");
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(i -> i.getArgument(0));

        service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, null);

        verify(dealMessageRepository, org.mockito.Mockito.times(1))
                .save(any(com.influora.domain.entity.DealMessage.class));
    }

    @Test
    @DisplayName("F-0291: a timeline failure never rolls back an invitation that succeeded")
    void testInviteSurvivesTimelineFailure() {
        stubHappyPathLookups();
        when(principal.getUserId()).thenReturn("01HBRANDUSER123456789");
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new RuntimeException("timeline down"))
                .when(dealMessageRepository)
                .save(any(com.influora.domain.entity.DealMessage.class));

        var response = service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi");
        assertEquals("INVITED", response.status());
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

        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(
                        Collaboration.invite("collab_prior", CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "hi"));

        assertEquals("COLLABORATION_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, org.mockito.Mockito.never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("invite: F-0225 -- a WITHDRAWN prior collaboration revives instead of 409-ing")
    void testInviteRevivesCancelledCollaboration() {
        stubHappyPathLookups();
        Collaboration withdrawn =
                Collaboration.apply("collab_prior", CAMPAIGN_ID, CREATOR_USER_ID, "old note", "INR");
        withdrawn.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(withdrawn));
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = service.invite(principal, CREATOR_PROFILE_ID, CAMPAIGN_ID, "come back?");

        // The defect was the "already on this campaign" 409 firing on an abandoned deal.
        assertEquals("INVITED", response.status());

        org.mockito.ArgumentCaptor<Collaboration> saved =
                org.mockito.ArgumentCaptor.forClass(Collaboration.class);
        verify(collaborationRepository).save(saved.capture());
        assertEquals(
                com.influora.domain.enums.CollaborationSource.INVITATION, saved.getValue().getSource());
        assertEquals("come back?", saved.getValue().getNotes());
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
    @DisplayName("getPublicProfile: resolves discoverable creator by their real ULID id, not just username")
    void testGetPublicProfileByUlidId() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));
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

        // Regression guard for the shape-sniffing bug this test would have caught: CREATOR_PROFILE_ID
        // ("01HCREATORPROFILE1234") lowercases to an all-alphanumeric string that UsernameUtils.isValid()
        // matches, exactly like a real 26-char ULID does. The OLD resolveDiscoverableProfile branched on
        // isValid() and would have routed this call through findByUsernameIgnoreCase(CREATOR_PROFILE_ID)
        // instead -- a stub that intentionally does NOT exist in this test -- so under the old code this
        // call throws CREATOR_NOT_FOUND (404) instead of returning the profile below.
        var response = service.getPublicProfile(principal, CREATOR_PROFILE_ID);

        assertEquals(CREATOR_PROFILE_ID, response.id());
        assertEquals(USERNAME, response.username());
        assertEquals("Riya Sharma", response.displayName());
        assertNotNull(response.scores());
        assertEquals(new BigDecimal("8.5"), response.scores().quality());
    }

    @Test
    @DisplayName(
            "getPublicProfile: resolves discoverable creator by their User id (brand-campaign-detail"
                    + " bids-tab caller), not just profile id or username")
    void testGetPublicProfileByUserId() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        // Isolation: stub ONLY findByUserId -- no findByIdAndDiscoverableTrue stub, no
        // findByUsernameIgnoreCase stub. This is exactly the shape brand-campaign-detail.tsx's bids
        // tab passes: deal.counterpartyId, which DealService's Counterparty.id sets to
        // collaboration.getCreatorId() -- a Collaboration.creatorId / users.id, never a
        // CreatorProfile id -- so findByIdAndDiscoverableTrue(CREATOR_USER_ID) would miss (wrong id
        // type) and, under the old two-branch resolveDiscoverableProfile, findByUsernameIgnoreCase
        // (CREATOR_USER_ID) would miss too, throwing CREATOR_NOT_FOUND (404). No stub exists for
        // either of those calls here, so if the fix regresses to the old branch order/set, Mockito's
        // default empty Optional makes this call 404 rather than silently mis-resolving.
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
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

        var response = service.getPublicProfile(principal, CREATOR_USER_ID);

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
    // BR-18 — batch score exposure via get() -> toResponseForWorkspace() -> loadScoresByCreator()
    // -> the real CreatorScoreRepository#findLatestByCreatorProfileIdIn batch finder (mocked here,
    // real query proven separately at the repository/migration level). get() feeds /creators/{id};
    // toResponse()/buildScores() is the exact same code path search()/featured()/suggestions() go
    // through for their own pages, so this single call site is a faithful proxy for all four.
    // ------------------------------------------------------------------------------------------

    private void stubSingleCreatorLookup(CreatorProfile profile) {
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(savedCreatorRepository.findByWorkspaceIdAndCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName(
            "get: CreatorResponse.scores().authenticity() is 100 - fakeFollowerScore (80 -> 20), never the raw suspicion score")
    void testGetInvertsFakeFollowerScoreIntoAuthenticity() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        stubSingleCreatorLookup(profile);

        CreatorScore score = mock(CreatorScore.class);
        when(score.getCreatorProfileId()).thenReturn(CREATOR_PROFILE_ID);
        when(score.getQualityScore()).thenReturn(new BigDecimal("81.00"));
        when(score.getFakeFollowerScore()).thenReturn(new BigDecimal("80.00"));
        when(score.getBrandSafetyScore()).thenReturn(new BigDecimal("95.00"));
        when(creatorScoreRepository.findLatestByCreatorProfileIdIn(List.of(CREATOR_PROFILE_ID)))
                .thenReturn(List.of(score));

        var response = service.get(principal, CREATOR_PROFILE_ID);

        assertNotNull(response.scores());
        assertEquals(new BigDecimal("20.00"), response.scores().authenticity());
        // Nice-to-have: quality/brandSafety pass straight through, untouched by the inversion.
        assertEquals(new BigDecimal("81.00"), response.scores().quality());
        assertEquals(new BigDecimal("95.00"), response.scores().brandSafety());
    }

    @Test
    @DisplayName(
            "get: a creator with no fake-follower score yet reports null authenticity, never fabricated as 0 or 100")
    void testGetNullFakeFollowerScoreStaysNullAuthenticity() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        stubSingleCreatorLookup(profile);

        CreatorScore score = mock(CreatorScore.class);
        when(score.getCreatorProfileId()).thenReturn(CREATOR_PROFILE_ID);
        when(score.getQualityScore()).thenReturn(new BigDecimal("70.00"));
        when(score.getFakeFollowerScore()).thenReturn(null);
        when(score.getBrandSafetyScore()).thenReturn(null);
        when(creatorScoreRepository.findLatestByCreatorProfileIdIn(List.of(CREATOR_PROFILE_ID)))
                .thenReturn(List.of(score));

        var response = service.get(principal, CREATOR_PROFILE_ID);

        assertNotNull(response.scores());
        assertNull(response.scores().authenticity());
        assertNull(response.scores().brandSafety());
        assertEquals(new BigDecimal("70.00"), response.scores().quality());
    }

    @Test
    @DisplayName("get: no computed score row at all -> CreatorResponse.scores() is null, not fabricated")
    void testGetNoScoreRowYieldsNullScores() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        stubSingleCreatorLookup(profile);
        when(creatorScoreRepository.findLatestByCreatorProfileIdIn(List.of(CREATOR_PROFILE_ID)))
                .thenReturn(List.of());

        var response = service.get(principal, CREATOR_PROFILE_ID);

        assertNull(response.scores());
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

    // ─── F-0139: pinned posts on the single-profile read ────────────────────────────────────
    //
    // M-2 (BrandF.md §87) replaced CreatorMapper's hardcoded Collections.emptyList() with the
    // creator's real pinned posts, but only on GET /creators/{id} — the batch/list paths still
    // pass the empty list deliberately, to avoid an extra portfolio read per row. Nothing
    // asserted either half, so the mapping could have been reverted, inverted, or accidentally
    // extended to the list path without a single test noticing.

    @Test
    @DisplayName("get: surfaces the creator's real pinned posts, not the old empty-list stub")
    void testGetHydratesRealPinnedPosts() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        stubSingleCreatorLookup(profile);
        // The visibility gate itself is PortfolioService's job (covered in PortfolioServiceTest);
        // what matters here is that this path asks for the posts and maps what comes back.
        when(portfolioService.getVisiblePinnedPosts(profile))
                .thenReturn(
                        List.of(
                                new PortfolioPinnedPost(
                                        "pp_1",
                                        "INSTAGRAM",
                                        "https://instagram.com/p/abc",
                                        "https://cdn/thumb.jpg",
                                        "Morning routine",
                                        12_000L,
                                        900L)));

        var response = service.get(principal, CREATOR_PROFILE_ID);

        assertEquals(1, response.portfolioItems().size());
        var item = response.portfolioItems().get(0);
        assertEquals("pp_1", item.id());
        assertEquals("INSTAGRAM", item.platform());
        // PortfolioPinnedPost has no title/description split — caption maps into title, and
        // description is deliberately left null rather than duplicating the same text.
        assertEquals("Morning routine", item.title());
        assertNull(item.description());
        assertEquals("https://cdn/thumb.jpg", item.thumbnailUrl());
    }

    @Test
    @DisplayName("get: an empty portfolio stays empty rather than becoming null")
    void testGetEmptyPortfolioStaysEmpty() {
        stubWorkspace();
        CreatorProfile profile = stubDiscoverableProfile();
        stubSingleCreatorLookup(profile);
        when(portfolioService.getVisiblePinnedPosts(profile)).thenReturn(List.of());

        assertEquals(List.of(), service.get(principal, CREATOR_PROFILE_ID).portfolioItems());
    }

    @Test
    @DisplayName("search: the batch path never hydrates portfolios — one read per row is the cost being avoided")
    void testSearchDoesNotHydratePortfolios() {
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
                        principal, null, null, null, null, null, null, null, null, null, null,
                        null, null, true, 1, 20, "engagement");

        assertEquals(1, result.page().items().size());
        assertEquals(
                List.of(),
                result.page().items().get(0).portfolioItems(),
                "list rows carry no portfolio by design");
        // The stronger assertion: it must not even ASK. If someone 'fixes' the empty list by
        // calling through here, this fails loudly — an N+1 portfolio read across a whole page
        // is the regression, and an assertion on the empty result alone would not catch it.
        verify(portfolioService, never()).getVisiblePinnedPosts(any());
    }
}
