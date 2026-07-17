package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcoupon.CreatorCouponDtos.CreatorCouponListItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #28 (P0-V3, Creator Week 4) — read-only list of the authenticated creator's coupon codes
 * across all campaigns. Identity always comes from {@link CreatorContextService#requireCreatorProfile}
 * — there is no creator-id path param to trust.
 */
@Service
public class CreatorCouponService {

    private final CreatorContextService creatorContext;
    private final CouponCodeRepository couponCodeRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UtmCampaignRepository utmCampaignRepository;

    /**
     * TRACK-3 (Priya ruling Q3 / GAP #1) — this API's own externally-reachable base URL (see
     * {@code influora.api.public-url} in application.yml / {@code API_PUBLIC_URL} env var), reused
     * (never hardcoded) to build the real click-tracking redirect link {@code
     * {publicBaseUrl}/track/click/{utmCampaignId}} — see {@code ConversionWebhookController#trackClick}.
     */
    private final String apiPublicUrl;

    public CreatorCouponService(
            CreatorContextService creatorContext,
            CouponCodeRepository couponCodeRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            UtmCampaignRepository utmCampaignRepository,
            @Value("${influora.api.public-url}") String apiPublicUrl) {
        this.creatorContext = creatorContext;
        this.couponCodeRepository = couponCodeRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.utmCampaignRepository = utmCampaignRepository;
        this.apiPublicUrl = apiPublicUrl;
    }

    @Transactional(readOnly = true)
    public List<CreatorCouponListItem> list(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        List<CouponCode> coupons =
                couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(profile.getId());

        Set<String> campaignIds =
                coupons.stream().map(CouponCode::getCampaignId).collect(Collectors.toSet());
        Map<String, Campaign> campaignsById =
                campaignRepository.findAllById(campaignIds).stream()
                        .collect(Collectors.toMap(Campaign::getId, Function.identity()));

        Set<String> workspaceIds =
                coupons.stream().map(CouponCode::getWorkspaceId).collect(Collectors.toSet());
        Map<String, Workspace> workspacesById =
                workspaceRepository.findAllById(workspaceIds).stream()
                        .collect(Collectors.toMap(Workspace::getId, Function.identity()));

        String creatorProfileId = profile.getId();
        return coupons.stream()
                .map(
                        coupon ->
                                toListItem(
                                        coupon,
                                        campaignsById.get(coupon.getCampaignId()),
                                        workspacesById.get(coupon.getWorkspaceId()),
                                        creatorProfileId))
                .toList();
    }

    private CreatorCouponListItem toListItem(
            CouponCode coupon, Campaign campaign, Workspace workspace, String creatorProfileId) {
        String campaignName = campaign != null ? campaign.getTitle() : "";
        String brandName = workspace != null ? workspace.getName() : "";
        var utmLink =
                utmCampaignRepository.findByCampaignIdAndCreatorProfileId(
                        coupon.getCampaignId(), creatorProfileId);
        String trackingUrl =
                utmLink
                        .map(utm -> Objects.requireNonNullElse(utm.getFullTrackingUrl(), utm.getShortUrl()))
                        .orElse(null);
        // TRACK-3 (Priya ruling Q3 / GAP #1) — the ACTUAL click-tracking link: GET
        // /track/click/{utmCampaignId} records the click then 302s to the brand site (see
        // ConversionWebhookController#trackClick). trackingUrl above is the raw brand URL and does
        // NOT count clicks — this is what the frontend must surface as the share link. Null when no
        // UTM link row exists for this campaign/creator pair (FE guards on it, same as trackingUrl).
        String redirectUrl =
                utmLink.map(utm -> apiPublicUrl + "/track/click/" + utm.getId()).orElse(null);

        return new CreatorCouponListItem(
                coupon.getId(),
                coupon.getCampaignId(),
                campaignName,
                brandName,
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getUsageLimit(),
                coupon.getUsageCount(),
                coupon.getExpiresAt(),
                coupon.getCreatedAt(),
                trackingUrl,
                redirectUrl);
    }
}
