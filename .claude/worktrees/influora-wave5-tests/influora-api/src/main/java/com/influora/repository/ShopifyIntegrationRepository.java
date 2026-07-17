package com.influora.repository;

import com.influora.domain.entity.ShopifyIntegration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopifyIntegrationRepository extends JpaRepository<ShopifyIntegration, String> {

    /** Workspace-scoped lookup -- every brand-authed read/write path must confirm the connection belongs to the caller's workspace. */
    Optional<ShopifyIntegration> findByWorkspaceIdAndRevokedFalse(String workspaceId);

    /**
     * Global shop-domain lookup, deliberately NOT workspace-scoped -- used by {@code
     * ShopifyWebhookController}, which is called by Shopify itself (HMAC-verified, but with no
     * workspace principal). {@code shop_domain} is the only trusted identifier available at that
     * call site to resolve the owning workspace; see that controller's class javadoc and {@link
     * com.influora.repository.CouponCodeRepository#findByCode} for the same shape of exception to
     * the general resolve-then-scope rule (a public, signature-verified webhook has no workspace
     * principal to scope by until AFTER this lookup resolves one).
     */
    Optional<ShopifyIntegration> findByShopDomainAndRevokedFalse(String shopDomain);

    boolean existsByShopDomainAndRevokedFalse(String shopDomain);
}
