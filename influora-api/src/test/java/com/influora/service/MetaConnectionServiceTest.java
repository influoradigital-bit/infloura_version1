package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.MetaGraphApiClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
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

/**
 * Unit tests for {@link MetaConnectionService}. CR-106: getStatus/disconnect must operate on the
 * creator-owned key-space ({@code workspace_id IS NULL}) — the same key-space {@link
 * com.influora.service.creatorcopilot.CreatorMetaOAuthService#connect} writes to — not the
 * brand/workspace-scoped one, which is a disjoint key-space by construction and would silently
 * match nothing for a real creator row.
 */
@ExtendWith(MockitoExtension.class)
class MetaConnectionServiceTest {

    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final String USER_ID = "user-1";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private FacebookPageClient facebookPageClient;
    @Mock private MetaGraphApiClient graphApiClient;

    private MetaConnectionService service;

    @BeforeEach
    void setUp() {
        service =
                new MetaConnectionService(
                        tokenRepository,
                        tokenStorage,
                        platformStatRepository,
                        facebookPageClient,
                        graphApiClient);
    }

    private CreatorProfile testProfile() {
        CreatorProfile profile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, USER_ID, "Test Creator");
        profile.applyUsername("creator_handle");
        return profile;
    }

    private MetaOAuthToken activeToken() {
        return MetaOAuthToken.builder()
                .id("token-1")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("cipher")
                .expiresAt(Instant.now().plusSeconds(86_400))
                .grantedScopesJson("[\"instagram_basic\",\"pages_show_list\"]")
                .build();
    }

    @Test
    @DisplayName("getStatus: returns disconnected when no active token exists")
    void getStatus_noToken_returnsDisconnected() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

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
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .encryptedAccessToken("cipher")
                        .expiresAt(Instant.now().minusSeconds(60))
                        .grantedScopesJson("[\"instagram_basic\"]")
                        .build();
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(expired));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertFalse(status.connected());
    }

    @Test
    @DisplayName("getStatus: live Meta fetch populates handle and followers when connected")
    void getStatus_connected_usesLiveMetaProfile() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeToken()));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("access-token"));
        when(facebookPageClient.resolveConnectedInstagram("access-token"))
                .thenReturn(new FacebookAccountsListResponse.InstagramBusinessAccount("ig-1", "live_handle", 99_000L));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

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

        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeToken()));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of(igStat));
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("access-token"));
        when(facebookPageClient.resolveConnectedInstagram("access-token"))
                .thenThrow(new MetaApiException("rate limited"));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertTrue(status.connected());
        assertEquals("@cached_handle", status.handle());
        assertEquals(42_000L, status.followers());
    }

    @Test
    @DisplayName("disconnect: delegates to the creator-scoped revoke, not the workspace-scoped one (CR-106)")
    void disconnect_revokesCreatorScopedToken() {
        MetaDisconnectResponse response = service.disconnect(CREATOR_PROFILE_ID);

        assertTrue(response.disconnected());
        verify(tokenStorage).revokeCreatorToken(CREATOR_PROFILE_ID);
        verify(tokenStorage, never()).revoke(anyString(), anyString());
        verify(facebookPageClient, never()).resolveConnectedInstagram(anyString());
    }

    // ------------------------------------------------------------------------------------------
    // F-0871 — an INSTAGRAM_LOGIN token must never be sent to the Facebook Page lookup
    // ------------------------------------------------------------------------------------------

    private MetaOAuthToken instagramLoginToken(String igUserId) {
        return MetaOAuthToken.builder()
                .id("token-ig")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("cipher")
                .expiresAt(Instant.now().plusSeconds(86_400))
                .grantedScopesJson(
                        "[\"instagram_business_basic\",\"instagram_business_manage_insights\"]")
                .igBusinessAccountId(igUserId)
                .authPath(MetaAuthPath.INSTAGRAM_LOGIN)
                .build();
    }

    private static InstagramUserResponse igUser(String username, Long followers) {
        return new InstagramUserResponse(null, username, null, null, followers, null, null, null, null);
    }

    @Test
    @DisplayName(
            "F-0871: an Instagram-Login creator status reads graph.instagram.com, never the Facebook"
                    + " Page lookup that rejects their token with 190")
    void getStatus_instagramLogin_readsInstagramHostNotFacebookPages() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(instagramLoginToken("17841400000000001")));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("IGAAtoken"));
        when(graphApiClient.get(
                        eq(MetaConnectionService.INSTAGRAM_LOGIN_PROFILE_PATH),
                        eq("IGAAtoken"),
                        eq(InstagramUserResponse.class),
                        eq("17841400000000001"),
                        eq(MetaAuthPath.INSTAGRAM_LOGIN)))
                .thenReturn(igUser("ig_only_creator", 12_345L));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertTrue(status.connected());
        assertEquals("@ig_only_creator", status.handle());
        assertEquals(12_345L, status.followers());
        verifyNoInteractions(facebookPageClient);
    }

    @Test
    @DisplayName("F-0871: a Facebook-Login creator status still uses the Page lookup and nothing else")
    void getStatus_facebookLogin_unchanged() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeToken()));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("EAAtoken"));
        when(facebookPageClient.resolveConnectedInstagram("EAAtoken"))
                .thenReturn(new FacebookAccountsListResponse.InstagramBusinessAccount("ig-1", "fb_path", 7L));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertEquals("@fb_path", status.handle());
        verifyNoInteractions(graphApiClient);
    }

    @Test
    @DisplayName(
            "F-0871: with no stored Instagram id the call still goes out, rate-limited under the"
                    + " creator id rather than a null key")
    void getStatus_instagramLogin_missingIgId_usesCreatorIdAsRateLimitKey() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(instagramLoginToken(null)));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of());
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("IGAAtoken"));
        when(graphApiClient.get(
                        any(), any(), eq(InstagramUserResponse.class), eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(igUser("no_id_yet", 5L));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertEquals("@no_id_yet", status.handle());
        verifyNoInteractions(facebookPageClient);
    }

    @Test
    @DisplayName(
            "F-0871: an Instagram API failure still reports connected, from cache, instead of failing"
                    + " the status call")
    void getStatus_instagramLogin_apiFailure_fallsBackToCache() {
        PlatformStat igStat = org.mockito.Mockito.mock(PlatformStat.class);
        when(igStat.getPlatform()).thenReturn("instagram");
        when(igStat.getHandle()).thenReturn("cached_ig");
        when(igStat.getFollowers()).thenReturn(900L);
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(instagramLoginToken("17841400000000001")));
        when(platformStatRepository.findByCreatorProfileId(CREATOR_PROFILE_ID)).thenReturn(List.of(igStat));
        when(tokenStorage.getValidCreatorToken(CREATOR_PROFILE_ID)).thenReturn(Optional.of("IGAAtoken"));
        when(graphApiClient.get(any(), any(), eq(InstagramUserResponse.class), any(), any()))
                .thenThrow(new MetaApiException("down"));

        MetaConnectionStatusResponse status = service.getStatus(testProfile());

        assertTrue(status.connected());
        assertEquals("@cached_ig", status.handle());
        assertEquals(900L, status.followers());
        verifyNoInteractions(facebookPageClient);
    }
}
