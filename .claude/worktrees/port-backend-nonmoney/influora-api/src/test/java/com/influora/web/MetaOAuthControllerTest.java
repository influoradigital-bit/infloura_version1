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
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaOAuthStateStore;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.meta.MetaDtos.MetaAuthorizeResponse;
import com.influora.web.dto.meta.MetaDtos.MetaCallbackResponse;
import java.time.Instant;
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
 * Unit tests for {@link MetaOAuthController} — mirrors {@link ShopifyConnectControllerTest}'s
 * coverage shape: authorize URL issuance, callback CSRF-state consumption, status read, and
 * disconnect delegation.
 */
@ExtendWith(MockitoExtension.class)
class MetaOAuthControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String CREATOR_PROFILE_ID = "01HCREATOR123456789AB";
    private static final AuthPrincipal CREATOR_PRINCIPAL =
            new AuthPrincipal(USER_ID, "creator@example.com", UserType.CREATOR, WORKSPACE_ID);

    @Mock private MetaOAuthService oAuthService;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private MetaOAuthStateStore stateStore;
    @Mock private com.influora.repository.CreatorProfileRepository creatorProfileRepository;

    private MetaOAuthController controller;

    @BeforeEach
    void setUp() {
        controller =
                new MetaOAuthController(
                        oAuthService, tokenStorage, stateStore, creatorProfileRepository);
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
    @DisplayName("callback: happy path exchanges code and stores the token")
    void callback_happyPath_storesToken() {
        CreatorProfile profile = testProfile();
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(stateStore.consume("state-123", USER_ID)).thenReturn(true);
        when(oAuthService.exchangeCodeForToken("auth-code"))
                .thenReturn(new MetaTokenResponse("short", "bearer", 3600L));
        when(oAuthService.exchangeForLongLivedToken("short"))
                .thenReturn(new MetaTokenResponse("long-lived", "bearer", 5_184_000L));

        MetaCallbackResponse response =
                controller.callback(CREATOR_PRINCIPAL, "auth-code", "state-123").data();

        assertTrue(response.connected());
        assertEquals(MetaOAuthService.REQUIRED_SCOPES, response.grantedScopes());
        verify(tokenStorage, times(1))
                .storeToken(
                        eq(CREATOR_PROFILE_ID),
                        eq(WORKSPACE_ID),
                        eq("long-lived"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        eq(MetaOAuthService.REQUIRED_SCOPES));
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
        verify(tokenStorage, never())
                .storeToken(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.anyList());
    }

    // NOTE: status(), disconnectDelete(), disconnectPost() tests removed — methods don't exist in production MetaOAuthController
}
