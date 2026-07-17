package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.DashboardService;
import com.influora.web.dto.dashboard.DashboardDtos.ActionItem;
import com.influora.web.dto.dashboard.DashboardDtos.PipelineStage;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand dashboard read APIs (BACKEND-API-SPEC §33.6). Both endpoints are workspace-scoped: the
 * caller must be an authenticated BRAND member of the resolved workspace (enforced via
 * {@link BrandContextService}), same guard as the rest of the brand surface.
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final BrandContextService brandContext;

    public DashboardController(
            DashboardService dashboardService, BrandContextService brandContext) {
        this.dashboardService = dashboardService;
        this.brandContext = brandContext;
    }

    @GetMapping("/actions")
    public ApiResponse<List<ActionItem>> actions(@AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(dashboardService.actions(workspace.getId()));
    }

    @GetMapping("/pipeline")
    public ApiResponse<List<PipelineStage>> pipeline(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return ApiResponse.ok(dashboardService.pipeline(workspace.getId()));
    }
}
