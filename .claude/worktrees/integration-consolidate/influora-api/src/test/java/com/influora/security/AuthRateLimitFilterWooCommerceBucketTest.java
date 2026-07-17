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
 * Proves {@link AuthRateLimitFilter} throttles the WooCommerce integration's public surface (Wave D
 * task D2), built in from the start rather than as a follow-up fix (unlike D1's Shopify integration
 * MEDIUM finding -- see {@code AuthRateLimitFilterShopifyBucketTest} javadoc). {@code POST
 * /webhooks/woocommerce} joins the existing {@code "tracking"} bucket (same treatment as {@code
 * /webhooks/shopify}/{@code /webhooks/redemption}), and {@code POST /woocommerce/connect} joins the
 * existing {@code "meta-oauth"} bucket (same treatment as the Shopify/Meta connect surfaces).
 * Mirrors {@code AuthRateLimitFilterShopifyBucketTest}'s structure exactly, including the
 * reflection-based {@code @Value} field setup.
 */
class AuthRateLimitFilterWooCommerceBucketTest {

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
    @DisplayName("POST /webhooks/woocommerce is throttled per-IP once trackingLimit is exceeded")
    void woocommerceWebhook_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/woocommerce");
            request.setRemoteAddr("10.2.0.5");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/woocommerce");
        third.setRemoteAddr("10.2.0.5");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /webhooks/woocommerce shares the same per-IP tracking bucket as /webhooks/shopify and /webhooks/redemption")
    void woocommerceWebhook_sharesTrackingBucketWithShopifyAndRedemption() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
        first.setRemoteAddr("10.2.0.9");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/webhooks/shopify");
        second.setRemoteAddr("10.2.0.9");
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/woocommerce");
        third.setRemoteAddr("10.2.0.9");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /woocommerce/connect is throttled per-IP once metaOAuthLimit is exceeded")
    void woocommerceConnect_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/woocommerce/connect");
            request.setRemoteAddr("10.2.0.6");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/woocommerce/connect");
        third.setRemoteAddr("10.2.0.6");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /woocommerce/connect shares the same meta-oauth bucket as GET /shopify/oauth/authorize")
    void woocommerceConnect_sharesMetaOAuthBucketWithShopifyOAuth() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/shopify/oauth/authorize");
        first.setRemoteAddr("10.2.0.10");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/woocommerce/connect");
        second.setRemoteAddr("10.2.0.10");
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/woocommerce/connect");
        third.setRemoteAddr("10.2.0.10");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("A different client IP gets its own independent tracking-bucket allowance for the WooCommerce webhook surface")
    void woocommerceWebhookBucket_isPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/woocommerce");
            request.setRemoteAddr("10.2.0.11");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest otherIp = new MockHttpServletRequest("POST", "/api/v1/webhooks/woocommerce");
        otherIp.setRemoteAddr("10.2.0.12");
        MockHttpServletResponse otherIpResponse = new MockHttpServletResponse();
        filter.doFilter(otherIp, otherIpResponse, new MockFilterChain());

        assertEquals(200, otherIpResponse.getStatus());
    }
}
