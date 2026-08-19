package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.MetaAuthPath;
import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaOAuthStateStore;
import com.influora.security.AuthPrincipal;
import com.influora.service.MetaConnectionService;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService.ConnectResult;
import com.influora.web.dto.meta.MetaDtos.MetaAuthorizeResponse;
import com.influora.web.dto.meta.MetaDtos.MetaCallbackResponse;
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
 * Unit tests for {@link MetaOAuthController}. {@code /callback} now delegates the exchange +
 * creator-owned token storage to {@link CreatorMetaOAuthService} (Creator AI Co-pilot Tier-1
 * OAuth-flip fix, be-services-plan.md §0/§3) instead of calling {@code MetaOAuthService}/{@code
 * MetaTokenStorage} directly with {@code principal.getWorkspaceId()} — that old call path is the
 * verified-broken one this fix replaces (a CREATOR principal has no workspaceId, so it always
 * threw {@code DataIntegrityViolationException} on insert).
 */
@ExtendWith(MockitoExtension.class)
class MetaOAuthControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final AuthPrincipal CREATOR_PRINCIPAL =
            new AuthPrincipal(USER_ID, "creator@example.com", UserType.CREATOR, WORKSPACE_ID);

    @Mock private MetaOAuthService oAuthService;
    @Mock private MetaOAuthStateStore stateStore;
    @Mock private com.influora.repository.CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorMetaOAuthService creatorMetaOAuthService;
    @Mock private MetaConnectionService metaConnectionService;

    private final com.influora.config.MetaApiProperties metaApiProperties =
            new com.influora.config.MetaApiProperties();

    private MetaOAuthController controller;

    @BeforeEach
    void setUp() {
        controller =
                new MetaOAuthController(
                        oAuthService,
                        stateStore,
                        creatorProfileRepository,
                        creatorMetaOAuthService,
                        metaConnectionService,
                        metaApiProperties);
    }

    private CreatorProfile testProfile() {
        return CreatorProfile.newForUser(CREATOR_PROFILE_ID, USER_ID, "Test Creator");
    }

    @Test
    @DisplayName("authorize: issues CSRF state and returns the Meta dialog URL")
    void authorize_issuesStateAndReturnsUrl() {
        when(stateStore.issue(eq(USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN))).thenReturn("state-123");
        when(oAuthService.buildAuthorizationUrl(eq("state-123")))
                .thenReturn("https://www.facebook.com/v19.0/dialog/oauth?...");

        var response = controller.authorize(CREATOR_PRINCIPAL, null);

        assertTrue(response.data() instanceof MetaAuthorizeResponse);
        assertEquals("state-123", response.data().state());
        verify(stateStore, times(1)).issue(eq(USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    @Test
    @DisplayName("authorize: rejects a non-creator principal")
    void authorize_rejectsNonCreator() {
        AuthPrincipal brandPrincipal = new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

        assertThrows(ApiException.class, () -> controller.authorize(brandPrincipal, null));
        verify(stateStore, never()).issue(anyString(), any(MetaAuthPath.class));
    }

    @Test
    @DisplayName("callback: happy path delegates to CreatorMetaOAuthService, never touches principal.workspaceId")
    void callback_happyPath_delegatesToCreatorMetaOAuthService() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(stateStore.consumePath("state-123", USER_ID))
                .thenReturn(java.util.Optional.of(MetaAuthPath.FACEBOOK_LOGIN));
        when(creatorMetaOAuthService.connect(CREATOR_PROFILE_ID, "auth-code", MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new ConnectResult(true, MetaOAuthService.REQUIRED_SCOPES, "business"));

        MetaCallbackResponse response =
                controller.callback(CREATOR_PRINCIPAL, "auth-code", "state-123").data();

        assertTrue(response.connected());
        assertEquals(MetaOAuthService.REQUIRED_SCOPES, response.grantedScopes());
        assertEquals("business", response.accountType());
        verify(creatorMetaOAuthService, times(1))
                .connect(CREATOR_PROFILE_ID, "auth-code", MetaAuthPath.FACEBOOK_LOGIN);
    }

    @Test
    @DisplayName("callback: NO_BUSINESS_ACCOUNT surfaces as a 200 with connected=false, accountType=personal")
    void callback_noBusinessAccount_surfacesAsPersonalAccountType() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(stateStore.consumePath("state-123", USER_ID))
                .thenReturn(java.util.Optional.of(MetaAuthPath.FACEBOOK_LOGIN));
        when(creatorMetaOAuthService.connect(CREATOR_PROFILE_ID, "auth-code", MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new ConnectResult(false, MetaOAuthService.REQUIRED_SCOPES, "personal"));

        MetaCallbackResponse response =
                controller.callback(CREATOR_PRINCIPAL, "auth-code", "state-123").data();

        assertTrue(!response.connected());
        assertEquals("personal", response.accountType());
    }

    @Test
    @DisplayName("callback: rejects invalid state before any token exchange")
    void callback_invalidState_rejectedBeforeExchange() {
        when(stateStore.consumePath("bad-state", USER_ID)).thenReturn(java.util.Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.callback(CREATOR_PRINCIPAL, "auth-code", "bad-state"));

        assertEquals("META_OAUTH_STATE_INVALID", ex.getCode());
        verify(oAuthService, never()).exchangeCodeForToken(anyString());
        verify(creatorMetaOAuthService, never())
                .connect(anyString(), anyString(), any(MetaAuthPath.class));
    }

    @Test
    @DisplayName("status: resolves the caller's own profile and delegates to MetaConnectionService")
    void status_delegatesToMetaConnectionService() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MetaConnectionStatusResponse expected =
                new MetaConnectionStatusResponse(
                        true, "@creator_handle", 1_000L, Instant.now(), List.of("instagram_basic"));
        when(metaConnectionService.getStatus(profile)).thenReturn(expected);

        MetaConnectionStatusResponse response = controller.status(CREATOR_PRINCIPAL).data();

        assertEquals(expected, response);
        verify(metaConnectionService, times(1)).getStatus(profile);
    }

    @Test
    @DisplayName("status: rejects a non-creator principal before touching the service")
    void status_rejectsNonCreator() {
        AuthPrincipal brandPrincipal = new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

        assertThrows(ApiException.class, () -> controller.status(brandPrincipal));
        verify(metaConnectionService, never()).getStatus(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("disconnect: resolves the caller's own profile id and delegates to MetaConnectionService (CR-106)")
    void disconnect_delegatesToMetaConnectionServiceWithOwnCreatorId() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(metaConnectionService.disconnect(CREATOR_PROFILE_ID))
                .thenReturn(new MetaDisconnectResponse(true));

        MetaDisconnectResponse response = controller.disconnect(CREATOR_PRINCIPAL).data();

        assertTrue(response.disconnected());
        verify(metaConnectionService, times(1)).disconnect(CREATOR_PROFILE_ID);
    }

    @Test
    @DisplayName("disconnect: rejects a non-creator principal before touching the service")
    void disconnect_rejectsNonCreator() {
        AuthPrincipal brandPrincipal = new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

        assertThrows(ApiException.class, () -> controller.disconnect(brandPrincipal));
        verify(metaConnectionService, never()).disconnect(anyString());
    }

    @Test
    @DisplayName("status: 404s when the authenticated user has no creator profile")
    void status_noCreatorProfile_notFound() {
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> controller.status(CREATOR_PRINCIPAL));

        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
        verify(metaConnectionService, never()).getStatus(org.mockito.ArgumentMatchers.any());
    }
}
