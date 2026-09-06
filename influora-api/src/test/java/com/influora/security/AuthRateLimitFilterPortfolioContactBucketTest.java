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
 * T-FESTIVALBOX-0905 phase 9 [Kabir F-3] — before this fix, {@code POST
 * /portfolio/{username}/contact} had NO edge throttle at all: {@link AuthRateLimitFilter#bucketFor}
 * had no branch matching it, despite {@code SecurityConfig}'s permitAll comment on this exact route
 * once claiming "server-side anti-spam on contact is enforced in PortfolioService" — which was also
 * false (see {@code PortfolioServiceContactTest}). This proves the new {@code portfolio-contact}
 * bucket now throttles the route per source IP, leaves the sibling (unrelated) {@code GET
 * /portfolio/{username}} read path unaffected, and keys independently per client IP so one attacker
 * cannot exhaust another visitor's budget.
 *
 * <p>This is only HALF the fix — the other half, a per-recipient-creator {@code AbuseThrottleService}
 * cap in {@code PortfolioService#contact} itself, is what bounds a caller that rotates source IP
 * between requests (which would otherwise defeat this per-IP edge bucket entirely). That half is
 * covered in {@code PortfolioServiceContactTest}, not here.
 */
class AuthRateLimitFilterPortfolioContactBucketTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        setField("enabled", true);
        setField("portfolioContactLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    private MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("POST /portfolio/{username}/contact is throttled per-IP once the limit is exceeded")
    void contact_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    request("/portfolio/priya-shah/contact", "10.9.1.1"), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse overLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/portfolio/priya-shah/contact", "10.9.1.1"), overLimit, new MockFilterChain());
        assertEquals(429, overLimit.getStatus());
    }

    @Test
    @DisplayName("the bucket is keyed per client IP — a different IP contacting the same creator gets its own fresh window")
    void contact_keyedPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    request("/portfolio/priya-shah/contact", "10.9.1.1"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }
        MockHttpServletResponse firstIpOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/portfolio/priya-shah/contact", "10.9.1.1"),
                firstIpOverLimit,
                new MockFilterChain());
        assertEquals(429, firstIpOverLimit.getStatus());

        MockHttpServletResponse secondIpOk = new MockHttpServletResponse();
        filter.doFilter(
                request("/portfolio/priya-shah/contact", "10.9.1.2"), secondIpOk, new MockFilterChain());
        assertEquals(200, secondIpOk.getStatus());
    }

    @Test
    @DisplayName("contacting DIFFERENT creators from the same IP still shares one bucket — the limit is per sender, not per recipient")
    void contact_differentCreatorsShareOneBucketPerIp() throws Exception {
        filter.doFilter(
                request("/portfolio/creator-a/contact", "10.9.1.5"),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                request("/portfolio/creator-b/contact", "10.9.1.5"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse thirdOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/portfolio/creator-c/contact", "10.9.1.5"),
                thirdOverLimit,
                new MockFilterChain());
        assertEquals(429, thirdOverLimit.getStatus());
    }

    @Test
    @DisplayName("an unrelated GET /portfolio/{username} read path is not throttled by this bucket")
    void unrelatedPortfolioReadPath_unaffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockHttpServletRequest getRequest =
                    new MockHttpServletRequest("GET", "/api/v1/portfolio/priya-shah");
            getRequest.setRemoteAddr("10.9.1.9");
            filter.doFilter(getRequest, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }
}
