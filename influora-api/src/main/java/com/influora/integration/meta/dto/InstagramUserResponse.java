package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /{ig-user-id}?fields=...} response — basic Instagram Business/Creator profile. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramUserResponse(
        String id,
        String username,
        String name,
        String biography,
        @JsonProperty("followers_count") Long followersCount,
        @JsonProperty("follows_count") Long followsCount,
        @JsonProperty("media_count") Long mediaCount,
        @JsonProperty("profile_picture_url") String profilePictureUrl,
        String website) {}
