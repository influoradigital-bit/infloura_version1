package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.tracking.CampaignTrackingService;
import com.influora.web.dto.tracking.TrackingDtos.CouponListResponse;
import com.influora.web.dto.tracking.TrackingDtos.CouponResponse;
import com.influora.web.dto.tracking.TrackingDtos.CreateCouponRequest;
import com.influora.web.dto.tracking.TrackingDtos.CreateTrackingLinkRequest;
import com.influora.web.dto.tracking.TrackingDtos.TrackingLinkListResponse;
import com.influora.web.dto.tracking.TrackingDtos.TrackingLinkResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing REST surface over the Phase 4 UTM/coupon tracking services (Wave A, task A1 —
 * {@code wiki/tech/REMAINING_WORK_PLAN.md}). The underlying services ({@code CampaignLinkService},
 * {@code CouponCodeService}) are done and signed off; this controller + {@code
 * CampaignTrackingService} give them a REST contract so a brand can generate a tracking link or
 * coupon and list what already exists for a campaign.
 *
 * <p><b>Security (load-bearing — Kabir review):</b> every method resolves the caller's workspace
 * via {@link BrandContextService#requireBrandWorkspace(AuthPrincipal)} and passes it into {@code
 * CampaignTrackingService}, which either delegates to a service that already resolve-then-scopes
 * the campaign ({@code createTrackingLink}/{@code createCoupon}), or performs its own
 * resolve-then-scope / workspace-scoped-finder check for the two list endpoints — see {@code
 * CampaignTrackingService} javadoc. No method here ever passes a bare, unauthorized {@code
 * campaignId} to a repository.
 *
 * <p><b>Not this controller's scope:</b> the public webhook/redemption endpoints ({@code
 * POST /webhooks/redemption}, conversion pixel/redirect) are a separate controller, {@code
 * ConversionWebhookController}, built in parallel as Wave A task A2 — deliberately kept out of
 * this file so there is no file collision between the two tasks.
 */
@RestController
@RequestMapping("/campaigns/{campaignId}")
public class CampaignTrackingController {

    private final CampaignTrackingService campaignTrackingService;
    private final BrandContextService brandContextService;

    public CampaignTrackingController(
            CampaignTrackingService campaignTrackingService, BrandContextService brandContextService) {
        this.campaignTrackingService = campaignTrackingService;
        this.brandContextService = brandContextService;
    }

    /** Create (or return the existing) UTM tracking link for a creator's campaign participation. */
    @PostMapping("/tracking-links")
    public ResponseEntity<ApiResponse<TrackingLinkResponse>> createTrackingLink(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String campaignId,
            @RequestBody CreateTrackingLinkRequest request) {
        String workspaceId = brandContextService.requireBrandWorkspace(principal).getId();
        TrackingLinkResponse response =
                campaignTrackingService.createTrackingLink(
                        workspaceId,
                        campaignId,
                        request.collaborationId(),
                        request.creatorProfileId(),
                        request.baseUrl(),
                        request.platform());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** List every UTM tracking link for a campaign the caller's workspace owns. */
    @GetMapping("/tracking-links")
    public ResponseEntity<ApiResponse<TrackingLinkListResponse>> listTrackingLinks(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        String workspaceId = brandContextService.requireBrandWorkspace(principal).getId();
        var links = campaignTrackingService.listTrackingLinks(workspaceId, campaignId);
        return ResponseEntity.ok(ApiResponse.ok(new TrackingLinkListResponse(links)));
    }

    /** Create (or return the existing) coupon for a creator's participation in a campaign. */
    @PostMapping("/coupons")
    public ResponseEntity<ApiResponse<CouponResponse>> createCoupon(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String campaignId,
            @RequestBody CreateCouponRequest request) {
        String workspaceId = brandContextService.requireBrandWorkspace(principal).getId();
        CouponResponse response =
                campaignTrackingService.createCoupon(
                        workspaceId,
                        campaignId,
                        request.creatorProfileId(),
                        request.discountType(),
                        request.discountValue(),
                        request.usageLimit(),
                        request.expiresAt());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** List every coupon for a campaign the caller's workspace owns. */
    @GetMapping("/coupons")
    public ResponseEntity<ApiResponse<CouponListResponse>> listCoupons(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        String workspaceId = brandContextService.requireBrandWorkspace(principal).getId();
        var coupons = campaignTrackingService.listCoupons(workspaceId, campaignId);
        return ResponseEntity.ok(ApiResponse.ok(new CouponListResponse(coupons)));
    }
}
