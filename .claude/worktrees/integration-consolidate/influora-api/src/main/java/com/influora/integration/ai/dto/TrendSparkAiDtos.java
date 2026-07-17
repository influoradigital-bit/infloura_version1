package com.influora.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire DTOs for influora-ai's {@code POST /internal/trendspark/nudge} (T4 contract Ash's T8
 * implements against). Phrasing-only call — the AI invents no facts (schema lock §4): trend text,
 * campaign type, video titles/ids all come FROM this request, never from the model.
 *
 * <p><b>Note the response has no price field at all</b> — by construction, the numeric price shown
 * to the user always comes from {@code SnapsbyCatalogVideo.priceInr} (server-derived), never from
 * anything the model returns, regardless of what the model's free-text {@code message} might
 * mention. {@link com.influora.integration.ai.TrendSparkAiClient} additionally re-validates that
 * every returned {@code video_id} is a subset of the ids sent (the hallucination kill-switch).
 */
public final class TrendSparkAiDtos {

    private TrendSparkAiDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NudgeRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("brand_name") String brandName,
            @JsonProperty("campaign_type") String campaignType,
            @JsonProperty("trend_text") String trendText,
            String mode,
            List<VideoRef> videos) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VideoRef(
            @JsonProperty("video_id") String videoId, String title, List<String> themes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NudgeResponse(boolean success, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(
                String message, @JsonProperty("video_ids") List<String> videoIds) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(Detail detail) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String code, String message) {}
    }
}
