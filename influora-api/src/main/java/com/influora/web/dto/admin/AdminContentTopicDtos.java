package com.influora.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * T-CONTENT-TOPICS -- {@code GET /admin/content-topics/preview}. This is the only check a row
 * typed into {@code content_topics} over a SQL client gets before it is (or is not) served to a
 * creator, so the response deliberately surfaces all three things an operator needs to trust a
 * row: what would actually be served today, what got dropped and why, and which categories the
 * match was run against.
 */
public final class AdminContentTopicDtos {

    private AdminContentTopicDtos() {}

    public record TopicView(
            @JsonProperty("id") Long id,
            @JsonProperty("category") String category,
            @JsonProperty("title") String title,
            @JsonProperty("angles") List<String> angles,
            @JsonProperty("live_until") String liveUntil,
            @JsonProperty("sensitivity") String sensitivity) {}

    /** A matched-but-unsafe row -- by id and rejection category only, same F-0786 discipline as
     * the production log line ({@code ContentTopicService}), never the title or angle text. */
    public record DroppedTopicView(
            @JsonProperty("id") Long id, @JsonProperty("rejection_category") String rejectionCategory) {}

    public record ContentTopicPreviewResponse(
            @JsonProperty("today") String today,
            @JsonProperty("resolved_categories") List<String> resolvedCategories,
            @JsonProperty("topics") List<TopicView> topics,
            @JsonProperty("dropped") List<DroppedTopicView> dropped) {}
}
