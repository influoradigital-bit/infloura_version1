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
 * <p><b>What this test can and cannot prove — read this before trusting it.</b> It asserts the
 * filter's own contract: the bucket key follows {@code getRemoteAddr()} and NOTHING else, so no
 * header a client controls can split or refresh a bucket.
 *
 * <p><b>It would have PASSED on the vulnerable build.</b> [Kabir CR-11 XFF re-review, point 6.]
 * The poisoning happened upstream, in {@code ForwardedHeaderFilter}, which is not in this harness;
 * and with no trusted proxies configured the old {@code clientIp()} also fell through to the peer
 * for these inputs. So this is a forward-looking guard on the filter's contract, NOT regression
 * cover for Blocker-1 — it commemorates a bug it could not have caught. The commit that added it
 * called it "revert-proven", which was true only of a hand-written stand-in for the old code, not
 * of the real defect.
 *
 * <p>What actually protects Blocker-1 from returning is {@code SecretsStartupValidator}'s
 * {@code forward-headers-strategy} check, which refuses to boot on {@code framework} — the defect
 * lived in config, so the guard has to as well.
 *
 * <p>A real test of {@code RemoteIpValve} is cheaper than the original commit implied:
 * {@code org.apache.catalina.filters.RemoteIpFilter} is the filter twin of the valve, shares its
 * resolution code, is already on the classpath via {@code tomcat-embed-core}, and works with
 * {@code MockHttpServletRequest} — no container, no Docker (which matters, since every
 * {@code @SpringBootTest} here dies on testcontainers discovery). Worth doing; not done yet.
 *
 * <p>Run: {@code mvn -o test -Dtest=AuthRateLimitFilterSpoofedForwardedForTest}
 */
class AuthRateLimitFilterSpoofedForwardedForTest {

    private static final String PATH = "/auth/brand/login"; // the `sensitive` bucket
    private static final int SENSITIVE_LIMIT = 3;
    private static final int CLIENT_ERROR_LIMIT = 2;

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
        setField("clientErrorLimit", CLIENT_ERROR_LIMIT);
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
    @DisplayName(
            "[SEC: Kabir CR-11 red-team, L-7] a matrix parameter cannot dodge the bucket entirely")
    void matrixParameterCannotEvadeTheBucket() throws Exception {
        // Spring Boot 3's PathPatternParser treats matrix variables as segment metadata, so
        // `POST /auth/brand/login;x=1` still ROUTES to the handler — but this filter matched the raw
        // URI, so the path failed every equals() and NO bucket was assigned at all. Unthrottled.
        //
        // Ranked LOW by the red-team only because Blocker-1 was handing out unlimited requests
        // anyway. Blocker-1 is fixed, so this became the next bypass — severity is relative to what
        // else is broken, and nothing re-ranks a finding when its dependency closes.
        // NOTE the endpoint: `/client-errors`, a LITERAL-path bucket matched with `.equals()`.
        // An earlier draft of this test used `/auth/brand/login` and passed with the guard removed —
        // vacuously, because the `/auth/` family matches with `startsWith` and was never vulnerable
        // to this. Only the `.equals()` buckets (`/client-errors`, `/wallet/withdraw`,
        // `/webhooks/*`, `/meera/voice/*`) can be dodged this way, so the test has to use one.
        for (int i = 1; i <= CLIENT_ERROR_LIMIT; i++) {
            assertEquals(200, postTo("/client-errors", "203.0.113.20"));
        }
        assertEquals(429, postTo("/client-errors", "203.0.113.20"));

        // Same client, same endpoint, one matrix param appended. Pre-fix `.equals()` failed, NO
        // bucket was assigned at all, and this returned 200 forever.
        assertEquals(429, postTo("/client-errors;x=1", "203.0.113.20"));
    }

    /** One POST to an arbitrary path from `peer`, no XFF. Returns the status. */
    private int postTo(String path, String peer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(peer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
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
