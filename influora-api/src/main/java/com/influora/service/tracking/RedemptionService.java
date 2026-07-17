package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Handles coupon redemption with idempotency (Phase 4 UTM/Coupon Tracking,
 * VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §5.4). {@code [SEC: Kabir]} -- the spec itself flags this:
 * "All redemption endpoints MUST use idempotency keys." Called from the (not-yet-built) {@code
 * ConversionWebhookController}'s {@code POST /api/v1/webhooks/redemption} -- i.e. the brand's own
 * commerce platform (Shopify/WooCommerce/etc.) telling us "this code was used at checkout," not a
 * workspace-authenticated brand request. There is no workspace principal at this call site, so
 * {@link #redeem} is intentionally NOT workspace-scoped, same shape of exception as {@code
 * CampaignLinkService#recordClick} and {@code ConversionTrackingService#recordConversion}.
 *
 * <p><b>Adapted from spec pseudocode to real fields</b> -- the spec's §5.4 pseudocode does not match
 * several real entity/repository shapes in this codebase; adapted as follows (verified by reading
 * the actual entities/migrations/repositories before writing this class, not assumed):
 *
 * <ul>
 *   <li>{@code coupon.getCodeType()} does not exist -- {@link CouponCode} has {@code
 *       getDiscountType()} (V24 column {@code discount_type}, a free-text {@code VARCHAR(20)}, not
 *       an enum). The real values this codebase writes are the lower-case strings {@code
 *       "percentage"} and {@code "fixed"} (see {@code CouponCodeService} javadoc / {@code
 *       CouponCodeServiceTest} fixtures) -- NOT the spec's upper-case {@code "PERCENTAGE"} / {@code
 *       "FIXED_AMOUNT"} / {@code "FREE_SHIPPING"}. {@link #calculateDiscount} switches on the real
 *       values and is deliberately case-insensitive (lower-cases the input before the switch) so a
 *       currently-mismatched-case caller fails safe into the {@code UNSUPPORTED_DISCOUNT_TYPE}
 *       branch rather than silently returning zero discount. There is no {@code "FREE_SHIPPING"}
 *       concept anywhere in this codebase's coupon schema -- not implemented, since inventing new
 *       discount-type semantics without a product/schema decision is out of scope here.
 *   <li>{@code coupon.getCreatorProfileId()} does not exist -- {@link CouponCode} has {@code
 *       getCreatorId()} (V24 column {@code creator_id}). Used directly.
 *   <li>{@code coupon.getMaxUsesPerUser()} does not exist -- {@code coupon_codes} (V24) has no
 *       per-user-limit column at all, only {@code usage_limit} (a total-across-all-users cap, already
 *       enforced by {@code CouponCodeService#validateCode}) and {@code usage_count}. This is a
 *       genuine schema gap, not a naming mismatch: there is no column to read a per-user cap from.
 *       Per-user redemption limiting is therefore deliberately NOT implemented in this pass -- {@code
 *       TODO(follow-up)}: if per-user limits become a product requirement, V24 needs a new nullable
 *       {@code max_uses_per_user INT} column on {@code coupon_codes} plus a {@code
 *       countByCustomerIdAndCouponId} finder on {@link CouponRedemptionRepository} (neither exists
 *       today) -- adding either without a design decision on what "per user" means when {@code
 *       customerId} is null (anonymous checkout) was judged out of scope for this slice.
 *   <li>{@code coupon.addRevenue(orderAmount)} does not exist -- {@code coupon_codes} has no
 *       revenue/total-redeemed-amount column (only {@code usage_limit}/{@code usage_count}). See
 *       {@link CouponCode#incrementUsageCount} javadoc for the same gap, documented at the entity.
 *       The per-order amounts are not lost -- they are durably recorded on the {@link
 *       CouponRedemption} row itself ({@code orderAmount}/{@code discountApplied}), so a later
 *       rollup pass can still compute this from existing data without a backfill.
 *   <li>{@code auditLog.recordMoneyEvent(...)} -- verified this method genuinely exists on {@link
 *       AuditLogService} with exactly the 7-arg signature the spec pseudocode uses ({@code
 *       workspaceId, eventType, serverAmount, beforeBalance, afterBalance, idempotencyKey, detail}).
 *       Unlike the other spec mismatches above, this one call was accurate. {@code workspaceId} is
 *       passed as {@code coupon.getWorkspaceId()} (available directly off {@link CouponCode}, unlike
 *       {@code UtmCampaign} which has no such column -- see {@code ConversionTrackingService}, which
 *       must pass {@code null} instead). {@code beforeBalance}/{@code afterBalance} are passed as
 *       {@code null} -- a coupon redemption is a discount applied to an external order, not a
 *       wallet/escrow balance mutation, so there is no before/after balance concept here; those
 *       params exist on {@code recordMoneyEvent} for wallet/escrow-shaped callers.
 * </ul>
 *
 * <p><b>[W1-7 / H15/H16 fix]</b> The actual mutating write previously lived on this class as a
 * {@code @Transactional protected doRedeem(...)}, called from inside the {@link
 * IdempotencyService#executeOnce} supplier lambda passed by {@link #redeem}. Because that lambda
 * captured this class's own {@code this} and called {@code doRedeem} directly, the call bypassed
 * Spring's transactional proxy (Spring AOP's well-documented self-invocation limitation) and
 * {@code @Transactional} was a silent no-op — a failure partway through the write would not roll
 * back what had already been saved. The write now lives on {@link RedemptionWriter}, a genuinely
 * separate {@code @Component}, so it is invoked through a real Spring proxy and {@code
 * @Transactional} actually applies.
 */
@Service
public class RedemptionService {

    private static final String IDEMPOTENCY_SCOPE = "redemption.redeem";

    private final CouponRedemptionRepository redemptionRepository;
    private final IdempotencyService idempotencyService;
    private final RedemptionWriter redemptionWriter;

    public RedemptionService(
            CouponRedemptionRepository redemptionRepository,
            IdempotencyService idempotencyService,
            RedemptionWriter redemptionWriter) {
        this.redemptionRepository = redemptionRepository;
        this.idempotencyService = idempotencyService;
        this.redemptionWriter = redemptionWriter;
    }

    /**
     * Redeems a coupon {@code code} for an order.
     *
     * <p><b>Idempotency [SEC: Kabir]</b> -- {@code idempotencyKey} is required (rejected if
     * null/blank with {@code IDEMPOTENCY_KEY_REQUIRED}, 400). {@link
     * CouponRedemptionRepository#findByIdempotencyKey} is checked FIRST, before any validation or
     * mutation; if a redemption with this key already exists, it is returned as-is -- a clean,
     * idempotent no-op, not a double-counted usage/revenue increment. This is the application-level
     * half of the guarantee the DB-level {@code UNIQUE(idempotency_key)} constraint backstops (see
     * V24 migration / {@link CouponRedemption} javadoc).
     *
     * <p><b>Concurrent double-submit [SEC: Kabir, race-condition fast-follow]</b> -- the check above
     * alone is not enough: two concurrent requests with the same key can both observe {@code
     * Optional.empty()} before either commits. Rather than let the losing transaction's {@code
     * INSERT} hit V24's {@code UNIQUE(idempotency_key)} and surface an uncaught {@code
     * DataIntegrityViolationException} as a bare 500 (Kabir's red-team finding), the actual mutation
     * is wrapped in {@link IdempotencyService#executeOnce}, the same shared insert-first-wins helper
     * already used by {@code CreateCampaignExecutor}/{@code RequestPaymentExecutor} -- its own {@code
     * idempotency_keys} table (V15) arbitrates the race via its {@code UNIQUE} constraint, not
     * application logic. The request that loses the reservation race is not an error: it re-queries
     * {@link CouponRedemptionRepository#findByIdempotencyKey} for the winner's row (which is either
     * already visible or about to be, since the winner is mid-transaction) and returns it gracefully.
     * Only if the winner's row is genuinely not yet visible does this throw {@code
     * IDEMPOTENCY_KEY_IN_PROGRESS} (409) -- a transient, retry-safe response, never a generic 500.
     *
     * @param code the coupon code string as entered/scanned at checkout (case-insensitive; matched
     *     via {@link CouponCodeRepository#findByCode}, which is itself uppercase-agnostic only in
     *     that callers are expected to normalize -- see {@link #normalizeCode})
     * @param orderId the brand's own external order identifier
     * @param orderAmount the order's pre-discount total; must be non-null and non-negative
     * @param customerId optional external customer identifier (for future per-user limiting -- see
     *     class javadoc gap note); may be null for anonymous checkouts
     * @param idempotencyKey required; MUST be derived from the brand's own idempotency token or a
     *     deterministic hash of coupon+order, never a fresh random value per attempt (see {@link
     *     CouponRedemption} javadoc)
     * @throws ApiException {@code IDEMPOTENCY_KEY_REQUIRED} (400) if {@code idempotencyKey} is
     *     null/blank
     * @throws ApiException {@code ORDER_AMOUNT_INVALID} (400) if {@code orderAmount} is null or
     *     negative
     * @throws ApiException {@code INVALID_CODE} (404) if {@code code} does not match any coupon
     * @throws ApiException {@code CODE_EXPIRED} (400) if the coupon's {@code expiresAt} has passed
     * @throws ApiException {@code CODE_LIMIT_REACHED} (400) if the coupon's {@code usageLimit} has
     *     already been met
     * @throws ApiException {@code IDEMPOTENCY_KEY_IN_PROGRESS} (409) if a concurrent request won the
     *     reservation race and its redemption row is not yet visible to re-query -- retry-safe
     */
    public CouponRedemption redeem(
            String code, String orderId, BigDecimal orderAmount, String customerId, String idempotencyKey) {
        return redeem(null, code, orderId, orderAmount, customerId, idempotencyKey);
    }

    /**
     * Workspace-scoped overload [SEC: Kabir Wave D1/E4 — FIXED]. {@code workspaceId} is the
     * caller-resolved, ALREADY-authenticated (signature-verified) workspace that this webhook
     * delivery was proven to originate from ({@code ShopifyWebhookController}/{@code
     * WooCommerceWebhookController}/{@code ConversionWebhookController}) — never
     * client-supplied on its own. Because {@link CouponCodeRepository#findByCode} is a global,
     * not workspace-scoped, lookup and coupon codes are only unique per-workspace ({@code
     * UNIQUE(workspace_id, code)}), a webhook legitimately signed by workspace A could otherwise
     * redeem a coupon code that happens to belong to workspace B. Passing {@code workspaceId}
     * here means a resolved coupon belonging to a DIFFERENT workspace is rejected with the same
     * {@code INVALID_CODE} 404 used for "code doesn't exist" — no new enumeration signal. Passing
     * {@code null} preserves the legacy, deliberately-unscoped behavior for callers with no
     * workspace identity to check against (see class javadoc).
     */
    public CouponRedemption redeem(
            String workspaceId,
            String code,
            String orderId,
            BigDecimal orderAmount,
            String customerId,
            String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "idempotency_key is required for coupon redemption",
                    HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir] Idempotency check FIRST -- before any validation, before any mutation. A
        // retried delivery of the same webhook returns the exact same redemption, never double-counts.
        CouponRedemption replay = replayIfPresent(idempotencyKey);
        if (replay != null) {
            return replay;
        }

        // [SEC: Kabir] Race fix: two concurrent requests with the same idempotencyKey can both pass
        // the check above before either commits (default REPEATABLE READ, no row to see yet). Rather
        // than let the loser's INSERT hit V24's UNIQUE(idempotency_key) and surface an uncaught
        // DataIntegrityViolationException as a bare 500, reserve the key through the shared
        // IdempotencyService FIRST (its own insert-first-wins table, V15) -- mirrors the exact
        // pattern already established by CreateCampaignExecutor/RequestPaymentExecutor, the other
        // consumers of this shared service. Whichever request wins the reservation runs the real
        // mutation; the loser is told via AlreadyInProgressException/AlreadyCompletedException and
        // simply re-queries the winner's now-visible-or-about-to-be-visible row instead of failing.
        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    null,
                    IDEMPOTENCY_SCOPE,
                    () -> redemptionWriter.doRedeem(workspaceId, code, orderId, orderAmount, customerId, idempotencyKey));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            CouponRedemption won = replayIfPresent(idempotencyKey);
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This redemption is already being processed -- retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    private CouponRedemption replayIfPresent(String idempotencyKey) {
        return redemptionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
    }
}
