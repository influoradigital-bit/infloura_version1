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
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
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
import java.time.Instant;
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
                        portfolioEventRepository);
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
}
