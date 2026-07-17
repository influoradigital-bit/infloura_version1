package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * [SEC: Kabir, Wave D1 MEDIUM — regression test for the FIX] proves {@link AuthRateLimitFilter}
 * now throttles the Shopify integration's public surface, which shipped with Wave D task D1 but
 * was never added to {@code bucketFor} — see {@code
 * wiki/errors/wave-d1-shopify-integration-redteam.md} MEDIUM finding. {@code POST
 * /webhooks/shopify} joins the existing {@code "tracking"} bucket (same treatment as {@code
 * /webhooks/redemption} — mirrors {@link AuthRateLimitFilterTrackingBucketTest}), and {@code GET
 * /shopify/oauth/authorize} / {@code GET /shopify/oauth/callback} join the existing {@code
 * "meta-oauth"} bucket (same treatment as the Meta OAuth connect surface). Uses reflection to set
 * the small {@code @Value}-backed limit fields directly, same convention as {@link
 * AuthRateLimitFilterTrackingBucketTest}.
 */
class AuthRateLimitFilterShopifyBucketTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 2);
        setField("trackingLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    @Test
    @DisplayName("POST /webhooks/shopify is throttled per-IP once trackingLimit is exceeded")
    void shopifyWebhook_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/shopify");
            request.setRemoteAddr("10.1.0.5");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/shopify");
        third.setRemoteAddr("10.1.0.5");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /webhooks/shopify shares the same per-IP tracking bucket as /webhooks/redemption")
    void shopifyWebhook_sharesTrackingBucketWithRedemption() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
        first.setRemoteAddr("10.1.0.9");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/webhooks/shopify");
        second.setRemoteAddr("10.1.0.9");
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/shopify");
        third.setRemoteAddr("10.1.0.9");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("GET /shopify/oauth/authorize is throttled per-IP once metaOAuthLimit is exceeded")
    void shopifyOAuthAuthorize_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
            request.setRemoteAddr("10.1.0.6");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
        third.setRemoteAddr("10.1.0.6");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName(
            "GET /shopify/oauth/callback shares the same per-IP meta-oauth bucket as"
                    + " /shopify/oauth/authorize AND /meta/oauth/authorize")
    void shopifyOAuthCallback_sharesMetaOAuthBucketWithAuthorizeAndMetaSurface() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
        first.setRemoteAddr("10.1.0.10");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/meta/oauth/authorize");
        second.setRemoteAddr("10.1.0.10");
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest third = new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/callback");
        third.setRemoteAddr("10.1.0.10");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("A different client IP gets its own independent meta-oauth-bucket allowance for the Shopify OAuth surface")
    void shopifyOAuthBucket_isPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
            request.setRemoteAddr("10.1.0.11");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest otherIp = new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
        otherIp.setRemoteAddr("10.1.0.12");
        MockHttpServletResponse otherIpResponse = new MockHttpServletResponse();
        filter.doFilter(otherIp, otherIpResponse, new MockFilterChain());

        assertEquals(200, otherIpResponse.getStatus());
    }
}
