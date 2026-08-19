package com.influora.integration.meta.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import com.influora.domain.entity.MetaAuthPath;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.integration.meta.dto.AudienceDemographicsResponse;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
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
 * Unit tests for InstagramInsightsClient (KAVYA_QA_TEST_PLAN §2.4, Instagram API client).
 * Verifies correct fields/paths requested, and that MetaRateLimitException/MetaTokenExpiredException
 * are thrown on 429/401 respectively. Mock the underlying MetaGraphApiClient.
 */
@ExtendWith(MockitoExtension.class)
class InstagramInsightsClientTest {

    private static final String IG_USER_ID = "instagram-user-12345";
    private static final String MEDIA_ID = "instagram-media-67890";
    private static final String ACCESS_TOKEN = "test-access-token";
    private static final String BUSINESS_ACCOUNT_ID = "business-account-id";

    @Mock private MetaGraphApiClient apiClient;

    @Captor private ArgumentCaptor<String> pathCaptor;

    private InstagramInsightsClient client;

    @BeforeEach
    void setUp() {
        client = new InstagramInsightsClient(apiClient);
    }

    @Test
    @DisplayName("getProfile: requests correct USER_FIELDS")
    void testGetProfileRequestsCorrectFields() {
        InstagramUserResponse mockResponse = new InstagramUserResponse(
                IG_USER_ID, "testuser", "Test User", "Bio", 10000L, 500L, 150L, null, null);

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramUserResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        InstagramUserResponse result = client.getProfile(IG_USER_ID, ACCESS_TOKEN);

        assertNotNull(result);
        assertEquals(IG_USER_ID, result.id());
        assertEquals("testuser", result.username());

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(InstagramUserResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + IG_USER_ID));
        assertTrue(path.contains("fields="));
        assertTrue(path.contains("username"));
        assertTrue(path.contains("followers_count"));
        assertTrue(path.contains("biography"));
    }

    @Test
    @DisplayName("getMedia: caps limit at 100 per Meta API constraint")
    void testGetMediaCapsLimitAt100() {
        InstagramMediaResponse mockResponse = new InstagramMediaResponse(null, null);

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramMediaResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        client.getMedia(IG_USER_ID, ACCESS_TOKEN, 250); // Request 250, should be capped

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(InstagramMediaResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("limit=100")); // Capped at 100
    }

    @Test
    @DisplayName("getMedia: requests correct MEDIA_FIELDS")
    void testGetMediaRequestsCorrectFields() {
        InstagramMediaResponse mockResponse = new InstagramMediaResponse(null, null);

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramMediaResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        client.getMedia(IG_USER_ID, ACCESS_TOKEN, 50);

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(InstagramMediaResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + IG_USER_ID + "/media"));
        assertTrue(path.contains("fields="));
        assertTrue(path.contains("caption"));
        assertTrue(path.contains("media_type"));
        assertTrue(path.contains("like_count"));
        assertTrue(path.contains("comments_count"));
    }

    @Test
    @DisplayName("getMediaInsights: requests correct INSIGHTS_METRICS")
    void testGetMediaInsightsRequestsCorrectFields() {
        InstagramInsightsResponse mockResponse = new InstagramInsightsResponse(null);

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(BUSINESS_ACCOUNT_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        client.getMediaInsights(MEDIA_ID, ACCESS_TOKEN, BUSINESS_ACCOUNT_ID);

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(BUSINESS_ACCOUNT_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + MEDIA_ID + "/insights"));
        assertTrue(path.contains("metric="));
        assertTrue(path.contains("reach"));
        assertTrue(path.contains("likes"));
        assertTrue(path.contains("comments"));
        assertTrue(path.contains("saved"));
        assertTrue(path.contains("shares"));
        assertTrue(path.contains("views"));
        assertTrue(path.contains("total_interactions"));
        // F-0355 regression guard: Meta removed these; requesting one 400s the whole call.
        assertFalse(path.contains("impressions"));
        assertFalse(path.contains("engagement"));
        assertFalse(path.contains("video_views"));
    }

    @Test
    @DisplayName("getAudienceDemographics: requests correct AUDIENCE_METRICS with lifetime period")
    void testGetAudienceDemographicsRequestsCorrectFields() {
        AudienceDemographicsResponse mockResponse = new AudienceDemographicsResponse(null);

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(AudienceDemographicsResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        client.getAudienceDemographics(IG_USER_ID, ACCESS_TOKEN);

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(AudienceDemographicsResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + IG_USER_ID + "/insights"));
        assertTrue(path.contains("metric="));
        assertTrue(path.contains("audience_city"));
        assertTrue(path.contains("audience_country"));
        assertTrue(path.contains("audience_gender_age"));
        assertTrue(path.contains("audience_locale"));
        assertTrue(path.contains("period=lifetime"));
    }

    @Test
    @DisplayName("getAccountInsights: includes date range parameters")
    void testGetAccountInsightsIncludesDateRange() {
        InstagramInsightsResponse mockResponse = new InstagramInsightsResponse(null);
        long sinceEpoch = 1704067200L; // 2024-01-01
        long untilEpoch = 1706745600L; // 2024-02-01

        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenReturn(mockResponse);

        client.getAccountInsights(IG_USER_ID, ACCESS_TOKEN, sinceEpoch, untilEpoch);

        verify(apiClient).get(pathCaptor.capture(), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN));
        String path = pathCaptor.getValue();

        assertTrue(path.contains("/" + IG_USER_ID + "/insights"));
        assertTrue(path.contains("since=" + sinceEpoch));
        assertTrue(path.contains("until=" + untilEpoch));
        assertTrue(path.contains("period=day"));
        assertTrue(path.contains("metric_type=total_value"));
        // F-0355 regression guard: account-level impressions was removed for ALL versions on
        // 2025-04-21; profile_views and website_clicks are no longer supported metric names.
        assertFalse(path.contains("impressions"));
        assertFalse(path.contains("profile_views"));
        assertFalse(path.contains("website_clicks"));
        assertTrue(path.contains("reach"));
        assertTrue(path.contains("views"));
        assertTrue(path.contains("total_interactions"));
    }

    @Test
    @DisplayName("getProfile: throws MetaRateLimitException when apiClient throws it")
    void testGetProfileThrowsRateLimitException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramUserResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaRateLimitException("Rate limit exceeded"));

        assertThrows(MetaRateLimitException.class, () -> client.getProfile(IG_USER_ID, ACCESS_TOKEN));
    }

    @Test
    @DisplayName("getProfile: throws MetaTokenExpiredException when apiClient throws it")
    void testGetProfileThrowsTokenExpiredException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramUserResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaTokenExpiredException("Token expired"));

        assertThrows(MetaTokenExpiredException.class, () -> client.getProfile(IG_USER_ID, ACCESS_TOKEN));
    }

    @Test
    @DisplayName("getMediaInsights: throws MetaRateLimitException on 429")
    void testGetMediaInsightsThrowsRateLimitException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(BUSINESS_ACCOUNT_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaRateLimitException("Rate limit hit"));

        assertThrows(MetaRateLimitException.class, () -> client.getMediaInsights(MEDIA_ID, ACCESS_TOKEN, BUSINESS_ACCOUNT_ID));
    }

    @Test
    @DisplayName("getMediaInsights: throws MetaTokenExpiredException on 401")
    void testGetMediaInsightsThrowsTokenExpiredException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(InstagramInsightsResponse.class), eq(BUSINESS_ACCOUNT_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaTokenExpiredException("Unauthorized"));

        assertThrows(MetaTokenExpiredException.class, () -> client.getMediaInsights(MEDIA_ID, ACCESS_TOKEN, BUSINESS_ACCOUNT_ID));
    }

    @Test
    @DisplayName("getAudienceDemographics: propagates rate limit exception")
    void testGetAudienceDemographicsThrowsRateLimitException() {
        when(apiClient.get(any(String.class), eq(ACCESS_TOKEN), eq(AudienceDemographicsResponse.class), eq(IG_USER_ID), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaRateLimitException("Too many requests"));

        assertThrows(MetaRateLimitException.class, () -> client.getAudienceDemographics(IG_USER_ID, ACCESS_TOKEN));
    }
}
