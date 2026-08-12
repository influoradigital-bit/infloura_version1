package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.domain.entity.PortfolioEvent;
import com.influora.domain.enums.PortfolioEventType;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioAnalyticsResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.SyncPlatformsResponse;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

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

    private PortfolioService service;

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
                        creatorMetricsRepository);
    }

    @Test
    @DisplayName("getPublic: non-discoverable profile returns 404")
    void getPublic_nonDiscoverable_returns404() {
        // CreatorProfile.newForUser defaults discoverable=true (V6 migration:
        // is_discoverable NOT NULL DEFAULT TRUE) — explicitly opt this profile OUT of
        // discoverability via the settings mutator so this test actually exercises the
        // non-discoverable 404 path instead of the (correct) discoverable default.
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Hidden Creator");
        profile.applyUsername("hidden_creator");
        profile.applySelfEdit(
                null, null, null, null, null, null, null, null, null, null, Boolean.FALSE);

        when(creatorProfileService.requireProfileByUsername("hidden_creator")).thenReturn(profile);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.getPublic("hidden_creator"));

        assertEquals("PORTFOLIO_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName(
            "analytics: totalInquiries is scoped by the profile's userId, not its profileId"
                    + " (collaborations.creator_id is an FK to users.id, not creator_profiles.id)")
    void analytics_scopesCountsByUserIdNotProfileId() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(collaborationRepository.countByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(3L);
        when(collaborationRepository.countByCreatorId(USER_ID)).thenReturn(7L);

        PortfolioAnalyticsResponse response = service.analytics(principal);

        assertEquals(7L, response.brandInquiries());
        // Locks in the creator-id vs user-id fix (PortfolioService.java analytics()) — must be
        // called with the profile's userId, never its (distinct) profileId.
        verify(collaborationRepository).countByCreatorId(USER_ID);
        verify(collaborationRepository).countByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED);
        verify(collaborationRepository, never()).countByCreatorId(PROFILE_ID);
        verify(collaborationRepository, never()).countByCreatorIdAndStatus(eq(PROFILE_ID), any());
    }

    @Test
    @DisplayName(
            "CR-71: profileClicksEstimated is always true, since profileClicks is a follower-count"
                    + " proxy, not a real click measurement")
    void analytics_profileClicksIsAlwaysMarkedEstimated() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(collaborationRepository.countByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(0L);
        when(collaborationRepository.countByCreatorId(USER_ID)).thenReturn(0L);

        PortfolioAnalyticsResponse response = service.analytics(principal);

        assertEquals(true, response.profileClicksEstimated());
    }

    @Test
    @DisplayName(
            "analytics: page views come from portfolio_events VIEW rows (last 30d) with a"
                    + " period-over-period deltaPercent, keyed by profileId")
    void analytics_pageViewsComputedFromViewEvents() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        // View counts are keyed by creator_profiles.id — the id recordPublicView writes.
        when(portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtAfter(
                        eq(PROFILE_ID), eq(PortfolioEventType.VIEW), any(Instant.class)))
                .thenReturn(150L);
        when(portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtBetween(
                        eq(PROFILE_ID), eq(PortfolioEventType.VIEW), any(Instant.class), any(Instant.class)))
                .thenReturn(100L);

        PortfolioAnalyticsResponse response = service.analytics(principal);

        assertEquals(150L, response.pageViews().last30Days());
        assertEquals(50, response.pageViews().deltaPercent()); // (150-100)/100 = +50%
    }

    @Test
    @DisplayName("analytics: deltaPercent is 0 when the prior 30-day window had no views (no baseline)")
    void analytics_pageViewsDeltaZeroWhenNoPriorViews() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtAfter(
                        eq(PROFILE_ID), eq(PortfolioEventType.VIEW), any(Instant.class)))
                .thenReturn(10L);
        when(portfolioEventRepository.countByCreatorProfileIdAndEventTypeAndOccurredAtBetween(
                        eq(PROFILE_ID), eq(PortfolioEventType.VIEW), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);

        PortfolioAnalyticsResponse response = service.analytics(principal);

        assertEquals(10L, response.pageViews().last30Days());
        assertEquals(0, response.pageViews().deltaPercent());
    }

    @Test
    @DisplayName(
            "analytics: mediaKitDownloads is a real count of MEDIA_KIT_DOWNLOAD events (no longer a"
                    + " hardcoded 0), keyed by profileId")
    void analytics_mediaKitDownloadsFromEvents() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(portfolioEventRepository.countByCreatorProfileIdAndEventType(
                        PROFILE_ID, PortfolioEventType.MEDIA_KIT_DOWNLOAD))
                .thenReturn(5L);

        PortfolioAnalyticsResponse response = service.analytics(principal);

        assertEquals(5L, response.mediaKitDownloads());
    }

    @Test
    @DisplayName("recordPublicView: saves one view event keyed by the profile's id for a discoverable profile")
    void recordPublicView_discoverable_savesEvent() {
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");
        profile.applyUsername("real_creator");
        when(creatorProfileService.requireProfileByUsername("real_creator")).thenReturn(profile);

        service.recordPublicView("real_creator");

        ArgumentCaptor<PortfolioEvent> captor = ArgumentCaptor.forClass(PortfolioEvent.class);
        verify(portfolioEventRepository).save(captor.capture());
        assertEquals(PROFILE_ID, captor.getValue().getCreatorProfileId());
        assertEquals(PortfolioEventType.VIEW, captor.getValue().getEventType());
    }

    @Test
    @DisplayName("recordPublicView: does NOT record a view for a non-discoverable profile")
    void recordPublicView_nonDiscoverable_doesNotSave() {
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Hidden Creator");
        profile.applyUsername("hidden_creator");
        profile.applySelfEdit(
                null, null, null, null, null, null, null, null, null, null, Boolean.FALSE);
        when(creatorProfileService.requireProfileByUsername("hidden_creator")).thenReturn(profile);

        service.recordPublicView("hidden_creator");

        verify(portfolioEventRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "CR-84: syncPlatforms throws NOT_CONNECTED instead of a fake success when the creator"
                    + " has no connected Instagram account")
    void syncPlatforms_noConnectedAccount_throwsNotConnected() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.syncPlatforms(principal));

        assertEquals("NOT_CONNECTED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(instagramInsightsClient, never()).getProfile(any(), any());
    }

    @Test
    @DisplayName(
            "CR-84: syncPlatforms throws TOKEN_EXPIRED instead of a fake success when the stored"
                    + " token has expired/been revoked")
    void syncPlatforms_expiredToken_throwsTokenExpired() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");
        MetaOAuthToken tokenRow =
                MetaOAuthToken.builder()
                        .id("01HTOKEN1234567890ABCDE")
                        .creatorProfileId(PROFILE_ID)
                        .igBusinessAccountId("17841400000000000")
                        .encryptedAccessToken("cipher")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(tokenRow));
        when(metaTokenStorage.getValidCreatorToken(PROFILE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.syncPlatforms(principal));

        assertEquals("TOKEN_EXPIRED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(instagramInsightsClient, never()).getProfile(any(), any());
    }

    @Test
    @DisplayName(
            "CR-84: syncPlatforms performs a real Meta Graph API fetch and upserts platform_stats"
                    + " instead of returning a fabricated success with no data change")
    void syncPlatforms_connected_fetchesLiveDataAndUpsertsStats() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Real Creator");
        MetaOAuthToken tokenRow =
                MetaOAuthToken.builder()
                        .id("01HTOKEN1234567890ABCDE")
                        .creatorProfileId(PROFILE_ID)
                        .igBusinessAccountId("17841400000000000")
                        .encryptedAccessToken("cipher")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        InstagramUserResponse igProfile =
                new InstagramUserResponse(
                        "17841400000000000", "real_creator", "Real Creator", "bio", 5000L, 200L, 42L, null, null);

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(tokenRow));
        when(metaTokenStorage.getValidCreatorToken(PROFILE_ID)).thenReturn(Optional.of("plaintext-token"));
        when(instagramInsightsClient.getProfile("17841400000000000", "plaintext-token")).thenReturn(igProfile);
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID))
                .thenReturn(List.of(PlatformStat.builder().followers(5000L).build()));

        SyncPlatformsResponse response = service.syncPlatforms(principal);

        assertEquals(5000L, profile.getTotalFollowers());

        ArgumentCaptor<CreatorMetric> metricCaptor = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metricCaptor.capture());
        assertEquals(5000L, metricCaptor.getValue().getFollowers());
        assertEquals("real_creator", metricCaptor.getValue().getUsername());
        assertEquals("INSTAGRAM", metricCaptor.getValue().getPlatform());

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertEquals(5000L, statCaptor.getValue().getFollowers());
        assertEquals("real_creator", statCaptor.getValue().getHandle());

        verify(creatorProfileRepository).save(profile);
        org.junit.jupiter.api.Assertions.assertNotNull(response.syncedAt());
    }
}
