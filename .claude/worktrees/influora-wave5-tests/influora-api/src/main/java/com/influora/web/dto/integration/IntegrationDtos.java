package com.influora.web.dto.integration;

/**
 * Store integration status/disconnect DTOs, unified for Shopify + WooCommerce (Wave D tasks D1/D2,
 * status + disconnect endpoints added in P2-9). Structurally different from {@code ShopifyDtos} and
 * {@code WooCommerceDtos}, which handle the provider-specific connect flows — these records handle
 * the shared status/disconnect shape that works for BOTH store types.
 */
public final class IntegrationDtos {

    private IntegrationDtos() {}

    /**
     * Response for {@code GET /integrations/store/status} — a single endpoint that returns the
     * connection state for the caller's workspace, detecting whether they have Shopify OR WooCommerce
     * connected (or neither). The frontend needs one status call for both types (not one per provider
     * — see {@code src/lib/api.ts} {@code storeIntegrations.status}).
     */
    public record IntegrationStatusResponse(
            boolean connected, StoreProvider provider, String shopDomainOrSiteUrl, String connectedAt) {}

    /**
     * Response for {@code DELETE /integrations/store/disconnect} — revokes the integration (does not
     * hard-delete the row, just marks {@code revoked=true}). Accepts a {@code provider} query param
     * so the frontend can be explicit about which integration to disconnect (Shopify vs WooCommerce).
     */
    public record DisconnectResponse(boolean disconnected) {}

    /**
     * Store provider enum matching {@code src/lib/api.ts} {@code StoreProvider} — frontend sends one
     * of these string values in {@code /disconnect?provider=...}. Must stay sync'd with the TS type
     * (case-sensitive string match).
     */
    public enum StoreProvider {
        SHOPIFY,
        WOOCOMMERCE
    }
}
