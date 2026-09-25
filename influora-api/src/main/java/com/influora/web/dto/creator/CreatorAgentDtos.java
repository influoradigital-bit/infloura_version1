package com.influora.web.dto.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.10, B6) — the creator-typed rate card shown on the
     * public media kit when {@code rate_card_shareable} is on.
     *
     * <p>Every component is a <b>String</b>, not a number, and that is deliberate: these are the
     * figures the creator typed to display ("8,000", "12,500"), not money the platform computes or
     * compares. They are never the private floors — a floor is a {@code BigDecimal} the negotiation
     * logic reasons about, a rate-card entry is a label. The {@code @Pattern} restricts each to
     * digits and commas so a creator cannot smuggle prose (or a script) onto a public page through
     * this field.
     */
    public record RateCardDto(
            @JsonProperty("reel") @Pattern(regexp = "^[0-9,]{0,12}$") String reel,
            @JsonProperty("story_set") @Pattern(regexp = "^[0-9,]{0,12}$") String storySet,
            @JsonProperty("post") @Pattern(regexp = "^[0-9,]{0,12}$") String post) {}

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
            @JsonProperty("consent_version") String consentVersion,
            /** B6 (SPEC.md &sect;3.10) — whether {@link #rateCard} may appear on the public media kit. */
            @JsonProperty("rate_card_shareable") boolean rateCardShareable,
            /**
             * B6 — the creator-typed rate card, or null when she has never set one. Null and
             * omitted (NON_NULL) rather than an empty {@link RateCardDto}, so the settings UI can
             * tell "never set" from "set to blanks".
             */
            @JsonProperty("rate_card") RateCardDto rateCard,
            /** B6 (SPEC.md &sect;2.9) — read-only here; the holdout is assigned once at row creation. */
            @JsonProperty("negotiation_holdout") boolean negotiationHoldout,
            /** SPEC.md &sect;3.7 — read-only; incremented by the draft-approval path, never by PUT. */
            @JsonProperty("approved_draft_count") int approvedDraftCount,
            /**
             * SPEC.md &sect;3.7 — COMPUTED in {@code CreatorAgentPreferencesService.toResponse},
             * never stored: {@code approvedDraftCount >= 10} AND consent at least 7 days old AND
             * still at approval level 0 AND never yet prompted. Read-only; the PUT request type
             * omits it.
             */
            @JsonProperty("level_up_eligible") boolean levelUpEligible,
            /**
             * V76 — the phone the creator films on, creator-typed free text, or null (omitted)
             * when she has not said. Set only via {@code PUT /creator/agent-preferences/phone}
             * ({@link UpdatePhoneModelRequest}); the full-replace PUT never touches it.
             */
            @JsonProperty("phone_model") String phoneModel,
            /**
             * Goal memory (V20260925150000) -- the "My goals" chips, as the fixed codes the
             * creator tapped; null (omitted) when not told. Set only via {@code PUT
             * /creator/agent-preferences/content-goal} ({@link UpdateContentGoalRequest}); the
             * full-replace PUT never touches them.
             */
            @JsonProperty("content_goal") String contentGoal,
            @JsonProperty("weekly_time_band") String weeklyTimeBand,
            /** Goal memory -- equipment codes, always present ({@code []} when none saved). */
            @JsonProperty("equipment") List<String> equipment,
            /** Goal memory -- "rather not" codes, always present ({@code []} when none saved). */
            @JsonProperty("content_dislikes") List<String> contentDislikes) {

        /**
         * V76 shape (with the phone, without goal memory), kept so the call sites that build a
         * response with a phone but no goals keep compiling; the goal fields are "not told".
         */
        public PreferencesResponse(
                BigDecimal reelFloor,
                BigDecimal storySetFloor,
                BigDecimal postFloor,
                String floorCurrency,
                List<String> excludedCategories,
                List<String> blockedBrands,
                int approvalLevel,
                String creatorLanguage,
                String brandTone,
                Integer workingHoursStart,
                Integer workingHoursEnd,
                String workingHoursTimezone,
                List<Integer> workingDays,
                Integer weeklySponsoredLimit,
                boolean represented,
                String agencyName,
                boolean consentAccepted,
                String consentVersion,
                boolean rateCardShareable,
                RateCardDto rateCard,
                boolean negotiationHoldout,
                int approvedDraftCount,
                boolean levelUpEligible,
                String phoneModel) {
            this(reelFloor, storySetFloor, postFloor, floorCurrency, excludedCategories, blockedBrands,
                    approvalLevel, creatorLanguage, brandTone, workingHoursStart, workingHoursEnd,
                    workingHoursTimezone, workingDays, weeklySponsoredLimit, represented, agencyName,
                    consentAccepted, consentVersion, rateCardShareable, rateCard, negotiationHoldout,
                    approvedDraftCount, levelUpEligible, phoneModel, null, null, List.of(), List.of());
        }

        /**
         * Pre-V76 shape, kept so the many call sites that build a response without a phone (test
         * fixtures, mostly) keep compiling; {@code phoneModel} is null, i.e. "not told".
         */
        public PreferencesResponse(
                BigDecimal reelFloor,
                BigDecimal storySetFloor,
                BigDecimal postFloor,
                String floorCurrency,
                List<String> excludedCategories,
                List<String> blockedBrands,
                int approvalLevel,
                String creatorLanguage,
                String brandTone,
                Integer workingHoursStart,
                Integer workingHoursEnd,
                String workingHoursTimezone,
                List<Integer> workingDays,
                Integer weeklySponsoredLimit,
                boolean represented,
                String agencyName,
                boolean consentAccepted,
                String consentVersion,
                boolean rateCardShareable,
                RateCardDto rateCard,
                boolean negotiationHoldout,
                int approvedDraftCount,
                boolean levelUpEligible) {
            this(reelFloor, storySetFloor, postFloor, floorCurrency, excludedCategories, blockedBrands,
                    approvalLevel, creatorLanguage, brandTone, workingHoursStart, workingHoursEnd,
                    workingHoursTimezone, workingDays, weeklySponsoredLimit, represented, agencyName,
                    consentAccepted, consentVersion, rateCardShareable, rateCard, negotiationHoldout,
                    approvedDraftCount, levelUpEligible, null, null, null, List.of(), List.of());
        }
    }

    /**
     * Goal memory (Meera intelligence v1) -- {@code PUT /creator/agent-preferences/content-goal}.
     * The chips send their WHOLE state on every tap, and this replaces all four goal fields at
     * once: null (or an empty list) clears that field. Every value must be one of the fixed codes
     * in {@code ContentGoalCodes}; an unknown code is a 400 and nothing is written. Its own
     * request, not fields on {@link UpdatePreferencesRequest}, so the settings page's full-replace
     * PUT cannot wipe it.
     */
    public record UpdateContentGoalRequest(
            @JsonProperty("content_goal") @Size(max = 20) String contentGoal,
            @JsonProperty("weekly_time_band") @Size(max = 12) String weeklyTimeBand,
            @JsonProperty("equipment") @Size(max = 10) List<String> equipment,
            @JsonProperty("content_dislikes") @Size(max = 10) List<String> contentDislikes) {}

    /**
     * V76 — {@code PUT /creator/agent-preferences/phone}: the phone the creator films on, so Meera
     * can give camera settings that fit it. Null or blank clears it (stored as null after a trim).
     * Its own request, not a field on {@link UpdatePreferencesRequest}, so the settings page's
     * full-replace PUT (which does not send it) cannot wipe it.
     */
    public record UpdatePhoneModelRequest(@JsonProperty("phone_model") @Size(max = 80) String phoneModel) {}

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
            @JsonProperty("agency_name") String agencyName,
            /** B6 (SPEC.md &sect;3.10) — opting out nulls {@code rateCardJson} on the entity, not merely hides it. */
            @JsonProperty("rate_card_shareable") Boolean rateCardShareable,
            /** B6 — the creator-typed card; {@code @Valid} so each component's {@code @Pattern} is actually enforced. */
            @JsonProperty("rate_card") @Valid RateCardDto rateCard) {}

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
