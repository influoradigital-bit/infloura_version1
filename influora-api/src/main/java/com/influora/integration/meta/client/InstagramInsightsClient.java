package com.influora.integration.meta.client;

import com.influora.domain.entity.MetaAuthPath;
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
    // Metric names verified against IG Media Insights (ref updated 2026-06-18) for the pinned
    // graph-api-version. Removed: impressions (deprecated for media created after 2024-07-02),
    // engagement (superseded by total_interactions, v18.0+), video_views (superseded by views).
    private static final String INSIGHTS_METRICS =
            "reach,likes,comments,saved,shares,views,total_interactions";
    // Account-level interaction metrics, all period=day + metric_type=total_value. impressions was
    // deprecated for every version on 2025-04-21 (replaced by views); profile_views and
    // website_clicks are no longer supported metrics — profile_links_taps is the surviving
    // profile-action counter. See IG Account Insights reference (updated 2026-06-16).
    private static final String ACCOUNT_METRICS =
            "reach,views,total_interactions,accounts_engaged,profile_links_taps";
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
        return getProfile(igUserId, accessToken, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public InstagramUserResponse getProfile(String igUserId, String accessToken, MetaAuthPath authPath) {
        String path = "/" + igUserId + "?fields=" + USER_FIELDS;
        return apiClient.get(path, accessToken, InstagramUserResponse.class, igUserId, authPath);
    }

    /**
     * Fetches recent media with basic metrics.
     * Required permission: {@code instagram_basic}.
     *
     * @param limit capped at 100 per request (Meta API limit)
     */
    public InstagramMediaResponse getMedia(String igUserId, String accessToken, int limit) {
        return getMedia(igUserId, accessToken, limit, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public InstagramMediaResponse getMedia(
            String igUserId, String accessToken, int limit, MetaAuthPath authPath) {
        int cappedLimit = Math.min(limit, 100);
        String path = "/" + igUserId + "/media?fields=" + MEDIA_FIELDS + "&limit=" + cappedLimit;
        return apiClient.get(path, accessToken, InstagramMediaResponse.class, igUserId, authPath);
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
        return getMediaInsights(mediaId, accessToken, businessAccountId, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public InstagramInsightsResponse getMediaInsights(
            String mediaId, String accessToken, String businessAccountId, MetaAuthPath authPath) {
        String path = "/" + mediaId + "/insights?metric=" + INSIGHTS_METRICS;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, businessAccountId, authPath);
    }

    /**
     * Fetches audience demographics (city, country, gender/age distribution).
     * Required permission: {@code instagram_manage_insights}. Only available for accounts with
     * 100+ followers — Meta returns an empty/error payload otherwise.
     */
    public AudienceDemographicsResponse getAudienceDemographics(String igUserId, String accessToken) {
        return getAudienceDemographics(igUserId, accessToken, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public AudienceDemographicsResponse getAudienceDemographics(
            String igUserId, String accessToken, MetaAuthPath authPath) {
        String path = "/" + igUserId + "/insights?metric=" + AUDIENCE_METRICS + "&period=lifetime";
        return apiClient.get(path, accessToken, AudienceDemographicsResponse.class, igUserId, authPath);
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
        return getAccountInsights(
                igUserId, accessToken, sinceEpochSeconds, untilEpochSeconds, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public InstagramInsightsResponse getAccountInsights(
            String igUserId,
            String accessToken,
            long sinceEpochSeconds,
            long untilEpochSeconds,
            MetaAuthPath authPath) {
        String path =
                "/"
                        + igUserId
                        + "/insights?metric=" + ACCOUNT_METRICS
                        + "&period=day&metric_type=total_value&since="
                        + sinceEpochSeconds
                        + "&until="
                        + untilEpochSeconds;
        return apiClient.get(path, accessToken, InstagramInsightsResponse.class, igUserId, authPath);
    }
}
