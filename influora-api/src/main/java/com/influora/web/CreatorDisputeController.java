package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.DealService;
import com.influora.service.DisputeService;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.dispute.DisputeDtos.DisputeListItemResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2-14 — creator-scoped dispute list with display fields (campaign name, brand name). Replaces
 * api.ts client-side derivation from {@code /deals}. Scoped to the calling creator via
 * {@link DisputeService#listDisplayForCreator}.
 */
@RestController
@RequestMapping("/creator/disputes")
public class CreatorDisputeController {

    private final DisputeService disputeService;
    private final DealService dealService;

    public CreatorDisputeController(DisputeService disputeService, DealService dealService) {
        this.disputeService = disputeService;
        this.dealService = dealService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DisputeListItemResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(disputeService.listDisplayForCreator(principal)));
    }

    /**
     * CR-80 — deals eligible for a new dispute (funded escrow, not already disputed/completed/
     * cancelled), computed server-side via {@link DealService#listEligibleForDispute}. Replaces
     * the client fetching {@code GET /deals?status=all} (the creator's entire deal history) and
     * filtering in JS.
     */
    @GetMapping("/eligible-deals")
    public ResponseEntity<ApiResponse<List<DealResponse>>> eligibleDeals(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.listEligibleForDispute(principal)));
    }
}
