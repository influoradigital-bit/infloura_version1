package com.influora.web.dto.creatorcoupon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Creator-facing coupon read DTOs — Task #28 (P0-V3, Creator Week 4). */
public final class CreatorCouponDtos {

    private CreatorCouponDtos() {}

    /**
     * One coupon row for the authenticated creator. Mirrors the frontend {@code CreatorCouponResponse}
     * contract in {@code src/lib/api.ts} — enriched with campaign/brand display names and an optional
     * tracking URL when a UTM link exists for the same campaign/creator pair.
     *
     * <p><b>TRACK-3 (Priya ruling Q3 / GAP #1):</b> {@code trackingUrl} is the raw brand URL + UTM
     * params ({@code UtmCampaign#getFullTrackingUrl}) — it does NOT route through our click-counter,
     * so sharing it silently drops clicks. {@code redirectUrl} is the actual click-tracking link
     * ({@code GET /track/click/{utmCampaignId}}, see {@code ConversionWebhookController#trackClick})
     * that records a click before 302-ing to the brand site — this is what the frontend surfaces as
     * the share link ({@code coupon.redirectUrl}, see {@code CreatorCampaignCard.tsx}). {@code
     * trackingUrl} is kept for backward compatibility / display only; it is not deprecated by removal
     * since some callers may still want the raw destination for display purposes, but it must never
     * be used as the "share this link" URL. Both are null when no UTM link exists for the
     * campaign/creator pair.
     */
    public record CreatorCouponListItem(
            String id,
            String campaignId,
            String campaignName,
            String brandName,
            String code,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            int usageCount,
            Instant expiresAt,
            Instant createdAt,
            String trackingUrl,
            String redirectUrl) {}

    public record CreatorCouponListResponse(List<CreatorCouponListItem> coupons) {}
}
