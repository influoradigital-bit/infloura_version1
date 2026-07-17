package com.influora.service.tracking;

import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

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
 *       .idempotency_key NOT NULL UNIQUE}). <b>[SEC: Vikram, P1 money-integrity fix -- FIXED]</b> The
 *       caller-supplied {@code idempotencyKey} overload previously provided NO real protection: it
 *       was recorded on the audit-log row only, AFTER {@code utm.incrementConversionCount()} /
 *       {@code utm.addRevenue(orderAmount)} had already run unconditionally, and {@code
 *       audit_log.idempotency_key} carries no {@code UNIQUE} constraint (V15) -- so an at-least-once
 *       webhook redelivery, or a replay of a validly-signed payload, silently double-counted
 *       conversions/revenue every single time. The actual rollup ({@link
 *       #doRecordConversion}) is now wrapped in {@link IdempotencyService#executeOnce}, keyed on a
 *       WORKSPACE-NAMESPACED, order-derived key -- {@code workspaceId + ":conv:" + utmCampaignId +
 *       ":" + orderId} (see {@link #recordConversion(String, String, String, BigDecimal, String)})
 *       -- not the caller-supplied {@code idempotencyKey} itself, so this closes the hole even if a
 *       replaying caller varies (or omits) that field: the same order can never be attributed twice
 *       for the same workspace+link. A losing/duplicate delivery is treated as an idempotent no-op
 *       (the first delivery already recorded the conversion), never a 500 or a silent double count.
 * </ul>
 */
@Service
public class ConversionTrackingService {

    /** [SEC: Vikram, P1] Namespace for the reservation key guarding the writer's write. */
    private static final String IDEMPOTENCY_SCOPE = "tracking.record_conversion";

    private final IdempotencyService idempotencyService;
    private final ConversionTrackingWriter conversionTrackingWriter;

    public ConversionTrackingService(
            IdempotencyService idempotencyService, ConversionTrackingWriter conversionTrackingWriter) {
        this.idempotencyService = idempotencyService;
        this.conversionTrackingWriter = conversionTrackingWriter;
    }

    /**
     * Records a conversion (purchase) attributed to a UTM tracking link: increments the link's
     * {@code conversionCount} and adds {@code orderAmount} to its {@code revenueAttributed} running
     * total. Legacy/internal-caller shape -- no workspace identity available, so the replay guard
     * below degrades to an unscoped (global) key. Prefer {@link #recordConversion(String, String,
     * String, BigDecimal, String)} wherever a workspace is resolvable.
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
    public void recordConversion(String utmCampaignId, String orderId, BigDecimal orderAmount) {
        recordConversion(null, utmCampaignId, orderId, orderAmount, null);
    }

    /**
     * Legacy overload retained for existing internal/test callers that have no workspace identity
     * to scope the replay guard with -- see {@link #recordConversion(String, String, String,
     * BigDecimal, String)} for the real (workspace-scoped) webhook path, which {@code
     * ConversionWebhookController} now uses exclusively.
     */
    public void recordConversion(
            String utmCampaignId, String orderId, BigDecimal orderAmount, String idempotencyKey) {
        recordConversion(null, utmCampaignId, orderId, orderAmount, idempotencyKey);
    }

    /**
     * Webhook-path overload, workspace-scoped [SEC: Vikram, P1 money-integrity fix -- FIXED]. {@code
     * workspaceId} is the caller-resolved, already signature-verified workspace this webhook
     * delivery was proven to originate from ({@code ConversionWebhookController}) — never
     * client-supplied on its own.
     *
     * <p><b>Replay guard, concretely:</b> the actual rollup ({@link #doRecordConversion}) is wrapped
     * in {@link IdempotencyService#executeOnce}, reserved under the key {@code workspaceId + ":conv:"
     * + utmCampaignId + ":" + orderId} — deliberately NOT the caller-supplied {@code idempotencyKey}
     * (which the previous, broken version relied on solely as an audit-log label with no enforcement
     * behind it — see class javadoc). Keying on the order identity itself, namespaced per workspace
     * and tracking link, means an at-least-once webhook redelivery or a replay of a validly-signed
     * payload can never double-count the same order twice, regardless of what (if anything) the
     * caller sends as {@code idempotencyKey}. A losing/duplicate delivery is a clean no-op — the
     * first delivery already recorded the conversion — never a 500 and never a silently-inflated
     * revenue total.
     *
     * @param workspaceId the UTM link's resolved owning workspace, or {@code null} if unresolvable
     *     (falls back to an unscoped, still-deduped-per-utm+order key)
     * @param utmCampaignId the tracking link's own id (ULID) -- unguessable, not workspace-checked
     *     (see class javadoc)
     * @param orderId the brand's own external order identifier
     * @param orderAmount the order's total revenue attributed to this link; must be non-null and
     *     non-negative
     * @param idempotencyKey the brand-supplied token from {@code ConversionWebhookController},
     *     recorded as the audit-log entry's own idempotency label (traceability only -- the actual
     *     dedupe guarantee comes from the order-derived reservation key above, not this field)
     * @throws ApiException {@code UTM_NOT_FOUND} (404) if {@code utmCampaignId} does not exist
     * @throws ApiException {@code ORDER_AMOUNT_INVALID} (400) if {@code orderAmount} is null or
     *     negative
     */
    public void recordConversion(
            String workspaceId,
            String utmCampaignId,
            String orderId,
            BigDecimal orderAmount,
            String idempotencyKey) {
        String reservationKey =
                (workspaceId == null ? "" : workspaceId) + ":conv:" + utmCampaignId + ":" + orderId;
        try {
            idempotencyService.executeOnce(
                    reservationKey,
                    workspaceId,
                    IDEMPOTENCY_SCOPE,
                    () -> {
                        conversionTrackingWriter.doRecordConversion(
                                utmCampaignId, orderId, orderAmount, idempotencyKey);
                        return null;
                    });
        } catch (IdempotencyService.AlreadyCompletedException
                | IdempotencyService.AlreadyInProgressException replay) {
            // [SEC: Vikram, P1] Duplicate/concurrent delivery for the SAME workspace+utm+order --
            // the first delivery already recorded (or is actively recording) this exact conversion.
            // A fire-and-forget webhook has no prior result to replay back to the caller (unlike
            // RedemptionService, there is no dedicated result row keyed by idempotencyKey here), so
            // this is treated as a clean idempotent no-op rather than a 409 -- the controller already
            // returns 200 {recorded:true} to the caller either way.
        }
    }
}
