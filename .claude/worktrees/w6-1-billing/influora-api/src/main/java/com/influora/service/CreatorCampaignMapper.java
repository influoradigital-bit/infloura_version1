package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.BrandSummary;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.BudgetDto;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;

public final class CreatorCampaignMapper {

    private CreatorCampaignMapper() {}

    public static CreatorCampaignListItem toListItem(
            Campaign c, Workspace workspace, Collaboration existing) {
        return new CreatorCampaignListItem(
                c.getId(),
                c.getTitle(),
                c.getDescription(),
                toBrand(workspace),
                toBudget(c),
                JsonLists.stringListFromJson(c.getPlatformsJson()),
                JsonLists.stringListFromJson(c.getRequirementsJson()),
                c.getApplicationDeadline(),
                c.getStartDate(),
                c.getEndDate(),
                c.getMaxCollaborators(),
                existing != null ? existing.getStatus().name() : null,
                c.getCreatedAt());
    }

    public static CreatorCampaignDetailResponse toDetail(
            Campaign c, Workspace workspace, Collaboration existing) {
        return new CreatorCampaignDetailResponse(
                c.getId(),
                c.getTitle(),
                c.getDescription(),
                toBrand(workspace),
                JsonLists.stringListFromJson(c.getObjectivesJson()),
                toBudget(c),
                JsonLists.stringListFromJson(c.getPlatformsJson()),
                JsonLists.stringListFromJson(c.getContentTypesJson()),
                JsonLists.stringListFromJson(c.getRequirementsJson()),
                JsonLists.stringListFromJson(c.getHashtagsJson()),
                c.getBrandGuidelines(),
                c.getApplicationDeadline(),
                c.getStartDate(),
                c.getEndDate(),
                c.getMaxCollaborators(),
                existing != null ? existing.getStatus().name() : null,
                c.getCreatedAt());
    }

    private static BrandSummary toBrand(Workspace workspace) {
        if (workspace == null) {
            return null;
        }
        return new BrandSummary(workspace.getId(), workspace.getName(), workspace.getLogoUrl());
    }

    private static BudgetDto toBudget(Campaign c) {
        if (c.getBudgetMin() == null && c.getBudgetMax() == null) {
            return null;
        }
        return new BudgetDto(c.getBudgetMin(), c.getBudgetMax(), c.getCurrency());
    }
}
