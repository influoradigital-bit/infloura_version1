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
 * Kavya U-1 re-review, H2 (KAVYA-U1-RECHECK-0917.md) — the {@code creator-brief-get} bucket on
 * {@code GET /creator/briefs/{id}}.
 *
 * <p>Before the F1 HIGH fix ({@code CreatorBriefService#readOrReanalyse}), this route was a pure
 * snapshot read and correctly unthrottled. It now re-analyses a stale {@code NEW} brief on read — a
 * real, blocking ~30s AI call — so an unthrottled creator (or a buggy client polling/retrying) could
 * pin request threads on back-to-back calls until the monthly brief allowance ran out. That allowance
 * bounds total AI SPEND; this bucket bounds REQUEST VOLUME, which is a different failure mode the
 * allowance does nothing about.
 *
 * <p>USER-keyed, for the same reason {@code creator-brief-paste} is: the identity to bound is the
 * creator's, not her network's. Matches only the single-brief GET — the list route
 * ({@code GET /creator/briefs}) is a cheap query with no per-row AI risk and stays unthrottled, and
 * the paste/dismiss POSTs have their own, unrelated handling.
 */
class AuthRateLimitFilterCreatorBriefGetBucketTest {

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
        setField("creatorBriefPasteLimit", 10);
        setField("creatorBriefGetLimit", 2);
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
    @DisplayName("GET /creator/briefs/{id} is throttled once the get limit is exceeded")
    void getThrottledAfterLimit() throws Exception {
        assertEquals(200, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));
        assertEquals(200, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));
        assertEquals(429, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));
    }

    @Test
    @DisplayName("the bucket is keyed per creator — one creator exhausting it does not throttle another")
    void keyedPerCreator() throws Exception {
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);
        assertEquals(429, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));

        assertEquals(
                200,
                statusOf("GET", "/creator/briefs/01HBRIEF1234", OTHER_CREATOR_TOKEN),
                "a second creator behind the same address must get her own window");
    }

    @Test
    @DisplayName("exhausting the get bucket on one brief id still throttles a DIFFERENT brief id")
    void keyedPerCreatorNotPerBriefId() throws Exception {
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);

        // Same creator, a DIFFERENT id -- still the same bucket (path pattern is
        // /creator/briefs/{id}, keyed on the creator, not the id), so this is the third call in
        // the window and must still be throttled. A rate limiter that reset per-id would let a
        // spam loop dodge the limit simply by cycling through her own brief ids.
        assertEquals(429, statusOf("GET", "/creator/briefs/01HBRIEFOTHR99", CREATOR_TOKEN));
    }

    @Test
    @DisplayName("the paste and dismiss routes, and the list route, are NOT in the get bucket")
    void otherBriefRoutesAreNotThrottledByTheGetBucket() throws Exception {
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);
        statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN);
        assertEquals(429, statusOf("GET", "/creator/briefs/01HBRIEF1234", CREATOR_TOKEN));

        // The list route is a cheap query with no bucket at all.
        assertEquals(200, statusOf("GET", "/creator/briefs", CREATOR_TOKEN));
        // Paste has its own, separately-tested bucket (creator-brief-paste).
        assertEquals(
                200,
                statusOf("POST", "/creator/briefs", CREATOR_TOKEN),
                "paste must use its own bucket, not this one");
        // Dismiss is a plain status write with no bucket.
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
