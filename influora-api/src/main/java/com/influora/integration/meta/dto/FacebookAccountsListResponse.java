package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code GET /me/accounts?fields=instagram_business_account{...}} — pages linked to the Meta user. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacebookAccountsListResponse(List<PageWithInstagram> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageWithInstagram(
            String id,
            // T-CREATORCONNECT-0902 — CreatorMarketplaceClient's page-token resolution requests
            // this field explicitly (fields=id,access_token,instagram_business_account{id}).
            // Every OTHER caller of this DTO omits access_token from its own fields= list, so this
            // stays null for them (Jackson leaves an absent JSON field null) — additive, no
            // behavior change for FacebookPageClient#resolveConnectedInstagram.
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("instagram_business_account") InstagramBusinessAccount instagramBusinessAccount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstagramBusinessAccount(
            String id,
            String username,
            @JsonProperty("followers_count") Long followersCount) {}
}
