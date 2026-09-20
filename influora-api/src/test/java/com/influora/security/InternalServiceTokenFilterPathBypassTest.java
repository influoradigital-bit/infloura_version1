package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.influora.config.InternalServiceTokenProperties;
import com.influora.service.AuditLogService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * EV-004 (P0): the {@code /internal/**} service-token gate must hold for every spelling of the
 * path that Spring MVC would still dispatch to {@link com.influora.web.MeeraInternalController}.
 *
 * <p>Before the fix, {@link InternalServiceTokenFilter} matched {@code getRequestURI()} — the raw,
 * undecoded request line — against the literal {@code /internal/}. {@code
 * /api/v1/%69nternal/meera/turns/release} failed that match, so the filter no-oped, the request
 * fell through to {@code anyRequest().authenticated()} (satisfied by any user JWT), and
 * {@link #mvcStillRoutesTheEncodedSpelling} proves MVC dispatches it to the real refund route.
 *
 * <p>Every variant is driven through the REAL filter with REAL {@link InternalRequestVerifier}
 * and {@link NonceCache}; only the audit sink is mocked. The contract: no service token means
 * 401 and the chain never runs; a normal (non-internal) route is untouched.
 */
class InternalServiceTokenFilterPathBypassTest {

    private static final String SIGNING_SECRET = "test-service-token-secret-at-least-32-bytes!!";
    private static final String HMAC_SECRET = "test-hmac-secret-at-least-32-bytes-long!!!!";
    private static final String CTX = "/api/v1";

    private InternalServiceTokenFilter filter;

    @BeforeEach
    void setUp() {
        InternalServiceTokenProperties props = new InternalServiceTokenProperties();
        props.setSigningSecret(SIGNING_SECRET);
        props.setHmacSigningSecret(HMAC_SECRET);
        props.setClockSkewSeconds(60);
        filter =
                new InternalServiceTokenFilter(
                        props, new InternalRequestVerifier(props, new NonceCache()), mock(AuditLogService.class));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Tomcat-shaped request: context path reported separately, request URI is the raw line. */
    private static MockHttpServletRequest tomcatStyle(String method, String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, rawUri);
        request.setContextPath(CTX);
        // A user JWT rides along: the attack is "any logged-in user", so prove it is irrelevant.
        request.addHeader("Authorization", "Bearer user.jwt.here");
        return request;
    }

    private record Outcome(int status, boolean chainRan) {}

    private Outcome run(HttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response.getStatus(), chain.getRequest() != null);
    }

    @ParameterizedTest(name = "[{index}] {0} requires the service token")
    @ValueSource(
            strings = {
                "/api/v1/internal/meera/turns/release",
                "/api/v1/%69nternal/meera/turns/release",
                "/api/v1/%49NTERNAL/meera/turns/release",
                "/api/v1/%2569nternal/meera/turns/release",
                "/api/v1/%252569nternal/meera/turns/release",
                "/api/v1/%2Finternal/meera/turns/release",
                "/api/v1/%2finternal/meera/turns/release",
                "/api/v1//internal/meera/turns/release",
                "/api/v1///internal//meera/turns/release",
                "/api/v1/./internal/meera/turns/release",
                "/api/v1/%2E/internal/meera/turns/release",
                "/api/v1/x/../internal/meera/turns/release",
                "/api/v1/internal;param/meera/turns/release",
                "/api/v1/internal;jsessionid=abc/meera/turns/release",
                "/api/v1/internal%3Bx/meera/turns/release",
                "/api/v1/INTERNAL/meera/turns/release",
                "/api/v1/Internal/meera/turns/release",
                "/api/v1/iNtErNaL/meera/turns/release",
                "/api/v1/internal/meera/turns/release/",
                "/api/v1/internal/",
                "/api/v1/internal",
                "/api/v1/%5Cinternal/meera/turns/release",
                "/api/%761/internal/meera/turns/release",
                "/api/v1/%zzinternal/meera/turns/release",
                "/api/v1/%69nternal/meera/%74urns/release",
            })
    void everySpellingOfInternalRequiresTheServiceToken(String rawUri) throws Exception {
        Outcome outcome = run(tomcatStyle("POST", rawUri));
        assertFalse(outcome.chainRan(), rawUri + " reached the controller with no service token");
        assertEquals(401, outcome.status(), rawUri);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest(name = "[{index}] mock-style (empty context path) {0} requires the service token")
    @ValueSource(
            strings = {
                "/api/v1/%69nternal/meera/turns/release",
                "/%69nternal/meera/turns/release",
                "/internal/meera/turns/release",
                "//internal/meera/turns/release",
            })
    void emptyContextPathSpellingsAlsoGated(String rawUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
        Outcome outcome = run(request);
        assertFalse(outcome.chainRan(), rawUri);
        assertEquals(401, outcome.status(), rawUri);
    }

    @ParameterizedTest(name = "[{index}] {0} is a normal route and passes straight through")
    @ValueSource(
            strings = {
                "/api/v1/meera/sessions/s1/messages",
                "/api/v1/creator/meera/sessions/s1/messages",
                "/api/v1/internals/x",
                "/api/v1/portfolio/internal",
                "/api/v1/wallet/withdraw",
                "/api/v1/health",
                "/api/v1/",
            })
    void normalRoutesAreUnaffected(String rawUri) throws Exception {
        Outcome outcome = run(tomcatStyle("GET", rawUri));
        assertTrue(outcome.chainRan(), rawUri + " was blocked by the internal gate");
        assertEquals(200, outcome.status(), rawUri);
    }

    @Test
    @DisplayName("A correctly signed Meera call on the canonical path still passes, and the HMAC covers the app path")
    void legitimateSignedCallStillPasses() throws Exception {
        String appPath = "/internal/meera/turns/release";
        String body = "{\"turn_id\":\"t-1\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CTX + appPath);
        request.setContextPath(CTX);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        signInto(request, "POST", appPath, body);

        Outcome outcome = run(request);
        assertTrue(outcome.chainRan(), "legit signed internal call was rejected: " + outcome.status());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(
                SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                        instanceof InternalPrincipal);
    }

    @Test
    @DisplayName("Proof of reachability: Spring MVC path matching dispatches the %69nternal spelling to the refund route")
    void mvcStillRoutesTheEncodedSpelling() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/%69nternal/meera/turns/release");
        request.setContextPath(CTX);
        var requestPath = ServletRequestPathUtils.parseAndCache(request);
        var pattern = PathPatternParser.defaultInstance.parse("/internal/meera/turns/release");
        assertTrue(
                pattern.matches(requestPath.pathWithinApplication()),
                "MVC would NOT route the encoded spelling — the bypass premise is wrong");

        MockHttpServletRequest matrix =
                new MockHttpServletRequest("POST", "/api/v1/internal;x=1/meera/turns/release");
        matrix.setContextPath(CTX);
        assertTrue(
                pattern.matches(ServletRequestPathUtils.parseAndCache(matrix).pathWithinApplication()));
    }

    /**
     * Answers "does StrictHttpFirewall already reject some variants?" with the real default
     * firewall Spring Security puts in front of this filter (no HttpFirewall bean overrides it in
     * this module). The %69 spelling, case variants and a trailing slash are NOT rejected — the
     * firewall is not a fix for EV-004, only a partial backstop.
     */
    @ParameterizedTest(name = "[{index}] StrictHttpFirewall rejects {0}")
    @ValueSource(
            strings = {
                "/api/v1/%2569nternal/meera/turns/release",
                "/api/v1/%2Finternal/meera/turns/release",
                "/api/v1//internal/meera/turns/release",
                "/api/v1/./internal/meera/turns/release",
                "/api/v1/x/../internal/meera/turns/release",
                "/api/v1/internal;param/meera/turns/release",
                "/api/v1/%5Cinternal/meera/turns/release",
                "/api/v1/%2E/internal/meera/turns/release",
            })
    void firewallRejects(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
        request.setContextPath(CTX);
        boolean rejected;
        try {
            new StrictHttpFirewall().getFirewalledRequest(request);
            rejected = false;
        } catch (RequestRejectedException e) {
            rejected = true;
        }
        assertTrue(rejected, rawUri + " passed the default StrictHttpFirewall");
    }

    @ParameterizedTest(name = "[{index}] StrictHttpFirewall lets {0} through (the filter must catch it)")
    @ValueSource(
            strings = {
                "/api/v1/%69nternal/meera/turns/release",
                "/api/v1/INTERNAL/meera/turns/release",
                "/api/v1/internal/meera/turns/release/",
            })
    void firewallAllows(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
        request.setContextPath(CTX);
        new StrictHttpFirewall().getFirewalledRequest(request);
    }

    // ---- signing helpers (mirror influora-ai app/clients/spring.py + service_token_minter.py) --

    private static void signInto(MockHttpServletRequest request, String method, String path, String body)
            throws Exception {
        Instant now = Instant.now();
        String token =
                Jwts.builder()
                        .issuer(InternalServiceTokenFilter.SERVICE_TOKEN_ISSUER)
                        .audience()
                        .add(InternalServiceTokenFilter.SERVICE_TOKEN_AUDIENCE)
                        .and()
                        .subject("meera-ai-service")
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plusSeconds(30)))
                        .signWith(Keys.hmacShaKeyFor(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .compact();
        String timestamp = Long.toString(now.getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String bodyHash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
                HexFormat.of()
                        .formatHex(
                                mac.doFinal(
                                        (method + path + bodyHash + timestamp + nonce)
                                                .getBytes(StandardCharsets.UTF_8)));
        request.addHeader("X-Meera-Service-Token", token);
        request.addHeader("X-Meera-Timestamp", timestamp);
        request.addHeader("X-Meera-Nonce", nonce);
        request.addHeader("X-Meera-Signature", signature);
    }
}
