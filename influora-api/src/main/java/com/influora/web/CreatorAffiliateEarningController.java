package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.AffiliateEarningsService;
import com.influora.web.dto.affiliate.AffiliateEarningDtos.CreatorAffiliateEarningsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V-GA-7 — {@code GET /creator/affiliate-earnings}. Principal-scoped read of Wave D affiliate
 * commission rows + SETTLED-vs-pending summary. Identity resolved inside {@link
 * AffiliateEarningsService} via {@link com.influora.service.CreatorContextService}.
 */
@RestController
@RequestMapping("/creator/affiliate-earnings")
public class CreatorAffiliateEarningController {

    private final AffiliateEarningsService affiliateEarningsService;

    public CreatorAffiliateEarningController(AffiliateEarningsService affiliateEarningsService) {
        this.affiliateEarningsService = affiliateEarningsService;
    }

    /** CR-83 — page/limit added; no page/limit param defaults to page 0, the service's own default size. */
    @GetMapping
    public ResponseEntity<ApiResponse<CreatorAffiliateEarningsResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.ok(affiliateEarningsService.listForCreator(principal, page, limit)));
    }
}
