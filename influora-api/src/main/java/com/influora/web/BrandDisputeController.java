package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.DisputeService;
import com.influora.web.dto.dispute.DisputeDtos.DisputeListItemResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-scoped dispute list (B7 / BRAND_EXECUTION_PLAN.md + P2-14). Read-only tracking view for
 * {@code src/pages/brand-disputes.tsx} — opening/resolving disputes stays on
 * {@code DealController}/{@code AdminDisputeController}. Scoped to the calling brand's workspace
 * via {@link DisputeService#listDisplayForBrand}, which resolves ownership through
 * {@code collaboration.campaign_id -> campaign.workspace_id} (same trust boundary as
 * {@code EscrowService}).
 *
 * <p>DP-2 (2026-08-09): removed the superseded {@code GET /brand/disputes} paginated route
 * (B7 original) — it had zero callers across both frontend client layers ({@code api.ts},
 * {@code meera-api.ts}) and was fully replaced by {@code /list} (P2-14).
 */
@RestController
@RequestMapping("/brand/disputes")
public class BrandDisputeController {

    private final DisputeService disputeService;

    public BrandDisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    /**
     * P2-14 — dispute list with display fields (campaign name, creator name). Replaces api.ts
     * client-side derivation from {@code /deals}.
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<DisputeListItemResponse>>> listDisplay(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(disputeService.listDisplayForBrand(principal)));
    }
}
