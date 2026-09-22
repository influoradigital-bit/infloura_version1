package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.exception.MetaApiException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Production 2026-09-22 17:53: graph.facebook.com took over 10s on a creator's code exchange.
 * {@code ResourceAccessException: ... Read timed out} escaped the service unhandled, and the
 * callback page showed the generic "An unexpected error occurred" (HTTP 500).
 *
 * <p>{@code withException(SocketTimeoutException)} makes the real {@code RestClient} raise the same
 * {@code ResourceAccessException} the production stack trace shows.
 */
class MetaOAuthServiceTimeoutTest {

    private MetaOAuthService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        MetaApiProperties props = new MetaApiProperties();
        props.setAppId("test-app-id");
        props.setAppSecret("test-app-secret");
        props.setInstagramAppId("test-ig-app-id");
        props.setInstagramAppSecret("test-ig-app-secret");
        props.setRedirectUri("https://influora.in/creator/settings/meta/callback");
        props.setGraphApiVersion("v25.0");
        service = new MetaOAuthService(props, "https://influora.in", builder.build());
    }

    private static void assertUnavailable(MetaApiException e) {
        assertEquals("META_UNAVAILABLE", e.getCode());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
        assertTrue(
                e.getMessage().contains("took too long") && e.getMessage().contains("start the connection again"),
                "the callback page shows this message verbatim, so it must tell the creator what to do: "
                        + e.getMessage());
        assertTrue(e.getCause() instanceof org.springframework.web.client.ResourceAccessException);
    }

    @Test
    @DisplayName("Facebook-login code exchange timing out answers META_UNAVAILABLE 504, not a 500")
    void facebookCodeExchangeTimeout() {
        server.expect(request -> {}).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(assertThrows(MetaApiException.class, () -> service.exchangeCodeForToken("CODE")));
    }

    @Test
    @DisplayName("Facebook long-lived exchange timing out answers META_UNAVAILABLE 504")
    void facebookLongLivedExchangeTimeout() {
        server.expect(request -> {}).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(
                assertThrows(MetaApiException.class, () -> service.exchangeForLongLivedToken("SHORT")));
    }

    @Test
    @DisplayName("Instagram-login code exchange timing out answers META_UNAVAILABLE 504")
    void instagramCodeExchangeTimeout() {
        server.expect(request -> {}).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(
                assertThrows(MetaApiException.class, () -> service.exchangeInstagramCodeForToken("CODE")));
    }

    @Test
    @DisplayName("Instagram long-lived exchange timing out answers META_UNAVAILABLE 504")
    void instagramLongLivedExchangeTimeout() {
        server.expect(request -> {}).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(
                assertThrows(
                        MetaApiException.class, () -> service.exchangeInstagramForLongLivedToken("IGAA_SHORT")));
    }

    @Test
    @DisplayName("the token exchanges wait longer than the 10s outbound default Meta exceeded")
    void oauthReadTimeoutIsLongerThanTheDefault() {
        assertTrue(MetaOAuthService.OAUTH_READ_TIMEOUT.compareTo(Duration.ofSeconds(20)) >= 0);
    }
}
