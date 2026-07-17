package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.WorkspaceSlugService;
import com.influora.web.dto.onboarding.OnboardingDtos.SlugCheckResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

    private final WorkspaceSlugService slugService;

    public WorkspaceController(WorkspaceSlugService slugService) {
        this.slugService = slugService;
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
}
