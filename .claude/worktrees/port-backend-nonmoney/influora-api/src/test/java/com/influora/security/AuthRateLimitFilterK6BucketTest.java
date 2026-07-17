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
 * Task #39 / M-K6-1…5 — proves {@link AuthRateLimitFilter} throttles review create/flag, dispute
 * open, discovery search/invite, campaign apply, and wallet withdraw. User-keyed buckets parse JWT
 * {@code sub} from the Bearer header (same pattern as Task #25). M-K6-2 (Redis/shared store) is
 * intentionally out of scope.
 */
class AuthRateLimitFilterK6BucketTest {

    private static final String CREATOR_TOKEN = "creator-token";
    private static final String BRAND_TOKEN = "brand-token";
    private static final String OTHER_TOKEN = "other-token";

    private JwtService jwtService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        stubSubject(CREATOR_TOKEN, "creator-user-1");
        stubSubject(BRAND_TOKEN, "brand-user-1");
        stubSubject(OTHER_TOKEN, "other-user-2");

        filter = new AuthRateLimitFilter(jwtService);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 60);
        setField("creatorDeliverableWriteLimit", 10);
        setField("brandDeliverableReviewLimit", 30);
        setField("contractSignLimit", 10);
        setField("reviewWriteLimit", 2);
        setField("reviewFlagLimit", 2);
        setField("disputeOpenLimit", 2);
        setField("discoveryInviteLimit", 2);
        setField("discoverySearchLimit", 2);
        setField("campaignApplyLimit", 2);
        setField("creatorWithdrawLimit", 2);
        setField("withdrawWindowSeconds", 3600L);
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

    private MockHttpServletRequest authed(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1" + path);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.setRemoteAddr("10.3.0.1");
        return request;
    }

    private void assertThrottledAfterLimit(String method, String path, String token) throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(authed(method, path, token), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(authed(method, path, token), third, new MockFilterChain());
        assertEquals(429, third.getStatus());
    }

    @Test
    @DisplayName("POST /creator/reviews is throttled per user once review-write limit is exceeded")
    void creatorReviewCreate_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/creator/reviews", CREATOR_TOKEN);
    }

    @Test
    @DisplayName("creator + brand review create share the same review-write bucket per user")
    void reviewWritePaths_shareBucketPerUser() throws Exception {
        // Same JWT sub hitting both surfaces — brand token used for both path shapes to prove
        // path matching, not role. Bucket is keyed by sub, not by path prefix.
        filter.doFilter(
                authed("POST", "/creator/reviews", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                authed("POST", "/brand/reviews", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/brand/reviews", BRAND_TOKEN), third, new MockFilterChain());
        assertEquals(429, third.getStatus());
    }

    @Test
    @DisplayName("POST /*/reviews/{id}/flag is throttled (M-K6-3 moderation-queue defense)")
    void reviewFlag_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/creator/reviews/rev-1/flag", CREATOR_TOKEN);
    }

    @Test
    @DisplayName("review-write and review-flag are independent buckets")
    void reviewWriteAndFlag_areIndependentBuckets() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed("POST", "/creator/reviews", CREATOR_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        MockHttpServletResponse flagResponse = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/creator/reviews/rev-1/flag", CREATOR_TOKEN),
                flagResponse,
                new MockFilterChain());
        assertEquals(200, flagResponse.getStatus());
    }

    @Test
    @DisplayName("POST /deals/{id}/disputes is throttled per user once dispute-open limit is exceeded")
    void disputeOpen_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/deals/deal-1/disputes", CREATOR_TOKEN);
    }

    @Test
    @DisplayName("Different users get independent dispute-open allowances")
    void disputeOpen_isPerUser() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed("POST", "/deals/deal-1/disputes", CREATOR_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/deals/deal-1/disputes", OTHER_TOKEN), other, new MockFilterChain());
        assertEquals(200, other.getStatus());
    }

    @Test
    @DisplayName("POST /creators/{id}/invite is throttled per user")
    void discoveryInvite_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/creators/c-1/invite", BRAND_TOKEN);
    }

    @Test
    @DisplayName("GET /creators is throttled per user (M-K6-5)")
    void discoverySearch_listThrottledAfterLimit() throws Exception {
        assertThrottledAfterLimit("GET", "/creators", BRAND_TOKEN);
    }

    @Test
    @DisplayName("GET /creators and GET /creators/search share the discovery-search bucket")
    void discoverySearchPaths_shareBucket() throws Exception {
        filter.doFilter(
                authed("GET", "/creators", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                authed("GET", "/creators/search", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(authed("GET", "/creators", BRAND_TOKEN), third, new MockFilterChain());
        assertEquals(429, third.getStatus());
    }

    @Test
    @DisplayName(
            "POST /creators/suggestions shares the discovery-search bucket with GET /creators/search"
                    + " (Kabir NEW-2)")
    void discoverySuggestions_sharesBucketWithSearch() throws Exception {
        // /creators/suggestions runs the same query shape/cost as /creators/search, so it must
        // count against the same 60/min discovery-search bucket rather than going unthrottled.
        filter.doFilter(
                authed("GET", "/creators/search", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                authed("POST", "/creators/suggestions", BRAND_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/creators/suggestions", BRAND_TOKEN), third, new MockFilterChain());
        assertEquals(429, third.getStatus());
    }

    @Test
    @DisplayName("GET /creators/featured is not throttled by discovery-search bucket")
    void discoveryFeatured_notThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    authed("GET", "/creators/featured", BRAND_TOKEN),
                    response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    @DisplayName("POST /creator/campaigns/{id}/apply is throttled per user")
    void campaignApply_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/creator/campaigns/camp-1/apply", CREATOR_TOKEN);
    }

    @Test
    @DisplayName("POST /wallet/withdraw is throttled per user (M-K6-4 HTTP layer)")
    void creatorWithdraw_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/wallet/withdraw", CREATOR_TOKEN);
    }

    @Test
    @DisplayName("Withdraw uses the hour window — Retry-After reflects withdrawWindowSeconds")
    void creatorWithdraw_retryAfterUsesHourWindow() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed("POST", "/wallet/withdraw", CREATOR_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/wallet/withdraw", CREATOR_TOKEN), third, new MockFilterChain());

        assertEquals(429, third.getStatus());
        // Fixed-window just started → Retry-After ≈ full hour window (3600), not the 60s default.
        assertEquals("3600", third.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName(
            "Percent-encoded /wallet/%77ithdraw shares the creator-withdraw bucket with"
                    + " /wallet/withdraw (Kabir NEW-1)")
    void creatorWithdraw_percentEncodedPath_sharesBucketWithPlainPath() throws Exception {
        // Percent-encoding a single letter of "withdraw" must not bypass bucketFor()'s matchers --
        // DispatcherServlet decodes the path before routing, so the raw, undecoded
        // getRequestURI() has to be decoded here too, or every bucket in this filter is
        // defeated identically. Mixing encoded/plain hits into the same counter proves the
        // two paths resolve to one bucket, not two independent (and therefore bypassable) ones.
        filter.doFilter(
                authed("POST", "/wallet/%77ithdraw", CREATOR_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                authed("POST", "/wallet/withdraw", CREATOR_TOKEN),
                new MockHttpServletResponse(),
                new MockFilterChain());

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/wallet/%77ithdraw", CREATOR_TOKEN), third, new MockFilterChain());
        assertEquals(429, third.getStatus());
    }

    @Test
    @DisplayName("Without a Bearer token, K6 buckets fall back to per-IP keying")
    void k6Bucket_fallsBackToIpWithoutJwt() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/v1/creator/reviews");
            request.setRemoteAddr("10.3.0.99");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest third =
                new MockHttpServletRequest("POST", "/api/v1/creator/reviews");
        third.setRemoteAddr("10.3.0.99");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, new MockFilterChain());

        assertEquals(429, thirdResponse.getStatus());
    }

    @Test
    @DisplayName("Existing deliverable-write bucket is unchanged by K6 additions")
    void existingDeliverableWrite_stillThrottledIndependently() throws Exception {
        setField("creatorDeliverableWriteLimit", 2);

        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed("POST", "/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/creator/deliverables/del-1/upload", CREATOR_TOKEN),
                third,
                new MockFilterChain());
        assertEquals(429, third.getStatus());

        // Exhausted deliverable-write must not block a fresh review-write allowance.
        MockHttpServletResponse review = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/creator/reviews", CREATOR_TOKEN), review, new MockFilterChain());
        assertEquals(200, review.getStatus());
    }
}
