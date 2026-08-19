package com.influora.integration.meta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave B task B3: unit tests for {@link InstagramMetricsFetcher} against mocked {@link
 * InstagramInsightsClient}/{@link MetaRateLimitTracker} -- per plan acceptance ("orchestrator
 * tested against mocked clients"). Mirrors the exact rate-limit-check and per-media
 * graceful-degradation behavior already proven for {@code MetricsPollingJob} in {@code
 * MetricsPollingJobTest}, since this class is documented (see its class javadoc) to preserve that
 * job's behavior contract rather than change it.
 */
@ExtendWith(MockitoExtension.class)
class InstagramMetricsFetcherTest {

    // Meta's Graph API requires the numeric IG Business Account ID in the request path — this is
    // what fetchAll/fetchMediaWithInsights take and what the client stubs/verifications assert,
    // NOT the internal ULID creatorProfileId. Mirrors MetricsPollingJobTest.IG_BUSINESS_ACCOUNT_ID.
    private static final String IG_BUSINESS_ACCOUNT_ID = "17841400000000001";
    private static final String TOKEN_VALUE = "test-meta-token-1234567890";

    @Mock private InstagramInsightsClient instagramClient;
    @Mock private MetaRateLimitTracker rateLimitTracker;

    private InstagramMetricsFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new InstagramMetricsFetcher(instagramClient, rateLimitTracker);
    }

    // ------------------------------------------------------------------
    // fetchAll: profile + media + insights composition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchAll: composes profile fetch + media list + per-media insights into one Result")
    void testFetchAllComposesProfileMediaAndInsights() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);

        InstagramUserResponse profile =
                new InstagramUserResponse("ig_1", "user1", "User", "Bio", 1000L, 100L, 20L, null, null);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile);

        InstagramMediaResponse.MediaItem mediaItem =
                new InstagramMediaResponse.MediaItem(
                        "media_1", "caption", "IMAGE", "http://img", "http://permalink/1",
                        "2026-06-01T10:15:30+00:00", 42L, 7L);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(List.of(mediaItem), null));

        InstagramInsightsResponse insights =
                new InstagramInsightsResponse(
                        List.of(
                                new InstagramInsightsResponse.InsightMetric(
                                        "reach", "lifetime", "reach", "reach",
                                        List.of(new InstagramInsightsResponse.InsightValue(500L, null)))));
        when(instagramClient.getMediaInsights("media_1", TOKEN_VALUE, IG_BUSINESS_ACCOUNT_ID, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(insights);

        InstagramMetricsFetcher.Result result = fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE);

        assertEquals(profile, result.profile());
        assertEquals(1, result.media().size());
        InstagramMetricsFetcher.MediaWithInsights mwi = result.media().get(0);
        assertEquals("media_1", mwi.mediaItem().id());
        assertEquals(insights, mwi.insights());
    }

    @Test
    @DisplayName("fetchAll: default media limit passed through to getMedia is 25 (matches MetricsPollingJob.RECENT_MEDIA_LIMIT)")
    void testFetchAllUsesDefaultMediaLimitOf25() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramUserResponse("ig_1", "u", "n", "b", 1L, 1L, 1L, null, null));
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(Collections.emptyList(), null));

        fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE);

        verify(instagramClient).getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN);
    }

    @Test
    @DisplayName("fetchAll: an explicit mediaLimit overload passes that limit through to getMedia")
    void testFetchAllExplicitMediaLimit() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramUserResponse("ig_1", "u", "n", "b", 1L, 1L, 1L, null, null));
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 10, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(Collections.emptyList(), null));

        fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 10);

        verify(instagramClient).getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 10, MetaAuthPath.FACEBOOK_LOGIN);
    }

    @Test
    @DisplayName("fetchAll: profile-fetch rate-limit exception propagates to the caller (no partial Result to return)")
    void testFetchAllPropagatesProfileRateLimitException() {
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaRateLimitException("rate limited"));

        assertThrows(MetaRateLimitException.class, () -> fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE));

        verify(instagramClient, never()).getMedia(anyString(), anyString(), anyInt(), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    @Test
    @DisplayName("fetchAll: profile-fetch token-expired exception propagates to the caller")
    void testFetchAllPropagatesTokenExpiredException() {
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaTokenExpiredException("expired"));

        assertThrows(MetaTokenExpiredException.class, () -> fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE));
    }

    @Test
    @DisplayName("fetchAll: generic profile-fetch MetaApiException propagates to the caller")
    void testFetchAllPropagatesGenericProfileApiException() {
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaApiException("server error"));

        assertThrows(MetaApiException.class, () -> fetcher.fetchAll(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE));
    }

    // ------------------------------------------------------------------
    // fetchMediaWithInsights: rate-limit pre-check before the media-list call
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchMediaWithInsights: at >=90% rate-limit usage, returns empty list without calling getMedia")
    void testFetchMediaSkipsWhenRateLimitApproaching() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(95);

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
        verify(instagramClient, never()).getMedia(anyString(), anyString(), anyInt(), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    @Test
    @DisplayName("fetchMediaWithInsights: exactly at the 90% threshold is treated as rate-limited (>= not >)")
    void testFetchMediaThresholdIsInclusive() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(90);

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
        verify(instagramClient, never()).getMedia(anyString(), anyString(), anyInt(), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    // ------------------------------------------------------------------
    // fetchMediaWithInsights: media-list fetch failures degrade to empty list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchMediaWithInsights: media-list rate-limited marks limited and returns empty list, no throw")
    void testFetchMediaListRateLimitedReturnsEmptyAndMarksLimited() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaRateLimitException("rate limited"));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
        verify(rateLimitTracker).markLimited(IG_BUSINESS_ACCOUNT_ID);
    }

    @Test
    @DisplayName("fetchMediaWithInsights: media-list generic API failure returns empty list, no throw")
    void testFetchMediaListApiFailureReturnsEmpty() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaApiException("server error"));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
        verify(rateLimitTracker, never()).markLimited(anyString());
    }

    @Test
    @DisplayName("fetchMediaWithInsights: null media response data yields empty list, no NPE")
    void testFetchMediaNullDataYieldsEmptyList() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(null, null));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
    }

    @Test
    @DisplayName("fetchMediaWithInsights: empty media list yields empty result, never calls getMediaInsights")
    void testFetchMediaEmptyListNoInsightsCalls() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(Collections.emptyList(), null));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertTrue(media.isEmpty());
        verify(instagramClient, never()).getMediaInsights(anyString(), anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    // ------------------------------------------------------------------
    // Per-media insights: graceful degradation (mirrors MetricsPollingJob.pollOneMedia)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("per-media insights: rate-limited insights call marks limited and yields null insights, base media item preserved")
    void testPerMediaInsightsRateLimitedDegradesToNullInsights() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        InstagramMediaResponse.MediaItem mediaItem =
                new InstagramMediaResponse.MediaItem(
                        "media_rl", "caption", "IMAGE", "http://img", "http://permalink/rl",
                        "2026-06-04T00:00:00+00:00", 1L, 1L);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(List.of(mediaItem), null));
        when(instagramClient.getMediaInsights("media_rl", TOKEN_VALUE, IG_BUSINESS_ACCOUNT_ID, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaRateLimitException("rate limited"));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertEquals(1, media.size());
        assertEquals("media_rl", media.get(0).mediaItem().id());
        assertNull(media.get(0).insights());
        verify(rateLimitTracker).markLimited(IG_BUSINESS_ACCOUNT_ID);
    }

    @Test
    @DisplayName("per-media insights: unsupported metric/type combo (Meta 400 -> MetaApiException) degrades to null insights, base item preserved")
    void testPerMediaInsightsUnsupportedComboDegradesToNullInsights() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        InstagramMediaResponse.MediaItem mediaItem =
                new InstagramMediaResponse.MediaItem(
                        "media_reels", "caption", "REELS", "http://reels", "http://permalink/reels",
                        "2026-06-03T12:00:00+00:00", 999L, 88L);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(List.of(mediaItem), null));
        when(instagramClient.getMediaInsights("media_reels", TOKEN_VALUE, IG_BUSINESS_ACCOUNT_ID, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaApiException("Meta Graph API call failed with status 400"));

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertEquals(1, media.size());
        assertEquals("media_reels", media.get(0).mediaItem().id());
        assertNull(media.get(0).insights());
    }

    @Test
    @DisplayName("per-media insights: one item's insights failure does not prevent other items from being fetched")
    void testOneMediaItemInsightsFailureDoesNotAbortOthers() {
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);

        InstagramMediaResponse.MediaItem good1 =
                new InstagramMediaResponse.MediaItem(
                        "media_ok1", "c", "IMAGE", "u", "p1", "2026-06-05T00:00:00+00:00", 1L, 1L);
        InstagramMediaResponse.MediaItem bad =
                new InstagramMediaResponse.MediaItem(
                        "media_bad", "c", "IMAGE", "u", "p2", "2026-06-05T00:00:00+00:00", 1L, 1L);
        InstagramMediaResponse.MediaItem good2 =
                new InstagramMediaResponse.MediaItem(
                        "media_ok2", "c", "IMAGE", "u", "p3", "2026-06-05T00:00:00+00:00", 1L, 1L);
        when(instagramClient.getMedia(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25, MetaAuthPath.FACEBOOK_LOGIN))
                .thenReturn(new InstagramMediaResponse(List.of(good1, bad, good2), null));

        InstagramInsightsResponse okInsights =
                new InstagramInsightsResponse(
                        List.of(
                                new InstagramInsightsResponse.InsightMetric(
                                        "reach", "lifetime", "reach", "reach",
                                        List.of(new InstagramInsightsResponse.InsightValue(10L, null)))));
        when(instagramClient.getMediaInsights(eq("media_ok1"), anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN))).thenReturn(okInsights);
        when(instagramClient.getMediaInsights(eq("media_bad"), anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenThrow(new MetaApiException("unexpected"));
        when(instagramClient.getMediaInsights(eq("media_ok2"), anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN))).thenReturn(okInsights);

        List<InstagramMetricsFetcher.MediaWithInsights> media =
                fetcher.fetchMediaWithInsights(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, 25);

        assertEquals(3, media.size());
        assertEquals(okInsights, media.get(0).insights());
        assertNull(media.get(1).insights());
        assertEquals(okInsights, media.get(2).insights());
        verify(instagramClient, times(3)).getMediaInsights(anyString(), anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }
}
