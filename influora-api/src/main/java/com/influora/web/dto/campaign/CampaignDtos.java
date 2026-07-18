package com.influora.web.dto.campaign;

import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CampaignDtos {

    private CampaignDtos() {}

    public record BudgetDto(
            @NotNull @DecimalMin("0.01") BigDecimal min,
            @NotNull @DecimalMin("0.01") BigDecimal max,
            @NotBlank String currency) {}

    public record TimelineDto(LocalDate startDate, LocalDate endDate) {}

    public record TargetAudienceDto(
            AgeRangeDto ageRange,
            List<String> genders,
            List<String> locations,
            List<String> interests,
            List<String> languages) {}

    public record AgeRangeDto(Integer min, Integer max) {}

    /**
     * Hype campaign's defining config block (docs/FRONTEND-DESIGN-TASK.md; sent by
     * brand-new-hype-campaign.tsx as the {@code hype} key alongside {@code campaignType: 'HYPE'}).
     * Required only when {@code campaignType == HYPE} — {@link CampaignValidator#validateHypeConfig}
     * enforces that conditionally rather than via bean-validation annotations here, so a STANDARD
     * request that happens to omit or send a partial {@code hype} block is never rejected.
     * {@code currency} and {@code slotsFilled} are optional/lenient and default to "INR"/0 in
     * {@code CampaignService#normalizeHype}. {@code liveUntil} is kept as a raw ISO-8601 string
     * (not {@code Instant}) because the storage round-trip uses {@code JsonLists}'s plain,
     * un-configured {@code ObjectMapper} (no jackson-datatype-jsr310 module registered) — a typed
     * {@code Instant} would (de)serialize fine at the HTTP boundary but fail when persisted via
     * {@code JsonLists.toJsonObject}/{@code objectFromJson}.
     */
    public record HypeConfigDto(
            String sourceReelUrl,
            String audioTrack,
            String hashtag,
            List<String> formatLanes,
            BigDecimal perReelRate,
            String currency,
            Integer slotCap,
            Integer slotsFilled,
            String liveUntil) {}

    public record CampaignWriteRequest(
            @NotBlank @Size(min = 5, max = 300) String title,
            String description,
            List<String> objectives,
            CampaignStatus status,
            /**
             * Wave D task D3 follow-up
             * (wiki/decisions/2026-07-07-d3-campaign-gating-scope.md): optional today — the
             * brand-facing form does not send this yet. Null/absent is treated as "not gated" (see
             * {@code CampaignService.create}); reuses the same {@link CampaignIntentType} taxonomy
             * as the AI-drafted path so both share one shared store-integration-required predicate.
             */
            CampaignIntentType campaignType,
            /** Only persisted/validated when {@code campaignType == HYPE}; ignored otherwise. */
            HypeConfigDto hype,
            @Valid @NotNull BudgetDto budget,
            @NotNull TimelineDto timeline,
            LocalDate applicationDeadline,
            List<String> platforms,
            List<String> contentTypes,
            List<String> requirements,
            List<String> hashtags,
            String brandGuidelines,
            Boolean isPrivate,
            Integer maxCollaborators,
            TargetAudienceDto targetAudience) {}

    /** Partial update — all fields optional. */
    public record CampaignPatchRequest(
            @Size(min = 5, max = 300) String title,
            String description,
            List<String> objectives,
            CampaignStatus status,
            /**
             * {@code campaignType} itself is immutable after creation (not part of this DTO) — only
             * the HYPE config block can be revised post-creation, and only for a campaign that was
             * already created as HYPE (see {@code CampaignService.update}).
             *
             * <p><b>[G3 fix]</b> Unlike the list fields below (platforms/hashtags/targetAudience,
             * full-replace-if-present), this is merged field-by-field onto the currently-stored
             * config ({@code CampaignService#mergeHype}) — a caller only needs to send the
             * sub-fields it's actually changing; any field left {@code null} here keeps its
             * previously-stored value instead of being wiped. Send {@code null} for a whole {@code
             * hype} block to leave the entire config untouched.
             */
            HypeConfigDto hype,
            @Valid BudgetDto budget,
            @Valid TimelineDto timeline,
            LocalDate applicationDeadline,
            List<String> platforms,
            List<String> contentTypes,
            List<String> requirements,
            List<String> hashtags,
            String brandGuidelines,
            Boolean isPrivate,
            Integer maxCollaborators,
            TargetAudienceDto targetAudience) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignResponse(
            String id,
            String workspaceId,
            String title,
            String description,
            List<String> objectives,
            CampaignStatus status,
            CampaignIntentType campaignType,
            HypeConfigDto hype,
            BudgetDto budget,
            TimelineDto timeline,
            LocalDate applicationDeadline,
            List<String> platforms,
            List<String> contentTypes,
            List<String> requirements,
            List<String> hashtags,
            String brandGuidelines,
            boolean isPrivate,
            Integer maxCollaborators,
            TargetAudienceDto targetAudience,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Integer collaboratorsCount,
            Integer activeCollaborations,
            Integer completedCollaborations,
            BigDecimal totalSpend) {}

    public record DuplicateResponse(String id) {}

    public record DeleteResponse(boolean ok) {}
}
