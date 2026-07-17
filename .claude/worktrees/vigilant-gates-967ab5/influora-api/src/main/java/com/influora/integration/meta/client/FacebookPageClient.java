package com.influora.integration.meta.client;

import com.influora.integration.meta.dto.FacebookPageResponse;
import org.springframework.stereotype.Component;

/**
 * Facebook Page Graph API calls (spec §1.7).
 * Required permissions: {@code pages_show_list} to list connected pages,
 * {@code pages_read_engagement} for the engagement fields below.
 */
@Component
public class FacebookPageClient {

    private static final String PAGE_FIELDS = "id,name,category,fan_count,followers_count,about,link";

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
}
