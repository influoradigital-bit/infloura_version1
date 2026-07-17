package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /{page-id}?fields=...} — Facebook Page profile + engagement counters. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacebookPageResponse(
        String id,
        String name,
        String category,
        @JsonProperty("fan_count") Long fanCount,
        @JsonProperty("followers_count") Long followersCount,
        @JsonProperty("about") String about,
        @JsonProperty("link") String link) {}
