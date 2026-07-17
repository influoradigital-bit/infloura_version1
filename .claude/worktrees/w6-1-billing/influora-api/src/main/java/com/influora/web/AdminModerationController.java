package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminModerationService;
import com.influora.web.dto.admin.AdminModerationDtos.ActionFlagRequest;
import com.influora.web.dto.admin.AdminModerationDtos.FlagDto;
import com.influora.web.dto.admin.AdminModerationDtos.PagedFlagsDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content moderation API for admin panel (P2-6: Vikram). Mounted at {@code /admin/moderation}
 * (full path {@code /api/v1/admin/moderation/...} given {@code
 * server.servlet.context-path=/api/v1} — same convention as every other {@code Admin*Controller},
 * see {@link AdminBrandController}'s class javadoc for the path-mismatch note that applies here
 * unchanged).
 *
 * <p>Path/verb shape matches {@code moderationApi} in {@code
 * src/admin/services/api-contracts.ts} (Priya) exactly: {@code listFlags} -> {@code GET
 * /admin/moderation/flags}, {@code actionFlag} -> {@code POST /admin/moderation/flags/{id}/action}.
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as every other {@code Admin*Controller}, to match {@code apiRequest()}'s
 * client contract (it wraps whatever JSON body it gets into {@code ApiResponse<T>} itself). See
 * {@link AdminBrandController} class javadoc for full explanation.
 *
 * <p><b>Security:</b> All endpoints require admin role ({@code SUPER_ADMIN}, {@code ADMIN}, or
 * {@code SUPPORT}) + MFA satisfaction via {@link
 * com.influora.service.admin.AdminContextService#requireRoleWithMfaSatisfied}. Role gate is inside
 * {@link AdminModerationService}, not {@code @PreAuthorize} annotations (no method-level security
 * is used anywhere in this codebase — manual service-layer checks are the established pattern, per
 * {@link com.influora.service.admin.AdminContextService} class javadoc).
 */
@RestController
@RequestMapping("/admin/moderation")
public class AdminModerationController {

    private final AdminModerationService adminModerationService;

    public AdminModerationController(AdminModerationService adminModerationService) {
        this.adminModerationService = adminModerationService;
    }

    /**
     * List pending content flags (PENDING status, oldest first). Backs {@code
     * src/admin/hooks/useFlagQueue.ts}.
     */
    @GetMapping("/flags")
    public PagedFlagsDto listFlags(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminModerationService.listFlags(principal, page, pageSize);
    }

    /**
     * Take action on a content flag. Supports: "REMOVE" (content removed, flag → ACTIONED),
     * "REJECT" (false positive, flag → REVIEWED), "WARN" (warning issued, flag → REVIEWED),
     * "ESCALATE" (P2+, not yet implemented). Backs {@code
     * src/admin/components/moderation/FlagQueue.tsx} action buttons.
     */
    @PostMapping("/flags/{id}/action")
    public FlagDto actionFlag(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @Valid @RequestBody ActionFlagRequest request) {
        return adminModerationService.actionFlag(principal, httpRequest, id, request);
    }
}
