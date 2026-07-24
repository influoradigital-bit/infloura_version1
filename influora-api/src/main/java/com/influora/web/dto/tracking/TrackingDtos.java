package com.influora.web.dto.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
     *
     * <p>[BUG FIX B18, feature-audit-brand-creator-2026-07-23.md] All four fields were previously
     * unvalidated. A request missing (or blank) {@code baseUrl} reached {@code
     * CampaignLinkService#buildAndSaveTrackingLink} -&gt; {@code buildTrackingUrl}, which calls
     * {@code baseUrl.contains("?")} with no null check — an unhandled {@code
     * NullPointerException} that fell through to {@code GlobalExceptionHandler}'s generic {@code
     * Exception} handler as a bare {@code 500 INTERNAL_ERROR} instead of a clean {@code 400}. Every
     * other brand-facing {@code Create*Request} record in this codebase (e.g. {@code
     * DealDtos.CreateDealRequest}) validates required string fields with {@code @NotBlank}; this
     * record simply never got that treatment. {@code @NotBlank} + {@code @Valid} on the controller
     * parameter (see {@code CampaignTrackingController#createTrackingLink}) now rejects a
     * missing/blank field with a validation {@code 400} before the service is ever called.
     *
     * <p>[SECURITY, Kabir red-team] {@code baseUrl} additionally carries an http/https scheme
     * allowlist via {@code @Pattern} — a defense-in-depth layer only. The authoritative gate is
     * {@code CampaignLinkService#validateBaseUrl}, which runs in the service so it is enforced
     * regardless of caller; without it a brand could persist {@code javascript:}/{@code data:}
     * (or any non-http(s)) value into {@code UtmCampaign.fullTrackingUrl}, later served back out
     * via the public {@code GET /track/click/{id}} redirect and the creator coupon-card href — a
     * stored open-redirect / stored-XSS primitive.
     */
    public record CreateTrackingLinkRequest(
            @NotBlank String collaborationId,
            @NotBlank String creatorProfileId,
            @NotBlank @Pattern(regexp = "^(?i)https?://\\S+$", message = "baseUrl must be an http or https URL")
                    String baseUrl,
            @NotBlank String platform) {}

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
