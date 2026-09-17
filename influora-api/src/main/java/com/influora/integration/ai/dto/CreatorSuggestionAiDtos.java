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
 * <p><b>F-0825 — {@code message_source} is MAPPED, not discarded. Do not "simplify" it away
 * again.</b> This javadoc used to say the field was read-and-discard because Java derived its own
 * {@code NudgeMessageSource} from whether the HTTP call succeeded. That was wrong, and it was a
 * security defect, not a cosmetic one: {@code influora-ai}'s own internal fallback
 * ({@code app/prompt/creator_suggestion.py}'s {@code fallback_message}, fired by the route on
 * spend-gate trip, provider error or model-output-validation failure) returns HTTP <b>200</b> with
 * {@code success: true} and {@code message_source: "FALLBACK"}. Deriving the label from "did the
 * call succeed" therefore stamped every python-side fallback as {@code AI} in
 * {@code creator_nudge_log}, destroying the audit trail exactly where it mattered most — the rows
 * whose copy no model ever vetted. The route sends this field on BOTH branches
 * ({@code app/routes/creator_suggestion.py} lines 248/256 FALLBACK, 343/351 AI), so it is always
 * present; {@code CreatorNudgeService.messageSourceOf} still fails closed to {@code FALLBACK} on a
 * missing or unrecognized value rather than assuming {@code AI}.
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
        public record Data(
                String headline,
                @JsonProperty("content_idea") String contentIdea,
                /* Raw wire value ("AI" | "FALLBACK"), deliberately NOT bound straight to the
                 * NudgeMessageSource enum: Jackson would throw on an unrecognized value and this
                 * client's contract is that it never throws. The string is interpreted (and failed
                 * closed) by CreatorNudgeService.messageSourceOf. */
                @JsonProperty("message_source") String messageSource) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(Detail detail) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String code, String message) {}
    }
}
