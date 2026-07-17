package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.dto.FacebookPageResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Facebook Page Graph API calls (spec §1.7).
 * Required permissions: {@code pages_show_list} to list connected pages,
 * {@code pages_read_engagement} for the engagement fields below.
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
     * Required permission: {@code pages_read_engagement}.
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
}
