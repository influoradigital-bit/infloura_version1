package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.Campaign;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import com.influora.web.dto.campaign.CampaignDtos.TargetAudienceDto;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;

public final class CampaignMapper {

    private CampaignMapper() {}

    public static CampaignResponse toResponse(Campaign c, CampaignMetrics metrics) {
        BudgetDto budget = null;
        if (c.getBudgetMin() != null && c.getBudgetMax() != null) {
            budget = new BudgetDto(c.getBudgetMin(), c.getBudgetMax(), c.getCurrency());
        }
        TimelineDto timeline = null;
        if (c.getStartDate() != null && c.getEndDate() != null) {
            timeline = new TimelineDto(c.getStartDate(), c.getEndDate());
        }
        TargetAudienceDto audience =
                JsonLists.objectFromJson(c.getTargetAudienceJson(), TargetAudienceDto.class);
        HypeConfigDto hype = JsonLists.objectFromJson(c.getHypeConfigJson(), HypeConfigDto.class);

        return new CampaignResponse(
                c.getId(),
                c.getWorkspaceId(),
                c.getTitle(),
                c.getDescription(),
                JsonLists.stringListFromJson(c.getObjectivesJson()),
                c.getStatus(),
                c.getCampaignType(),
                hype,
                budget,
                timeline,
                c.getApplicationDeadline(),
                JsonLists.stringListFromJson(c.getPlatformsJson()),
                JsonLists.stringListFromJson(c.getContentTypesJson()),
                JsonLists.stringListFromJson(c.getRequirementsJson()),
                JsonLists.stringListFromJson(c.getHashtagsJson()),
                c.getBrandGuidelines(),
                c.isPrivate(),
                c.getMaxCollaborators(),
                audience,
                c.getCreatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                metrics.collaboratorsCount(),
                metrics.activeCollaborations(),
                metrics.completedCollaborations(),
                metrics.totalSpend());
    }

    public record CampaignMetrics(
            int collaboratorsCount,
            int activeCollaborations,
            int completedCollaborations,
            BigDecimal totalSpend) {

        public static CampaignMetrics empty() {
            return new CampaignMetrics(0, 0, 0, BigDecimal.ZERO);
        }
    }
}
