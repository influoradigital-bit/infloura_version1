package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorCouponService;
import com.influora.web.dto.creatorcoupon.CreatorCouponDtos.CreatorCouponListItem;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task #28 (P0-V3, Creator Week 4) — {@code GET /creator/coupons}. Read-only, self-scoped via
 * {@code CreatorContextService} inside {@link CreatorCouponService}; brand-driven creation stays on
 * {@code CampaignTrackingController}.
 */
@RestController
@RequestMapping("/creator/coupons")
public class CreatorCouponController {

    private final CreatorCouponService creatorCouponService;

    public CreatorCouponController(CreatorCouponService creatorCouponService) {
        this.creatorCouponService = creatorCouponService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CreatorCouponListItem>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(creatorCouponService.list(principal)));
    }
}
