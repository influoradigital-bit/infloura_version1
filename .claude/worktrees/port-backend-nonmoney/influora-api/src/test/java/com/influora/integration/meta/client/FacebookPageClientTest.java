package com.influora.integration.meta.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.integration.meta.dto.FacebookPageResponse;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for FacebookPageClient (KAVYA_QA_TEST_PLAN §2.4, Facebook Page API client).
 * Verifies correct fields/paths requested, and exception propagation. Mock MetaGraphApiClient.
 */
@ExtendWith(MockitoExtension.class)
class FacebookPageClientTest {

    private static final String PAGE_ID = "facebook-page-12345";
    private static final String ACCESS_TOKEN = "test-access-token";

    @Mock private MetaGraphApiClient apiClient;

    @Captor private ArgumentCaptor<String> pathCaptor;

    private FacebookPageClient client;

    @BeforeEach
    void setUp() {
        client = new FacebookPageClient(apiClient);
    }

    @Test
    @DisplayName("getPage: requests correct PAGE_FIELDS")
    void testGetPageRequestsCorrectFields() {
        FacebookPageResponse mockResponse = new FacebookPageResponse(
                PAGE_ID, "Test Page", "Business", 5000L, 4800L, "About us", "https://facebook.com/testpage");

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(FacebookPageResponse.class), eq(PAGE_ID)))
                .thenReturn(mockResponse);

        FacebookPageResponse result = client.getPage(PAGE_ID, ACCESS_TOKEN);

        assertNotNull(result);
        assertEquals(PAGE_ID, result.id());
        assertEquals("Test Page", result.name());
        assertEquals(5000L, result.fanCount());

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(FacebookPageResponse.class), eq(PAGE_ID));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + PAGE_ID));
        assertTrue(path.contains("fields="));
        assertTrue(path.contains("id"));
        assertTrue(path.contains("name"));
        assertTrue(path.contains("category"));
        assertTrue(path.contains("fan_count"));
        assertTrue(path.contains("followers_count"));
        assertTrue(path.contains("about"));
        assertTrue(path.contains("link"));
    }

    @Test
    @DisplayName("getPage: throws MetaRateLimitException on 429")
    void testGetPageThrowsRateLimitException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(FacebookPageResponse.class), eq(PAGE_ID)))
                .thenThrow(new MetaRateLimitException("Rate limit exceeded"));

        assertThrows(MetaRateLimitException.class, () -> client.getPage(PAGE_ID, ACCESS_TOKEN));
    }

    @Test
    @DisplayName("getPage: throws MetaTokenExpiredException on 401")
    void testGetPageThrowsTokenExpiredException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(FacebookPageResponse.class), eq(PAGE_ID)))
                .thenThrow(new MetaTokenExpiredException("Token expired"));

        assertThrows(MetaTokenExpiredException.class, () -> client.getPage(PAGE_ID, ACCESS_TOKEN));
    }
}
