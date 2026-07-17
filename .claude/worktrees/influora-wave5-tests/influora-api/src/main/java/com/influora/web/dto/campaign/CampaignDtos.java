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
