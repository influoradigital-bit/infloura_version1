package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks the full funnel: click -&gt; conversion -&gt; sale (Phase 4 UTM/Coupon Tracking,
 * VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §5.3). Called from the (not-yet-built) {@code
 * ConversionWebhookController}'s {@code POST /api/v1/webhooks/conversion} -- the brand's own
 * commerce platform reporting a completed order attributed to a tracking link, not a
 * workspace-authenticated brand request. There is no workspace principal at this call site, so
 * {@link #recordConversion} is intentionally NOT workspace-scoped -- same shape of exception as
 * {@code CampaignLinkService#recordClick} (it scopes only by the UTM row's own unguessable ULID
 * id, which was itself only ever created after a workspace-authorization check at link-creation
 * time).
 *
 * <p><b>Adapted from spec pseudocode to real fields</b> -- verified against the actual entities
 * before writing this class:
 *
 * <ul>
 *   <li>{@code utm.incrementConversionCount()} / {@code utm.addRevenue(orderAmount)} -- these
 *       methods did NOT exist on {@link UtmCampaign} before this task (the class javadoc there
 *       explicitly flagged conversion tracking as a deferred follow-up that should add them rather
 *       than mutate the fields directly). Added both methods on {@link UtmCampaign} as part of this
 *       change, following the exact same pattern as the existing {@code incrementClickCount}/{@code
 *       incrementUniqueVisitors} (increment the counter, bump {@code updatedAt}). The underlying
 *       columns ({@code conversion_count}, {@code revenue_attributed}) already exist in the V23
 *       schema -- this was a missing-method gap, not a missing-column gap.
 *   <li>{@code campaign.addAttribution(orderAmount)} -- {@link com.influora.domain.entity.Campaign}
 *       has NO attribution/revenue-rollup field or method at all (confirmed by reading the entity in
 *       full). This is a genuine missing-column gap, not a naming mismatch, and a campaign-level
 *       revenue rollup spanning potentially many UTM links/coupons is a bigger design question
 *       (would need a new migration + a decision on whether it double-counts against
 *       coupon-redemption revenue vs. UTM-conversion revenue when a checkout can be attributed via
 *       both). Deliberately deferred out of scope for this pass -- {@code TODO(follow-up)}:
 *       campaign-level revenue rollup, if/when needed, should probably be a computed read (SUM
 *       across a campaign's {@code utm_campaigns.revenue_attributed} rows) rather than a second
 *       mutable counter that can drift out of sync with the per-link source of truth.
 *   <li>{@code auditLog.recordMoneyEvent(...)} -- verified this method genuinely exists on {@code
 *       AuditLogService} with exactly the 7-arg signature the spec pseudocode uses. {@code
 *       workspaceId} is passed as {@code null} here -- unlike {@link
 *       com.influora.domain.entity.CouponCode}, {@link UtmCampaign} has no direct {@code
 *       workspace_id} column (see that entity's javadoc: authorization is derived via a join through
 *       {@code campaigns}, not stored on the row itself), and looking up the owning campaign just to
 *       populate an audit-log field was judged not worth an extra query/failure mode in this webhook
 *       path -- {@code campaignId} is already included in the audit detail map for traceability.
 *       {@code beforeBalance}/{@code afterBalance} are {@code null} for the same reason as {@code
 *       RedemptionService}: this is not a wallet/escrow balance mutation.
 *   <li>The spec's {@code recordConversion} has no idempotency key parameter at all -- unlike {@code
 *       RedemptionService#redeem}, which is explicitly {@code [SEC: Kabir]}-flagged for idempotency,
 *       §5.3 does not flag this method the same way and {@code utm_campaigns} has no unique
 *       idempotency-key column to de-duplicate against (compare {@code coupon_redemptions
 *       .idempotency_key NOT NULL UNIQUE}). Implemented exactly as specified (no idempotency
 *       guarantee) -- a retried webhook delivery to this endpoint WILL double-count clicks/revenue.
 *       {@code TODO(follow-up)}: if the conversion webhook can be retried by the brand's commerce
 *       platform, this needs the same idempotency-key treatment {@code coupon_redemptions} already
 *       has; flagging this explicitly rather than silently shipping a webhook endpoint with a
 *       different guarantee than its sibling.
 * </ul>
 */
@Service
public class ConversionTrackingService {

    private final UtmCampaignRepository utmCampaignRepository;
    private final AuditLogService auditLogService;

    public ConversionTrackingService(
            UtmCampaignRepository utmCampaignRepository, AuditLogService auditLogService) {
        this.utmCampaignRepository = utmCampaignRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Records a conversion (purchase) attributed to a UTM tracking link: increments the link's
     * {@code conversionCount} and adds {@code orderAmount} to its {@code revenueAttributed} running
     * total.
     *
     * @param utmCampaignId the tracking link's own id (ULID) -- unguessable, not workspace-checked
     *     (see class javadoc)
     * @param orderId the brand's own external order identifier (included in the audit-log detail
     *     only; not persisted on {@link UtmCampaign} itself, which has no per-order columns)
     * @param orderAmount the order's total revenue attributed to this link; must be non-null and
     *     non-negative
     * @throws ApiException {@code UTM_NOT_FOUND} (404) if {@code utmCampaignId} does not exist
     * @throws ApiException {@code ORDER_AMOUNT_INVALID} (400) if {@code orderAmount} is null or
     *     negative
     */
    @Transactional
    public void recordConversion(String utmCampaignId, String orderId, BigDecimal orderAmount) {
        UtmCampaign utm =
                utmCampaignRepository
                        .findById(utmCampaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));

        if (orderAmount == null || orderAmount.signum() < 0) {
            throw new ApiException(
                    "ORDER_AMOUNT_INVALID", "orderAmount must be a non-negative amount", HttpStatus.BAD_REQUEST);
        }

        utm.incrementConversionCount();
        utm.addRevenue(orderAmount);
        utmCampaignRepository.save(utm);

        // Campaign-level attribution rollup (spec's campaign.addAttribution(orderAmount)) is
        // deliberately out of scope -- Campaign has no attribution field. See class javadoc.

        auditLogService.recordMoneyEvent(
                null,
                "CONVERSION_TRACKED",
                orderAmount,
                null,
                null,
                "conv:" + utm.getCampaignId() + ":" + orderId,
                Map.of(
                        "utmCampaignId", utmCampaignId,
                        "campaignId", utm.getCampaignId(),
                        "creatorId", utm.getCreatorProfileId(),
                        "orderId", orderId == null ? "" : orderId));
    }
}
