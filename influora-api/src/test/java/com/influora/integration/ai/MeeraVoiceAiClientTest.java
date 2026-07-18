package com.influora.integration.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyServiceTokenProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.security.SpringJwksKeyService;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import com.influora.testsupport.TestEcKeys;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MeeraVoiceAiClient} (voice proxy, influora-ai {@code POST /voice/speak}).
 * Mocks the underlying {@link HttpClient} (injected via the package-visible constructor) rather
 * than standing up a real server — same "mock the transport" spirit as {@code
 * BrandSafetyAiClientTest}/{@code TrendSparkAiClientTest}.
 */
@ExtendWith(MockitoExtension.class)
class MeeraVoiceAiClientTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<byte[]> httpResponse;

    private BrandSafetyServiceTokenService tokenService;
    private MeeraVoiceAiClient client;

    @BeforeEach
    void setUp() {
        var tokenProps = new BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");

        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-voice-client");
        tokenService = new BrandSafetyServiceTokenService(tokenProps, new SpringJwksKeyService(jwksProps));

        client = new MeeraVoiceAiClient("http://localhost:8000", 10, tokenService, httpClient);
    }

    @Test
    @DisplayName("speak: 200 audio/wav response passes bytes + content type through")
    void testSpeakSuccessPassthrough() throws Exception {
        byte[] audioBytes = "RIFF-fake-wav-bytes".getBytes(StandardCharsets.UTF_8);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(audioBytes);
        when(httpResponse.headers())
                .thenReturn(HttpHeaders.of(Map.of("Content-Type", java.util.List.of("audio/wav")), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertTrue(result.ok());
        assertArrayEquals(audioBytes, result.audioBytes());
        assertEquals("audio/wav", result.contentType());
    }

    @Test
    @DisplayName("speak: sends Authorization Bearer header and workspace_id/text body to the exact path")
    void testSpeakSendsBearerTokenAndBody() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new byte[0]);
        when(httpResponse.headers())
                .thenReturn(HttpHeaders.of(Map.of("Content-Type", java.util.List.of("audio/wav")), (a, b) -> true));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(httpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        client.speak(WORKSPACE_ID, "hello there");

        HttpRequest sentRequest = requestCaptor.getValue();
        java.util.List<String> authHeaders = sentRequest.headers().allValues("Authorization");
        assertEquals(1, authHeaders.size());
        assertTrue(authHeaders.get(0).startsWith("Bearer "));
        assertEquals("http://localhost:8000/voice/speak", sentRequest.uri().toString());
    }

    @Test
    @DisplayName("speak: Python fallback JSON (200, non-audio content type) returns SpeakResult.fallback()")
    void testSpeakFallbackJsonReturnsFallback() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.headers())
                .thenReturn(
                        HttpHeaders.of(
                                Map.of("Content-Type", java.util.List.of("application/json")), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
        assertEquals(null, result.audioBytes());
    }

    @Test
    @DisplayName("speak: non-200 response returns fallback, never throws")
    void testSpeakNon200ReturnsFallback() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
    }

    @Test
    @DisplayName("speak: transport failure (exception from HttpClient.send) returns fallback, never throws")
    void testSpeakTransportFailureReturnsFallback() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
    }

    @Test
    @DisplayName("speak: blank text returns fallback without any HTTP call")
    void testSpeakBlankTextReturnsFallbackWithoutHttpCall() throws Exception {
        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "  ");

        assertFalse(result.ok());
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("speak: missing workspaceId returns fallback without any HTTP call")
    void testSpeakMissingWorkspaceIdReturnsFallbackWithoutHttpCall() throws Exception {
        assertFalse(client.speak(null, "hello").ok());
        assertFalse(client.speak("", "hello").ok());
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
