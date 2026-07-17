package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.integration.shopify.dto.ShopifyTokenResponse;
import com.influora.integration.shopify.oauth.ShopifyOAuthService;
import com.influora.integration.shopify.oauth.ShopifyOAuthStateStore;
import com.influora.integration.shopify.oauth.ShopifyTokenStorage;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.shopify.ShopifyDtos.ShopifyAuthorizeResponse;
import com.influora.web.dto.shopify.ShopifyDtos.ShopifyCallbackResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopify OAuth connect flow for brand workspaces (Wave D task D1, free "custom app" install path
 * -- see {@code ShopifyOAuthService} javadoc). Mirrors {@code MetaOAuthController}'s shape:
 *
 * <ul>
 *   <li>{@code /authorize} returns the authorize dialog URL as JSON (matching this codebase's
 *       {@code ApiResponse} envelope convention) rather than issuing a server-side 302 -- the SPA
 *       frontend navigates the browser itself.
 *   <li>{@code /callback} is the endpoint Shopify redirects the merchant's browser to directly, so
 *       it also returns a normal JSON {@code ApiResponse}; the frontend route at {@code
 *       redirect-uri} reads the result and forwards the user.
 * </ul>
 *
 * <p>Brand-authenticated (unlike Meta's creator-only flow) -- Shopify stores are connected at the
 * workspace level via {@link BrandContextService#requireBrandWorkspace}, same resolve-then-scope
 * pattern used by {@code CampaignTrackingController}.
 */
@RestController
@RequestMapping("/shopify/oauth")
public class ShopifyConnectController {

    private final ShopifyOAuthService oAuthService;
    private final ShopifyTokenStorage tokenStorage;
    private final ShopifyOAuthStateStore stateStore;
    private final BrandContextService brandContextService;

    public ShopifyConnectController(
            ShopifyOAuthService oAuthService,
            ShopifyTokenStorage tokenStorage,
            ShopifyOAuthStateStore stateStore,
            BrandContextService brandContextService) {
        this.oAuthService = oAuthService;
        this.tokenStorage = tokenStorage;
        this.stateStore = stateStore;
        this.brandContextService = brandContextService;
    }

    /**
     * Mints a CSRF-bound state token (bound to both the user and the validated shop domain — see
     * {@link ShopifyOAuthStateStore} javadoc) and returns the Shopify authorization dialog URL.
     */
    @GetMapping("/authorize")
    public ApiResponse<ShopifyAuthorizeResponse> authorize(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestParam String shop) {
        brandContextService.requireBrand(principal);
        String validatedShop = ShopifyOAuthService.validateShopDomain(shop);
        String state = stateStore.issue(principal.getUserId(), validatedShop);
        return ApiResponse.ok(
                new ShopifyAuthorizeResponse(oAuthService.buildAuthorizationUrl(validatedShop, state), state));
    }

    /**
     * Handles Shopify's redirect back with {@code code}/{@code state}/{@code shop}. Exchanges the
     * code for a permanent access token, then stores it encrypted scoped to the caller's own
     * workspace.
     */
    @GetMapping("/callback")
    public ApiResponse<ShopifyCallbackResponse> callback(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam String shop) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        String validatedShop = ShopifyOAuthService.validateShopDomain(shop);

        if (!stateStore.consume(state, principal.getUserId(), validatedShop)) {
            throw new ApiException(
                    "SHOPIFY_OAUTH_STATE_INVALID",
                    "OAuth state is invalid, expired, already used, or does not match the requested shop",
                    HttpStatus.BAD_REQUEST);
        }

        ShopifyTokenResponse tokenResponse = oAuthService.exchangeCodeForToken(validatedShop, code);
        List<String> grantedScopes =
                tokenResponse.scope() == null || tokenResponse.scope().isBlank()
                        ? oAuthService.requestedScopes()
                        : Arrays.asList(tokenResponse.scope().split(","));

        tokenStorage.storeToken(workspace.getId(), validatedShop, tokenResponse.accessToken(), grantedScopes);

        return ApiResponse.ok(new ShopifyCallbackResponse(true, validatedShop, grantedScopes));
    }
}
