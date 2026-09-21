package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.FeaturedCreator;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.Workspace;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.FeaturedCreatorRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.SavedCreatorRepository;
import com.influora.repository.ShipmentRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.portfolio.PortfolioService;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorSuggestionRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * F-0980 — the behaviour half of {@code .proof-os/gates/portfolio-visibility-enforced.sh}'s
 * producer check.
 *
 * <p>The defect: {@code PortfolioVisibility.platformStats()} had exactly ONE reader in
 * {@code src/main} — {@code PortfolioService#assemble} — and the visibility gate was satisfied
 * by counting readers. Meanwhile FIVE brand-facing producers in {@link CreatorDiscoveryService}
 * read rows straight out of {@code PlatformStatRepository} and emitted them onto the top-level
 * {@code platforms} field of the brand DTOs, so a creator who switched "Platform stats" off in
 * the portfolio editor still served their handle, follower count, engagement rate and
 * profileUrl to every brand. A reader count can never see a producer that walks around the
 * flag; only an assertion on the emitted shape can.
 *
 * <p>This test therefore wires a REAL {@link PortfolioService} into a REAL
 * {@link CreatorDiscoveryService} — a mocked PortfolioService would stub the very projection
 * under test and prove nothing — and asserts the emitted {@code platforms} list on ALL FIVE
 * shapes. It is parameterised over {@link Shape} rather than written as five methods so that
 * adding a sixth brand-facing endpoint without adding a case here is visible as a missing
 * constant rather than as silence.
 *
 * <p>Falsification: reverting ANY ONE of the five call sites to a raw
 * {@code platformStatRepository} read fails exactly that shape's case. A test that exercised
 * only {@code getPublicProfile} — the obvious one — would have gone green on the shipped bug,
 * because four of the five producers never mention {@code toPlatformResponse} at all.
 *
 * <p>{@link #flagOnStillEmitsTheRow} is the control: without it, a projection that returned
 * {@code List.of()} unconditionally would pass every case above and delete a feature.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorDiscoveryPlatformStatsVisibilityTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String USERNAME = "riya_fitness";
    private static final String HANDLE = "@riya.fitness";
    private static final String PROFILE_URL = "https://instagram.com/riya.fitness";

    /** The five brand-facing shapes that carry a top-level {@code platforms} list. */
    enum Shape {
        DISCOVERY_SEARCH,
        PUBLIC_PROFILE,
        GET_BY_ID,
        FEATURED_CURATED,
        AI_SUGGESTIONS
    }

    // --- CreatorDiscoveryService collaborators ---------------------------------------------
    @Mock private BrandContextService brandContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private SavedCreatorRepository savedCreatorRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private FeaturedCreatorRepository featuredCreatorRepository;
    @Mock private CreatorScoreRepository creatorScoreRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private AuthPrincipal principal;

    // --- PortfolioService collaborators (the projection owner is REAL) ----------------------
    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileService creatorProfileService;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AudienceDemographicsRepository audienceDemographicsRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private PortfolioEventRepository portfolioEventRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private ExternalCreatorLinkService externalCreatorLinkService;
    @Mock private AbuseThrottleService abuseThrottleService;

    private PortfolioService portfolioService;
    private CreatorDiscoveryService service;
    private CreatorProfile profile;
    private PlatformStat row;

    @BeforeEach
    void setUp() {
        portfolioService =
                new PortfolioService(
                        creatorContext,
                        creatorProfileService,
                        creatorProfileRepository,
                        platformStatRepository,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        audienceDemographicsRepository,
                        reviewRepository,
                        r2StorageService,
                        new R2Properties(),
                        new NoOpMalwareScanService(),
                        eventPublisher,
                        userRepository,
                        deliverableRepository,
                        portfolioEventRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        instagramInsightsClient,
                        creatorMetricsRepository,
                        externalCreatorLinkService,
                        abuseThrottleService);

        service =
                new CreatorDiscoveryService(
                        brandContext,
                        creatorProfileRepository,
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
                        dealMessageRepository,
                        subscriptionService);

        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyUsername(USERNAME);

        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);

        // Every lookup the five shapes use to reach this one profile.
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(PROFILE_ID))
                .thenReturn(Optional.of(profile));
        when(creatorProfileRepository.findByIdAndDiscoverableTrue(USERNAME))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findByUserId(USERNAME)).thenReturn(Optional.empty());
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME))
                .thenReturn(Optional.of(profile));
        when(creatorProfileRepository.findAllById(List.of(PROFILE_ID))).thenReturn(List.of(profile));
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(profile), Pageable.ofSize(20), 1));
        when(creatorProfileRepository.findAll(any(Specification.class), eq(Pageable.ofSize(5000))))
                .thenReturn(new PageImpl<>(List.of(profile)));

        // The curated /featured path, which is the only caller of mapResponses.
        FeaturedCreator featured = mock(FeaturedCreator.class);
        when(featured.getCreatorProfileId()).thenReturn(PROFILE_ID);
        when(featured.getFeaturedCategory()).thenReturn("rising_star");
        when(featuredCreatorRepository.findActiveFeatured(any(), any(Instant.class), any()))
                .thenReturn(List.of(featured));

        // One real, Meta-verified row. If it ever reaches a brand while the flag is off, the
        // creator's handle and profileUrl have leaked — which is exactly F-0980.
        row =
                PlatformStat.builder()
                        .id("01HPLATFORMSTAT000001")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle(HANDLE)
                        .followers(125_000L)
                        .engagementRate(new BigDecimal("4.20"))
                        .verified(true)
                        .profileUrl(PROFILE_URL)
                        .build();
        lenient().when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of(row));
        lenient()
                .when(platformStatRepository.findByCreatorProfileIdIn(List.of(PROFILE_ID)))
                .thenReturn(List.of(row));
    }

    /** All nine components, written out so no default can be mistaken for the thing under test. */
    private void platformStatsVisibility(boolean on) {
        profile.applyPortfolioSettingsJson(
                "{\"visibility\":{"
                        + "\"trustBar\":true,"
                        + "\"badges\":true,"
                        + "\"platformStats\":"
                        + on
                        + ","
                        + "\"pastCollabs\":true,"
                        + "\"contentPortfolio\":true,"
                        + "\"customLinks\":true,"
                        + "\"rateCard\":\"brands_only\","
                        + "\"languages\":true,"
                        + "\"contactForm\":true}}");
    }

    private List<PlatformStatResponse> platformsFor(Shape shape) {
        switch (shape) {
            case DISCOVERY_SEARCH:
                return service.search(
                                principal, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, 1, 20, null)
                        .page()
                        .items()
                        .get(0)
                        .platforms();
            case PUBLIC_PROFILE:
                return service.getPublicProfile(principal, USERNAME).platforms();
            case GET_BY_ID:
                return service.get(principal, PROFILE_ID).platforms();
            case FEATURED_CURATED:
                return service.getFeatured(principal, null, 5)
                        .featured()
                        .get(0)
                        .creators()
                        .get(0)
                        .platforms();
            case AI_SUGGESTIONS:
                return service.suggest(
                                principal,
                                new CreatorSuggestionRequest(
                                        "Increase awareness for protein supplement",
                                        "Fitness enthusiasts 18-35",
                                        200_000,
                                        List.of("INSTAGRAM")))
                        .suggestions()
                        .get(0)
                        .creator()
                        .platforms();
            default:
                throw new IllegalStateException("unmapped brand-facing shape: " + shape);
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void platformStatsOffEmitsNoRowsToABrand(Shape shape) {
        platformStatsVisibility(false);

        List<PlatformStatResponse> platforms = platformsFor(shape);

        assertEquals(
                List.of(),
                platforms,
                shape
                        + " served platform rows for a creator who switched 'Platform stats' off"
                        + " -- handle, follower count, engagement rate and profileUrl leaked"
                        + " (F-0980)");
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void flagOnStillEmitsTheRow(Shape shape) {
        platformStatsVisibility(true);

        List<PlatformStatResponse> platforms = platformsFor(shape);

        assertEquals(1, platforms.size(), shape + " dropped a visible platform row");
        assertEquals(HANDLE, platforms.get(0).handle());
        assertEquals(PROFILE_URL, platforms.get(0).profileUrl());
    }

    /**
     * The profile-level rollup is deliberately NOT gated by this flag (Priya's ruling, F-0980):
     * {@code totalFollowers} is the discovery ranking and filter key, so nulling it would delist
     * the creator from every follower-range search they would otherwise match — a consequence
     * the editor's switch never warns about. Hiding headline reach, if ever wanted, is a
     * separate control with its own hint. This asserts the ruling so a later "tidy-up" that
     * gates the aggregate too fails loudly instead of silently delisting creators.
     */
    @ParameterizedTest
    @EnumSource(value = Shape.class, names = {"PUBLIC_PROFILE", "GET_BY_ID", "DISCOVERY_SEARCH"})
    void headlineReachSurvivesTheFlagBeingOff(Shape shape) {
        platformStatsVisibility(false);
        // The rollup the aggregation job writes from the very rows this flag hides: 125k,
        // Meta-VERIFIED. The brand must still see it and must still be able to filter on it.
        profile.applyFollowerTotals(FollowerTotals.from(List.of(row)));
        assertEquals(125_000L, profile.getTotalFollowers());

        assertTrue(platformsFor(shape).isEmpty());
        switch (shape) {
            case PUBLIC_PROFILE:
                assertEquals(
                        profile.getTotalFollowers(),
                        service.getPublicProfile(principal, USERNAME).totalFollowers());
                break;
            case GET_BY_ID:
                assertEquals(
                        profile.getTotalFollowers(),
                        service.get(principal, PROFILE_ID).totalFollowers());
                break;
            default:
                assertEquals(
                        profile.getTotalFollowers(),
                        service.search(
                                        principal, null, null, null, null, null, null, null, null,
                                        null, null, null, null, null, 1, 20, null)
                                .page()
                                .items()
                                .get(0)
                                .totalFollowers());
        }
    }
}
