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
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8), B0-42 — the {@code creator-brief-paste} bucket.
 *
 * <p>{@code POST /creator/briefs} is the one creator route that spends real money on every call: each
 * paste is an AI extraction billed against the creator's own monthly brief allowance. The window bound
 * this bucket applies and that monthly allowance are two different controls — the allowance stops a
 * month of cost, this stops a minute of it — and neither substitutes for the other.
 *
 * <p>Two properties here are not decoration. It is USER-keyed, because an IP key on a creator route is
 * wrong in both directions: creators behind one mobile carrier NAT would starve each other, and a
 * single creator could reset her own window by changing network. And it matches the POST only: the GET
 * route has its own, separate bucket now (Kavya U-1 re-review, H2 — a stale-NEW brief re-analyses on
 * read, which is a real AI call) at a more generous limit, tested in
 * {@code AuthRateLimitFilterCreatorBriefGetBucketTest}, not this stricter one.
 */
class AuthRateLimitFilterBriefPasteBucketTest {

    private static final String CREATOR_TOKEN = "creator-token";
    private static final String OTHER_CREATOR_TOKEN = "other-creator-token";

    private JwtService jwtService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        stubSubject(CREATOR_TOKEN, "creator-user-1");
        stubSubject(OTHER_CREATOR_TOKEN, "creator-user-2");

        filter = new AuthRateLimitFilter(jwtService, null);
        setField("enabled", true);
        setField("sensitiveLimit", 50);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 30);
        setField("creatorDeliverableWriteLimit", 20);
        setField("brandDeliverableReviewLimit", 20);
        setField("contractSignLimit", 10);
        setField("reviewWriteLimit", 10);
        setField("reviewFlagLimit", 10);
        setField("disputeOpenLimit", 5);
        setField("discoveryInviteLimit", 20);
        setField("discoverySearchLimit", 60);
        setField("campaignApplyLimit", 20);
        setField("creatorWithdrawLimit", 5);
        setField("withdrawWindowSeconds", 3600L);
        setField("meeraTurnLimit", 20);
        setField("meeraVoiceLimit", 20);
        setField("creatorBriefPasteLimit", 2);
        // Kavya U-1 re-review, H2 — GET /creator/briefs/{id} now has its OWN bucket
        // (creator-brief-get) rather than falling through unthrottled. Set generously here so this
        // file's few incidental GETs (proving they are NOT in the PASTE bucket) are not themselves
        // throttled by the get bucket's own, separately-tested limit -- see
        // AuthRateLimitFilterCreatorBriefGetBucketTest for that bucket's own behaviour.
        setField("creatorBriefGetLimit", 20);
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
        request.setRemoteAddr("10.9.0.1");
        return request;
    }

    private int statusOf(String method, String path, String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(authed(method, path, token), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("POST /creator/briefs is throttled once the paste limit is exceeded")
    void pasteThrottledAfterLimit() throws Exception {
        assertEquals(200, statusOf("POST", "/creator/briefs", CREATOR_TOKEN));
        assertEquals(200, statusOf("POST", "/creator/briefs", CREATOR_TOKEN));
        assertEquals(429, statusOf("POST", "/creator/briefs", CREATOR_TOKEN));
    }

    @Test
    @DisplayName("the bucket is keyed per creator — one creator exhausting it does not throttle another")
    void keyedPerCreator() throws Exception {
        statusOf("POST", "/creator/briefs", CREATOR_TOKEN);
        statusOf("POST", "/creator/briefs", CREATOR_TOKEN);
        assertEquals(429, statusOf("POST", "/creator/briefs", CREATOR_TOKEN));

        assertEquals(
                200,
                statusOf("POST", "/creator/briefs", OTHER_CREATOR_TOKEN),
                "a second creator behind the same address must get her own window");
    }

    @Test
    @DisplayName("GET /creator/briefs and the read/dismiss routes are NOT in the paste bucket")
    void readsAreNotThrottledByThePasteBucket() throws Exception {
        statusOf("POST", "/creator/briefs", CREATOR_TOKEN);
        statusOf("POST", "/creator/briefs", CREATOR_TOKEN);
        assertEquals(429, statusOf("POST", "/creator/briefs", CREATOR_TOKEN));

        // The list route has no bucket at all (a plain snapshot query), the single-brief GET has
        // its OWN bucket now (creator-brief-get, set generously above and tested on its own in
        // AuthRateLimitFilterCreatorBriefGetBucketTest), and dismiss is a plain status write with
        // no bucket -- none of the three share the exhausted PASTE bucket above.
        assertEquals(200, statusOf("GET", "/creator/briefs", CREATOR_TOKEN));
        assertEquals(200, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));
        assertEquals(200, statusOf("POST", "/creator/briefs/01HBRIEF1234/dismiss", CREATOR_TOKEN));
    }

    @Test
    @DisplayName("an unrelated creator route is unaffected by the new bucket")
    void unrelatedCreatorRouteUnaffected() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, statusOf("GET", "/creator/agent/preferences", CREATOR_TOKEN));
        }
    }
}
