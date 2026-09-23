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
 * F-audit-A2 — proves {@link AuthRateLimitFilter} now (1) throttles {@code POST
 * /creator/meera/shoot-check/frame} per creator, the same as its sibling voice paths, where it
 * previously had NO bucket at all, and (2) rejects an oversize body on that one path via {@code
 * Content-Length} before any multipart parsing happens, without touching the global 500MB upload
 * limit every other endpoint relies on.
 */
class AuthRateLimitFilterFrameCheckBucketTest {

    private static final String CREATOR_TOKEN = "creator-token";
    private static final String OTHER_TOKEN = "other-creator-token";
    private static final String PATH = "/creator/meera/shoot-check/frame";

    private JwtService jwtService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = mock(JwtService.class);
        stubSubject(CREATOR_TOKEN, "creator-user-1");
        stubSubject(OTHER_TOKEN, "creator-user-2");

        filter = new AuthRateLimitFilter(jwtService, null);
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
        setField("meeraTurnLimit", 20);
        setField("meeraVoiceLimit", 30);
        setField("frameCheckLimit", 2);
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

    private MockHttpServletRequest authed(String path, String token, long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.setRemoteAddr("10.4.0.1");
        if (contentLength >= 0) {
            // MockHttpServletRequest#getContentLengthLong() derives its value from the byte[] set
            // via setContent(), NOT from a header added via addHeader (unlike a real Tomcat
            // request, where Content-Length comes straight off the wire, no buffering needed) --
            // so the request body is actually sized here to make the production code path under
            // test (request.getContentLengthLong()) see the value. The header is also added so
            // the request looks realistic.
            request.setContent(new byte[(int) contentLength]);
            request.addHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
            request.setContentType("multipart/form-data; boundary=x");
        }
        return request;
    }

    @Test
    @DisplayName("POST /creator/meera/shoot-check/frame is throttled per creator once the frame-check limit is exceeded")
    void frameCheck_throttledAfterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(authed(PATH, CREATOR_TOKEN, 1000), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse overLimit = new MockHttpServletResponse();
        filter.doFilter(authed(PATH, CREATOR_TOKEN, 1000), overLimit, new MockFilterChain());
        assertEquals(429, overLimit.getStatus());
    }

    @Test
    @DisplayName("frame-check is keyed per creator — a different JWT sub gets its own fresh bucket")
    void frameCheck_keyedPerCreator() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    authed(PATH, CREATOR_TOKEN, 1000), new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse creatorOverLimit = new MockHttpServletResponse();
        filter.doFilter(authed(PATH, CREATOR_TOKEN, 1000), creatorOverLimit, new MockFilterChain());
        assertEquals(429, creatorOverLimit.getStatus());

        MockHttpServletResponse otherCreatorOk = new MockHttpServletResponse();
        filter.doFilter(authed(PATH, OTHER_TOKEN, 1000), otherCreatorOk, new MockFilterChain());
        assertEquals(200, otherCreatorOk.getStatus());
    }

    @Test
    @DisplayName(
            "an oversize frame-check body is rejected by Content-Length before multipart parsing, with a 413")
    void oversizeBody_rejectedBeforeParsing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Comfortably over both the 1.5MB controller check AND the filter's own cap -- if this
        // ever reached Spring's multipart resolver, the global 500MB limit would happily accept it.
        filter.doFilter(authed(PATH, CREATOR_TOKEN, 5_000_000L), response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertEquals("application/json", response.getContentType());
    }

    @Test
    @DisplayName("a normal-size frame-check body (under the cap) is never rejected for size")
    void normalSizeBody_notRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(authed(PATH, CREATOR_TOKEN, 500_000L), response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("the oversize check is scoped to the frame-check path only — an oversize body elsewhere is unaffected")
    void oversizeCheck_scopedToFrameCheckPathOnly() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                authed("/creator/meera/voice/speak", CREATOR_TOKEN, 5_000_000L),
                response,
                new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
