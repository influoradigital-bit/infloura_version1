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
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.notification.NotificationService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioAnalyticsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;

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
                        notificationService,
                        userRepository);
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
}
