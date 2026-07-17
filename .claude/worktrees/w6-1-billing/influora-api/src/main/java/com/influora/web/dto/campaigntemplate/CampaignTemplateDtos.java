package com.influora.web.dto.campaigntemplate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.web.dto.campaign.CampaignDtos.TargetAudienceDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DTOs for {@code CampaignTemplateController} — see wiki/tech/CAMPAIGN-TEMPLATES-WORKFLOW.md §1. */
public final class CampaignTemplateDtos {

    private CampaignTemplateDtos() {}

    /**
     * POST /campaign-templates — saves an existing (caller-owned) campaign's writable field set
     * as a new CUSTOM template. Field values are copied server-side from {@code campaignId}, never
     * accepted directly in the body, so a template can never diverge from a real campaign's actual
     * saved state.
     */
    public record SaveAsTemplateRequest(
            @NotBlank String campaignId,
            @NotBlank @Size(min = 1, max = 200) String name,
            String description) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignTemplateResponse(
            String id,
            String name,
            String description,
            CampaignTemplateCategory category,
            CampaignTemplateScope scope,
            String workspaceId,
            String createdBy,
            CampaignIntentType campaignType,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            List<String> platforms,
            List<String> contentTypes,
            List<String> objectives,
            List<String> requirements,
            List<String> hashtags,
            TargetAudienceDto targetAudience,
            String brandGuidelines,
            Instant createdAt,
            Instant updatedAt) {}

    public record DeleteResponse(boolean ok) {}
}
