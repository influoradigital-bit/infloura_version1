package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Task #25 — proves {@link AuthRateLimitFilter} throttles the creator deliverable write surface
 * (Kabir M-19-2), brand deliverable review surface (M-21-1), and contract sign POST (L-23-3).
 * User-keyed buckets parse JWT {@code sub} from the Bearer header (filter runs before the auth
 * filter). Uses reflection for {@code @Value} fields and a Mockito {@link JwtService} stub.
 */
class AuthRateLimitFilterDeliverableContractBucketTest {

    private static final String CREATOR_TOKEN = "creator-token";
    private static final String BRAND_TOKEN = "brand-token";
    private static final String OTHER_CREATOR_TOKEN = "other-creator-token";

    private JwtService jwtService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        stubSubject(CREATOR_TOKEN, "creator-user-1");
        stubSubject(BRAND_TOKEN, "brand-user-1");
        stubSubject(OTHER_CREATOR_TOKEN, "creator-user-2");

        filter = new AuthRateLimitFilter(jwtService);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 60);
        setField("creatorDeliverableWriteLimit", 2);
        setField("brandDeliverableReviewLimit", 2);
        setField("contractSignLimit", 2);
        setField("windowSeconds", 60L);
    }

    private void stubSubject(String token, String userId) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(userId);
        when(jwtService.parseAccessToken(token)).thenReturn(claims);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    private MockHttpServletRequest creatorPost(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.setRemoteAddr("10.2.0.1");
        return request;
    }

    @Test
    @DisplayName("POST /creator/deliverables/{id}/upload is throttled per creator once write limit is exceeded")
    void creatorUpload_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    creatorPost("/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                    response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                thirdResponse,
                new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("upload + submit + metrics share the same per-creator deliverable-write bucket")
    void creatorDeliverableWritePaths_shareBucket() throws Exception {
        filter.doFilter(
                creatorPost("/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                creatorPost("/creator/deliverables/del-2/submit", CREATOR_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/creator/deliverables/del-3/metrics", CREATOR_TOKEN),
                thirdResponse,
                new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("Different creators get independent deliverable-write allowances")
    void creatorDeliverableWrite_isPerUser() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    creatorPost("/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        MockHttpServletResponse otherCreatorResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/creator/deliverables/del-1/upload", OTHER_CREATOR_TOKEN),
                otherCreatorResponse,
                new MockFilterChain());

        assertEquals(200, otherCreatorResponse.getStatus());
    }

    @Test
    @DisplayName("GET /creator/deliverables/{id}/status is not throttled by deliverable-write bucket")
    void creatorStatus_notThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/creator/deliverables/del-1/status");
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + CREATOR_TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    @DisplayName("POST /deliverables/{id}/approve is throttled per brand user once review limit is exceeded")
    void brandApprove_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    creatorPost("/deliverables/del-1/approve", BRAND_TOKEN),
                    response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/deliverables/del-2/approve", BRAND_TOKEN),
                thirdResponse,
                new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("approve + revise share the same per-brand deliverable-review bucket")
    void brandReviewPaths_shareBucket() throws Exception {
        filter.doFilter(
                creatorPost("/deliverables/del-1/approve", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                creatorPost("/deliverables/del-2/revise", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/deliverables/del-3/approve", BRAND_TOKEN),
                thirdResponse,
                new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("POST /contracts/{id}/sign is throttled per user once contract-sign limit is exceeded")
    void contractSign_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    creatorPost("/contracts/ctr-1/sign", CREATOR_TOKEN),
                    response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(
                creatorPost("/contracts/ctr-2/sign", CREATOR_TOKEN),
                thirdResponse,
                new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("Without a Bearer token, deliverable-write bucket falls back to per-IP keying")
    void creatorDeliverableWrite_fallsBackToIpWithoutJwt() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/v1/creator/deliverables/del-1/upload");
            request.setRemoteAddr("10.2.0.99");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest third =
                new MockHttpServletRequest("POST", "/api/v1/creator/deliverables/del-2/upload");
        third.setRemoteAddr("10.2.0.99");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }
}
