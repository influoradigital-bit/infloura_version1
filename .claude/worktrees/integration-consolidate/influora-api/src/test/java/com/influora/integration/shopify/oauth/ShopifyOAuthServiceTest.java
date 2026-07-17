package com.influora.integration.shopify.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.influora.common.ApiException;
import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.dto.ShopifyTokenResponse;
import com.influora.integration.shopify.exception.ShopifyApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Unit tests for {@link ShopifyOAuthService} — mirrors {@code MetaOAuthServiceTest}'s structure.
 * Covers shop-domain validation (the SSRF-relevant gate — see class javadoc), authorization URL
 * construction, and code-for-token exchange. No real Shopify endpoints hit — mock the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class ShopifyOAuthServiceTest {

    private static final String API_KEY = "test-api-key";
    private static final String API_SECRET = "test-api-secret";
    private static final String REDIRECT_URI = "https://influora.com/shopify/oauth/callback";
    private static final String VALID_SHOP = "my-test-store.myshopify.com";

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private ShopifyOAuthService service;

    @BeforeEach
    void setUp() {
        ShopifyProperties props = createTestProperties();
        service = new ShopifyOAuthService(props, restClient);
    }

    // ------------------------------------------------------------------------------------------
    // Shop-domain validation [SEC: SSRF gate]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("validateShopDomain: accepts a genuine *.myshopify.com domain, lower-cased")
    void validateShopDomain_acceptsValidDomain() {
        assertEquals(VALID_SHOP, ShopifyOAuthService.validateShopDomain(VALID_SHOP));
        assertEquals(VALID_SHOP, ShopifyOAuthService.validateShopDomain("My-Test-Store.MyShopify.Com"));
    }

    @Test
    @DisplayName("validateShopDomain: rejects null/blank")
    void validateShopDomain_rejectsBlank() {
        ApiException nullEx = assertThrows(ApiException.class, () -> ShopifyOAuthService.validateShopDomain(null));
        assertEquals("INVALID_SHOP_DOMAIN", nullEx.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, nullEx.getStatus());

        ApiException blankEx = assertThrows(ApiException.class, () -> ShopifyOAuthService.validateShopDomain("  "));
        assertEquals("INVALID_SHOP_DOMAIN", blankEx.getCode());
    }

    @Test
    @DisplayName("validateShopDomain: rejects a non-myshopify.com host (SSRF vector)")
    void validateShopDomain_rejectsArbitraryHost() {
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> ShopifyOAuthService.validateShopDomain("evil.attacker.com"));
        assertEquals("INVALID_SHOP_DOMAIN", ex.getCode());
    }

    @Test
    @DisplayName("validateShopDomain: rejects a protocol-prefixed or path-suffixed value")
    void validateShopDomain_rejectsUrlShapedValue() {
        assertThrows(
                ApiException.class,
                () -> ShopifyOAuthService.validateShopDomain("https://my-store.myshopify.com"));
        assertThrows(
                ApiException.class,
                () -> ShopifyOAuthService.validateShopDomain("my-store.myshopify.com/admin"));
        assertThrows(
                ApiException.class,
                () -> ShopifyOAuthService.validateShopDomain("my-store.myshopify.com.evil.com"));
    }

    // ------------------------------------------------------------------------------------------
    // buildAuthorizationUrl
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("buildAuthorizationUrl: includes shop host, client_id, scope, redirect_uri, state")
    void testBuildAuthorizationUrl() {
        String state = "csrf-state-12345";
        String url = service.buildAuthorizationUrl(VALID_SHOP, state);

        assertNotNull(url);
        assertTrue(url.startsWith("https://" + VALID_SHOP + "/admin/oauth/authorize"));
        assertTrue(url.contains("client_id=" + API_KEY));
        assertTrue(url.contains("scope=read_orders"));
        assertTrue(url.contains("redirect_uri=" + java.net.URLEncoder.encode(REDIRECT_URI, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(url.contains("state=" + state));
    }

    @Test
    @DisplayName("buildAuthorizationUrl: rejects an invalid shop domain before building any URL")
    void testBuildAuthorizationUrlRejectsInvalidShop() {
        assertThrows(ApiException.class, () -> service.buildAuthorizationUrl("evil.com", "state"));
    }

    // ------------------------------------------------------------------------------------------
    // exchangeCodeForToken
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("exchangeCodeForToken: returns the permanent access token on success")
    void testExchangeCodeForTokenSuccess() {
        String code = "test-auth-code";
        ShopifyTokenResponse mockResponse = new ShopifyTokenResponse("shpat_abc123", "read_orders,read_products");

        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(requestBodySpec).when(requestBodyUriSpec).uri(any(String.class));
        doReturn(requestBodySpec).when(requestBodySpec).body(any(Object.class));
        doReturn(responseSpec).when(requestBodySpec).retrieve();
        doReturn(mockResponse).when(responseSpec).body(ShopifyTokenResponse.class);

        ShopifyTokenResponse result = service.exchangeCodeForToken(VALID_SHOP, code);

        assertNotNull(result);
        assertEquals("shpat_abc123", result.accessToken());
        assertEquals("read_orders,read_products", result.scope());
    }

    @Test
    @DisplayName("exchangeCodeForToken: throws ShopifyApiException on HTTP error")
    void testExchangeCodeForTokenHttpError() {
        String code = "invalid-code";

        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(requestBodySpec).when(requestBodyUriSpec).uri(any(String.class));
        doReturn(requestBodySpec).when(requestBodySpec).body(any(Object.class));
        doReturn(responseSpec).when(requestBodySpec).retrieve();
        doThrow(
                        new RestClientResponseException(
                                "Invalid code", HttpStatus.BAD_REQUEST.value(), "Bad Request", null, null, null))
                .when(responseSpec)
                .body(ShopifyTokenResponse.class);

        assertThrows(ShopifyApiException.class, () -> service.exchangeCodeForToken(VALID_SHOP, code));
    }

    @Test
    @DisplayName("exchangeCodeForToken: rejects an invalid shop domain before making any HTTP call")
    void testExchangeCodeForTokenRejectsInvalidShop() {
        assertThrows(ApiException.class, () -> service.exchangeCodeForToken("evil.com", "code"));
    }

    // ------------------------------------------------------------------------------------------
    // requestedScopes
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("requestedScopes: parses the comma-separated configured scopes list")
    void testRequestedScopes() {
        assertEquals(java.util.List.of("read_orders", "read_products"), service.requestedScopes());
    }

    private ShopifyProperties createTestProperties() {
        ShopifyProperties props = new ShopifyProperties();
        props.setApiKey(API_KEY);
        props.setApiSecret(API_SECRET);
        props.setRedirectUri(REDIRECT_URI);
        props.setScopes("read_orders,read_products");
        return props;
    }
}
