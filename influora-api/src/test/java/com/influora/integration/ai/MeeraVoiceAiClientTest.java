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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MeeraVoiceAiClient} (voice proxy, influora-ai {@code POST /voice/speak}).
 * Mocks the underlying {@link CloseableHttpClient} (injected via the package-visible constructor)
 * rather than standing up a real server — same "mock the transport" spirit as {@code
 * BrandSafetyAiClientTest}/{@code TrendSparkAiClientTest}. Responses are real {@link
 * BasicClassicHttpResponse} instances (not mocks) so the client's own {@code
 * HttpClientResponseHandler} runs unmodified against them, exactly as it would against a real
 * Apache HttpClient5 response.
 */
@ExtendWith(MockitoExtension.class)
class MeeraVoiceAiClientTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private CloseableHttpClient httpClient;

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

    private static ClassicHttpResponse fakeResponse(int status, byte[] body, String contentType) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
        if (body != null) {
            response.setEntity(
                    new ByteArrayEntity(body, contentType == null ? null : ContentType.parse(contentType)));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private void mockExecuteReturning(ClassicHttpResponse response) throws Exception {
        when(httpClient.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(
                        invocation -> {
                            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
                            return handler.handleResponse(response);
                        });
    }

    @Test
    @DisplayName("speak: 200 audio/wav response passes bytes + content type through")
    void testSpeakSuccessPassthrough() throws Exception {
        byte[] audioBytes = "RIFF-fake-wav-bytes".getBytes(StandardCharsets.UTF_8);
        mockExecuteReturning(fakeResponse(200, audioBytes, "audio/wav"));

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertTrue(result.ok());
        assertArrayEquals(audioBytes, result.audioBytes());
        assertEquals("audio/wav", result.contentType());
    }

    @Test
    @DisplayName("speak: sends Authorization Bearer header and workspace_id/text body to the exact path")
    void testSpeakSendsBearerTokenAndBody() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);

        client.speak(WORKSPACE_ID, "hello there");

        verify(httpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        HttpPost sentRequest = requestCaptor.getValue();
        String authHeader = sentRequest.getFirstHeader("Authorization").getValue();
        assertTrue(authHeader.startsWith("Bearer "));
        assertEquals("http://localhost:8000/voice/speak", sentRequest.getRequestUri());
    }

    @Test
    @DisplayName("speak: Python fallback JSON (200, non-audio content type) returns SpeakResult.fallback()")
    void testSpeakFallbackJsonReturnsFallback() throws Exception {
        mockExecuteReturning(fakeResponse(200, "{\"fallback\":true}".getBytes(StandardCharsets.UTF_8), "application/json"));

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
        assertEquals(null, result.audioBytes());
    }

    @Test
    @DisplayName("speak: non-200 response returns fallback, never throws")
    void testSpeakNon200ReturnsFallback() throws Exception {
        mockExecuteReturning(fakeResponse(500, null, null));

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
    }

    @Test
    @DisplayName("speak: transport failure (exception from HttpClient.execute) returns fallback, never throws")
    void testSpeakTransportFailureReturnsFallback() throws Exception {
        when(httpClient.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenThrow(new IOException("connection refused"));

        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "hello there");

        assertFalse(result.ok());
    }

    @Test
    @DisplayName("speak: blank text returns fallback without any HTTP call")
    void testSpeakBlankTextReturnsFallbackWithoutHttpCall() throws Exception {
        MeeraVoiceAiClient.SpeakResult result = client.speak(WORKSPACE_ID, "  ");

        assertFalse(result.ok());
        verify(httpClient, never()).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }

    @Test
    @DisplayName("speak: missing workspaceId returns fallback without any HTTP call")
    void testSpeakMissingWorkspaceIdReturnsFallbackWithoutHttpCall() throws Exception {
        assertFalse(client.speak(null, "hello").ok());
        assertFalse(client.speak("", "hello").ok());
        verify(httpClient, never()).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
    }
}
