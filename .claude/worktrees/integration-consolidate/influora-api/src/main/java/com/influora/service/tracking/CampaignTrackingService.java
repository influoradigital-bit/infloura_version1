package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.web.dto.tracking.TrackingDtos.CouponResponse;
import com.influora.web.dto.tracking.TrackingDtos.TrackingLinkResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand-facing REST-layer service backing {@code CampaignTrackingController} (Wave A, task A1 —
 * {@code wiki/tech/REMAINING_WORK_PLAN.md}). Wraps the already-built-and-signed-off Phase 4
 * services ({@code CampaignLinkService#createTrackingLink}, {@code
 * CouponCodeService#addCreatorToCampaign}) for the two POST endpoints, and maps their entities to
 * brand-facing DTOs (mirrors {@code AnalyticsService}'s controller/service split).
 *
 * <p><b>Workspace authorization on the two GET list endpoints — load-bearing, this is the new
 * surface this task adds.</b> {@code createTrackingLink}/{@code addCreatorToCampaign} already
 * enforce workspace ownership internally (resolve-then-scope against {@code
 * campaignRepository.findByIdAndWorkspaceId}), so the two POST methods here just delegate and map.
 * But the two list methods ({@link #listTrackingLinks}, {@link #listCoupons}) are NEW read paths
 * that don't exist on the underlying services — this class must itself resolve-then-scope the
 * campaign before listing anything, exactly like every other brand-facing entry point in this
 * codebase ({@code AnalyticsService}, {@code DeliverableMetricService#getCampaignAnalytics}).
 *
 * <ul>
 *   <li>{@link #listTrackingLinks}: {@code utm_campaigns} has no direct {@code workspace_id}
 *       column (see {@code UtmCampaignRepository} javadoc), so this resolves the campaign via
 *       {@code campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)} FIRST, then
 *       lists by the now-authorized {@code campaignId} via {@code UtmCampaignRepository
 *       #findByCampaignId} — resolve-then-scope, not a direct workspace-scoped finder.
 *   <li>{@link #listCoupons}: {@code coupon_codes} has a direct {@code workspace_id} column, so
 *       this uses {@code CouponCodeRepository#findByWorkspaceIdAndCampaignId} directly — the
 *       workspace check is expressed in the query itself, not a separate resolve step. A workspace
 *       that doesn't own {@code campaignId} simply gets an empty list back (same class of
 *       information non-leak as {@code CampaignLinkService}'s undifferentiated {@code
 *       CAMPAIGN_NOT_FOUND}), which is why {@link #listTrackingLinks} additionally checks campaign
 *       existence explicitly — see that method's javadoc for why the two list paths are shaped
 *       slightly differently but both are equally workspace-safe.
 * </ul>
 */
@Service
public class CampaignTrackingService {

    private final CampaignLinkService campaignLinkService;
    private final CouponCodeService couponCodeService;
    private final CampaignRepository campaignRepository;
    private final UtmCampaignRepository utmCampaignRepository;
    private final CouponCodeRepository couponCodeRepository;

    public CampaignTrackingService(
            CampaignLinkService campaignLinkService,
            CouponCodeService couponCodeService,
            CampaignRepository campaignRepository,
            UtmCampaignRepository utmCampaignRepository,
            CouponCodeRepository couponCodeRepository) {
        this.campaignLinkService = campaignLinkService;
        this.couponCodeService = couponCodeService;
        this.campaignRepository = campaignRepository;
        this.utmCampaignRepository = utmCampaignRepository;
        this.couponCodeRepository = couponCodeRepository;
    }

    /**
     * Create (or return the existing) tracking link. Delegates all authorization/validation to
     * {@code CampaignLinkService#createTrackingLink}, which already resolves-then-scopes the
     * campaign against {@code workspaceId}.
     */
    @Transactional
    public TrackingLinkResponse createTrackingLink(
            String workspaceId,
            String campaignId,
            String collaborationId,
            String creatorProfileId,
            String baseUrl,
            String platform) {
        UtmCampaign utm =
                campaignLinkService.createTrackingLink(
                        workspaceId, campaignId, collaborationId, creatorProfileId, baseUrl, platform);
        return toTrackingLinkResponse(utm);
    }

    /**
     * List every UTM tracking link for {@code campaignId}, but only if {@code workspaceId} actually
     * owns that campaign. {@code utm_campaigns} has no {@code workspace_id} column, so ownership is
     * proven by successfully resolving the campaign first — same {@code CAMPAIGN_NOT_FOUND} (404),
     * ownership-vs-nonexistence-undifferentiated discretion as the write path.
     */
    @Transactional(readOnly = true)
    public List<TrackingLinkResponse> listTrackingLinks(String workspaceId, String campaignId) {
        campaignRepository
                .findByIdAndWorkspaceId(campaignId, workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        return utmCampaignRepository.findByCampaignId(campaignId).stream()
                .map(CampaignTrackingService::toTrackingLinkResponse)
                .toList();
    }

    /**
     * Create (or return the existing) coupon for a creator's participation in a campaign.
     * Delegates all authorization/validation to {@code CouponCodeService#addCreatorToCampaign},
     * which already resolves-then-scopes the campaign against {@code workspaceId}.
     */
    @Transactional
    public CouponResponse createCoupon(
            String workspaceId,
            String campaignId,
            String creatorProfileId,
            String discountType,
            java.math.BigDecimal discountValue,
            Integer usageLimit,
            java.time.Instant expiresAt) {
        CouponCode coupon =
                couponCodeService.addCreatorToCampaign(
                        workspaceId, campaignId, creatorProfileId, discountType, discountValue, usageLimit, expiresAt);
        return toCouponResponse(coupon);
    }

    /**
     * List every coupon for {@code campaignId}, scoped directly to {@code workspaceId} via {@code
     * CouponCodeRepository#findByWorkspaceIdAndCampaignId} — {@code coupon_codes} has a direct
     * {@code workspace_id} column, so the workspace check is expressed in the query itself. A
     * campaign belonging to a different workspace simply yields an empty list, same as any other
     * workspace-scoped finder in this codebase.
     */
    @Transactional(readOnly = true)
    public List<CouponResponse> listCoupons(String workspaceId, String campaignId) {
        return couponCodeRepository.findByWorkspaceIdAndCampaignId(workspaceId, campaignId).stream()
                .map(CampaignTrackingService::toCouponResponse)
                .toList();
    }

    private static TrackingLinkResponse toTrackingLinkResponse(UtmCampaign utm) {
        return new TrackingLinkResponse(
                utm.getId(),
                utm.getCampaignId(),
                utm.getCollaborationId(),
                utm.getCreatorProfileId(),
                utm.getBaseUrl(),
                utm.getUtmSource(),
                utm.getUtmMedium(),
                utm.getUtmCampaign(),
                utm.getUtmContent(),
                utm.getFullTrackingUrl(),
                utm.getShortUrl(),
                utm.getClickCount(),
                utm.getUniqueVisitors(),
                utm.getConversionCount(),
                utm.getRevenueAttributed(),
                utm.getCreatedAt(),
                utm.getUpdatedAt(),
                utm.getExpiresAt());
    }

    private static CouponResponse toCouponResponse(CouponCode coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCampaignId(),
                coupon.getCreatorId(),
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getUsageLimit(),
                coupon.getUsageCount(),
                coupon.getExpiresAt(),
                coupon.getCreatedAt());
    }
}
