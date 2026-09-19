package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.config.JwksSigningKeyProperties;
import com.influora.domain.enums.UserType;
import com.influora.service.meera.OnBehalfTokenService;
import com.influora.testsupport.TestEcKeys;
import io.jsonwebtoken.Claims;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.4) — the {@code creator-tool} bucket on
 * {@code POST /internal/meera/creator/*}.
 *
 * <p><b>The defect this exists to prevent.</b> Every call to that surface arrives server-to-server
 * from the single influora-ai process, so all of them share one source IP. An IP-keyed bucket there
 * is not a per-caller cap — it is a platform-wide one, and the first busy creator of the window
 * starves every other creator on the platform. The tests below therefore assert the OPPOSITE of what
 * an IP-keyed bucket would do: two creators calling from the SAME address must not share a window.
 *
 * <p>Uses real, signed on-behalf tokens rather than a mocked service, because the key derivation
 * verifies the token — an unverified subject would be attacker-chosen (this filter runs before
 * {@code InternalServiceTokenFilter}), and a forged header could then burn a named creator's window
 * from outside.
 */
class AuthRateLimitFilterCreatorToolBucketTest {

    private static final String CREATOR_A = "01HCREATORAAAAAAAAAAA";
    private static final String CREATOR_B = "01HCREATORBBBBBBBBBBB";

    /** One address for everything: the Python process. */
    private static final String AI_SERVICE_IP = "10.9.0.7";

    private OnBehalfTokenService onBehalfTokenService;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        JwksSigningKeyProperties jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-creator-tool");
        onBehalfTokenService = new OnBehalfTokenService(new SpringJwksKeyService(jwksProps));

        filter = new AuthRateLimitFilter(null, onBehalfTokenService);
        setField("enabled", true);
        setField("sensitiveLimit", 10);
        setField("windowSeconds", 60L);
        setField("creatorToolLimit", 2);
        setField("creatorToolVerifyFailureBudget", 20);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(filter, value);
    }

    private String tokenFor(String creatorUserId) {
        return onBehalfTokenService.mint(
                creatorUserId, "01HCONV1234567890AB", "01HTURN1234567890AB", creatorUserId, UserType.CREATOR, "get_my_deals");
    }

    private MockHttpServletRequest call(String path, String onBehalfToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        if (onBehalfToken != null) {
            request.addHeader("X-Onbehalf-Authorization", onBehalfToken);
        }
        request.setRemoteAddr(AI_SERVICE_IP);
        return request;
    }

    private int statusOf(String path, String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(call(path, token), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("the creator tool surface is throttled once the per-creator limit is exceeded")
    void creatorTool_throttledAfterLimit() throws Exception {
        String token = tokenFor(CREATOR_A);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", token));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", token));
        assertEquals(429, statusOf("/internal/meera/creator/get_my_deals", token));
    }

    @Test
    @DisplayName(
            "THE POINT: one creator exhausting her window does NOT throttle a second creator calling"
                    + " from the same influora-ai IP -- an IP-keyed bucket would 429 her here")
    void creatorTool_isKeyedOnTheCreatorNotTheCallingProcess() throws Exception {
        String tokenA = tokenFor(CREATOR_A);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", tokenA));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", tokenA));
        assertEquals(429, statusOf("/internal/meera/creator/get_my_deals", tokenA));

        // Same source address, different creator: a completely fresh window.
        String tokenB = tokenFor(CREATOR_B);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", tokenB));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", tokenB));
    }

    @Test
    @DisplayName(
            "one creator's window is shared across the tools she calls -- the bucket bounds the"
                    + " creator, not the route")
    void creatorTool_windowIsSharedAcrossRoutesForOneCreator() throws Exception {
        String token = tokenFor(CREATOR_A);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", token));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_metrics", token));
        assertEquals(429, statusOf("/internal/meera/creator/get_my_deals", token));
    }

    @Test
    @DisplayName(
            "a FORGED on-behalf token does not open a window in a named creator's bucket -- it fails"
                    + " verification and falls back to IP-keying, which is what an unproven caller"
                    + " deserves")
    void creatorTool_forgedTokenCannotBurnAnotherCreatorsWindow() throws Exception {
        // Not signed by this service's keypair at all.
        String forged = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIwMUhDUkVBVE9SQUFBQUFBQUFBQUEifQ.";
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", forged));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", forged));
        // The forger has exhausted the IP-keyed fallback window, not CREATOR_A's.
        assertEquals(429, statusOf("/internal/meera/creator/get_my_deals", forged));

        String realTokenA = tokenFor(CREATOR_A);
        assertEquals(
                200,
                statusOf("/internal/meera/creator/get_my_deals", realTokenA),
                "the named creator's own window must be untouched by the forgery");
    }

    @Test
    @DisplayName("a Bearer-prefixed on-behalf header is accepted, since the mesh may add the scheme")
    void creatorTool_acceptsBearerPrefix() throws Exception {
        String token = tokenFor(CREATOR_A);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", "Bearer " + token));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", token));
        assertEquals(
                429,
                statusOf("/internal/meera/creator/get_my_deals", token),
                "both forms must land in the SAME bucket, or the prefix would double the limit");
    }

    // ---------------------------------------------------------------------------------------
    // [SEC: Kabir Wave 2, finding 1] An unauthenticated flood must not be able to buy signature
    // verifications. Measured before the fix: ~1.2 ms for a well-formed wrong-signature token
    // against ~0.005 ms for a request with no header, and the key was derived before the counter
    // was consulted, so a 429 shed none of it. The tests below count actual verify() calls, which
    // is the thing the cost is proportional to.
    // ---------------------------------------------------------------------------------------

    /** Counts the calls that actually reach the signature check. */
    private static final class CountingOnBehalfTokenService extends OnBehalfTokenService {
        private final AtomicInteger verifyCalls = new AtomicInteger();

        CountingOnBehalfTokenService(SpringJwksKeyService keyService) {
            super(keyService);
        }

        @Override
        public Claims verify(String token) {
            verifyCalls.incrementAndGet();
            return super.verify(token);
        }
    }

    private CountingOnBehalfTokenService countingService() throws Exception {
        JwksSigningKeyProperties props = new JwksSigningKeyProperties();
        props.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        props.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        props.setKid("test-kid-creator-tool");
        CountingOnBehalfTokenService counting =
                new CountingOnBehalfTokenService(new SpringJwksKeyService(props));
        onBehalfTokenService = counting;
        filter = new AuthRateLimitFilter(null, counting);
        setField("enabled", true);
        setField("windowSeconds", 60L);
        setField("creatorToolLimit", 2);
        setField("creatorToolVerifyFailureBudget", 5);
        return counting;
    }

    /**
     * Flips one base64url character of a token's signature, leaving the header and payload — and so
     * every claim, including {@code sub} — byte-identical. This is the shape a flood sends, because
     * it is the shape that costs a full curve operation to reject, and it is also the shape that
     * catches a cache keyed on anything less than the whole token.
     */
    private static String corruptSignature(String jwt) {
        int lastDot = jwt.lastIndexOf('.');
        char first = jwt.charAt(lastDot + 1);
        return jwt.substring(0, lastDot + 1)
                + (first == 'A' ? 'B' : 'A')
                + jwt.substring(lastDot + 2);
    }

    private String wrongSignatureTokenFor(String creatorUserId) {
        return corruptSignature(tokenFor(creatorUserId));
    }

    @Test
    @DisplayName(
            "THE POINT (finding 1): 60 requests with 60 DIFFERENT well-formed wrong-signature tokens"
                    + " buy at most the per-IP failure budget in signature verifications -- not 60")
    void creatorTool_floodCannotBuyUnboundedSignatureVerifications() throws Exception {
        CountingOnBehalfTokenService counting = countingService();

        for (int i = 0; i < 60; i++) {
            // A different token every request, so memoisation cannot be what saves us here.
            statusOf(
                    "/internal/meera/creator/get_my_deals",
                    wrongSignatureTokenFor("01HFLOODCREATOR" + String.format("%05d", i)));
        }

        assertEquals(
                5,
                counting.verifyCalls.get(),
                "the flood bought one verification per request -- the budget did not shed anything");
    }

    @Test
    @DisplayName(
            "finding 1: a quarantined address is not given a free pass -- it falls to IP-keying and"
                    + " its own creator-tool window 429s it")
    void creatorTool_quarantinedAddressStillGets429() throws Exception {
        countingService();

        // Burn the budget and the IP-keyed window together.
        for (int i = 0; i < 10; i++) {
            statusOf(
                    "/internal/meera/creator/get_my_deals",
                    wrongSignatureTokenFor("01HFLOODCREATOR" + String.format("%05d", i)));
        }

        assertEquals(
                429,
                statusOf(
                        "/internal/meera/creator/get_my_deals",
                        wrongSignatureTokenFor("01HFLOODCREATORFINAL")),
                "a caller that has proven nothing must still be throttled, just cheaply");
    }

    @Test
    @DisplayName(
            "finding 1: the failure budget is charged on FAILURES only -- a legitimate caller"
                    + " presenting valid tokens from one address is never quarantined")
    void creatorTool_validTrafficNeverSpendsTheFailureBudget() throws Exception {
        CountingOnBehalfTokenService counting = countingService();
        setField("creatorToolLimit", 1000);

        // Far more calls than the budget of 5, all from the one influora-ai address.
        for (int i = 0; i < 40; i++) {
            assertEquals(
                    200,
                    statusOf(
                            "/internal/meera/creator/get_my_deals",
                            tokenFor("01HGOODCREATOR" + String.format("%06d", i))),
                    "a valid token was dropped to IP-keying: the budget is charging successes");
        }
        assertEquals(
                40,
                counting.verifyCalls.get(),
                "every distinct valid token must still be verified -- a miss always verifies");
    }

    @Test
    @DisplayName(
            "finding 1: the same token across one turn's tool calls is verified ONCE, and the"
                    + " creator's counter still decrements on every call")
    void creatorTool_repeatCallsInOneTurnVerifyOnceAndStillCount() throws Exception {
        CountingOnBehalfTokenService counting = countingService();
        String token = tokenFor(CREATOR_A);

        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", token));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_metrics", token));
        assertEquals(
                429,
                statusOf("/internal/meera/creator/get_my_deals", token),
                "memoising the subject must not stop the window being consumed");

        assertEquals(
                1,
                counting.verifyCalls.get(),
                "a turn reusing its 120-second token should cost one verification, not three");
    }

    @Test
    @DisplayName(
            "finding 1: the memo is keyed on the token, NEVER on its unverified sub -- a forged"
                    + " token naming a creator whose real token is already memoised cannot read that"
                    + " entry")
    void creatorTool_memoCannotBeReachedByAForgedSubject() throws Exception {
        countingService();
        setField("creatorToolLimit", 2);

        // Put CREATOR_A in the memo via a real, verified token.
        String real = tokenFor(CREATOR_A);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", real));

        // THAT EXACT token with its signature broken: same header, same payload, same sub, same
        // iat/exp/jti -- only the signature differs. A memo keyed on the subject, on the claims, or
        // on anything short of the whole token would hit CREATOR_A's entry and burn her window.
        // (Minting a second token for her would NOT prove this: a fresh jti gives it a different
        // payload, so even a claim-keyed cache would miss and the test would pass vacuously.)
        String forged = corruptSignature(real);
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", forged));
        assertEquals(200, statusOf("/internal/meera/creator/get_my_deals", forged));
        assertEquals(
                429,
                statusOf("/internal/meera/creator/get_my_deals", forged),
                "the forger burned the IP-keyed fallback window, as an unproven caller should");

        assertEquals(
                200,
                statusOf("/internal/meera/creator/get_my_deals", tokenFor(CREATOR_A)),
                "CREATOR_A had one call left and the forgery must not have spent it");
    }

    @Test
    @DisplayName(
            "finding 1: an absurdly long header is rejected on length before the JWT parser sees it")
    void creatorTool_oversizedHeaderNeverReachesTheParser() throws Exception {
        CountingOnBehalfTokenService counting = countingService();

        assertEquals(
                200,
                statusOf(
                        "/internal/meera/creator/get_my_deals",
                        tokenFor(CREATOR_A) + "x".repeat(8192)));
        assertEquals(0, counting.verifyCalls.get());
    }

    // ---------------------------------------------------------------------------------------
    // [SEC: Kabir Wave 2, finding 6]
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "finding 6: no X-RateLimit-* on /internal/** -- this filter runs ahead of the mesh gate,"
                    + " so those headers would report a named creator's remaining quota to a caller"
                    + " who has not presented a service token")
    void creatorTool_doesNotLeakQuotaHeadersAheadOfTheMeshGate() throws Exception {
        String token = tokenFor(CREATOR_A);

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(
                call("/internal/meera/creator/get_my_deals", token), allowed, new MockFilterChain());
        assertEquals(200, allowed.getStatus());
        assertNull(allowed.getHeader("X-RateLimit-Limit"));
        assertNull(allowed.getHeader("X-RateLimit-Remaining"));

        // ... and not on the 429 either, which is where the remaining count would be most useful
        // to someone probing.
        filter.doFilter(
                call("/internal/meera/creator/get_my_deals", token),
                new MockHttpServletResponse(),
                new MockFilterChain());
        MockHttpServletResponse throttled = new MockHttpServletResponse();
        filter.doFilter(
                call("/internal/meera/creator/get_my_deals", token), throttled, new MockFilterChain());
        assertEquals(429, throttled.getStatus());
        assertNull(throttled.getHeader("X-RateLimit-Limit"));
        assertNull(throttled.getHeader("X-RateLimit-Remaining"));
        assertNotNull(
                throttled.getHeader("Retry-After"),
                "Retry-After stays: it is on a response already refused and the mesh client needs it");
    }

    @Test
    @DisplayName(
            "finding 6: the quota headers are withheld on /internal/** only -- a public throttled"
                    + " route still reports them, since CorsConfig exposes them to the browser")
    void creatorTool_quotaHeadersSurviveOnPublicRoutes() throws Exception {
        setField("sensitiveLimit", 10);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(AI_SERVICE_IP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        assertEquals("9", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("unrelated internal routes are not caught by this bucket")
    void creatorTool_doesNotCatchOtherInternalRoutes() throws Exception {
        String token = tokenFor(CREATOR_A);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, statusOf("/internal/meera/show_creators", token));
        }
        // A nested path is not the creator tool surface either.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, statusOf("/internal/meera/creator/get_my_deals/extra", token));
        }
    }
}
