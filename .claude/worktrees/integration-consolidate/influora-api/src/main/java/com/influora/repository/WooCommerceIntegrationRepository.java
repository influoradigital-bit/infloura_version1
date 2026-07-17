package com.influora.repository;

import com.influora.domain.entity.WooCommerceIntegration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WooCommerceIntegrationRepository extends JpaRepository<WooCommerceIntegration, String> {

    /** Workspace-scoped lookup -- every brand-authed read/write path must confirm the connection belongs to the caller's workspace. */
    Optional<WooCommerceIntegration> findByWorkspaceIdAndRevokedFalse(String workspaceId);

    /**
     * Global site-url lookup, deliberately NOT workspace-scoped -- used by {@code
     * WooCommerceWebhookController}, which is called by a brand's own WooCommerce site (no
     * workspace principal, no HMAC-verified-yet request at the point this lookup runs). {@code
     * site_url} is the only trusted identifier available at that call site to resolve the owning
     * workspace AND the per-integration webhook secret needed to verify the signature in the first
     * place -- see that controller's class javadoc, and {@code
     * ShopifyIntegrationRepository#findByShopDomainAndRevokedFalse} for the same shape of exception
     * to the general resolve-then-scope rule (a public webhook has no workspace principal to scope
     * by until AFTER this lookup resolves one).
     */
    Optional<WooCommerceIntegration> findBySiteUrlAndRevokedFalse(String siteUrl);

    boolean existsBySiteUrlAndRevokedFalse(String siteUrl);
}
