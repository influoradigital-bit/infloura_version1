package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.dto.InstagramShortLivedTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * F-0819 — the diagnostics that decide why the Instagram long-lived exchange is refused.
 *
 * <p>Uses a REAL {@code RestClient} bound to {@link MockRestServiceServer}, not a Mockito mock of
 * it: the failure being diagnosed lives in what actually goes on the wire, and a stubbed client
 * would stub that away (reference_mock_stubs_the_layer_the_bug_lives_in).
 */
class MetaOAuthServiceInstagramDiagnosticsTest {

    private static final String SHORT_TOKEN = "IGAAQ1shortlivedtokenvalue0123456789";
    private static final String IG_SECRET = "instagram-secret-value-0123456789";
    private static final String FB_SECRET = "facebook-secret-value-0123456789";

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(MetaOAuthService.class);
    private final Logger dtoLogger =
            (Logger) LoggerFactory.getLogger(InstagramShortLivedTokenResponse.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private MockRestServiceServer server;
    private MetaOAuthService service;

    @BeforeEach
    void setUp() {
        logs.start();
        serviceLogger.addAppender(logs);
        dtoLogger.addAppender(logs);
        dtoLogger.setLevel(Level.INFO);

        MetaApiProperties props = new MetaApiProperties();
        props.setAppId("fb-app-id");
        props.setAppSecret(FB_SECRET);
        props.setInstagramAppId("ig-app-id");
        props.setInstagramAppSecret(IG_SECRET);
        props.setRedirectUri("https://influora.in/creator/settings/meta/callback");
        props.setGraphApiVersion("v25.0");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new MetaOAuthService(props, "https://influora.in", builder.build());
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        dtoLogger.detachAppender(logs);
    }

    private String allLogText() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName("F-0819: a refused long-lived exchange logs fingerprints and still throws")
    void refusedExchangeLogsDiagnosticsAndRethrows() {
        server.expect(method(HttpMethod.GET))
                .andRespond(
                        withBadRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"error\":{\"message\":\"Unsupported request - method type:"
                                                + " get\",\"type\":\"IGApiException\",\"code\":100}}"));

        assertThrows(
                MetaApiException.class, () -> service.exchangeInstagramForLongLivedToken(SHORT_TOKEN));

        List<ILoggingEvent> diagnostics =
                logs.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .filter(e -> e.getFormattedMessage().startsWith("F-0819"))
                        .toList();
        assertEquals(1, diagnostics.size(), "exactly one diagnostics line per refused exchange");
        String line = diagnostics.get(0).getFormattedMessage();
        assertTrue(line.contains("shortLivedToken=IGAA...(len=" + SHORT_TOKEN.length() + ")"), line);
        assertTrue(line.contains("instagramAppId=ig-app-id"), line);
        assertTrue(line.contains("instagramSecretEqualsFacebookSecret=false"), line);
    }

    @Test
    @DisplayName("F-0819: no log line anywhere contains the token or either secret")
    void neverLogsCredentials() {
        server.expect(method(HttpMethod.GET)).andRespond(withBadRequest());

        assertThrows(
                MetaApiException.class, () -> service.exchangeInstagramForLongLivedToken(SHORT_TOKEN));

        String text = allLogText();
        assertFalse(text.contains(SHORT_TOKEN), "token leaked into logs");
        assertFalse(text.contains(IG_SECRET), "instagram secret leaked into logs");
        assertFalse(text.contains(FB_SECRET), "facebook secret leaked into logs");
    }

    @Test
    @DisplayName("F-0819: a successful exchange logs nothing extra")
    void successLogsNoDiagnostics() {
        server.expect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"IGAAlonglivedtoken0123456789\",\"token_type\":\"bearer\","
                                        + "\"expires_in\":5183944}",
                                MediaType.APPLICATION_JSON));

        service.exchangeInstagramForLongLivedToken(SHORT_TOKEN);

        assertTrue(
                logs.list.stream().noneMatch(e -> e.getFormattedMessage().startsWith("F-0819")),
                allLogText());
    }

    @Test
    @DisplayName("F-0819: the code-exchange body's shape is logged as names, never values")
    void codeExchangeShapeIsLoggedWithoutValues() throws Exception {
        String body =
                "{\"data\":[{\"access_token\":\"" + SHORT_TOKEN + "\",\"user_id\":17841400000000001,"
                        + "\"permissions\":\"instagram_business_basic\"}]}";

        new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, InstagramShortLivedTokenResponse.class);

        String line =
                logs.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.startsWith("F-0819 instagram code-exchange response"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no shape line: " + allLogText()));
        assertTrue(line.contains("shape=data-wrapped"), line);
        assertTrue(line.contains("topLevelKeys=[data]"), line);
        assertTrue(line.contains("bodyKeys=[access_token, user_id, permissions]"), line);
        assertFalse(line.contains(SHORT_TOKEN), "token leaked: " + line);
        assertFalse(line.contains("17841400000000001"), "user id value leaked: " + line);
    }
}
