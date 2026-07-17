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
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.integration.shopify.dto.ShopifyTokenResponse;
import com.influora.integration.shopify.oauth.ShopifyOAuthService;
import com.influora.integration.shopify.oauth.ShopifyOAuthStateStore;
import com.influora.integration.shopify.oauth.ShopifyTokenStorage;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.shopify.ShopifyDtos.ShopifyAuthorizeResponse;
import com.influora.web.dto.shopify.ShopifyDtos.ShopifyCallbackResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ShopifyConnectController} — mirrors {@code MetaOAuthController}'s test
 * coverage shape: authorize URL issuance, callback CSRF-state consumption (including the
 * Shopify-specific shop-domain binding), and token storage delegation.
 */
@ExtendWith(MockitoExtension.class)
class ShopifyConnectControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SHOP = "my-test-store.myshopify.com";
    private static final AuthPrincipal BRAND_PRINCIPAL =
            new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

    @Mock private ShopifyOAuthService oAuthService;
    @Mock private ShopifyTokenStorage tokenStorage;
    @Mock private ShopifyOAuthStateStore stateStore;
    @Mock private BrandContextService brandContextService;

    private ShopifyConnectController controller;

    @BeforeEach
    void setUp() {
        controller = new ShopifyConnectController(oAuthService, tokenStorage, stateStore, brandContextService);
    }

    private Workspace testWorkspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "fashion", "1-10");
    }

    @Test
    @DisplayName("authorize: issues a state bound to the validated shop and returns the dialog URL")
    void authorize_issuesStateAndReturnsUrl() {
        when(oAuthService.buildAuthorizationUrl(SHOP, "state-123")).thenReturn("https://" + SHOP + "/admin/oauth/authorize?...");
        when(stateStore.issue(USER_ID, SHOP)).thenReturn("state-123");

        var response = controller.authorize(BRAND_PRINCIPAL, SHOP);

        assertTrue(response.data() instanceof ShopifyAuthorizeResponse);
        assertEquals("state-123", response.data().state());
        assertTrue(response.data().authorizationUrl().startsWith("https://" + SHOP));
        verify(stateStore, times(1)).issue(USER_ID, SHOP);
    }

    @Test
    @DisplayName("authorize: rejects a non-brand principal")
    void authorize_rejectsNonBrand() {
        org.mockito.Mockito.doThrow(
                        new ApiException("WRONG_USER_TYPE", "This endpoint is for brand accounts only", org.springframework.http.HttpStatus.FORBIDDEN))
                .when(brandContextService)
                .requireBrand(BRAND_PRINCIPAL);

        assertThrows(ApiException.class, () -> controller.authorize(BRAND_PRINCIPAL, SHOP));
        verify(stateStore, never()).issue(anyString(), anyString());
    }

    @Test
    @DisplayName("authorize: rejects an invalid shop domain before minting any state")
    void authorize_rejectsInvalidShop() {
        assertThrows(ApiException.class, () -> controller.authorize(BRAND_PRINCIPAL, "evil.com"));
        verify(stateStore, never()).issue(anyString(), anyString());
    }

    @Test
    @DisplayName("callback: happy path exchanges code, consumes state, and stores the token scoped to the caller's workspace")
    void callback_happyPath_storesTokenForWorkspace() {
        Workspace workspace = testWorkspace();
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(workspace);
        when(stateStore.consume("state-123", USER_ID, SHOP)).thenReturn(true);
        when(oAuthService.exchangeCodeForToken(SHOP, "auth-code"))
                .thenReturn(new ShopifyTokenResponse("shpat_abc", "read_orders,read_products"));

        ShopifyCallbackResponse response =
                controller.callback(BRAND_PRINCIPAL, "auth-code", "state-123", SHOP).data();

        assertTrue(response.connected());
        assertEquals(SHOP, response.shopDomain());
        assertEquals(List.of("read_orders", "read_products"), response.grantedScopes());
        verify(tokenStorage, times(1))
                .storeToken(eq(WORKSPACE_ID), eq(SHOP), eq("shpat_abc"), eq(List.of("read_orders", "read_products")));
    }

    @Test
    @DisplayName("callback: rejects an invalid/expired/mismatched-shop state BEFORE any token exchange")
    void callback_invalidState_rejectedBeforeExchange() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());
        when(stateStore.consume("bad-state", USER_ID, SHOP)).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> controller.callback(BRAND_PRINCIPAL, "auth-code", "bad-state", SHOP));

        assertEquals("SHOPIFY_OAUTH_STATE_INVALID", ex.getCode());
        verify(oAuthService, never()).exchangeCodeForToken(anyString(), anyString());
        verify(tokenStorage, never()).storeToken(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("callback: falls back to the requested scopes when Shopify's token response omits scope")
    void callback_missingScopeInResponse_fallsBackToRequestedScopes() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());
        when(stateStore.consume("state-123", USER_ID, SHOP)).thenReturn(true);
        when(oAuthService.exchangeCodeForToken(SHOP, "auth-code")).thenReturn(new ShopifyTokenResponse("shpat_abc", null));
        when(oAuthService.requestedScopes()).thenReturn(List.of("read_orders"));

        ShopifyCallbackResponse response =
                controller.callback(BRAND_PRINCIPAL, "auth-code", "state-123", SHOP).data();

        assertEquals(List.of("read_orders"), response.grantedScopes());
        verify(tokenStorage, times(1)).storeToken(WORKSPACE_ID, SHOP, "shpat_abc", List.of("read_orders"));
    }
}
