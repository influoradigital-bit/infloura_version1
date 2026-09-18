package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.config.ShopifyProperties;
import com.influora.domain.entity.Workspace;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.repository.WooCommerceIntegrationRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GET /integrations/store/status tells the settings page whether this deployment can run the
 * Shopify OAuth flow at all. Without it the page advertised "Shopify · OAuth — one click" in
 * environments where POST /shopify/oauth/authorize answers 503 to everyone.
 */
@ExtendWith(MockitoExtension.class)
class StoreIntegrationStatusShopifyAvailabilityTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE0000000001";

    @Mock private ShopifyIntegrationRepository shopifyRepository;
    @Mock private WooCommerceIntegrationRepository wooCommerceRepository;
    @Mock private BrandContextService brandContextService;
    @Mock private AuthPrincipal principal;

    private final ShopifyProperties shopifyProperties = new ShopifyProperties();
    private StoreIntegrationStatusController controller;

    @BeforeEach
    void setUp() {
        controller =
                new StoreIntegrationStatusController(
                        shopifyRepository, wooCommerceRepository, brandContextService, shopifyProperties);
        when(brandContextService.requireBrandWorkspace(principal))
                .thenReturn(Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "Retail", "SMB"));
        when(shopifyRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(wooCommerceRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("no Shopify app credentials -> shopifyAvailable=false")
    void unavailableWithoutCredentials() {
        assertFalse(controller.status(principal).data().shopifyAvailable());
    }

    @Test
    @DisplayName("all three credentials present -> shopifyAvailable=true")
    void availableWithCredentials() {
        shopifyProperties.setApiKey("client-id-123");
        shopifyProperties.setApiSecret("client-secret-456");
        shopifyProperties.setWebhookSigningSecret("whsec-789");

        assertTrue(controller.status(principal).data().shopifyAvailable());
    }

    @Test
    @DisplayName(
            "the OAuth pair without the webhook signing secret still reports UNAVAILABLE — a store"
                    + " would connect and then receive no order events (QA review)")
    void unavailableWithoutWebhookSecret() {
        shopifyProperties.setApiKey("client-id-123");
        shopifyProperties.setApiSecret("client-secret-456");

        assertFalse(controller.status(principal).data().shopifyAvailable());
    }
}
