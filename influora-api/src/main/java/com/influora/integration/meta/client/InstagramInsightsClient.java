package com.influora.integration.meta.client;

import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.dto.AccountInsightsResponse;
import com.influora.integration.meta.dto.AudienceBreakdowns;
import com.influora.integration.meta.dto.FollowerDemographicsResponse;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaPermissionDeniedException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
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
            "id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count,thumbnail_url";
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
    // Follower demographics (2026-09-24). The old audience_gender_age / audience_country /
    // audience_city / audience_locale metrics were removed for EVERY Graph API version on
    // 2023-12-11 (v18.0 changelog), so the weekly job never stored a single breakdown. Their
    // replacement is follower_demographics: period=lifetime, metric_type=total_value, one
    // breakdown per call. `timeframe` is listed for it in the IG User Insights reference;
    // this_month is the value v20.0 kept for the demographics metrics.
    static final String FOLLOWER_DEMOGRAPHICS_PATH =
            "/insights?metric=follower_demographics&period=lifetime&metric_type=total_value";
    static final String FOLLOWER_DEMOGRAPHICS_TIMEFRAME = "this_month";
    // Engaged audience (2026-09-26): who engaged with the creator's content, same call shape as
    // follower_demographics (period=lifetime, metric_type=total_value, one breakdown per call).
    // Meta: "Not returned if the IG User has less than 100 engagements during the timeframe."
    static final String ENGAGED_AUDIENCE_DEMOGRAPHICS_PATH =
            "/insights?metric=engaged_audience_demographics&period=lifetime&metric_type=total_value";
    static final String ENGAGED_AUDIENCE_TIMEFRAME = "this_month";

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
     * The creator's follower demographics: age and gender, country, and city (three
     * {@code follower_demographics} calls, one breakdown each). Required permission:
     * {@code instagram_manage_insights}. Meta returns nothing for an account under 100 followers,
     * which comes back here as an empty {@link AudienceBreakdowns}.
     */
    public AudienceBreakdowns getAudienceDemographics(String igUserId, String accessToken) {
        return getAudienceDemographics(igUserId, accessToken, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public AudienceBreakdowns getAudienceDemographics(
            String igUserId, String accessToken, MetaAuthPath authPath) {
        return AudienceBreakdowns.fromResponses(
                followerDemographics(igUserId, accessToken, authPath, "age,gender"),
                followerDemographics(igUserId, accessToken, authPath, "country"),
                followerDemographics(igUserId, accessToken, authPath, "city"));
    }

    /**
     * One {@code follower_demographics} breakdown. If Meta rejects the request with
     * {@code timeframe} (the reference lists it, but it is the one parameter we have not seen a
     * live answer for), it is asked once more without it rather than losing the week. Rate-limit,
     * expired-token and permission errors are never retried: they would fail the same way.
     */
    FollowerDemographicsResponse followerDemographics(
            String igUserId, String accessToken, MetaAuthPath authPath, String breakdown) {
        String path = "/" + igUserId + FOLLOWER_DEMOGRAPHICS_PATH + "&breakdown=" + breakdown;
        try {
            return apiClient.get(
                    path + "&timeframe=" + FOLLOWER_DEMOGRAPHICS_TIMEFRAME,
                    accessToken,
                    FollowerDemographicsResponse.class,
                    igUserId,
                    authPath);
        } catch (MetaRateLimitException | MetaTokenExpiredException | MetaPermissionDeniedException e) {
            throw e;
        } catch (MetaApiException e) {
            return apiClient.get(path, accessToken, FollowerDemographicsResponse.class, igUserId, authPath);
        }
    }

    /**
     * Who engaged with the creator's content this month: age and gender, country and city
     * ({@code engaged_audience_demographics}, {@code timeframe=this_month}, one breakdown per call).
     * Required permission: {@code instagram_manage_insights}.
     *
     * <p>Meta returns nothing when the account had fewer than 100 engagements in the timeframe.
     * That comes back here as an EMPTY {@link AudienceBreakdowns} (a normal state, never an
     * error), and when the age/gender call is already empty the country and city calls are not
     * made: they cannot be non-empty for the same account and month, and each call spends rate
     * budget. Unlike {@link #followerDemographics} there is no retry without {@code timeframe}:
     * this metric requires it, so a retry would fail the same way. Every Meta error propagates;
     * the caller decides what a failure means for the row.
     */
    public AudienceBreakdowns getEngagedAudienceDemographics(
            String igUserId, String accessToken, MetaAuthPath authPath) {
        FollowerDemographicsResponse ageGender =
                engagedAudienceDemographics(igUserId, accessToken, authPath, "age,gender");
        AudienceBreakdowns first = AudienceBreakdowns.fromResponses(ageGender, null, null);
        if (first.isEmpty()) {
            return first;
        }
        return AudienceBreakdowns.fromResponses(
                ageGender,
                engagedAudienceDemographics(igUserId, accessToken, authPath, "country"),
                engagedAudienceDemographics(igUserId, accessToken, authPath, "city"));
    }

    /** One {@code engaged_audience_demographics} breakdown (same response shape as follower_demographics). */
    FollowerDemographicsResponse engagedAudienceDemographics(
            String igUserId, String accessToken, MetaAuthPath authPath, String breakdown) {
        String path =
                "/" + igUserId + ENGAGED_AUDIENCE_DEMOGRAPHICS_PATH
                        + "&timeframe=" + ENGAGED_AUDIENCE_TIMEFRAME
                        + "&breakdown=" + breakdown;
        return apiClient.get(path, accessToken, FollowerDemographicsResponse.class, igUserId, authPath);
    }

    /**
     * T-CREATORCONNECT-0902 — Business Discovery: reads ANOTHER professional Instagram account's
     * public profile, keyed off the CALLER's own connected IG business account
     * ({@code callerIgUserId}). No permission needed from the target account. Required
     * permission: {@code instagram_basic}. Always FACEBOOK_LOGIN — Business Discovery is not
     * available on the {@code graph.instagram.com} host.
     */
    public com.influora.integration.meta.dto.BusinessDiscoveryResponse businessDiscovery(
            String callerIgUserId, String targetUsername, String accessToken) {
        String path =
                "/"
                        + callerIgUserId
                        + "?fields=business_discovery.username("
                        + targetUsername
                        + "){id,username,name,biography,profile_picture_url,followers_count,media_count}";
        return apiClient.get(
                path,
                accessToken,
                com.influora.integration.meta.dto.BusinessDiscoveryResponse.class,
                callerIgUserId);
    }

    /**
     * Fetches account-level insights over a date range: one {@code total_value} per metric (see
     * {@link AccountInsightsResponse}).
     * Required permission: {@code instagram_manage_insights}.
     *
     * @param sinceEpochSeconds range start (Unix timestamp)
     * @param untilEpochSeconds range end (Unix timestamp) — Meta caps the range at 30 days
     */
    public AccountInsightsResponse getAccountInsights(
            String igUserId, String accessToken, long sinceEpochSeconds, long untilEpochSeconds) {
        return getAccountInsights(
                igUserId, accessToken, sinceEpochSeconds, untilEpochSeconds, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /** As above, routed to the host matching the token's origin (T-IGLOGIN-0820). */
    public AccountInsightsResponse getAccountInsights(
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
        return apiClient.get(path, accessToken, AccountInsightsResponse.class, igUserId, authPath);
    }
}
