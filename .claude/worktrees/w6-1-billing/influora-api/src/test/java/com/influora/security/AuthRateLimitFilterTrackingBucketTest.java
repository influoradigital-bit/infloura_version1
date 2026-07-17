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
 * Wave A task A2: proves {@link AuthRateLimitFilter} actually throttles the new public tracking
 * surface ({@code /webhooks/redemption}, {@code /webhooks/conversion}, {@code /track/click/*}) —
 * this is the only abuse defense those endpoints have, since they carry no workspace principal
 * (see {@code ConversionWebhookController} class javadoc). Uses reflection to set the small
 * {@code trackingLimit}/{@code windowSeconds} fields directly (same {@code @Value}-backed fields
 * the filter reads in production, just not wired through a full Spring context — this codebase has
 * no {@code @WebMvcTest}/{@code @SpringBootTest} harness for filters today).
 */
class AuthRateLimitFilterTrackingBucketTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    @Test
    @DisplayName("POST /webhooks/redemption is throttled per-IP once trackingLimit is exceeded")
    void redemptionWebhook_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
            request.setRemoteAddr("10.0.0.5");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
        third.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /webhooks/conversion shares the same per-IP tracking bucket as /webhooks/redemption")
    void conversionWebhook_sharesTrackingBucketWithRedemption() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
        first.setRemoteAddr("10.0.0.9");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/webhooks/conversion");
        second.setRemoteAddr("10.0.0.9");
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/webhooks/conversion");
        third.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("GET /track/click/{id} is throttled per-IP once trackingLimit is exceeded")
    void trackClick_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/track/click/01HUTM1234567890ABCDE");
            request.setRemoteAddr("10.0.0.7");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third =
                new MockHttpServletRequest("GET", "/api/v1/track/click/01HUTM1234567890ABCDE");
        third.setRemoteAddr("10.0.0.7");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("A different client IP gets its own independent tracking-bucket allowance")
    void trackingBucket_isPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
            request.setRemoteAddr("10.0.0.11");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest otherIp = new MockHttpServletRequest("POST", "/api/v1/webhooks/redemption");
        otherIp.setRemoteAddr("10.0.0.12");
        MockHttpServletResponse otherIpResponse = new MockHttpServletResponse();
        filter.doFilter(otherIp, otherIpResponse, new MockFilterChain());

        assertEquals(200, otherIpResponse.getStatus());
    }
}
