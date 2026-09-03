package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.CreatorMarketplaceCreatorsResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import org.springframework.stereotype.Component;

/**
 * Meta Creator Marketplace search (T-CREATORCONNECT-0902 §Meta Creator Marketplace client).
 * Thin, flagged, ships DARK — see {@link CreatorMarketplaceCreatorsResponse}'s javadoc for why.
 * Gated by {@code influora.meta.creator-marketplace.enabled} at the CALLER ({@code
 * ExternalCreatorService}), not here; this class makes the real Graph call unconditionally when
 * invoked; it is the caller's job to never invoke it while the flag is off.
 *
 * <p>Uses a Page access token, resolved by the caller via {@code GET /me/accounts?fields=id,
 * access_token,instagram_business_account{id}} against the workspace's FACEBOOK_LOGIN user
 * token — this client never resolves or stores that token itself (no new storage).
 */
@Component
public class CreatorMarketplaceClient {

    // Q7.2 — `is_account_verified` and `insights` were requested but never persisted anywhere
    // (external_creators has no matching column, and Meta's own `insights` shape is undocumented
    // dark data — see CreatorMarketplaceCreatorsResponse#Creator javadoc). Dropped rather than
    // fetched-and-discarded on every enrichment cycle; add them back alongside a real column/
    // mapping if a future Marketplace insights feature needs them.
    private static final String FIELDS =
            "id,username,biography,country,profile_picture_url,"
                    + "has_brand_partnership_experience,past_brand_partnership_partners";

    private final MetaGraphApiClient apiClient;

    public CreatorMarketplaceClient(MetaGraphApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** Optional search/filter params — every field nullable, omitted from the query when null. */
    public record SearchParams(
            String query,
            String creatorCountries,
            Long creatorMinFollowers,
            Long creatorMaxFollowers,
            String creatorInterests) {}

    public CreatorMarketplaceCreatorsResponse search(
            String igUserId, String pageAccessToken, SearchParams params) {
        StringBuilder path = new StringBuilder("/").append(igUserId).append("/creator_marketplace_creators");
        path.append("?fields=").append(FIELDS);
        if (params != null) {
            appendParam(path, "query", params.query());
            appendParam(path, "creator_countries", params.creatorCountries());
            appendParam(
                    path,
                    "creator_min_followers",
                    params.creatorMinFollowers() != null ? params.creatorMinFollowers().toString() : null);
            appendParam(
                    path,
                    "creator_max_followers",
                    params.creatorMaxFollowers() != null ? params.creatorMaxFollowers().toString() : null);
            appendParam(path, "creator_interests", params.creatorInterests());
        }
        return apiClient.get(
                path.toString(), pageAccessToken, CreatorMarketplaceCreatorsResponse.class, igUserId);
    }

    private static void appendParam(StringBuilder path, String key, String value) {
        if (value != null && !value.isBlank()) {
            path.append('&').append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
