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
 * CR-11: proves {@link AuthRateLimitFilter} actually throttles {@code POST /client-errors} per IP
 * — the contract's mandatory "rate-limit per IP" constraint (see {@code
 * wiki/tech/cr-11-client-error-contract.md} and {@code ClientErrorController}). This endpoint is
 * unauthenticated by design, so per-IP is the only throttle identity available; unlike the
 * user-keyed buckets, a request with no {@code Authorization} header must still be throttled, not
 * skipped. Same reflection-based harness as the sibling {@code AuthRateLimitFilter*BucketTest}
 * classes — no {@code @WebMvcTest}/{@code @SpringBootTest} filter harness exists in this codebase.
 */
class AuthRateLimitFilterClientErrorsBucketTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 30);
        setField("clientErrorLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    @Test
    @DisplayName("POST /client-errors is throttled per-IP once clientErrorLimit is exceeded, even with no Authorization header")
    void clientErrors_throttledAfterLimit_noAuthHeader() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/client-errors");
            request.setRemoteAddr("10.0.1.5");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/client-errors");
        third.setRemoteAddr("10.0.1.5");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("A different client IP gets its own independent client-errors bucket allowance")
    void clientErrors_isPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/client-errors");
            request.setRemoteAddr("10.0.1.6");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest otherIp = new MockHttpServletRequest("POST", "/api/v1/client-errors");
        otherIp.setRemoteAddr("10.0.1.7");
        MockHttpServletResponse otherIpResponse = new MockHttpServletResponse();
        filter.doFilter(otherIp, otherIpResponse, new MockFilterChain());

        assertEquals(200, otherIpResponse.getStatus());
    }
}
