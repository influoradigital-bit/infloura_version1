package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.dto.FacebookPageResponse;
import com.influora.integration.meta.dto.MetaPermissionsResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Facebook Page Graph API calls (spec §1.7).
 * Required permission: {@code pages_show_list} to list connected pages.
 *
 * <p>CR-115 — {@code pages_read_engagement} is NO LONGER in {@link
 * com.influora.integration.meta.oauth.MetaOAuthService#REQUIRED_SCOPES} (removed: {@link
 * #getPage} had zero production callers). {@link #getPage} below still requests engagement
 * fields and WILL fail with a Meta permission error if wired up without first re-adding that
 * scope to REQUIRED_SCOPES and re-consenting existing connections.
 */
@Component
public class FacebookPageClient {

    private static final String PAGE_FIELDS = "id,name,category,fan_count,followers_count,about,link";
    private static final String ACCOUNTS_FIELDS =
            "instagram_business_account{id,username,followers_count}";

    private final MetaGraphApiClient apiClient;

    public FacebookPageClient(MetaGraphApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetches profile + engagement counters for a connected Facebook Page.
     * Requires {@code pages_read_engagement} — see the CR-115 class-level note: that scope is
     * NOT currently requested, so this call will fail against a live token until it is.
     *
     * <p>Note (spec §1.8): {@code page_views_total} format migration lands June 15, 2026 — this
     * client does not yet request that metric; add it here once the new format is finalized.
     */
    public FacebookPageResponse getPage(String pageId, String accessToken) {
        String path = "/" + pageId + "?fields=" + PAGE_FIELDS;
        return apiClient.get(path, accessToken, FacebookPageResponse.class, pageId);
    }

    /**
     * Resolves the connected Instagram Business Account behind the caller's Facebook pages
     * ({@code GET /me/accounts}) — used to refresh live handle/follower data on the connection
     * status read. Picks the first page with a connected Instagram business account; returns
     * {@code null} when none is connected (caller null-checks).
     */
    public FacebookAccountsListResponse.InstagramBusinessAccount resolveConnectedInstagram(
            String accessToken) {
        String path = "/me/accounts?fields=" + ACCOUNTS_FIELDS;
        FacebookAccountsListResponse response =
                apiClient.get(path, accessToken, FacebookAccountsListResponse.class, "me");
        if (response == null || response.data() == null) {
            return null;
        }
        List<FacebookAccountsListResponse.PageWithInstagram> pages = response.data();
        for (FacebookAccountsListResponse.PageWithInstagram page : pages) {
            if (page.instagramBusinessAccount() != null) {
                return page.instagramBusinessAccount();
            }
        }
        return null;
    }

    /**
     * Fetches the scopes actually granted/declined for this token ({@code GET /me/permissions},
     * CR-104) — the only way to know what a creator really approved in the OAuth dialog, as
     * opposed to what {@code MetaOAuthService.REQUIRED_SCOPES} merely requested. Callers filter
     * on {@link MetaPermissionsResponse.Permission#isGranted()} themselves; this method returns
     * the raw list (both granted and declined entries) unfiltered.
     */
    public MetaPermissionsResponse fetchPermissions(String accessToken) {
        return apiClient.get("/me/permissions", accessToken, MetaPermissionsResponse.class, "me");
    }
}
