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
 * [SEC: Kabir red-team MEDIUM fix] Proves {@link AuthRateLimitFilter} now throttles the Meera
 * chat-turn ({@code POST /meera/sessions/{id}/messages}) and voice ({@code POST
 * /meera/voice/speak}) cost surfaces, which previously had no throttle at all despite each call
 * being a real per-call LLM/TTS provider cost. Both buckets are user-keyed (JWT {@code sub}),
 * mirroring the convention proven in {@link AuthRateLimitFilterK6BucketTest}, and paths unrelated
 * to Meera must remain unaffected by the new buckets.
 */
class AuthRateLimitFilterMeeraBucketTest {

    private static final String BRAND_TOKEN = "brand-token";
    private static final String OTHER_TOKEN = "other-token";

    private JwtService jwtService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        stubSubject(BRAND_TOKEN, "brand-user-1");
        stubSubject(OTHER_TOKEN, "other-user-2");

        filter = new AuthRateLimitFilter(jwtService);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
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
        setField("meeraTurnLimit", 2);
        setField("meeraVoiceLimit", 2);
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

    private void assertThrottledAfterLimit(String method, String path, String token, int limit)
            throws Exception {
        for (int i = 0; i < limit; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(authed(method, path, token), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse overLimit = new MockHttpServletResponse();
        filter.doFilter(authed(method, path, token), overLimit, new MockFilterChain());
        assertEquals(429, overLimit.getStatus());
    }

    @Test
    @DisplayName("POST /meera/sessions/{id}/messages is throttled per user once meera-turn limit is exceeded")
    void meeraTurn_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/meera/sessions/convo-1/messages", BRAND_TOKEN, 2);
    }

    @Test
    @DisplayName("POST /meera/voice/speak is throttled per user once meera-voice limit is exceeded")
    void meeraVoice_throttledAfterLimit() throws Exception {
        assertThrottledAfterLimit("POST", "/meera/voice/speak", BRAND_TOKEN, 2);
    }

    @Test
    @DisplayName("meera-turn and meera-voice are independent buckets per user — exhausting one does not throttle the other")
    void meeraTurnAndVoice_areIndependentBuckets() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    authed("POST", "/meera/sessions/convo-1/messages", BRAND_TOKEN),
                    response,
                    new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse turnOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/meera/sessions/convo-1/messages", BRAND_TOKEN),
                turnOverLimit,
                new MockFilterChain());
        assertEquals(429, turnOverLimit.getStatus());

        // Same user, different bucket (voice) — still fresh.
        MockHttpServletResponse voiceStillOk = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/meera/voice/speak", BRAND_TOKEN), voiceStillOk, new MockFilterChain());
        assertEquals(200, voiceStillOk.getStatus());
    }

    @Test
    @DisplayName("meera-turn is keyed per user — a different JWT sub gets its own fresh bucket")
    void meeraTurn_keyedPerUser() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed("POST", "/meera/sessions/convo-1/messages", BRAND_TOKEN),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }
        MockHttpServletResponse brandOverLimit = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/meera/sessions/convo-1/messages", BRAND_TOKEN),
                brandOverLimit,
                new MockFilterChain());
        assertEquals(429, brandOverLimit.getStatus());

        MockHttpServletResponse otherUserOk = new MockHttpServletResponse();
        filter.doFilter(
                authed("POST", "/meera/sessions/convo-1/messages", OTHER_TOKEN),
                otherUserOk,
                new MockFilterChain());
        assertEquals(200, otherUserOk.getStatus());
    }

    @Test
    @DisplayName("Unrelated Meera-adjacent path (/meera/sessions, session start) is not throttled by the new buckets")
    void meeraSessionStart_unaffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(authed("POST", "/meera/sessions", BRAND_TOKEN), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }
}
