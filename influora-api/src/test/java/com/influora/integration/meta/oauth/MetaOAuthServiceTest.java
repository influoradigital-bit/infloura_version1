package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Unit tests for MetaOAuthService (KAVYA_QA_TEST_PLAN §2.1, OAuth flow).
 * Covers authorization URL construction, code→token exchange, short→long-lived exchange, refresh.
 * No real Meta endpoints hit — mock the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class MetaOAuthServiceTest {

    private static final String APP_ID = "test-app-id";
    private static final String APP_SECRET = "test-app-secret";
    private static final String REDIRECT_URI = "https://influora.com/oauth/callback";
    private static final String GRAPH_API_VERSION = "v25.0";

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private MetaOAuthService service;

    @BeforeEach
    void setUp() {
        MetaApiProperties props = createTestProperties();
        service = new MetaOAuthService(props, restClient);
    }

    @Test
    @DisplayName("buildAuthorizationUrl: includes all required scopes and parameters")
    void testBuildAuthorizationUrl() {
        String state = "csrf-state-12345";
        String url = service.buildAuthorizationUrl(state);

        assertNotNull(url);
        assertTrue(url.startsWith("https://www.facebook.com/v25.0/dialog/oauth"));
        assertTrue(url.contains("client_id=" + APP_ID));
        // redirect_uri is URL-encoded, check for encoded version
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Finfluora.com%2Foauth%2Fcallback"));
        assertTrue(url.contains("scope=instagram_basic"));
        assertTrue(url.contains("instagram_manage_insights"));
        assertTrue(url.contains("pages_show_list"));
        // CR-115 — pages_read_engagement removed from REQUIRED_SCOPES; assert it's gone.
        assertFalse(url.contains("pages_read_engagement"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=" + state));
    }

    @Test
    @DisplayName("buildAuthorizationUrl: URL-encodes special characters in parameters")
    void testBuildAuthorizationUrlEncodesSpecialCharacters() {
        String stateWithSpecialChars = "state/with&special=chars";
        String url = service.buildAuthorizationUrl(stateWithSpecialChars);

        // Should be URL-encoded
        assertTrue(url.contains("state=state%2Fwith%26special%3Dchars"));
    }

    @Test
    @DisplayName("exchangeCodeForToken: returns valid token response on success")
    void testExchangeCodeForTokenSuccess() {
        String code = "test-auth-code";
        MetaTokenResponse mockResponse = new MetaTokenResponse("short-token", "bearer", 3600L);

        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(any(String.class));
        doReturn(responseSpec).when(requestHeadersUriSpec).retrieve();
        doReturn(mockResponse).when(responseSpec).body(MetaTokenResponse.class);

        MetaTokenResponse result = service.exchangeCodeForToken(code);

        assertNotNull(result);
        assertEquals("short-token", result.accessToken());
        assertEquals("bearer", result.tokenType());
        assertEquals(3600L, result.expiresInSeconds());
    }

    @Test
    @DisplayName("exchangeCodeForToken: throws MetaApiException on HTTP error")
    void testExchangeCodeForTokenHttpError() {
        String code = "invalid-code";

        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(any(String.class));
        doReturn(responseSpec).when(requestHeadersUriSpec).retrieve();
        doReturn(null).when(responseSpec).body(MetaTokenResponse.class);
        when(responseSpec.body(MetaTokenResponse.class))
                .thenThrow(new RestClientResponseException(
                        "Invalid code", HttpStatus.BAD_REQUEST.value(), "Bad Request", null, null, null));

        assertThrows(MetaApiException.class, () -> service.exchangeCodeForToken(code));
    }

    @Test
    @DisplayName("exchangeForLongLivedToken: returns long-lived token on success")
    void testExchangeForLongLivedTokenSuccess() {
        String shortToken = "short-lived-token";
        MetaTokenResponse mockResponse = new MetaTokenResponse("long-lived-token", "bearer", 5184000L); // 60 days

        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(any(String.class));
        doReturn(responseSpec).when(requestHeadersUriSpec).retrieve();
        doReturn(mockResponse).when(responseSpec).body(MetaTokenResponse.class);

        MetaTokenResponse result = service.exchangeForLongLivedToken(shortToken);

        assertNotNull(result);
        assertEquals("long-lived-token", result.accessToken());
        assertEquals(5184000L, result.expiresInSeconds());
    }

    @Test
    @DisplayName("refreshLongLivedToken: delegates to exchangeForLongLivedToken")
    void testRefreshLongLivedToken() {
        String currentToken = "current-token";
        MetaTokenResponse mockResponse = new MetaTokenResponse("refreshed-token", "bearer", 5184000L);

        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(any(String.class));
        doReturn(responseSpec).when(requestHeadersUriSpec).retrieve();
        doReturn(mockResponse).when(responseSpec).body(MetaTokenResponse.class);

        MetaTokenResponse result = service.refreshLongLivedToken(currentToken);

        assertNotNull(result);
        assertEquals("refreshed-token", result.accessToken());
    }

    @Test
    @DisplayName("REQUIRED_SCOPES: contains exactly the three required scopes (CR-115 — pages_read_engagement removed, unused)")
    void testRequiredScopes() {
        assertEquals(3, MetaOAuthService.REQUIRED_SCOPES.size());
        assertTrue(MetaOAuthService.REQUIRED_SCOPES.contains("instagram_basic"));
        assertTrue(MetaOAuthService.REQUIRED_SCOPES.contains("instagram_manage_insights"));
        assertTrue(MetaOAuthService.REQUIRED_SCOPES.contains("pages_show_list"));
        assertFalse(MetaOAuthService.REQUIRED_SCOPES.contains("pages_read_engagement"));
    }

    private MetaApiProperties createTestProperties() {
        MetaApiProperties props = new MetaApiProperties();
        props.setAppId(APP_ID);
        props.setAppSecret(APP_SECRET);
        props.setRedirectUri(REDIRECT_URI);
        props.setGraphApiVersion(GRAPH_API_VERSION);
        props.setTokenRefreshDaysBeforeExpiry(7);
        props.setRateLimitAlertThreshold(80);
        props.setRateLimitThrottleThreshold(90);
        return props;
    }
}
