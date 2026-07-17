package com.influora.integration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.config.MeeraChatAiProperties;
import com.influora.integration.ai.MeeraChatAiClient.ChatTurnResult;
import com.influora.security.SpringJwksKeyService;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import com.influora.testsupport.TestEcKeys;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MeeraChatAiClient} (Wave 3 task A4). Mocks the underlying {@link
 * HttpClient} (injected via the package-visible constructor), same "mock the transport" spirit as
 * {@code BrandSafetyAiClientTest}. {@code /chat} is SSE, not a single JSON body, so {@code
 * httpResponse.body()} here is a {@code Stream<String>} of raw SSE lines rather than a {@code
 * String}.
 */
@ExtendWith(MockitoExtension.class)
class MeeraChatAiClientTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<Stream<String>> httpResponse;

    private MeeraChatAiProperties props;
    private BrandSafetyServiceTokenService tokenService;
    private MeeraChatAiClient client;

    @BeforeEach
    void setUp() {
        props = new MeeraChatAiProperties();
        props.setBaseUrl("http://localhost:8000");

        var tokenProps = new BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");

        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-meera-chat-client");
        tokenService = new BrandSafetyServiceTokenService(tokenProps, new SpringJwksKeyService(jwksProps));

        client = new MeeraChatAiClient(props, tokenService, httpClient);
    }

    private static Stream<String> sseLines(String... lines) {
        return Stream.of(lines);
    }

    @Test
    @DisplayName("sendTurn: concatenates every `token` event's text into the final assistant reply")
    void testSendTurnHappyPathConcatenatesTokens() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(
                        sseLines(
                                "event: prompt_meta",
                                "data: {\"prompt_version\": \"v1\"}",
                                "",
                                "event: token",
                                "data: {\"text\": \"Hello, \"}",
                                "",
                                "event: token",
                                "data: {\"text\": \"here are three creators.\"}",
                                "",
                                "event: done",
                                "data: {\"finish_reason\": \"stop\"}",
                                ""));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ChatTurnResult result = client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID));

        assertEquals("Hello, here are three creators.", result.text());
    }

    @Test
    @DisplayName("sendTurn: sends Authorization Bearer header (service token) to POST {baseUrl}/chat")
    void testSendTurnSendsBearerTokenToChatPath() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(sseLines("event: token", "data: {\"text\": \"hi\"}", "", "event: done", "data: {}", ""));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(httpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID));

        HttpRequest sentRequest = requestCaptor.getValue();
        List<String> authHeaders = sentRequest.headers().allValues("Authorization");
        assertEquals(1, authHeaders.size());
        assertTrue(authHeaders.get(0).startsWith("Bearer "));
        assertEquals("http://localhost:8000/chat", sentRequest.uri().toString());
    }

    @Test
    @DisplayName("sendTurn: an `error` SSE event raises MeeraChatAiException, never a partial reply")
    void testSendTurnErrorEventThrows() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(
                        sseLines(
                                "event: token",
                                "data: {\"text\": \"partial...\"}",
                                "",
                                "event: error",
                                "data: {\"code\": \"provider_timeout\", \"fallback\": \"text\"}",
                                ""));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        assertThrows(
                MeeraChatAiException.class,
                () -> client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID)));
    }

    @Test
    @DisplayName("sendTurn: a stream that ends without a `done` event raises MeeraChatAiException")
    void testSendTurnMissingDoneEventThrows() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(sseLines("event: token", "data: {\"text\": \"hi\"}", ""));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        assertThrows(
                MeeraChatAiException.class,
                () -> client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID)));
    }

    @Test
    @DisplayName("sendTurn: a `done` event with zero token text raises MeeraChatAiException")
    void testSendTurnEmptyTextThrows() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(sseLines("event: done", "data: {\"finish_reason\": \"stop\"}", ""));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        assertThrows(
                MeeraChatAiException.class,
                () -> client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID)));
    }

    @Test
    @DisplayName("sendTurn: non-200 response raises MeeraChatAiException, not a silent fallback")
    void testSendTurnNon200Throws() throws Exception {
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpResponse.body())
                .thenReturn(sseLines("{\"error\": {\"code\": \"ai_spend_ceiling_reached\"}}"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        assertThrows(
                MeeraChatAiException.class,
                () -> client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID)));
    }

    @Test
    @DisplayName("sendTurn: transport failure (exception from HttpClient.send) raises MeeraChatAiException")
    void testSendTurnTransportFailureThrows() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        assertThrows(
                MeeraChatAiException.class,
                () -> client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID)));
    }

    @Test
    @DisplayName("sendTurn: missing workspaceId raises MeeraChatAiException before any HTTP call")
    void testSendTurnMissingWorkspaceIdThrows() throws Exception {
        assertThrows(MeeraChatAiException.class, () -> client.sendTurn("", Map.of()));
        assertThrows(MeeraChatAiException.class, () -> client.sendTurn(null, Map.of()));
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("sendTurn: heartbeat comment lines (`: ping`) are ignored, not treated as an event")
    void testSendTurnIgnoresHeartbeatComments() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(
                        sseLines(
                                "event: token",
                                "data: {\"text\": \"still here\"}",
                                "",
                                ": ping",
                                "",
                                "event: done",
                                "data: {}",
                                ""));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ChatTurnResult result = client.sendTurn(WORKSPACE_ID, Map.of("workspace_id", WORKSPACE_ID));

        assertEquals("still here", result.text());
    }
}
