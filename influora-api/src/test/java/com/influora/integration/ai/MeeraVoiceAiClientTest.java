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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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
    private SpringJwksKeyService jwksKeyService;
    private MeeraVoiceAiClient client;

    @BeforeEach
    void setUp() {
        var tokenProps = new BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");

        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-voice-client");
        jwksKeyService = new SpringJwksKeyService(jwksProps);
        tokenService = new BrandSafetyServiceTokenService(tokenProps, jwksKeyService);

        client = new MeeraVoiceAiClient("http://localhost:8000", 10, tokenService, httpClient);
    }

    /** Real ES256 verification of the Bearer token a captured {@link HttpPost} carries. */
    private Claims verifiedClaimsOf(HttpPost request) {
        String bearer = request.getFirstHeader("Authorization").getValue().substring("Bearer ".length());
        return Jwts.parser().verifyWith(jwksKeyService.publicKey()).build().parseSignedClaims(bearer).getPayload();
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
        // getRequestUri() is the HTTP request-target — the path only ("/voice/speak"). Asserting
        // the absolute URL against it could never pass; getUri() is the accessor that carries
        // scheme+host, and checking it also pins the configured base-url, not just the path.
        assertEquals("http://localhost:8000/voice/speak", sentRequest.getUri().toString());
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

    @Test
    @DisplayName("C4 regression: speak(workspaceId, text, lang) includes \"lang\" in the request body sent to influora-ai")
    void testSpeakWithLangIncludesLangInRequestBody() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);

        client.speak(WORKSPACE_ID, "namaste", "hi-IN");

        verify(httpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(
                        requestCaptor.getValue().getEntity().getContent().readAllBytes(),
                        StandardCharsets.UTF_8);
        assertTrue(sentBody.contains("\"lang\":\"hi-IN\""));
        assertTrue(sentBody.contains("\"text\":\"namaste\""));
    }

    @Test
    @DisplayName(
            "C4 regression: speak(workspaceId, text) (no lang) omits \"lang\" from the request body"
                    + " entirely — never sends a JSON null, which would defeat voice.py's"
                    + " body.get(\"lang\", \"en-IN\") default")
    void testSpeakWithoutLangOmitsLangFromRequestBody() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));

        ArgumentCaptor<HttpPost> requestCaptor = ArgumentCaptor.forClass(HttpPost.class);

        client.speak(WORKSPACE_ID, "hello there");

        verify(httpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(
                        requestCaptor.getValue().getEntity().getContent().readAllBytes(),
                        StandardCharsets.UTF_8);
        assertFalse(sentBody.contains("lang"));
    }

    // ------------------------------------------------------------------------------------------
    // F-audit-A1 -- speakForCreator: mints WITH userType=CREATOR and forwards a real on-behalf JWT.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("speak: the ordinary (BRAND) overload's minted token carries NO userType claim")
    void testSpeakTokenCarriesNoUserTypeClaim() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.speak(WORKSPACE_ID, "hello there");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        assertEquals(null, verifiedClaimsOf(captor.getValue()).get("userType"));
    }

    @Test
    @DisplayName("speakForCreator: mints a token with userType=CREATOR, verified for real (ES256)")
    void testSpeakForCreatorTokenCarriesCreatorUserType() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.speakForCreator(WORKSPACE_ID, "hello there", null, "real-onbehalf-jwt");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        Claims claims = verifiedClaimsOf(captor.getValue());
        assertEquals("CREATOR", claims.get("userType"));
        assertEquals(WORKSPACE_ID, claims.get("workspace_id"));
        assertEquals("service", claims.get("scope"));
    }

    @Test
    @DisplayName("speakForCreator: forwards the given onBehalfJwt as \"onbehalf_jwt\" in the JSON body")
    void testSpeakForCreatorIncludesOnBehalfJwtInBody() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.speakForCreator(WORKSPACE_ID, "hello there", null, "real-onbehalf-jwt-value");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(captor.getValue().getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(sentBody.contains("\"onbehalf_jwt\":\"real-onbehalf-jwt-value\""), sentBody);
    }

    @Test
    @DisplayName(
            "speak (the plain BRAND overload) NEVER sends an onbehalf_jwt field -- influora-ai's"
                    + " own bearer fallback must apply exactly as it always has for this caller")
    void testPlainSpeakNeverIncludesOnBehalfJwtInBody() throws Exception {
        mockExecuteReturning(fakeResponse(200, new byte[0], "audio/wav"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.speak(WORKSPACE_ID, "hello there", "hi-IN");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(captor.getValue().getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(sentBody.contains("onbehalf_jwt"), sentBody);
    }

    // ------------------------------------------------------------------------------------------
    // F-audit-A1 -- transcribeForCreator: same two properties, for the multipart voice-INPUT leg.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("transcribeForCreator: mints a token with userType=CREATOR, verified for real (ES256)")
    void testTranscribeForCreatorTokenCarriesCreatorUserType() throws Exception {
        mockExecuteReturning(fakeResponse(200, "{}".getBytes(StandardCharsets.UTF_8), "application/json"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.transcribeForCreator(WORKSPACE_ID, new byte[] {1, 2, 3}, "audio/webm", "real-onbehalf-jwt");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        assertEquals("CREATOR", verifiedClaimsOf(captor.getValue()).get("userType"));
    }

    @Test
    @DisplayName("transcribeForCreator: the multipart body carries an onbehalf_jwt field with the given value")
    void testTranscribeForCreatorIncludesOnBehalfJwtField() throws Exception {
        mockExecuteReturning(fakeResponse(200, "{}".getBytes(StandardCharsets.UTF_8), "application/json"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.transcribeForCreator(WORKSPACE_ID, new byte[] {1, 2, 3}, "audio/webm", "real-onbehalf-jwt-value");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(captor.getValue().getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(sentBody.contains("name=\"onbehalf_jwt\""), sentBody);
        assertTrue(sentBody.contains("real-onbehalf-jwt-value"), sentBody);
    }

    @Test
    @DisplayName("transcribe (the plain BRAND overload) never includes an onbehalf_jwt multipart field")
    void testPlainTranscribeNeverIncludesOnBehalfJwtField() throws Exception {
        mockExecuteReturning(fakeResponse(200, "{}".getBytes(StandardCharsets.UTF_8), "application/json"));
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);

        client.transcribe(WORKSPACE_ID, new byte[] {1, 2, 3}, "audio/webm");

        verify(httpClient).execute(captor.capture(), any(HttpClientResponseHandler.class));
        String sentBody =
                new String(captor.getValue().getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(sentBody.contains("onbehalf_jwt"), sentBody);
        assertEquals(null, verifiedClaimsOf(captor.getValue()).get("userType"));
    }
}
