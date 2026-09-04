package com.influora.web.dto.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.2-2.7, A3/A6) — wire DTOs for {@code
 * /creator/agent-preferences/**}. Wire keys are snake_case throughout, matching SPEC.md's literal
 * response examples and the TS interface (SPEC.md 4.3) Ananya builds against — a deliberate
 * exception to this codebase's usual camelCase browser-DTO convention, same reasoning as {@code
 * MeeraContextDtos} adapting to a fixed external contract rather than the reverse.
 */
public final class CreatorAgentDtos {

    private CreatorAgentDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreferencesResponse(
            @JsonProperty("reel_floor") BigDecimal reelFloor,
            @JsonProperty("story_set_floor") BigDecimal storySetFloor,
            @JsonProperty("post_floor") BigDecimal postFloor,
            /** Gate fix round 2, item 3 (Priya Q8) — ISO 4217 code the three floors above are denominated in. */
            @JsonProperty("floor_currency") String floorCurrency,
            @JsonProperty("excluded_categories") List<String> excludedCategories,
            @JsonProperty("blocked_brands") List<String> blockedBrands,
            @JsonProperty("approval_level") int approvalLevel,
            @JsonProperty("creator_language") String creatorLanguage,
            @JsonProperty("brand_tone") String brandTone,
            @JsonProperty("working_hours_start") Integer workingHoursStart,
            @JsonProperty("working_hours_end") Integer workingHoursEnd,
            /** Gate fix round 2, item 3 (Priya Q8) — IANA zone id working_hours_start/end are in. */
            @JsonProperty("working_hours_timezone") String workingHoursTimezone,
            @JsonProperty("working_days") List<Integer> workingDays,
            @JsonProperty("weekly_sponsored_limit") Integer weeklySponsoredLimit,
            @JsonProperty("represented") boolean represented,
            @JsonProperty("agency_name") String agencyName,
            @JsonProperty("consent_accepted") boolean consentAccepted,
            /** Gate fix round 2, item 1 (Priya Q3) — which DPDP notice version consentAccepted was computed against. */
            @JsonProperty("consent_version") String consentVersion) {}

    /**
     * PUT request — SPEC.md 2.3. {@code represented=true} requires a non-blank {@code agencyName}
     * (validated in the service, not here — the requirement is cross-field).
     */
    public record UpdatePreferencesRequest(
            @JsonProperty("reel_floor") @DecimalMin("0") BigDecimal reelFloor,
            @JsonProperty("story_set_floor") @DecimalMin("0") BigDecimal storySetFloor,
            @JsonProperty("post_floor") @DecimalMin("0") BigDecimal postFloor,
            /** Gate fix round 2, item 3 (Priya Q8) — validated as an ISO 4217 code in the service. */
            @JsonProperty("floor_currency") @Size(min = 3, max = 3) String floorCurrency,
            @JsonProperty("excluded_categories") List<String> excludedCategories,
            @JsonProperty("blocked_brands") List<String> blockedBrands,
            @JsonProperty("approval_level") @Min(0) @Max(2) int approvalLevel,
            @JsonProperty("creator_language") @Size(min = 2, max = 10) String creatorLanguage,
            @JsonProperty("brand_tone") String brandTone,
            @JsonProperty("working_hours_start") @Min(0) @Max(23) Integer workingHoursStart,
            @JsonProperty("working_hours_end") @Min(0) @Max(23) Integer workingHoursEnd,
            /** Gate fix round 2, item 3 (Priya Q8) — validated as an IANA zone id in the service. */
            @JsonProperty("working_hours_timezone") String workingHoursTimezone,
            @JsonProperty("working_days") List<Integer> workingDays,
            @JsonProperty("weekly_sponsored_limit") @Min(0) Integer weeklySponsoredLimit,
            @JsonProperty("represented") boolean represented,
            @JsonProperty("agency_name") String agencyName) {}

    public record ConsentResponse(
            @JsonProperty("consent_accepted_at") Instant consentAcceptedAt,
            /** Gate fix round 2, item 1 (Priya Q3) — the version just recorded (always CURRENT_CONSENT_VERSION). */
            @JsonProperty("consent_version") String consentVersion) {}

    public record ConversationSummary(
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("last_message_at") Instant lastMessageAt,
            @JsonProperty("message_count") int messageCount) {}

    public record ConversationListResponse(@JsonProperty("conversations") List<ConversationSummary> conversations) {}

    public record ConversationExportMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("timestamp") Instant timestamp) {}

    public record ConversationExportResponse(
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("messages") List<ConversationExportMessage> messages) {}
}
