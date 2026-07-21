package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaOAuthStateStore;
import com.influora.security.AuthPrincipal;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService.ConnectResult;
import com.influora.web.dto.meta.MetaDtos.MetaAuthorizeResponse;
import com.influora.web.dto.meta.MetaDtos.MetaCallbackResponse;
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

    private MetaOAuthController controller;

    @BeforeEach
    void setUp() {
        controller =
                new MetaOAuthController(
                        oAuthService, stateStore, creatorProfileRepository, creatorMetaOAuthService);
    }

    private CreatorProfile testProfile() {
        return CreatorProfile.newForUser(CREATOR_PROFILE_ID, USER_ID, "Test Creator");
    }

    @Test
    @DisplayName("authorize: issues CSRF state and returns the Meta dialog URL")
    void authorize_issuesStateAndReturnsUrl() {
        when(stateStore.issue(eq(USER_ID))).thenReturn("state-123");
        when(oAuthService.buildAuthorizationUrl(eq("state-123")))
                .thenReturn("https://www.facebook.com/v19.0/dialog/oauth?...");

        var response = controller.authorize(CREATOR_PRINCIPAL);

        assertTrue(response.data() instanceof MetaAuthorizeResponse);
        assertEquals("state-123", response.data().state());
        verify(stateStore, times(1)).issue(eq(USER_ID));
    }

    @Test
    @DisplayName("authorize: rejects a non-creator principal")
    void authorize_rejectsNonCreator() {
        AuthPrincipal brandPrincipal = new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

        assertThrows(ApiException.class, () -> controller.authorize(brandPrincipal));
        verify(stateStore, never()).issue(anyString());
    }

    @Test
    @DisplayName("callback: happy path delegates to CreatorMetaOAuthService, never touches principal.workspaceId")
    void callback_happyPath_delegatesToCreatorMetaOAuthService() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(stateStore.consume("state-123", USER_ID)).thenReturn(true);
        when(creatorMetaOAuthService.connect(CREATOR_PROFILE_ID, "auth-code"))
                .thenReturn(new ConnectResult(true, MetaOAuthService.REQUIRED_SCOPES, "business"));

        MetaCallbackResponse response =
                controller.callback(CREATOR_PRINCIPAL, "auth-code", "state-123").data();

        assertTrue(response.connected());
        assertEquals(MetaOAuthService.REQUIRED_SCOPES, response.grantedScopes());
        assertEquals("business", response.accountType());
        verify(creatorMetaOAuthService, times(1)).connect(CREATOR_PROFILE_ID, "auth-code");
    }

    @Test
    @DisplayName("callback: NO_BUSINESS_ACCOUNT surfaces as a 200 with connected=false, accountType=personal")
    void callback_noBusinessAccount_surfacesAsPersonalAccountType() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(stateStore.consume("state-123", USER_ID)).thenReturn(true);
        when(creatorMetaOAuthService.connect(CREATOR_PROFILE_ID, "auth-code"))
                .thenReturn(new ConnectResult(false, MetaOAuthService.REQUIRED_SCOPES, "personal"));

        MetaCallbackResponse response =
                controller.callback(CREATOR_PRINCIPAL, "auth-code", "state-123").data();

        assertTrue(!response.connected());
        assertEquals("personal", response.accountType());
    }

    @Test
    @DisplayName("callback: rejects invalid state before any token exchange")
    void callback_invalidState_rejectedBeforeExchange() {
        when(stateStore.consume("bad-state", USER_ID)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.callback(CREATOR_PRINCIPAL, "auth-code", "bad-state"));

        assertEquals("META_OAUTH_STATE_INVALID", ex.getCode());
        verify(oAuthService, never()).exchangeCodeForToken(anyString());
        verify(creatorMetaOAuthService, never()).connect(anyString(), anyString());
    }

    // NOTE: status(), disconnectDelete(), disconnectPost() tests removed — methods don't exist in production MetaOAuthController
}
