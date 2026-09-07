package com.influora.integration.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.oauth.ShopifyTokenStorage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * [SEC: F-0726] Input-validation coverage for {@link ShopifyOrderOwnershipVerifier}.
 *
 * <p>Scope note, stated rather than left implicit: these tests cover the guards that run BEFORE any
 * outbound call, because those are the ones that decide whether attacker-controlled text ever
 * reaches a URL. The 200/404/5xx response handling is exercised at the controller level in {@code
 * ShopifyWebhookControllerTest} against a mocked verifier — mocking {@link RestClient}'s fluent
 * builder chain here would assert the shape of a Spring API rather than this class's behaviour, and
 * the real HTTP contract is not provable offline either way. That gap is real and is recorded on
 * the F-0726 verdict, not papered over with a mock that would pass whatever the code did.
 */
@ExtendWith(MockitoExtension.class)
class ShopifyOrderOwnershipVerifierTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String SHOP_DOMAIN = "my-test-store.myshopify.com";

    @Mock private ShopifyTokenStorage tokenStorage;
    @Mock private RestClient restClient;

    private ShopifyOrderOwnershipVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ShopifyOrderOwnershipVerifier(new ShopifyProperties(), tokenStorage, restClient);
    }

    @ParameterizedTest(name = "order id {0} is refused before any outbound call")
    @ValueSource(
            strings = {
                "1/../../shop.json",
                "123?fields=x",
                "123.json",
                "abc",
                "",
                "12345678901234567890123456789012345"
            })
    @DisplayName(
            "orderBelongsToShop: refuses a non-numeric order id before building a URL — the id comes"
                    + " from the request body, so it must never reach the Admin API path (F-0726)")
    void orderBelongsToShop_rejectsNonNumericOrderId(String hostileOrderId) {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> verifier.orderBelongsToShop(SHOP_DOMAIN, WORKSPACE_ID, hostileOrderId));

        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.getCode());
        // Load-bearing: nothing was fetched, and no token was even decrypted.
        verifyNoInteractions(restClient);
        verify(tokenStorage, never()).getValidToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName(
            "orderBelongsToShop: refuses a shop domain that is not *.myshopify.com before any"
                    + " outbound call — SSRF guard on the host half of the URL")
    void orderBelongsToShop_rejectsNonShopifyHost() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> verifier.orderBelongsToShop("attacker.example.com", WORKSPACE_ID, "12345"));

        assertEquals("INVALID_SHOP_DOMAIN", ex.getCode());
        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName(
            "orderBelongsToShop: a workspace with no stored token is refused, not treated as 'order"
                    + " not found' — a revoked connection must not read as a forgery")
    void orderBelongsToShop_missingToken_isRefusedNotDenied() {
        when(tokenStorage.getValidToken(WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> verifier.orderBelongsToShop(SHOP_DOMAIN, WORKSPACE_ID, "12345"));

        assertEquals("SHOPIFY_NOT_CONNECTED", ex.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(restClient);
    }
}
