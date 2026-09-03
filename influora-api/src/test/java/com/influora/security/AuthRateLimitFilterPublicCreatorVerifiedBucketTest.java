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
 * T-MEERA-CREATOR-PHASE-A (fix round 2, item 4 — Priya Q10). Before this fix, {@code GET
 * /public/creators/{username}/verified} (A9's public, unauthenticated, indexable verified-metrics
 * page) had NO throttle at all — {@link AuthRateLimitFilter#bucketFor} had no branch matching it,
 * so the entire discoverable creator base could be scraped as fast as the server could answer.
 * Proves the new {@code public-creator-verified} bucket now throttles it per-IP, leaves an
 * unrelated public-creators path unaffected, and keys independently per client IP.
 */
class AuthRateLimitFilterPublicCreatorVerifiedBucketTest {

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
        setField("discoverySearchLimit", 60);
        setField("publicCreatorVerifiedLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    private MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1" + path);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("GET /public/creators/{username}/verified is throttled per-IP once the limit is exceeded")
    void verifiedMetrics_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("/public/creators/priya-shah/verified", "10.9.0.1"), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse overLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/public/creators/priya-shah/verified", "10.9.0.1"), overLimit, new MockFilterChain());
        assertEquals(429, overLimit.getStatus());
    }

    @Test
    @DisplayName("the bucket is keyed per client IP — a different IP scraping the same username gets its own fresh window")
    void verifiedMetrics_keyedPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    request("/public/creators/priya-shah/verified", "10.9.0.1"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }
        MockHttpServletResponse firstIpOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/public/creators/priya-shah/verified", "10.9.0.1"),
                firstIpOverLimit,
                new MockFilterChain());
        assertEquals(429, firstIpOverLimit.getStatus());

        MockHttpServletResponse secondIpOk = new MockHttpServletResponse();
        filter.doFilter(
                request("/public/creators/priya-shah/verified", "10.9.0.2"), secondIpOk, new MockFilterChain());
        assertEquals(200, secondIpOk.getStatus());
    }

    @Test
    @DisplayName("scraping DIFFERENT usernames from the same IP still shares one bucket — the limit is per scraper, not per username")
    void verifiedMetrics_differentUsernamesShareOneBucketPerIp() throws Exception {
        filter.doFilter(
                request("/public/creators/creator-a/verified", "10.9.0.5"),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                request("/public/creators/creator-b/verified", "10.9.0.5"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse thirdOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                request("/public/creators/creator-c/verified", "10.9.0.5"),
                thirdOverLimit,
                new MockFilterChain());
        assertEquals(429, thirdOverLimit.getStatus());
    }

    @Test
    @DisplayName("an unrelated /public/creators path (no /verified suffix) is not throttled by this bucket")
    void unrelatedPublicCreatorsPath_unaffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("/public/creators/priya-shah", "10.9.0.9"), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }
}
