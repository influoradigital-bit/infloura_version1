package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.ShopifyProperties;
import com.influora.domain.entity.Workspace;
import com.influora.integration.shopify.ShopifyWebhookRegistrar;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ShopifyConnectController.class);

    private final ShopifyOAuthService oAuthService;
    private final ShopifyTokenStorage tokenStorage;
    private final ShopifyOAuthStateStore stateStore;
    private final BrandContextService brandContextService;
    private final ShopifyProperties shopifyProperties;
    private final ShopifyWebhookRegistrar webhookRegistrar;

    public ShopifyConnectController(
            ShopifyOAuthService oAuthService,
            ShopifyTokenStorage tokenStorage,
            ShopifyOAuthStateStore stateStore,
            BrandContextService brandContextService,
            ShopifyProperties shopifyProperties,
            ShopifyWebhookRegistrar webhookRegistrar) {
        this.oAuthService = oAuthService;
        this.tokenStorage = tokenStorage;
        this.stateStore = stateStore;
        this.brandContextService = brandContextService;
        this.shopifyProperties = shopifyProperties;
        this.webhookRegistrar = webhookRegistrar;
    }

    /**
     * Mints a CSRF-bound state token (bound to both the user and the validated shop domain — see
     * {@link ShopifyOAuthStateStore} javadoc) and returns the Shopify authorization dialog URL.
     */
    @GetMapping("/authorize")
    public ApiResponse<ShopifyAuthorizeResponse> authorize(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestParam String shop) {
        brandContextService.requireBrand(principal);

        // [F-0732] Fail loudly rather than handing back an authorize URL with a blank client_id.
        // ShopifyProperties.apiKey/apiSecret default to "" and NOTHING binds them — no yaml
        // placeholder, no deploy file, no generate-env.sh entry (F-0729) — so isConfigured() is
        // false in every environment today. Without this guard the brand is redirected into
        // Shopify's own UI, which answers a blank client_id with its own error page; that reads to
        // the brand as their store or account being broken rather than as this deploy simply not
        // having a Shopify app configured. Mirrors MetaOAuthController#authorize's identical guard
        // (META_NOT_CONFIGURED, 503), including the deliberately environment-level message: the
        // caller is told the capability is unavailable here, never which credential is missing.
        //
        // Guarding ONLY authorize is intentional, and matches Meta's shape: /callback is
        // unreachable with a valid state because no state can be minted once this throws. Adding a
        // second guard there would suggest the callback is independently reachable, which it is not.
        if (!shopifyProperties.isConfigured()) {
            throw new ApiException(
                    "SHOPIFY_NOT_CONFIGURED",
                    "Connecting a Shopify store is not available on this environment",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

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

        // [F-0730] A Shopify app receives ONLY the topics it subscribes to. Storing the token used
        // to be the last step, so a store could complete OAuth, report "connected", and never
        // deliver a single order — ShopifyWebhookController was reachable in principle and dead in
        // practice, with nothing reporting a problem.
        //
        // Registration failure REVOKES the token just stored, and the connect fails. The tempting
        // alternative — keep the token, log the failure, return connected:true — reproduces exactly
        // the silent half-connected state this ticket exists to end: status would read "connected"
        // while no order could ever arrive. A brand who sees an error retries; a brand who sees
        // success never looks again.
        try {
            webhookRegistrar.registerOrderWebhooks(validatedShop, tokenResponse.accessToken());
        } catch (RuntimeException registrationFailure) {
            tokenStorage.revoke(workspace.getId());
            log.error(
                    "Shopify connect for shop={} rolled back: the access token was obtained but the order"
                            + " webhooks could not be registered, so the store would have been connected and"
                            + " deaf (F-0730)",
                    validatedShop,
                    registrationFailure);
            throw registrationFailure;
        }

        return ApiResponse.ok(new ShopifyCallbackResponse(true, validatedShop, grantedScopes));
    }
}
