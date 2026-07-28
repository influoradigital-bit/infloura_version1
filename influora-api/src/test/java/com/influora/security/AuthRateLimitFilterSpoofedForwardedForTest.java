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
 * [SEC: Kabir CR-11 endpoint red-team, Blocker-1] A client-supplied {@code X-Forwarded-For} must
 * never influence the rate-limit bucket key.
 *
 * <p>The defect: {@code clientIp()} hand-parsed XFF behind a comma-separated trusted-proxy
 * allow-list, and the deploys set {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}. Spring's
 * {@code ForwardedHeaderFilter} then ran ahead of the Security chain, overwrote
 * {@code getRemoteAddr()} with the <em>left-most</em> XFF entry (client-spoofable — Caddy appends
 * the true peer rather than replacing the header), and stripped the headers. The allow-list check
 * compared the spoofed value against itself, never matched, and fell through to {@code return
 * peer} — returning the attacker's own header as the key. Rotating the header per request meant no
 * limit existed, on every IP-keyed bucket including login brute-force.
 *
 * <p>The fix is upstream config ({@code forward-headers-strategy: native}, so Tomcat's
 * {@code RemoteIpValve} resolves the peer with its own allow-list and a right-to-left walk), and
 * downstream {@code clientIp()} simply reads {@code getRemoteAddr()}.
 *
 * <p><b>What this test can and cannot prove.</b> It asserts the filter's own contract: the bucket
 * key follows {@code getRemoteAddr()} and NOTHING else, so no header a client controls can split or
 * refresh a bucket. It cannot exercise {@code RemoteIpValve} itself — that lives in the embedded
 * container, and this codebase has no {@code @SpringBootTest} harness for the filter chain (see the
 * sibling {@code AuthRateLimitFilter*BucketTest} classes, same reflection harness). Whether the
 * valve is wired correctly is a deploy-config property, verified by the runbook check, not here.
 * Recorded explicitly so nobody reads a green suite as proof the whole chain is safe.
 *
 * <p>Run: {@code mvn -o test -Dtest=AuthRateLimitFilterSpoofedForwardedForTest}
 */
class AuthRateLimitFilterSpoofedForwardedForTest {

    private static final String PATH = "/auth/brand/login"; // the `sensitive` bucket
    private static final int SENSITIVE_LIMIT = 3;

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthRateLimitFilter(null);
        setField("enabled", true);
        setField("sensitiveLimit", SENSITIVE_LIMIT);
        setField("otpLimit", 5);
        setField("refreshLimit", 30);
        setField("metaOAuthLimit", 20);
        setField("trackingLimit", 30);
        setField("clientErrorLimit", 30);
        setField("windowSeconds", 60L);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    /** One login attempt from {@code peer}, optionally carrying a spoofed XFF. Returns the status. */
    private int attempt(String peer, String spoofedXff) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRemoteAddr(peer);
        if (spoofedXff != null) {
            request.addHeader("X-Forwarded-For", spoofedXff);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("a rotating spoofed X-Forwarded-For cannot buy extra login attempts")
    void spoofedForwardedForCannotEvadeTheSensitiveBucket() throws Exception {
        // Same attacker, same socket, a fresh forged XFF every time — the exact evasion.
        for (int i = 1; i <= SENSITIVE_LIMIT; i++) {
            assertEquals(200, attempt("203.0.113.9", "10.1.1." + i), "attempt " + i + " should pass");
        }
        // Pre-fix this returned 200 forever: each new header value minted a fresh bucket.
        assertEquals(429, attempt("203.0.113.9", "10.1.1.99"));
        // And a completely different forged value still doesn't reset it.
        assertEquals(429, attempt("203.0.113.9", "198.51.100.7"));
        // Nor does dropping the header entirely.
        assertEquals(429, attempt("203.0.113.9", null));
    }

    @Test
    @DisplayName("the bucket follows getRemoteAddr(), so a genuinely different peer is unaffected")
    void aDifferentPeerKeepsItsOwnBudget() throws Exception {
        for (int i = 1; i <= SENSITIVE_LIMIT; i++) {
            assertEquals(200, attempt("203.0.113.9", null));
        }
        assertEquals(429, attempt("203.0.113.9", null));

        // A real second client must not be throttled by the first one's exhaustion — the guard has
        // to block evasion without collapsing everyone into one global bucket, which is the other
        // way this can be got wrong (and was, whenever TRUSTED_PROXIES was left empty).
        assertEquals(200, attempt("203.0.113.10", null));
        // Even when it carries an XFF that matches the exhausted client's forged one.
        assertEquals(200, attempt("203.0.113.11", "10.1.1.1"));
    }
}
