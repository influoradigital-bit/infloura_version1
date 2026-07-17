package com.influora.service;

import com.influora.domain.enums.CampaignIntentType;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.repository.WooCommerceIntegrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave D task D3 (`REMAINING_WORK_PLAN.md`): answers one question — "does this workspace have at
 * least one ACTIVE store integration connected?" — for gating store-attribution-dependent campaign
 * creation (see {@code CreateCampaignExecutor}, which is the only caller today).
 *
 * <p><b>Extensibility, deliberately additive:</b> Shopify ({@link ShopifyIntegrationRepository}) is
 * the only connected store type as of this class's creation (Wave D task D1, done). WooCommerce
 * (D2) is a separate, concurrently-in-progress build and this class does NOT reference any
 * WooCommerce-specific entity/repository — none exists yet on this branch at the time D3 was
 * written. When D2 lands, add a `hasActiveWooCommerceConnection(workspaceId)` private check the
 * same shape as {@link #hasActiveShopifyConnection} and OR it into {@link
 * #hasActiveStoreIntegration}; no existing method signature needs to change. This mirrors the
 * pattern {@code MetricsAuthorizationService} uses for a single-purpose resolve-then-scope check
 * rather than a generic polymorphic "integration" abstraction, since today there is exactly one
 * concrete implementation to check and a second is additive, not a rewrite.
 *
 * <p>Workspace-scoped: every query here is scoped by {@code workspaceId} directly (via {@link
 * ShopifyIntegrationRepository#findByWorkspaceIdAndRevokedFalse}), so there is no separate
 * resolve-then-scope step needed beyond passing in a {@code workspaceId} the caller has already
 * authenticated/authorized (see {@code CreateCampaignExecutor}, which receives {@code workspaceId}
 * from its own internal-auth-resolved principal, same as every other Meera D-tier executor).
 */
@Service
public class IntegrationHealthService {

    private final ShopifyIntegrationRepository shopifyIntegrationRepository;
    private final WooCommerceIntegrationRepository wooCommerceIntegrationRepository;

    public IntegrationHealthService(
            ShopifyIntegrationRepository shopifyIntegrationRepository,
            WooCommerceIntegrationRepository wooCommerceIntegrationRepository) {
        this.shopifyIntegrationRepository = shopifyIntegrationRepository;
        this.wooCommerceIntegrationRepository = wooCommerceIntegrationRepository;
    }

    /**
     * True if {@code workspaceId} has at least one ACTIVE (non-revoked) store integration of any
     * supported type connected. Checks both Shopify (D1) and WooCommerce (D2) — P2-9 follow-up added
     * WooCommerce support as described in the original class javadoc.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveStoreIntegration(String workspaceId) {
        return hasActiveShopifyConnection(workspaceId) || hasActiveWooCommerceConnection(workspaceId);
    }

    private boolean hasActiveShopifyConnection(String workspaceId) {
        return shopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(workspaceId).isPresent();
    }

    private boolean hasActiveWooCommerceConnection(String workspaceId) {
        return wooCommerceIntegrationRepository.findByWorkspaceIdAndRevokedFalse(workspaceId).isPresent();
    }

    /**
     * Wave D task D3 follow-up
     * (wiki/decisions/2026-07-07-d3-campaign-gating-scope.md): the ONE shared predicate for "does
     * this campaign type require a connected store integration," extracted here so both creation
     * paths — {@code CreateCampaignExecutor} (AI-drafted) and {@code CampaignService.create}
     * (human REST) — call the same logic instead of each keeping its own copy. Only {@code DIRECT}
     * (the product-price-bearing, conversion/"sale"-shaped type) needs order-attribution back to a
     * connected store; {@code HYPE}/{@code REVIEW}/{@code STANDARD} are awareness/relationship-
     * shaped with nothing to attribute a purchase to. A {@code null} type (untyped/legacy request —
     * see {@code Campaign#campaignType} javadoc and migration V30) is treated as NOT requiring an
     * integration, i.e. not gated.
     */
    public static boolean requiresStoreIntegration(CampaignIntentType campaignType) {
        return campaignType == CampaignIntentType.DIRECT;
    }
}
