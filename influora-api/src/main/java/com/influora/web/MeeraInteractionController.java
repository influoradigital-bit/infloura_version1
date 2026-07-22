package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.MeeraInteractionEventType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.web.dto.meera.MeeraInteractionDtos.OptionTappedRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing write for Phase 2 item 2.3's {@code OPTION_TAPPED} flywheel event (Meera:
 * Label-to-Moat build plan §2.3, Priya's Q3 ruling) — the tap is a pure browser event with no
 * other backend trigger. Normal authenticated brand-session auth, NOT the {@code
 * /internal/meera/*} dual-credential mesh gate — this is a real browser request, not a
 * Python-&gt;Spring internal call (contrast {@link MeeraInternalController#interactionLog}, the
 * {@code OPTIONS_PRESENTED} counterpart).
 *
 * <p><b>IDOR fix (Priya's explicit ruling):</b> the {@code workspaceId} path segment is NEVER
 * trusted for authorization — the service resolves the workspace from the AUTHENTICATED PRINCIPAL
 * via {@link BrandContextService#requireBrandWorkspace}, the same resolve-from-principal
 * discipline every other brand-session write in this codebase uses (e.g. {@link
 * BrandDeliverableController}). A brand cannot log — or, more importantly, ever read anything
 * back scoped to — another workspace's id merely by editing the URL.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/meera/interactions")
public class MeeraInteractionController {

    private final BrandContextService brandContext;
    private final MeeraInteractionLogService meeraInteractionLogService;

    public MeeraInteractionController(
            BrandContextService brandContext, MeeraInteractionLogService meeraInteractionLogService) {
        this.brandContext = brandContext;
        this.meeraInteractionLogService = meeraInteractionLogService;
    }

    @PostMapping("/option-tapped")
    public ResponseEntity<ApiResponse<Void>> optionTapped(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String workspaceId,
            @RequestBody OptionTappedRequest body) {
        // `workspaceId` (the path segment) is deliberately UNUSED for authorization — see class
        // javadoc. The workspace this event is recorded against comes only from the principal.
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        meeraInteractionLogService.record(
                workspace.getId(),
                body.sessionId(),
                MeeraInteractionEventType.OPTION_TAPPED,
                body.toolName(),
                body.recommended(),
                null,
                null,
                null);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
