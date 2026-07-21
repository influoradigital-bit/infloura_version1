package com.influora.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTOs for influora-ai's {@code POST /internal/creator-suggestion} (be-services-plan.md §4,
 * frozen request shape per {@code creator-copilot-ai-route-plan.md} §1.1 post-R1). Exactly 3
 * request fields — {@code creator_profile_id}, {@code theme_matched}, {@code trend_text} — no
 * {@code display_name} and no caption text of any kind: {@code caption_snippet} was removed by
 * Priya's R1 ruling on the AI-route plan specifically so no creator-authored free text reaches any
 * model in Tier-1. {@code theme_matched} is a closed-vocab value {@code ThemeMatchService.score}
 * already matched against (server-derived, never attacker-influenced); {@code trend_text} is the
 * one remaining untrusted field and is wrapped server-side (Python route responsibility).
 *
 * <p>Response {@code Data} tolerates an unrecognized {@code message_source} field the Python route
 * may also send ({@code @JsonIgnoreProperties(ignoreUnknown = true)}) — this Java client derives
 * its own {@code NudgeMessageSource} from whether the call succeeded at all, per {@code
 * CreatorNudgeService}, so that field is read-and-discard here rather than mapped.
 */
public final class CreatorSuggestionAiDtos {

    private CreatorSuggestionAiDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SuggestionRequest(
            @JsonProperty("creator_profile_id") String creatorProfileId,
            @JsonProperty("theme_matched") String themeMatched,
            @JsonProperty("trend_text") String trendText) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuggestionResponse(boolean success, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(String headline, @JsonProperty("content_idea") String contentIdea) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(Detail detail) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String code, String message) {}
    }
}
