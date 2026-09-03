package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /{ig-user-id}?fields=business_discovery.username({u}){...}} — read-only lookup of
 * ANOTHER professional Instagram account's public profile, keyed off the CALLER's own connected
 * IG business account (no permission from the target account needed). Required permission:
 * {@code instagram_basic}. {@code business_discovery} is {@code null} when Meta has no such
 * professional account for the requested username — callers must treat that as 404, never invent
 * data (T-CREATORCONNECT-0902).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessDiscoveryResponse(
        String id, @JsonProperty("business_discovery") BusinessDiscovery businessDiscovery) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusinessDiscovery(
            String id,
            String username,
            String name,
            String biography,
            @JsonProperty("profile_picture_url") String profilePictureUrl,
            @JsonProperty("followers_count") Long followersCount,
            @JsonProperty("media_count") Long mediaCount) {}
}
