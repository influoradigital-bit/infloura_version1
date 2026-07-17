package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.integration.woocommerce.WooCommerceIntegrationService;
import com.influora.integration.woocommerce.WooCommerceSiteUrl;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.woocommerce.WooCommerceDtos.WooCommerceConnectRequest;
import com.influora.web.dto.woocommerce.WooCommerceDtos.WooCommerceConnectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WooCommerce connect flow for brand workspaces (Wave D task D2, {@code
 * wiki/tech/REMAINING_WORK_PLAN.md}). Structurally different from {@code ShopifyConnectController}
 * because WooCommerce has no OAuth app-install flow at all: WooCommerce is REST-webhook based
 * (Store API / WooCommerce REST API webhooks), confirmed against WooCommerce's own webhook
 * documentation, not assumed by analogy to Shopify -- a brand creates a webhook directly in their
 * own WooCommerce admin (Settings -> Advanced -> Webhooks), pointed at our {@code
 * /webhooks/woocommerce} endpoint, and WooCommerce lets them set (or auto-generates) a "Secret" for
 * that webhook. There is no authorize dialog, no code/state exchange, no redirect -- the brand
 * simply submits their site URL and that secret to us directly via this single endpoint.
 *
 * <p>Brand-authenticated, same as {@code ShopifyConnectController} -- WooCommerce stores are
 * connected at the workspace level via {@link BrandContextService#requireBrandWorkspace}, same
 * resolve-then-scope pattern used by every other brand-facing connect controller in this codebase.
 *
 * <p><b>[SEC] Site URL normalization is load-bearing, not cosmetic.</b> {@link
 * WooCommerceSiteUrl#normalize} canonicalizes the submitted URL (scheme + host [+ port], lower-cased,
 * no path/trailing slash) BEFORE it is persisted -- see that class's javadoc for why an
 * un-normalized value would cause a real functional bug (every subsequent webhook rejected as
 * {@code SITE_NOT_CONNECTED}), not just an SSRF-shaped concern. This integration never makes an
 * outbound call to the submitted URL at all (webhook-receive-only), so there is no SSRF vector
 * here the way there would be if this server dialed out to a caller-supplied host.
 */
@RestController
@RequestMapping("/woocommerce")
public class WooCommerceConnectController {

    private final WooCommerceIntegrationService integrationService;
    private final BrandContextService brandContextService;

    public WooCommerceConnectController(
            WooCommerceIntegrationService integrationService, BrandContextService brandContextService) {
        this.integrationService = integrationService;
        this.brandContextService = brandContextService;
    }

    /**
     * Validates + normalizes the submitted site URL, requires a non-blank webhook secret, and
     * stores the secret AES-256-GCM encrypted, scoped to the caller's own workspace (rotating any
     * existing connection for that workspace in place, mirroring {@code
     * ShopifyConnectController#callback}'s reconnect behavior).
     */
    @PostMapping("/connect")
    public ApiResponse<WooCommerceConnectResponse> connect(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestBody WooCommerceConnectRequest request) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);

        String normalizedSiteUrl = WooCommerceSiteUrl.normalize(request == null ? null : request.siteUrl());

        String webhookSecret = request == null ? null : request.webhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ApiException(
                    "WEBHOOK_SECRET_REQUIRED",
                    "webhookSecret is required -- copy the Secret from the webhook you create in"
                            + " WooCommerce (Settings -> Advanced -> Webhooks)",
                    HttpStatus.BAD_REQUEST);
        }

        integrationService.connect(workspace.getId(), normalizedSiteUrl, webhookSecret);

        return ApiResponse.ok(new WooCommerceConnectResponse(true, normalizedSiteUrl));
    }
}
