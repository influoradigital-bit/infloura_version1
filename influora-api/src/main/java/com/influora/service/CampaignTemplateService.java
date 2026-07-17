package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.campaign.CampaignDtos.TargetAudienceDto;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.CampaignTemplateResponse;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.DeleteResponse;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.SaveAsTemplateRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B5 — Campaign Templates. SYSTEM templates (4 curated presets, seeded by
 * V20260714150000__campaign_templates.sql) are free and readable by every brand tier. CUSTOM
 * templates are workspace-owned, save is Pro-gated at the controller
 * ({@code @RequiresPlan(CAMPAIGN_TEMPLATES)}), and delete is restricted to the owning workspace.
 */
@Service
public class CampaignTemplateService {

    private final CampaignTemplateRepository templateRepository;
    private final CampaignRepository campaignRepository;
    private final BrandContextService brandContext;

    public CampaignTemplateService(
            CampaignTemplateRepository templateRepository,
            CampaignRepository campaignRepository,
            BrandContextService brandContext) {
        this.templateRepository = templateRepository;
        this.campaignRepository = campaignRepository;
        this.brandContext = brandContext;
    }

    @Transactional(readOnly = true)
    public List<CampaignTemplateResponse> list(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        List<CampaignTemplate> result = new ArrayList<>();
        result.addAll(templateRepository.findByScope(CampaignTemplateScope.SYSTEM));
        result.addAll(
                templateRepository.findByScopeAndWorkspaceId(
                        CampaignTemplateScope.CUSTOM, workspace.getId()));
        return result.stream().map(CampaignTemplateService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CampaignTemplateResponse get(AuthPrincipal principal, String templateId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        CampaignTemplate template = requireVisible(templateId, workspace.getId());
        return toResponse(template);
    }

    /**
     * Save the caller's own campaign as a new CUSTOM template. Pro-gating is enforced at the
     * controller via {@code @RequiresPlan(PlanFeature.CAMPAIGN_TEMPLATES)} — this method assumes
     * the gate already passed.
     */
    @Transactional
    public CampaignTemplateResponse saveAsTemplate(AuthPrincipal principal, SaveAsTemplateRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        Campaign source =
                campaignRepository
                        .findByIdAndWorkspaceId(req.campaignId(), workspace.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        CampaignTemplate template =
                CampaignTemplate.builder()
                        .id(Ulids.newUlid())
                        .name(req.name().trim())
                        .description(req.description())
                        .category(CampaignTemplateCategory.CUSTOM)
                        .scope(CampaignTemplateScope.CUSTOM)
                        .workspaceId(workspace.getId())
                        .createdBy(principal.getUserId())
                        .budgetMin(source.getBudgetMin())
                        .budgetMax(source.getBudgetMax())
                        .platformsJson(source.getPlatformsJson())
                        .contentTypesJson(source.getContentTypesJson())
                        .objectivesJson(source.getObjectivesJson())
                        .requirementsJson(source.getRequirementsJson())
                        .hashtagsJson(source.getHashtagsJson())
                        .targetAudienceJson(source.getTargetAudienceJson())
                        .brandGuidelines(source.getBrandGuidelines())
                        .build();

        templateRepository.save(template);
        return toResponse(template);
    }

    /**
     * Delete own CUSTOM template only. 403 if the caller's workspace doesn't own it, 400 if it's a
     * SYSTEM template (nobody deletes SYSTEM templates) — the one authz check that matters here
     * per the workflow doc's Kavya QA note.
     */
    @Transactional
    public DeleteResponse delete(AuthPrincipal principal, String templateId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());

        CampaignTemplate template =
                templateRepository
                        .findById(templateId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "TEMPLATE_NOT_FOUND", "Template not found", HttpStatus.NOT_FOUND));

        if (template.isSystem()) {
            throw new ApiException(
                    "SYSTEM_TEMPLATE_IMMUTABLE", "SYSTEM templates cannot be deleted", HttpStatus.BAD_REQUEST);
        }
        if (!workspace.getId().equals(template.getWorkspaceId())) {
            throw new ApiException(
                    "FORBIDDEN", "You do not own this template", HttpStatus.FORBIDDEN);
        }

        templateRepository.delete(template);
        return new DeleteResponse(true);
    }

    /** SYSTEM templates are visible to any workspace; CUSTOM templates only to their owner. */
    private CampaignTemplate requireVisible(String templateId, String workspaceId) {
        CampaignTemplate template =
                templateRepository
                        .findById(templateId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "TEMPLATE_NOT_FOUND", "Template not found", HttpStatus.NOT_FOUND));
        if (!template.isSystem() && !workspaceId.equals(template.getWorkspaceId())) {
            throw new ApiException("TEMPLATE_NOT_FOUND", "Template not found", HttpStatus.NOT_FOUND);
        }
        return template;
    }

    private static CampaignTemplateResponse toResponse(CampaignTemplate t) {
        return new CampaignTemplateResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getCategory(),
                t.getScope(),
                t.getWorkspaceId(),
                t.getCreatedBy(),
                t.getCampaignType(),
                t.getBudgetMin(),
                t.getBudgetMax(),
                JsonLists.stringListFromJson(t.getPlatformsJson()),
                JsonLists.stringListFromJson(t.getContentTypesJson()),
                JsonLists.stringListFromJson(t.getObjectivesJson()),
                JsonLists.stringListFromJson(t.getRequirementsJson()),
                JsonLists.stringListFromJson(t.getHashtagsJson()),
                JsonLists.objectFromJson(t.getTargetAudienceJson(), TargetAudienceDto.class),
                t.getBrandGuidelines(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
