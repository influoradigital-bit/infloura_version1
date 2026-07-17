package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.AudienceDemographicsResponse;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
import org.springframework.stereotype.Component;

/**
 * Instagram-specific Graph API calls (spec §1.4). Field lists are kept minimal per endpoint —
 * only what downstream scoring/insights features actually consume.
 */
@Component
public class InstagramInsightsClient {

    private static final String USER_FIELDS =
            "id,username,name,biography,followers_count,follows_count,media_count,profile_picture_url,website";
    private static final String MEDIA_FIELDS =
            "id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count";
    private static final String INSIGHTS_METRICS =
            "impressions,reach,engagement,saved,shares,video_views,likes,comments";
    // 2026 compliance: Page Viewer Metric migration deadline June 15 (spec §1.8) — revisit before then.
    private static final String AUDIENCE_METRICS = "audience_city,audience_country,audience_gender_age,audience_locale";

    private final MetaGraphApiClient apiClient;

    public InstagramInsightsClient(MetaGraphApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetches basic profile data for an Instagram Business/Creator account.
     * Required permission: {@code instagram_basic}.
     */
    public InstagramUserResponse getProfile(String igUserId, String accessToken) {
        String path = "/" + igUserId + "?fields=" + USER_FIELDS;
        return apiClient.get(path, accessToken, InstagramUserResponse.class, igUserId);
    }

    /**
     * Fetches recent media with basic metrics.
     * Required permission: {@code instagram_basic}.
     *
     * @param limit capped at 100 per request (Meta API limit)
     */
    public InstagramMediaResponse getMedia(String igUserId, String accessToken, int limit) {
        int cappedLimit = Math.min(limit, 100);
        String path = "/" + igUserId + "/media?fields=" + MEDIA_FIELDS + "&limit=" + cappedLimit;
        return apiClient.get(path, accessToken, InstagramMediaResponse.class, igUserId);
    }

    /**
     * Fetches insights for a specific media object.
     * Required permission: {@code instagram_manage_insights}.
     *
     * <p>Available metrics vary by {@code media_type} (IMAGE, VIDEO, CAROUSEL_ALBUM, REELS) —
     * Meta rejects unsupported metrics for a given type with a 400; callers should catch and
     * degrade gracefully rather than treating it as a hard failure.
     */
    public InstagramInsightsResponse getMediaInsights(String mediaId, String accessToken, String businessAccountId) {
        String path = "/" + mediaId + "/insights?metric=" + INSIGHTS_METRICS;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, businessAccountId);
    }

    /**
     * Fetches audience demographics (city, country, gender/age distribution).
     * Required permission: {@code instagram_manage_insights}. Only available for accounts with
     * 100+ followers — Meta returns an empty/error payload otherwise.
     */
    public AudienceDemographicsResponse getAudienceDemographics(String igUserId, String accessToken) {
        String path = "/" + igUserId + "/insights?metric=" + AUDIENCE_METRICS + "&period=lifetime";
        return apiClient.get(path, accessToken, AudienceDemographicsResponse.class, igUserId);
    }

    /**
     * Fetches account-level insights over a date range.
     * Required permission: {@code instagram_manage_insights}.
     *
     * @param sinceEpochSeconds range start (Unix timestamp)
     * @param untilEpochSeconds range end (Unix timestamp) — Meta caps the range at 30 days
     */
    public InstagramInsightsResponse getAccountInsights(
            String igUserId, String accessToken, long sinceEpochSeconds, long untilEpochSeconds) {
        String path =
                "/"
                        + igUserId
                        + "/insights?metric=impressions,reach,profile_views,website_clicks"
                        + "&period=day&since="
                        + sinceEpochSeconds
                        + "&until="
                        + untilEpochSeconds;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, igUserId);
    }
}
