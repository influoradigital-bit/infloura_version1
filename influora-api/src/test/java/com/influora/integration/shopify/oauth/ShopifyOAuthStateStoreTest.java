package com.influora.integration.shopify.oauth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ShopifyOAuthStateStore} — mirrors {@code MetaOAuthStateStore}'s CSRF-state test coverage, plus the shop-domain-binding addition specific to Shopify's flow. */
class ShopifyOAuthStateStoreTest {

    private static final String USER_ID = "user-1";
    private static final String SHOP = "my-store.myshopify.com";

    @Test
    @DisplayName("issue: mints a non-null state token")
    void testIssue() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertNotNull(state);
        assertFalse(state.isBlank());
    }

    @Test
    @DisplayName("consume: succeeds for the exact (userId, shopDomain) pair the state was issued for")
    void testConsumeSucceedsForMatchingUserAndShop() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertTrue(store.consume(state, USER_ID, SHOP));
    }

    @Test
    @DisplayName("consume: is single-use — a second consume of the same state fails")
    void testConsumeIsSingleUse() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertTrue(store.consume(state, USER_ID, SHOP));
        assertFalse(store.consume(state, USER_ID, SHOP));
    }

    @Test
    @DisplayName("consume: fails if the shop domain does not match the one the state was issued for")
    void testConsumeFailsForMismatchedShop() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertFalse(store.consume(state, USER_ID, "different-store.myshopify.com"));
    }

    @Test
    @DisplayName("consume: fails if the user does not match the one the state was issued for")
    void testConsumeFailsForMismatchedUser() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertFalse(store.consume(state, "different-user", SHOP));
    }

    @Test
    @DisplayName("consume: fails for a null or unknown state token")
    void testConsumeFailsForUnknownOrNullState() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        assertFalse(store.consume(null, USER_ID, SHOP));
        assertFalse(store.consume("nonexistent-state", USER_ID, SHOP));
    }

    @Test
    @DisplayName("consume: shop domain comparison is case-insensitive")
    void testConsumeShopComparisonIsCaseInsensitive() {
        ShopifyOAuthStateStore store = new ShopifyOAuthStateStore();
        String state = store.issue(USER_ID, SHOP);
        assertTrue(store.consume(state, USER_ID, "My-Store.MyShopify.Com"));
    }
}
