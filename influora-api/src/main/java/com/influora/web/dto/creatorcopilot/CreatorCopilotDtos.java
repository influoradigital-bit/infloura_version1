package com.influora.web.dto.creatorcopilot;

/**
 * Wire DTOs for {@code GET/POST /creator/copilot/*} — FROZEN v1 shape, byte-for-byte matching
 * {@code wiki/build/creator-copilot-API-CONTRACT.md} §2's TypeScript {@code DailySuggestion}/
 * {@code CreatorSuggestionTodayResponse}. Plain camelCase field names (default Jackson
 * serialization, no {@code @JsonProperty} needed) since this is a browser-facing contract, unlike
 * the Spring-&gt;influora-ai internal DTOs which use snake_case.
 */
public final class CreatorCopilotDtos {

    private CreatorCopilotDtos() {}

    /** {@code expiresAt} is an ISO-8601 string — end of the creator's current UTC day, computed at
     * read time in {@code CreatorNudgeService.toDto}, never a stored column. */
    public record SuggestionDto(
            String id, String theme, String headline, String contentIdea, String expiresAt) {}

    /** {@code status} carries exactly one of {@code "pending_tagging" | "ready" |
     * "no_suggestion_today"} — the same literal strings {@code CreatorNudgeService.SuggestionResult}
     * uses internally, passed through as a plain String with no enum/mapping layer. */
    public record SuggestionTodayResponse(SuggestionDto suggestion, String status) {}
}
