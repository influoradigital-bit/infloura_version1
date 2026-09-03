package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedProfileResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.8, A9; fix round 2, item 4 — Priya Q10). Covers the
 * suspension gap: {@link CreatorProfile#isSuspended()} was never consulted by {@link
 * PublicCreatorService#getVerifiedMetrics}, so an admin-suspended creator's public verified page
 * kept serving live metrics with a plain 200. Also proves the existing non-enumeration property
 * (not-discoverable, not-Meta-connected, suspended, and truly-nonexistent all return the SAME 404)
 * still holds after the fix.
 */
@ExtendWith(MockitoExtension.class)
class PublicCreatorServiceTest {

    private static final String USERNAME = "priya-shah";
    private static final String PROFILE_ID = "profile-1";
    private static final String USER_ID = "user-1";

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorProfile profile;

    private PublicCreatorService service;

    @BeforeEach
    void setUp() {
        service =
                new PublicCreatorService(
                        creatorProfileRepository,
                        creatorMetricsRepository,
                        metaOAuthTokenRepository,
                        collaborationRepository);
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn(PROFILE_ID);
    }

    private void stubDiscoverableAndConnected() {
        when(profile.isDiscoverable()).thenReturn(true);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalse(PROFILE_ID))
                .thenReturn(List.of(mock(MetaOAuthToken.class)));
    }

    @Test
    @DisplayName("a suspended creator gets 404 CREATOR_NOT_FOUND, even when discoverable and Meta-connected")
    void suspendedCreator_returns404() {
        stubDiscoverableAndConnected();
        when(profile.isSuspended()).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.getVerifiedMetrics(USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("a non-suspended, discoverable, Meta-connected creator's metrics are served")
    void activeCreator_isServed() {
        stubDiscoverableAndConnected();
        when(profile.isSuspended()).thenReturn(false);
        when(profile.getUsername()).thenReturn(USERNAME);
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getUserId()).thenReturn(USER_ID);
        when(profile.getTotalFollowers()).thenReturn(12_400L);
        when(profile.getEngagementRate()).thenReturn(null);
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorIdAndStatus(
                        USER_ID, com.influora.domain.enums.CollaborationStatus.COMPLETED))
                .thenReturn(List.of());

        VerifiedProfileResponse response = service.getVerifiedMetrics(USERNAME);

        assertEquals(USERNAME, response.username());
    }

    @Test
    @DisplayName("a not-discoverable creator gets the SAME 404 code as a suspended one -- no enumeration signal")
    void notDiscoverableCreator_returnsSame404AsSuspended() {
        when(profile.isDiscoverable()).thenReturn(false);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalse(PROFILE_ID)).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.getVerifiedMetrics(USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
