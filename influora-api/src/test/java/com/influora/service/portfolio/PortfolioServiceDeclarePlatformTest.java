package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.portfolio.PortfolioDtos.PlatformDeclarationRequest;
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
import org.springframework.http.HttpStatus;

/**
 * F-0694/F-0695 — a brand narrowing Discover to {@code platforms=INSTAGRAM} runs {@code
 * CreatorProfileSpecifications#hasPlatforms}, an {@code EXISTS} subquery over {@code
 * platform_stats}. Before this change the only two writers of that table ({@code
 * PlatformStatsAggregationJob} and {@link PortfolioService#syncPlatforms}) both built their row
 * from a Meta {@code CreatorMetric}, and {@code creator-onboarding.tsx}'s only Instagram branch was
 * a full-page Meta OAuth redirect. A creator who declined, failed, or could not complete OAuth
 * therefore had no {@code PlatformStat} row at all and was invisible to the one filter a brand uses
 * to find Instagram influencers.
 *
 * <p>This class pins the four things that make the self-declared path safe to expose to brands:
 * the row is written at all (1), it is written as creator-reported and never renders as
 * platform-verified (2), it can never overwrite or downgrade a real Meta-synced row (3), and it
 * never fires the {@code JOINED} cross-link hook — a typed handle is a claim, not proof of
 * ownership, and letting it mark an admin-imported external creator as joined would be an
 * impersonation vector (4).
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceDeclarePlatformTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileService creatorProfileService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AudienceDemographicsRepository audienceDemographicsRepository;
    @Mock private ReviewRepository reviewRepository;
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

    private PortfolioService service;
    private AuthPrincipal principal;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
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

        principal = new AuthPrincipal(USER_ID, "creator", null, null);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyUsername("riya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    private static PlatformStat verifiedInstagramRow() {
        PlatformStat stat =
                PlatformStat.builder()
                        .id("01HPLATFORMSTAT123456")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle("riya.real")
                        .followers(412_000L)
                        .verified(true)
                        .build();
        return stat;
    }

    @Test
    @DisplayName(
            "declarePlatform: a creator with no Meta token still gets a platform_stats row, so the"
                    + " brand's platforms=INSTAGRAM filter can find them (F-0694)")
    void declarePlatform_withoutMetaToken_writesThePlatformStatRowTheFilterNeeds() {
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());

        service.declarePlatform(
                principal, new PlatformDeclarationRequest("INSTAGRAM", "@riya.creates", 48_000L));

        ArgumentCaptor<PlatformStat> saved = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(saved.capture());
        assertEquals("INSTAGRAM", saved.getValue().getPlatform());
        assertEquals("riya.creates", saved.getValue().getHandle(), "the leading @ must be stripped");
        assertEquals(48_000L, saved.getValue().getFollowers());

        // No Meta call was needed to get here — that is the entire point of the fix.
        verifyNoInteractions(instagramInsightsClient);
        verifyNoInteractions(metaOAuthTokenRepository);
    }

    @Test
    @DisplayName(
            "declarePlatform: the row is creator-reported and never renders as platform-verified"
                    + " (CR-119 provenance)")
    void declarePlatform_marksTheSnapshotCreatorReportedAndLeavesVerifiedFalse() {
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());

        service.declarePlatform(
                principal, new PlatformDeclarationRequest("INSTAGRAM", "riya.creates", 48_000L));

        ArgumentCaptor<CreatorMetric> metric = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metric.capture());
        assertEquals(
                CreatorMetric.DATA_SOURCE_CREATOR_REPORTED,
                metric.getValue().getDataSource(),
                "a typed follower count must never be labelled META_API");
        assertFalse(
                metric.getValue().isPlatformVerified(),
                "isPlatformVerified is the ONLY thing allowed to set PlatformStat.verified");
        assertNull(
                metric.getValue().getAvgEngagementRate(),
                "engagement rate stays measured-only — it drives ranking and is not self-declarable");

        ArgumentCaptor<PlatformStat> saved = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(saved.capture());
        assertFalse(
                saved.getValue().isVerified(),
                "the brand-facing verified badge must stay dark for a self-declared row");
    }

    @Test
    @DisplayName(
            "declarePlatform: a Meta-synced row is never overwritten by a typed number — real data"
                    + " outranks a claim")
    void declarePlatform_refusesToDowngradeAVerifiedRow() {
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(verifiedInstagramRow()));

        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.declarePlatform(
                                        principal,
                                        new PlatformDeclarationRequest("INSTAGRAM", "riya.creates", 9_000_000L)));

        assertEquals("PLATFORM_ALREADY_VERIFIED", e.getCode());
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(platformStatRepository, never()).save(any());
        verify(creatorMetricsRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "declarePlatform: never fires the JOINED cross-link hook — a typed handle is a claim,"
                    + " not proof that the creator owns that Instagram account")
    void declarePlatform_doesNotCrossLinkAnExternalCreator() {
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());

        service.declarePlatform(
                principal, new PlatformDeclarationRequest("INSTAGRAM", "someone.elses.handle", 900_000L));

        verify(externalCreatorLinkService, never()).onCreatorIdentified(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("declarePlatform: rejects a handle that is not a plausible platform username")
    void declarePlatform_rejectsAGarbageHandle() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.declarePlatform(
                                        principal, new PlatformDeclarationRequest("INSTAGRAM", "not a handle)", 1_000L)));
        assertEquals("INVALID_HANDLE", e.getCode());
        verify(platformStatRepository, never()).save(any());
    }

    @Test
    @DisplayName("declarePlatform: rejects a platform outside the Discover filter's own catalog")
    void declarePlatform_rejectsAnUnknownPlatform() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.declarePlatform(
                                        principal, new PlatformDeclarationRequest("MYSPACE", "riya", 1_000L)));
        assertEquals("INVALID_PLATFORM", e.getCode());
        verify(platformStatRepository, never()).save(any());
    }

    @Test
    @DisplayName("declarePlatform: rejects a negative or absurd follower count")
    void declarePlatform_rejectsAnOutOfRangeFollowerCount() {
        assertEquals(
                "INVALID_FOLLOWERS",
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.declarePlatform(
                                                principal, new PlatformDeclarationRequest("INSTAGRAM", "riya", -1L)))
                        .getCode());
        assertEquals(
                "INVALID_FOLLOWERS",
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.declarePlatform(
                                                principal,
                                                new PlatformDeclarationRequest("INSTAGRAM", "riya", 5_000_000_000L)))
                        .getCode());
        verify(platformStatRepository, never()).save(any());
    }
}
