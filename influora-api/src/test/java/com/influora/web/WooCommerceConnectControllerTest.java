package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.integration.woocommerce.WooCommerceIntegrationService;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.woocommerce.WooCommerceDtos.WooCommerceConnectRequest;
import com.influora.web.dto.woocommerce.WooCommerceDtos.WooCommerceConnectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WooCommerceConnectController} -- mirrors {@code
 * ShopifyConnectControllerTest}'s test coverage shape, adapted for a webhook-secret-submission flow
 * instead of an OAuth code/state exchange (see controller class javadoc for why WooCommerce has no
 * authorize/callback round trip at all).
 */
@ExtendWith(MockitoExtension.class)
class WooCommerceConnectControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SITE_URL = "https://my-test-store.example.com";
    private static final AuthPrincipal BRAND_PRINCIPAL =
            new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

    @Mock private WooCommerceIntegrationService integrationService;
    @Mock private BrandContextService brandContextService;

    private WooCommerceConnectController controller;

    @BeforeEach
    void setUp() {
        controller = new WooCommerceConnectController(integrationService, brandContextService);
    }

    private Workspace testWorkspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "fashion", "1-10");
    }

    @Test
    @DisplayName("connect: happy path normalizes the site url and stores the secret scoped to the caller's workspace")
    void connect_happyPath_storesSecretForWorkspace() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());

        WooCommerceConnectResponse response =
                controller
                        .connect(BRAND_PRINCIPAL, new WooCommerceConnectRequest(SITE_URL + "/", "the-secret"))
                        .data();

        assertTrue(response.connected());
        assertEquals(SITE_URL, response.siteUrl());
        verify(integrationService, times(1)).connect(eq(WORKSPACE_ID), eq(SITE_URL), eq("the-secret"));
    }

    @Test
    @DisplayName("connect: rejects a non-brand principal before any storage")
    void connect_rejectsNonBrand() {
        org.mockito.Mockito.doThrow(
                        new ApiException(
                                "WRONG_USER_TYPE",
                                "This endpoint is for brand accounts only",
                                org.springframework.http.HttpStatus.FORBIDDEN))
                .when(brandContextService)
                .requireBrandWorkspace(BRAND_PRINCIPAL);

        assertThrows(
                ApiException.class,
                () -> controller.connect(BRAND_PRINCIPAL, new WooCommerceConnectRequest(SITE_URL, "secret")));
        verify(integrationService, never()).connect(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("connect: rejects an invalid site url before any storage")
    void connect_rejectsInvalidSiteUrl() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.connect(BRAND_PRINCIPAL, new WooCommerceConnectRequest("not a url", "secret")));

        assertEquals("INVALID_SITE_URL", ex.getCode());
        verify(integrationService, never()).connect(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("connect: rejects a null webhook secret")
    void connect_rejectsNullSecret() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.connect(BRAND_PRINCIPAL, new WooCommerceConnectRequest(SITE_URL, null)));

        assertEquals("WEBHOOK_SECRET_REQUIRED", ex.getCode());
        verify(integrationService, never()).connect(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("connect: rejects a blank webhook secret")
    void connect_rejectsBlankSecret() {
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(testWorkspace());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.connect(BRAND_PRINCIPAL, new WooCommerceConnectRequest(SITE_URL, "   ")));

        assertEquals("WEBHOOK_SECRET_REQUIRED", ex.getCode());
        verify(integrationService, never()).connect(anyString(), anyString(), anyString());
    }
}
