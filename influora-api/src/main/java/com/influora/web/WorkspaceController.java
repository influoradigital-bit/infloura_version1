package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.security.AuthPrincipal;
import com.influora.service.WorkspaceService;
import com.influora.service.WorkspaceSlugService;
import com.influora.web.dto.onboarding.OnboardingDtos.SlugCheckResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceReadResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

    private final WorkspaceSlugService slugService;
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceSlugService slugService, WorkspaceService workspaceService) {
        this.slugService = slugService;
        this.workspaceService = workspaceService;
    }

    /**
     * §32.1 — slug availability for onboarding UI. Public; optional auth supplies excludeWorkspaceId.
     */
    @GetMapping("/slug-check")
    public ResponseEntity<ApiResponse<SlugCheckResponse>> slugCheck(
            @RequestParam String slug,
            @AuthenticationPrincipal AuthPrincipal principal) {
        String excludeId = principal != null ? principal.getWorkspaceId() : null;
        var result = slugService.checkAvailability(slug, excludeId);
        return ResponseEntity.ok(
                ApiResponse.ok(
                        new SlugCheckResponse(result.slug(), result.available(), result.suggestions())));
    }

    /**
     * L-9 — {@code GET /workspaces/me}. Brand Settings &gt; General &gt; Workspace Information (also
     * the read side of any other workspace-profile editor). Any active member may read — see
     * {@link WorkspaceService#getMyWorkspace}.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<WorkspaceReadResponse>> getMyWorkspace(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = workspaceService.getMyWorkspace(principal);
        return ResponseEntity.ok(ApiResponse.ok(toReadResponse(workspace)));
    }

    /**
     * L-9 — {@code PATCH /workspaces/me}. OWNER/ADMIN only (enforced in {@link
     * WorkspaceService#updateMyWorkspace}). Persists {@code name}/{@code email} (-&gt; {@code
     * billing_email})/{@code phone}/{@code websiteUrl} plus the pre-existing {@code industry}/{@code
     * companySize}/{@code description}/{@code logoUrl} fields.
     */
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<WorkspaceReadResponse>> updateMyWorkspace(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody WorkspaceUpdateRequest body) {
        Workspace workspace =
                workspaceService.updateMyWorkspace(
                        principal,
                        body.name(),
                        body.email(),
                        body.phone(),
                        body.industry(),
                        body.companySize(),
                        body.websiteUrl(),
                        body.description(),
                        body.logoUrl());
        return ResponseEntity.ok(ApiResponse.ok(toReadResponse(workspace)));
    }

    private static WorkspaceReadResponse toReadResponse(Workspace workspace) {
        return new WorkspaceReadResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getSlug(),
                workspace.getBillingEmail(),
                workspace.getPhone(),
                workspace.getIndustry(),
                workspace.getCompanySize(),
                workspace.getWebsiteUrl(),
                workspace.getLogoUrl(),
                workspace.getVerificationStatus() != null ? workspace.getVerificationStatus().name() : null);
    }
}
