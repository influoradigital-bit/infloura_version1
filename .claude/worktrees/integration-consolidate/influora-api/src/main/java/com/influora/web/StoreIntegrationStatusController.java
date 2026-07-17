package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WooCommerceIntegration;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.repository.WooCommerceIntegrationRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.integration.IntegrationDtos.DisconnectResponse;
import com.influora.web.dto.integration.IntegrationDtos.IntegrationStatusResponse;
import com.influora.web.dto.integration.IntegrationDtos.StoreProvider;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store integration status + disconnect endpoints (Wave D tasks D1/D2 follow-up, P2-9) — unified for
 * Shopify + WooCommerce, handling the shared read/revoke operations that are provider-agnostic.
 * Structurally different from {@code ShopifyConnectController} / {@code WooCommerceConnectController},
 * which handle the provider-specific OAuth/connect flows — this controller handles the shared
 * status/disconnect shape that works for BOTH store types.
 *
 * <p>Brand-authenticated, same as all other integration controllers -- status/disconnect operations
 * are scoped to the caller's own workspace via {@link BrandContextService#requireBrandWorkspace}, same
 * resolve-then-scope pattern used by {@code ShopifyConnectController}, {@code
 * WooCommerceConnectController}, and every other brand-facing integration controller in this codebase.
 *
 * <p><b>Frontend contract (matching {@code src/lib/api.ts}):</b>
 *
 * <ul>
 *   <li>{@code GET /integrations/store/status} returns a single status response detecting whether the
 *       caller's workspace has Shopify OR WooCommerce connected (or neither) — the frontend needs one
 *       call for both types, not one per provider.
 *   <li>{@code DELETE /integrations/store/disconnect?provider=SHOPIFY|WOOCOMMERCE} revokes the
 *       specified integration (marks {@code revoked=true}, does not hard-delete). The frontend sends
 *       an explicit {@code provider} param so the backend knows which integration to revoke (in case
 *       the workspace has both connected, or the frontend wants to be defensive/explicit).
 * </ul>
 */
@RestController
@RequestMapping("/integrations/store")
public class StoreIntegrationStatusController {

    private final ShopifyIntegrationRepository shopifyRepository;
    private final WooCommerceIntegrationRepository wooCommerceRepository;
    private final BrandContextService brandContextService;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    public StoreIntegrationStatusController(
            ShopifyIntegrationRepository shopifyRepository,
            WooCommerceIntegrationRepository wooCommerceRepository,
            BrandContextService brandContextService) {
        this.shopifyRepository = shopifyRepository;
        this.wooCommerceRepository = wooCommerceRepository;
        this.brandContextService = brandContextService;
    }

    /**
     * Returns the connection status for the caller's workspace. Checks both Shopify and WooCommerce
     * integrations (only one can be connected at a time per workspace today — if that assumption
     * changes, this will need to return a list or prioritize one). If neither is connected, returns
     * {@code connected=false} with null provider/domain/connectedAt.
     */
    @GetMapping("/status")
    @Transactional(readOnly = true)
    public ApiResponse<IntegrationStatusResponse> status(@AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);

        // Check Shopify first
        Optional<ShopifyIntegration> shopify =
                shopifyRepository.findByWorkspaceIdAndRevokedFalse(workspace.getId());
        if (shopify.isPresent()) {
            ShopifyIntegration s = shopify.get();
            return ApiResponse.ok(new IntegrationStatusResponse(
                    true,
                    StoreProvider.SHOPIFY,
                    s.getShopDomain(),
                    ISO_FORMATTER.format(s.getConnectedAt())));
        }

        // Check WooCommerce
        Optional<WooCommerceIntegration> wooCommerce =
                wooCommerceRepository.findByWorkspaceIdAndRevokedFalse(workspace.getId());
        if (wooCommerce.isPresent()) {
            WooCommerceIntegration w = wooCommerce.get();
            return ApiResponse.ok(new IntegrationStatusResponse(
                    true,
                    StoreProvider.WOOCOMMERCE,
                    w.getSiteUrl(),
                    ISO_FORMATTER.format(w.getConnectedAt())));
        }

        // No active integration
        return ApiResponse.ok(new IntegrationStatusResponse(false, null, null, null));
    }

    /**
     * Disconnects the specified store integration (Shopify or WooCommerce) by marking it as revoked.
     * Does not hard-delete the row (preserves audit history). If the specified provider is not
     * connected, throws {@code INTEGRATION_NOT_FOUND}.
     */
    @DeleteMapping("/disconnect")
    @Transactional
    public ApiResponse<DisconnectResponse> disconnect(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestParam StoreProvider provider) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);

        if (provider == StoreProvider.SHOPIFY) {
            ShopifyIntegration integration = shopifyRepository
                    .findByWorkspaceIdAndRevokedFalse(workspace.getId())
                    .orElseThrow(() -> new ApiException(
                            "INTEGRATION_NOT_FOUND",
                            "No active Shopify integration found for this workspace",
                            HttpStatus.NOT_FOUND));

            integration.revoke();
            shopifyRepository.save(integration);
            return ApiResponse.ok(new DisconnectResponse(true));
        }

        if (provider == StoreProvider.WOOCOMMERCE) {
            WooCommerceIntegration integration = wooCommerceRepository
                    .findByWorkspaceIdAndRevokedFalse(workspace.getId())
                    .orElseThrow(() -> new ApiException(
                            "INTEGRATION_NOT_FOUND",
                            "No active WooCommerce integration found for this workspace",
                            HttpStatus.NOT_FOUND));

            integration.revoke();
            wooCommerceRepository.save(integration);
            return ApiResponse.ok(new DisconnectResponse(true));
        }

        // Should never reach here if StoreProvider enum is exhaustive, but defensive
        throw new ApiException(
                "INVALID_PROVIDER",
                "Invalid store provider: " + provider + ". Must be SHOPIFY or WOOCOMMERCE",
                HttpStatus.BAD_REQUEST);
    }
}
