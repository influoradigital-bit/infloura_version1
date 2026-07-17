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
            @JsonProperty("instagram_business_account") InstagramBusinessAccount instagramBusinessAccount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstagramBusinessAccount(
            String id,
            String username,
            @JsonProperty("followers_count") Long followersCount) {}
}
