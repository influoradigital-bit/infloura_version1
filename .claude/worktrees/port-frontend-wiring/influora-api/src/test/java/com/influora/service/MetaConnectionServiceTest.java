package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import com.influora.web.dto.meta.MetaDtos.MetaDisconnectResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetaConnectionServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final String USER_ID = "user-1";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private FacebookPageClient facebookPageClient;

    private MetaConnectionService service;

    @BeforeEach
    void setUp() {
        service =
                new MetaConnectionService(
                        tokenRepository, tokenStorage, platformStatRepository, facebookPageClient);
    }

    private CreatorProfile testProfile() {
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, USER_ID, "Test Creator");
        profile.applyUsername("creator_handle");
        return profile;
    }

    private MetaOAuthToken activeToken() {
        return MetaOAuthToken.builder()
                .id("token-1")
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("cipher")
                .expiresAt(Instant.now().plusSeconds(86_400))
                .grantedScopesJson("[\"instagram_basic\",\"pages_show_list\"]")
                .build();
    }

    @Test
    @DisplayName("getStatus: returns disconnected when no active token exists")
    void getStatus_noToken_returnsDisconnected() {
        when(tokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        MetaConnectionStatusResponse status = service.getStatus(testProfile(), WORKSPACE_ID);

        assertFalse(status.connected());
        assertNull(status.handle());
        assertNull(status.followers());
        assertNull(status.connectedAt());
        assertTrue(status.grantedScopes().isEmpty());
    }

    @Test
    @DisplayName("getStatus: returns disconnected when token is expired")
    void getStatus_expiredToken_returnsDisconnected() {
        MetaOAuthToken expired =
                MetaOAuthToken.builder()
                        .id("token-expired")
                        .workspaceId(WORKSPACE_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .encryptedAccessToken("cipher")
                        .expiresAt(Instant.now().minusSeconds(60))
                        .grantedScopesJson("[\"instagram_basic\"]")
                        .build();
        when(tokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(expired));

        MetaConnectionStatusResponse status = service.getStatus(testProfile(), WORKSPACE_ID);

        assertFalse(status.connected());
    }

    @Test
    @DisplayName("getStatus: live Meta fetch populates handle and followers when connected")
    void getStatus_connected_usesLiveMetaProfile() {
        when(tokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeToken()));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of("access-token"));
        when(facebookPageClient.resolveConnectedInstagram("access-token"))
                .thenReturn(new FacebookAccountsListResponse.InstagramBusinessAccount("ig-1", "live_handle", 99_000L));

        MetaConnectionStatusResponse status = service.getStatus(testProfile(), WORKSPACE_ID);

        assertTrue(status.connected());
        assertEquals("@live_handle", status.handle());
        assertEquals(99_000L, status.followers());
        assertEquals(List.of("instagram_basic", "pages_show_list"), status.grantedScopes());
        assertTrue(status.connectedAt() != null);
    }

    @Test
    @DisplayName("getStatus: falls back to cached platform stats when Meta API fails")
    void getStatus_metaApiFailure_fallsBackToPlatformStat() {
        PlatformStat igStat = org.mockito.Mockito.mock(PlatformStat.class);
        when(igStat.getPlatform()).thenReturn("instagram");
        when(igStat.getHandle()).thenReturn("cached_handle");
        when(igStat.getFollowers()).thenReturn(42_000L);

        when(tokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeToken()));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of(igStat));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of("access-token"));
        when(facebookPageClient.resolveConnectedInstagram("access-token"))
                .thenThrow(new MetaApiException("rate limited"));

        MetaConnectionStatusResponse status = service.getStatus(testProfile(), WORKSPACE_ID);

        assertTrue(status.connected());
        assertEquals("@cached_handle", status.handle());
        assertEquals(42_000L, status.followers());
    }

    @Test
    @DisplayName("disconnect: delegates revoke to MetaTokenStorage")
    void disconnect_revokesToken() {
        MetaDisconnectResponse response = service.disconnect(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertTrue(response.disconnected());
        verify(tokenStorage).revoke(WORKSPACE_ID, CREATOR_PROFILE_ID);
        verify(facebookPageClient, never()).resolveConnectedInstagram(anyString());
    }
}
