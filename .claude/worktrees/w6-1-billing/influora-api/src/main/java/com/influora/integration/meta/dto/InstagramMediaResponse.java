package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code GET /{ig-user-id}/media?fields=...} — paged list of recent media with basic metrics. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramMediaResponse(List<MediaItem> data, Paging paging) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediaItem(
            String id,
            String caption,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("media_url") String mediaUrl,
            String permalink,
            String timestamp,
            @JsonProperty("like_count") Long likeCount,
            @JsonProperty("comments_count") Long commentsCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paging(Cursors cursors, String next) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cursors(String before, String after) {}
}
