package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.CampaignRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.tracking.CampaignTrackingService;
import com.influora.web.dto.tracking.TrackingDtos.CouponResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-side coupon issuing for a Festival Box sponsor (T-FESTIVALBOX-0905 phase 11).
 *
 * <h2>Why this exists at all</h2>
 *
 * The brand-facing endpoints ({@code CampaignTrackingController}, mounted at {@code
 * /campaigns/{campaignId}}) resolve the workspace via {@code
 * BrandContextService#requireBrandWorkspace}, which throws {@code WRONG_USER_TYPE} (403) unless the
 * JWT's {@code userType} is {@code BRAND}. An admin JWT is always {@code ADMIN}, and this codebase
 * has no impersonation / act-as mechanism, so <b>an admin could not issue a sponsor's coupons at
 * all</b> — no matter what the client sent. That made the whole Festival Box tracking chain inert
 * in practice: with no coupon registered, no redemption can ever attribute, so no sale and no
 * creator commission is ever recorded.
 *
 * <p>This service is the missing admin-scoped door to the SAME underlying logic. It deliberately
 * adds no new coupon behaviour: {@code CouponCodeService#addCreatorToCampaign} and {@code
 * #addBrandLevelCoupon} already accept {@code workspaceId} as a plain parameter and do their own
 * resolve-then-scope check against it, so all this does is establish which workspace an admin is
 * acting on and delegate. Duplicating the issuing logic here would have created a second place for
 * the brand-level-uniqueness rule and the code-collision retry to drift.
 *
 * <h2>How the workspace is resolved — and why not from the caller</h2>
 *
 * The campaign id alone determines the workspace: this looks the {@link Campaign} up by id and
 * reads {@code campaign.getWorkspaceId()} off the row, then hands that to the shared service. An
 * admin therefore cannot pass a mismatched (campaign, workspace) pair, because they never supply a
 * workspace at all — there is no parameter to get wrong. The shared service still re-checks
 * ownership with {@code findByIdAndWorkspaceId}, which is now trivially satisfied; that redundancy
 * is deliberate, so this class can never become the one caller that skips the scope check.
 *
 * <h2>What an admin is actually doing here</h2>
 *
 * <b>Influora mints the code; the brand does not supply it.</b> {@code CouponCodeService} generates
 * the string server-side ({@code generateCreatorCoupon} / {@code generateBrandCoupon}) and the
 * caller supplies only the discount terms. The operational consequence, which belongs in the admin
 * UI copy: after issuing here, someone must create that exact code in the sponsor's own Shopify /
 * WooCommerce store. A code that exists in our table but not in their store is dead at checkout —
 * it will sit on a public Festival Box page and fail for every shopper who tries it.
 */
@Service
public class AdminCampaignCouponService {

    private static final Logger log = LoggerFactory.getLogger(AdminCampaignCouponService.class);

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final CampaignRepository campaignRepository;
    private final CampaignTrackingService campaignTrackingService;

    public AdminCampaignCouponService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            CampaignRepository campaignRepository,
            CampaignTrackingService campaignTrackingService) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.campaignRepository = campaignRepository;
        this.campaignTrackingService = campaignTrackingService;
    }

    /**
     * Issue a coupon on a campaign, on behalf of the sponsor.
     *
     * <p>A null/blank {@code creatorProfileId} issues the campaign's single BRAND-LEVEL code (the
     * page-exclusive one shown on the Festival Box page); a non-blank one issues that creator's own
     * code. That branch belongs to {@code CampaignTrackingService#createCoupon}, which is the one
     * place it is decided — this does not re-implement it.
     *
     * <p>A second brand-level code on the same campaign surfaces as {@code BRAND_CODE_EXISTS}
     * (409) from the shared service, enforced both by a pre-check and by the {@code
     * uq_coupon_campaign_brand_level} constraint. It is deliberately allowed to propagate rather
     * than being caught and softened here: the admin needs to know the campaign already has one.
     */
    @Transactional
    public CouponResponse issue(
            AuthPrincipal principal,
            HttpServletRequest httpRequest,
            String campaignId,
            String creatorProfileId,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        String workspaceId = requireCampaign(campaignId).getWorkspaceId();

        CouponResponse response =
                campaignTrackingService.createCoupon(
                        workspaceId,
                        campaignId,
                        creatorProfileId,
                        discountType,
                        discountValue,
                        usageLimit,
                        expiresAt);

        // Audited AFTER the write, like every other admin mutation. Records the code itself: a
        // coupon is a money-bearing artifact and "which code did we hand this sponsor, and who
        // issued it" is exactly the question an audit trail has to answer later.
        adminAuditLogService.record(
                principal,
                httpRequest,
                "CREATE",
                "CAMPAIGN_COUPON",
                response.id(),
                null,
                Map.of(
                        "campaignId", campaignId,
                        "workspaceId", workspaceId,
                        "code", response.code(),
                        "brandLevel", creatorProfileId == null || creatorProfileId.isBlank()),
                null);

        log.info(
                "Admin issued {} coupon {} on campaign {}",
                creatorProfileId == null || creatorProfileId.isBlank() ? "brand-level" : "per-creator",
                response.id(),
                campaignId);
        return response;
    }

    /** Every coupon on a campaign, for the admin panel's list. */
    @Transactional(readOnly = true)
    public List<CouponResponse> list(AuthPrincipal principal, String campaignId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        String workspaceId = requireCampaign(campaignId).getWorkspaceId();
        return campaignTrackingService.listCoupons(workspaceId, campaignId);
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository
                .findById(campaignId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
    }
}
