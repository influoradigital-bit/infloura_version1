package com.influora.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * T-CONTENT-TOPICS -- {@code GET /admin/content-topics/preview}. This is the only check a row
 * typed into {@code content_topics} over a SQL client gets before it is (or is not) served to a
 * creator, so the response deliberately surfaces what an operator needs to trust a row: what
 * would actually be served today, what got dropped and why, which categories the match was run
 * against, and which parts of today's rows will not reach the creators they look like they
 * reach.
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

    /**
     * One part of a servable-today row's category that will not do what it looks like -- by id,
     * raw category, the part and the reason, never the row's title or angles.
     */
    public record UnmatchedCategoryView(
            @JsonProperty("id") Long id,
            @JsonProperty("category") String category,
            @JsonProperty("part") String part,
            @JsonProperty("reason") String reason) {}

    /**
     * {@code unmatched_categories} is NOT filtered by the previewed categories: it lists every
     * flagged part of every servable-today row (APPROVED, in window) -- not a known category, a
     * calendar category no creator group reaches, or a part next to {@code ALL} -- with {@code
     * unmatched_categories_note} saying what each reason means.
     */
    public record ContentTopicPreviewResponse(
            @JsonProperty("today") String today,
            @JsonProperty("resolved_categories") List<String> resolvedCategories,
            @JsonProperty("topics") List<TopicView> topics,
            @JsonProperty("dropped") List<DroppedTopicView> dropped,
            @JsonProperty("unmatched_categories") List<UnmatchedCategoryView> unmatchedCategories,
            @JsonProperty("unmatched_categories_note") String unmatchedCategoriesNote) {}
}
