package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.enums.PlanFeature;
import com.influora.security.AuthPrincipal;
import com.influora.security.RequiresPlan;
import com.influora.service.CampaignTemplateService;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.CampaignTemplateResponse;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.DeleteResponse;
import com.influora.web.dto.campaigntemplate.CampaignTemplateDtos.SaveAsTemplateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B5 — Campaign Templates (Task 23). Brand-member-gated, same pattern as {@link
 * CampaignController}. SYSTEM templates are free to every tier; saving a CUSTOM template is
 * Pro-gated via {@link RequiresPlan} (feature {@link PlanFeature#CAMPAIGN_TEMPLATES}), enforced
 * live by {@code PlanGateInterceptor} — not a TODO stub, the plan-gate filter is already wired in
 * this codebase (see {@code PlanGateFilter}/{@code PlanGateWebConfig}).
 */
@RestController
@RequestMapping("/campaign-templates")
public class CampaignTemplateController {

    private final CampaignTemplateService campaignTemplateService;

    public CampaignTemplateController(CampaignTemplateService campaignTemplateService) {
        this.campaignTemplateService = campaignTemplateService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CampaignTemplateResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(campaignTemplateService.list(principal)));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<ApiResponse<CampaignTemplateResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String templateId) {
        return ResponseEntity.ok(ApiResponse.ok(campaignTemplateService.get(principal, templateId)));
    }

    @RequiresPlan(feature = PlanFeature.CAMPAIGN_TEMPLATES)
    @PostMapping
    public ResponseEntity<ApiResponse<CampaignTemplateResponse>> saveAsTemplate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SaveAsTemplateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(campaignTemplateService.saveAsTemplate(principal, body)));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<ApiResponse<DeleteResponse>> delete(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String templateId) {
        return ResponseEntity.ok(ApiResponse.ok(campaignTemplateService.delete(principal, templateId)));
    }
}
