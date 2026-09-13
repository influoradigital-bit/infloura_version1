package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.influora.config.MetaApiProperties;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * F-0813 — the {@code redirect_uri} must reach Meta encoded exactly ONCE.
 *
 * <p><b>Why this class exists rather than another test in {@link MetaOAuthServiceTest}.</b> That
 * suite mocks {@code RestClient} with Mockito — {@code .uri(...)} is a stub, so the encoding this
 * bug lives in never runs. It has covered {@code exchangeCodeForToken} since the flow was written
 * and could not have caught this, because the layer it stubs IS the defect. These tests bind a
 * REAL {@code RestClient} to {@link MockRestServiceServer} so the URI is genuinely built and the
 * assertion sees what would go on the wire.
 *
 * <p><b>The defect.</b> Callers percent-encode query values with {@code urlEncode} and hand
 * {@code fetchToken} a finished URL. {@code RestClient.uri(String)} treats that as a URI TEMPLATE
 * and encodes it AGAIN, so {@code https%3A%2F%2F…} left as {@code https%253A%252F%252F…}. Meta
 * decodes once, gets {@code https%3A%2F%2F…}, and rejects it:
 *
 * <pre>
 *   {"error":{"message":"redirect_uri isn't an absolute URI. Check RFC 3986.","code":191}}
 * </pre>
 *
 * <p><b>Confirmed against Meta itself</b> on 2026-09-13, real app credentials, dummy code, the two
 * encodings the only difference:
 *
 * <pre>
 *   single-encoded -> code=100 "Invalid verification code format."   (reached the code check)
 *   double-encoded -> code=191 "redirect_uri isn't an absolute URI." (production's exact error)
 * </pre>
 *
 * <p>This broke ONLY the exchange, never the dialog: {@code buildAuthorizationUrl} returns its URL
 * to the browser as a string and never passes through {@code RestClient}. That asymmetry is why
 * the Meta dialog always opened and the connect never once completed in production.
 */
class MetaOAuthServiceUriEncodingTest {

    private static final String APP_ID = "test-app-id";
    private static final String APP_SECRET = "test-app-secret";
    private static final String GRAPH_API_VERSION = "v25.0";
    private static final String WEB_BASE_URL = "https://influora.in";
    private static final String REDIRECT_URI = "https://influora.in/creator/settings/meta/callback";

    /** What a correctly single-encoded redirect_uri looks like on the wire. */
    private static final String SINGLE_ENCODED =
            "https%3A%2F%2Finfluora.in%2Fcreator%2Fsettings%2Fmeta%2Fcallback";

    /** What the bug put on the wire, and what Meta answers 191 to. */
    private static final String DOUBLE_ENCODED =
            "https%253A%252F%252Finfluora.in%252Fcreator%252Fsettings%252Fmeta%252Fcallback";

    private static final String TOKEN_JSON =
            "{\"access_token\":\"tok_abc\",\"token_type\":\"bearer\",\"expires_in\":5183944}";

    private MetaOAuthService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new MetaOAuthService(testProperties(), WEB_BASE_URL, builder.build());
    }

    private static MetaApiProperties testProperties() {
        MetaApiProperties props = new MetaApiProperties();
        props.setAppId(APP_ID);
        props.setAppSecret(APP_SECRET);
        props.setRedirectUri(REDIRECT_URI);
        props.setGraphApiVersion(GRAPH_API_VERSION);
        return props;
    }

    @Test
    @DisplayName(
            "F-0813: the code exchange sends redirect_uri encoded ONCE — a second pass is what Meta"
                    + " rejects with 191")
    void codeExchangeSendsSingleEncodedRedirectUri() {
        String expected =
                "https://graph.facebook.com/v25.0/oauth/access_token"
                        + "?client_id=test-app-id"
                        + "&client_secret=test-app-secret"
                        + "&redirect_uri=" + SINGLE_ENCODED
                        + "&code=CODE123";

        server.expect(requestTo(expected))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        service.exchangeCodeForToken("CODE123");

        // Fails loudly if the URI that went out differs by even one character — which is exactly
        // what double-encoding does.
        server.verify();
    }

    @Test
    @DisplayName("F-0813: the URI that goes out is not the double-encoded form")
    void codeExchangeDoesNotDoubleEncode() {
        // Asserted independently of the exact-match test above so a future change to the parameter
        // ORDER (which would break requestTo) cannot quietly take this guarantee with it.
        final String[] seen = new String[1];
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        s.expect(
                        request -> {
                            seen[0] = request.getURI().toString();
                            return;
                        })
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        MetaOAuthService svc = new MetaOAuthService(testProperties(), WEB_BASE_URL, builder.build());

        svc.exchangeCodeForToken("CODE123");

        assertFalse(
                seen[0].contains(DOUBLE_ENCODED),
                "redirect_uri was encoded twice — Meta answers 191 'redirect_uri isn't an absolute"
                        + " URI' to exactly this: " + seen[0]);
        assertTrue(
                seen[0].contains(SINGLE_ENCODED),
                "redirect_uri should appear encoded exactly once: " + seen[0]);
    }

    @Test
    @DisplayName(
            "F-0813: a URI built the way fetchToken does survives RestClient untouched (the"
                    + " premise of the fix)")
    void uriObjectIsNotReEncoded() {
        // Pins WHY URI.create is used rather than uri(String): the same pre-encoded string handed
        // over as a java.net.URI must arrive unchanged. If a future Spring version started
        // re-encoding a URI object, this reds before anyone learns it from Meta.
        String preEncoded =
                "https://graph.facebook.com/v25.0/oauth/access_token?redirect_uri=" + SINGLE_ENCODED;

        assertEquals(
                preEncoded,
                URI.create(preEncoded).toString(),
                "URI.create must preserve the single encoding it was given");
    }

    @Test
    @DisplayName("F-0813: the long-lived exchange goes through the same fixed path")
    void longLivedExchangeAlsoSingleEncoded() {
        // No redirect_uri here, so this one survived the bug by luck — its parameters are token
        // strings that are almost always [A-Za-z0-9_-], where a second encoding pass is a no-op.
        // A token containing '+' or '/' would have broken identically, so the fix is verified on
        // this caller too rather than assumed to be unnecessary.
        String expected =
                "https://graph.facebook.com/v25.0/oauth/access_token"
                        + "?grant_type=fb_exchange_token"
                        + "&client_id=test-app-id"
                        + "&client_secret=test-app-secret"
                        + "&fb_exchange_token=short%2Blived%2Ftoken";

        server.expect(requestTo(expected))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        service.exchangeForLongLivedToken("short+lived/token");

        server.verify();
    }
}
