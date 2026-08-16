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

    // ---------------------------------------------------------------------------------------
    // CR-119 — `syncPlatforms` is the SECOND writer of PlatformStat.verified (the first being
    // PlatformStatsAggregationJob#upsertPlatformStat). It is the more user-visible of the two:
    // it fires on a creator's on-demand "Sync" rather than the 3:45 AM batch. Reverting its
    // CR-119 change wholesale used to leave this entire suite green, so the flag that tells a
    // paying brand "these followers were confirmed with the platform" was, on this path,
    // guarded by nothing. These pin it.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "CR-119: syncPlatforms marks a NEW platform stat verified — the fetch is a real Meta"
                    + " Graph API call, so the row may legitimately claim verified provenance")
    void syncPlatforms_marksNewStatVerifiedForRealMetaFetch() {
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

        service.syncPlatforms(principal);

        ArgumentCaptor<CreatorMetric> metricCaptor = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metricCaptor.capture());
        assertEquals(
                CreatorMetric.DATA_SOURCE_META_API,
                metricCaptor.getValue().getDataSource(),
                "the on-demand sync must declare its provenance explicitly, not lean on a default");

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                statCaptor.getValue().isVerified(),
                "a stat written from a real Meta Graph API fetch must claim verified provenance —"
                        + " this was hardcoded false, leaving the brand-facing badge dead");
    }

    @Test
    @DisplayName(
            "CR-119: syncPlatforms promotes an EXISTING legacy unverified row rather than pinning"
                    + " it to its stale false")
    void syncPlatforms_promotesExistingUnverifiedRowOnRealFetch() {
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

        // Every row created before CR-119 is verified=false, because no Java writer ever set true.
        PlatformStat legacyUnverified =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle("real_creator")
                        .followers(1000L)
                        .verified(false)
                        .build();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(tokenRow));
        when(metaTokenStorage.getValidCreatorToken(PROFILE_ID)).thenReturn(Optional.of("plaintext-token"));
        when(instagramInsightsClient.getProfile("17841400000000000", "plaintext-token")).thenReturn(igProfile);
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(legacyUnverified));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID))
                .thenReturn(List.of(legacyUnverified));

        service.syncPlatforms(principal);

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                statCaptor.getValue().isVerified(),
                "an on-demand real Meta sync must be able to lift a legacy unverified row to verified");
    }

    @Test
    @DisplayName(
            "CR-119: syncPlatforms declares META_API provenance explicitly — it is the ONLY reason"
                    + " this path may write verified=true")
    void syncPlatforms_verifiedComesFromExplicitlyDeclaredProvenance() {
        // WHY THIS IS NOT A DEMOTION TEST — read before adding one.
        //
        // The aggregation job has a demotion test (a self-reported snapshot must clear a
        // previously-verified row). The equivalent CANNOT be written against this path as
        // black-box behaviour: syncPlatforms constructs its own CreatorMetric with
        // `.dataSource(DATA_SOURCE_META_API)` hardcoded (see PortfolioService#syncPlatforms), so
        // `metric.isPlatformVerified()` is unconditionally true here and no caller input can make
        // it false. A never-demote latch (`existing.isVerified() || metric.isPlatformVerified()`)
        // is therefore behaviourally IDENTICAL to the correct expression on this path — it is
        // unreachable by construction, not merely untested. A test that "caught" it would have to
        // assert on the source expression rather than on behaviour, which pins the implementation
        // and not the contract.
        //
        // What genuinely protects the shared semantic is that BOTH upserts derive the flag from
        // the same single predicate, CreatorMetric#isPlatformVerified() — which is directly
        // covered (PlatformStatsAggregationJobTest's demote/fail-closed/self-reported cases). What
        // this test pins is the one thing that IS reachable and would break silently here: that
        // this path keeps declaring its provenance explicitly instead of drifting onto the
        // builder's fail-closed default, which would silently stop every synced row from being
        // verified at all.
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
        PlatformStat previouslyVerified =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle("real_creator")
                        .followers(1000L)
                        .verified(true)
                        .build();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(tokenRow));
        when(metaTokenStorage.getValidCreatorToken(PROFILE_ID)).thenReturn(Optional.of("plaintext-token"));
        when(instagramInsightsClient.getProfile("17841400000000000", "plaintext-token")).thenReturn(igProfile);
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(previouslyVerified));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID))
                .thenReturn(List.of(previouslyVerified));

        service.syncPlatforms(principal);

        ArgumentCaptor<CreatorMetric> metricCaptor = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metricCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                metricCaptor.getValue().isPlatformVerified(),
                "syncPlatforms must declare META_API provenance explicitly — drifting onto the"
                        + " builder's fail-closed default would silently unverify every synced row");

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                statCaptor.getValue().isVerified(),
                "and the written row must carry that provenance through");
    }

    // ─── F-0139: getVisiblePinnedPosts — the brand-facing pinned-post read ──────────────────
    //
    // M-2 replaced CreatorMapper's hardcoded Collections.emptyList() with real pinned posts on
    // GET /creators/{id}. Nothing asserted any of it: every existing test passed identically
    // whether the mapping were right, inverted, or reverted to the stub. These pin the two
    // things that actually matter — that real posts come through, and that the creator's
    // contentPortfolio visibility flag is honoured on this path too (it is a different caller
    // than the public portfolio page, and a leak here would expose a section the creator
    // deliberately turned off).

    private static CreatorProfile profileWithSettings(String settingsJson) {
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyPortfolioSettingsJson(settingsJson);
        return profile;
    }

    /** `contentPortfolio` is the 5th component of PortfolioVisibility (PortfolioDtos.java:14-23). */
    private static String settingsJson(boolean contentPortfolio, String pinnedPostsJson) {
        return "{\"visibility\":{\"trustBar\":true,\"badges\":true,\"platformStats\":true,"
                + "\"pastCollabs\":true,\"contentPortfolio\":"
                + contentPortfolio
                + ",\"customLinks\":true,\"rateCard\":\"brands_only\",\"languages\":true,"
                + "\"contactForm\":true},\"pinnedPosts\":"
                + pinnedPostsJson
                + "}";
    }

    private static final String ONE_PINNED_POST =
            "[{\"id\":\"pp_1\",\"platform\":\"INSTAGRAM\",\"embedUrl\":\"https://instagram.com/p/abc\","
                    + "\"thumbnailUrl\":\"https://cdn/thumb.jpg\",\"caption\":\"Morning routine\","
                    + "\"views\":12000,\"likes\":900}]";

    @Test
    @DisplayName("getVisiblePinnedPosts: returns the creator's real pinned posts when the section is on")
    void getVisiblePinnedPosts_visible_returnsRealPosts() {
        CreatorProfile profile = profileWithSettings(settingsJson(true, ONE_PINNED_POST));

        var posts = service.getVisiblePinnedPosts(profile);

        assertEquals(1, posts.size(), "a pinned post with the section visible must reach the brand");
        assertEquals("pp_1", posts.get(0).id());
        assertEquals("INSTAGRAM", posts.get(0).platform());
        // Proves it is the stored row, not a placeholder: this is the exact regression M-2 fixed.
        assertEquals("Morning routine", posts.get(0).caption());
    }

    @Test
    @DisplayName("getVisiblePinnedPosts: contentPortfolio=false yields empty, even with posts stored")
    void getVisiblePinnedPosts_sectionHidden_returnsEmpty() {
        // Posts ARE stored — only the visibility flag is off. If the gate were dropped this test
        // fails while the one above still passes, which is the whole point of asserting both.
        CreatorProfile profile = profileWithSettings(settingsJson(false, ONE_PINNED_POST));

        assertEquals(
                List.of(),
                service.getVisiblePinnedPosts(profile),
                "a creator who hid their content portfolio must not have it leak into brand"
                        + " discovery just because a different caller reads it");
    }

    @Test
    @DisplayName("getVisiblePinnedPosts: absent or unparseable settings yield empty, never null")
    void getVisiblePinnedPosts_noSettings_returnsEmpty() {
        // loadSettings falls back to `new PortfolioSettings()` for both cases. Empty (not null)
        // matters because CreatorMapper streams this straight into portfolioItems.
        assertEquals(List.of(), service.getVisiblePinnedPosts(profileWithSettings(null)));
        assertEquals(List.of(), service.getVisiblePinnedPosts(profileWithSettings("   ")));
        assertEquals(List.of(), service.getVisiblePinnedPosts(profileWithSettings("{not json")));
    }
}
