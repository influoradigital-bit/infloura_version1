package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.ShopifyIntegration;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.repository.WooCommerceIntegrationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Wave D task D3: {@link IntegrationHealthService} true/false coverage for connected/disconnected workspaces. */
@ExtendWith(MockitoExtension.class)
class IntegrationHealthServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";

    @Mock private ShopifyIntegrationRepository shopifyIntegrationRepository;
    @Mock private WooCommerceIntegrationRepository wooCommerceIntegrationRepository;

    private IntegrationHealthService service;

    @BeforeEach
    void setUp() {
        service =
                new IntegrationHealthService(
                        shopifyIntegrationRepository, wooCommerceIntegrationRepository);
    }

    @Test
    @DisplayName("Workspace with an active (non-revoked) Shopify connection -> true")
    void testConnectedWorkspaceReturnsTrue() {
        ShopifyIntegration integration =
                ShopifyIntegration.builder()
                        .id("01HWXYZ123456789012346")
                        .workspaceId(WORKSPACE_ID)
                        .shopDomain("test-shop.myshopify.com")
                        .encryptedAccessToken("cipher")
                        .build();
        when(shopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.of(integration));

        assertTrue(service.hasActiveStoreIntegration(WORKSPACE_ID));
    }

    @Test
    @DisplayName("Workspace with no connection at all -> false")
    void testDisconnectedWorkspaceReturnsFalse() {
        when(shopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.empty());
        when(wooCommerceIntegrationRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertFalse(service.hasActiveStoreIntegration(WORKSPACE_ID));
    }

    @Test
    @DisplayName("Workspace whose only connection is revoked -> false (repository query excludes revoked)")
    void testRevokedOnlyConnectionReturnsFalse() {
        // findByWorkspaceIdAndRevokedFalse itself filters revoked=false at the query level, so a
        // workspace whose only row is revoked resolves the same as "no row" here.
        when(shopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.empty());
        when(wooCommerceIntegrationRepository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertFalse(service.hasActiveStoreIntegration(WORKSPACE_ID));
    }
}
