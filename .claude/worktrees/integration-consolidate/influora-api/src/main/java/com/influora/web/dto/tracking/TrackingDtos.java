package com.influora.web.dto.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Brand-facing DTOs for {@code CampaignTrackingController} — REST surface over the Phase 4
 * UTM/coupon tracking services ({@code CampaignLinkService}, {@code CouponCodeService}), which are
 * built and signed off (see {@code wiki/tech/REMAINING_WORK_PLAN.md} Wave A, task A1).
 *
 * <p>Response records only expose what a brand UI needs — the tracking URL and click/conversion
 * counters for UTM links, the code/discount/usage info for coupons — mirroring how {@code
 * AnalyticsDtos} shaped its brand-facing responses (no internal-only fields like raw entity ids
 * beyond what identifies the row, no leaking of unrelated internal state).
 *
 * <p><b>Not this file's scope:</b> the public webhook/redemption DTOs ({@code
 * ConversionWebhookController}'s request/response shapes) live in a separate {@code
 * web/dto/tracking/WebhookDtos.java}, built in parallel as Wave A task A2. This file only carries
 * the brand-authenticated tracking-link/coupon-generation surface.
 */
public final class TrackingDtos {

    private TrackingDtos() {}

    // ------------------------------------------------------------------------------------------
    // Tracking links (UTM) — POST/GET /campaigns/{campaignId}/tracking-links
    // ------------------------------------------------------------------------------------------

    /**
     * Request body for {@code POST /campaigns/{campaignId}/tracking-links}. {@code campaignId}
     * itself comes from the path, not this body.
     */
    public record CreateTrackingLinkRequest(
            String collaborationId, String creatorProfileId, String baseUrl, String platform) {}

    /** Brand-facing view of a {@code UtmCampaign} row — created by or already existing for the campaign. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrackingLinkResponse(
            String id,
            String campaignId,
            String collaborationId,
            String creatorProfileId,
            String baseUrl,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String fullTrackingUrl,
            String shortUrl,
            long clickCount,
            long uniqueVisitors,
            long conversionCount,
            BigDecimal revenueAttributed,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt) {}

    public record TrackingLinkListResponse(List<TrackingLinkResponse> trackingLinks) {}

    // ------------------------------------------------------------------------------------------
    // Coupons — POST/GET /campaigns/{campaignId}/coupons
    // ------------------------------------------------------------------------------------------

    /**
     * Request body for {@code POST /campaigns/{campaignId}/coupons}. {@code usageLimit}/{@code
     * expiresAt} are nullable — {@code null} means unlimited usage / no expiry, matching {@code
     * CouponCodeService#addCreatorToCampaign}'s contract.
     */
    public record CreateCouponRequest(
            String creatorProfileId,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {}

    /** Brand-facing view of a {@code CouponCode} row — created by or already existing for the campaign. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CouponResponse(
            String id,
            String campaignId,
            String creatorProfileId,
            String code,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            int usageCount,
            Instant expiresAt,
            Instant createdAt) {}

    public record CouponListResponse(List<CouponResponse> coupons) {}
}
