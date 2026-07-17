package com.influora.integration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyAiProperties;
import com.influora.config.JwksSigningKeyProperties;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.security.SpringJwksKeyService;
import com.influora.service.integration.BrandSafetyServiceTokenService;
import com.influora.testsupport.TestEcKeys;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for BrandSafetyAiClient (Wave C task C3). Mocks the underlying {@link HttpClient}
 * (injected via the package-visible constructor) rather than standing up a real server — same
 * "mock the transport" spirit as {@code InstagramInsightsClientTest} mocking {@code
 * MetaGraphApiClient}.
 */
@ExtendWith(MockitoExtension.class)
class BrandSafetyAiClientTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private BrandSafetyAiProperties props;
    private BrandSafetyServiceTokenService tokenService;
    private BrandSafetyAiClient client;

    @BeforeEach
    void setUp() {
        props = new BrandSafetyAiProperties();
        props.setBaseUrl("http://localhost:8000");
        props.setMaxItemsPerCall(25);

        var tokenProps = new com.influora.config.BrandSafetyServiceTokenProperties();
        tokenProps.setSigningSecret("test-signing-secret-at-least-32-bytes-long!!");

        var jwksProps = new JwksSigningKeyProperties();
        jwksProps.setPrivateKeyPem(TestEcKeys.PRIVATE_KEY_PEM);
        jwksProps.setPublicKeyPem(TestEcKeys.PUBLIC_KEY_PEM);
        jwksProps.setKid("test-kid-brand-safety-client");
        tokenService = new BrandSafetyServiceTokenService(tokenProps, new SpringJwksKeyService(jwksProps));

        client = new BrandSafetyAiClient(props, tokenService, httpClient);
    }

    @Test
    @DisplayName("classify: happy path returns classified items in order")
    void testClassifyHappyPath() throws Exception {
        String responseBody =
                """
                {
                  "success": true,
                  "data": {
                    "items": [
                      {
                        "content_id": "m1",
                        "garm_flags": [{"category": "spam_or_harmful_content", "risk": "floor", "rationale": "clean"}],
                        "content_sentiment": "positive",
                        "sentiment_score": 0.8,
                        "brand_safety_score": 95.0,
                        "overall_rationale": "no concerns"
                      }
                    ]
                  }
                }
                """;
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        List<ContentItem> items = List.of(new ContentItem("m1", "hello world", "IMAGE", null));
        List<ClassifiedItem> result = client.classify(WORKSPACE_ID, items);

        assertEquals(1, result.size());
        assertEquals("m1", result.get(0).contentId());
        assertEquals("positive", result.get(0).contentSentiment());
        assertEquals(95.0, result.get(0).brandSafetyScore());
    }

    @Test
    @DisplayName("classify: sends Authorization Bearer header with a minted token")
    void testClassifySendsBearerToken() throws Exception {
        String responseBody =
                """
                {"success": true, "data": {"items": [
                  {"content_id":"m1","garm_flags":[],"content_sentiment":"neutral","sentiment_score":0.0,"brand_safety_score":100.0,"overall_rationale":""}
                ]}}
                """;
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseBody);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(httpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        client.classify(WORKSPACE_ID, List.of(new ContentItem("m1", "caption", null, null)));

        HttpRequest sentRequest = requestCaptor.getValue();
        List<String> authHeaders = sentRequest.headers().allValues("Authorization");
        assertEquals(1, authHeaders.size());
        assertTrue(authHeaders.get(0).startsWith("Bearer "));
        assertEquals("http://localhost:8000/internal/brand-safety", sentRequest.uri().toString());
    }

    @Test
    @DisplayName("classify: non-200 response raises BrandSafetyAiException, not a silent fallback")
    void testClassifyNon200Throws() throws Exception {
        when(httpResponse.statusCode()).thenReturn(502);
        when(httpResponse.body())
                .thenReturn("{\"detail\": {\"code\": \"classification_failed\", \"message\": \"nope\"}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        List<ContentItem> items = List.of(new ContentItem("m1", "hi", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, items));
    }

    @Test
    @DisplayName("classify: transport failure (exception from HttpClient.send) raises BrandSafetyAiException")
    void testClassifyTransportFailureThrows() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        List<ContentItem> items = List.of(new ContentItem("m1", "hi", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, items));
    }

    @Test
    @DisplayName("classify: malformed response body (wrong shape) raises BrandSafetyAiException")
    void testClassifyMalformedResponseThrows() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"not\": \"the expected shape\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        List<ContentItem> items = List.of(new ContentItem("m1", "hi", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, items));
    }

    @Test
    @DisplayName("classify: response item count mismatch raises BrandSafetyAiException")
    void testClassifyItemCountMismatchThrows() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn("{\"success\": true, \"data\": {\"items\": []}}"); // 0 items back, 1 sent
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        List<ContentItem> items = List.of(new ContentItem("m1", "hi", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, items));
    }

    @Test
    @DisplayName("classify: empty items list raises BrandSafetyAiException before any HTTP call")
    void testClassifyEmptyItemsThrowsWithoutHttpCall() throws Exception {
        assertThrows(
                BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, List.of()));
        verify(httpClient, org.mockito.Mockito.never())
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("classify: batch exceeding maxItemsPerCall raises BrandSafetyAiException before any HTTP call")
    void testClassifyTooManyItemsThrowsWithoutHttpCall() throws Exception {
        props.setMaxItemsPerCall(2);
        List<ContentItem> items =
                List.of(
                        new ContentItem("m1", "a", null, null),
                        new ContentItem("m2", "b", null, null),
                        new ContentItem("m3", "c", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify(WORKSPACE_ID, items));
        verify(httpClient, org.mockito.Mockito.never())
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("classify: missing workspaceId raises BrandSafetyAiException before any HTTP call")
    void testClassifyMissingWorkspaceIdThrows() throws Exception {
        List<ContentItem> items = List.of(new ContentItem("m1", "hi", null, null));

        assertThrows(BrandSafetyAiException.class, () -> client.classify("", items));
        assertThrows(BrandSafetyAiException.class, () -> client.classify(null, items));
        verify(httpClient, org.mockito.Mockito.never())
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
