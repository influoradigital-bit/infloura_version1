package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class RedemptionService {

    private static final String IDEMPOTENCY_SCOPE = "redemption.redeem";

    private final CouponRedemptionRepository redemptionRepository;
    private final CouponCodeRepository couponCodeRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    public RedemptionService(
            CouponRedemptionRepository redemptionRepository,
            CouponCodeRepository couponCodeRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService) {
        this.redemptionRepository = redemptionRepository;
        this.couponCodeRepository = couponCodeRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
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
                    () -> doRedeem(code, orderId, orderAmount, customerId, idempotencyKey));
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

    @Transactional
    protected CouponRedemption doRedeem(
            String code, String orderId, BigDecimal orderAmount, String customerId, String idempotencyKey) {

        if (orderAmount == null || orderAmount.signum() < 0) {
            throw new ApiException(
                    "ORDER_AMOUNT_INVALID", "orderAmount must be a non-negative amount", HttpStatus.BAD_REQUEST);
        }

        CouponCode coupon = validateCode(code);

        // Per-user limit: NOT implemented -- coupon_codes has no max-uses-per-user column. See class
        // javadoc gap note. Only the total usage_limit (checked in validateCode) is enforced today.

        BigDecimal discountApplied = calculateDiscount(coupon, orderAmount);

        CouponRedemption redemption =
                CouponRedemption.builder()
                        .id(Ulids.newUlid())
                        .couponId(coupon.getId())
                        .orderId(orderId)
                        .orderAmount(orderAmount)
                        .discountApplied(discountApplied)
                        .customerId(customerId)
                        .idempotencyKey(idempotencyKey)
                        .build();

        redemptionRepository.save(redemption);

        // Update coupon usage stats. addRevenue(...) has no backing column -- see class javadoc gap
        // note; only the usage counter is incremented.
        coupon.incrementUsageCount();
        couponCodeRepository.save(coupon);

        auditLogService.recordMoneyEvent(
                coupon.getWorkspaceId(),
                "COUPON_REDEEMED",
                discountApplied,
                null,
                null,
                idempotencyKey,
                Map.of(
                        "couponId", coupon.getId(),
                        "code", coupon.getCode(),
                        "orderId", orderId == null ? "" : orderId,
                        "creatorId", coupon.getCreatorId()));

        return redemption;
    }

    /**
     * Validates a coupon code is redeemable right now: exists, not expired, under its usage limit.
     * Mirrors {@code CouponCodeService#validateCode}'s checks, but that method is {@code
     * @Transactional(readOnly = true)} and keyed off {@code findByCode} which does not exist on
     * {@link CouponCodeRepository} until this task added it -- rather than change that method's
     * transaction/locking semantics for a caller it wasn't written for, this service does its own
     * equivalent validation inline against the same fields, using the {@code findByCode} finder
     * added for exactly this purpose (see that repository method's javadoc for why it is
     * deliberately not workspace-scoped).
     *
     * <p>[SPEC ADAPTATION] The spec's §5.4 pseudocode calls {@code coupon.isActive()} (a boolean
     * "active/inactive" flag) as part of this check. No such column/flag exists on {@link
     * CouponCode} (V24 has no {@code is_active}/{@code status} column) -- a coupon's only
     * "inactive" states this schema can express are "expired" ({@code expiresAt} in the past) and
     * "exhausted" ({@code usageCount >= usageLimit}), both of which are checked below. There is no
     * separate soft-delete/deactivate concept to check.
     */
    private CouponCode validateCode(String code) {
        CouponCode coupon =
                couponCodeRepository
                        .findByCode(normalizeCode(code))
                        .orElseThrow(
                                () -> new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));

        if (coupon.getExpiresAt() != null && coupon.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException("CODE_EXPIRED", "Coupon code has expired", HttpStatus.BAD_REQUEST);
        }

        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            throw new ApiException(
                    "CODE_LIMIT_REACHED", "Coupon code usage limit reached", HttpStatus.BAD_REQUEST);
        }

        return coupon;
    }

    /**
     * Calculates the discount amount for {@code orderAmount} given {@code coupon}'s real {@code
     * discountType}/{@code discountValue}. Matches the real, lower-case string values this codebase
     * writes ({@code "percentage"}, {@code "fixed"}) rather than the spec pseudocode's upper-case
     * enum-shaped strings -- see class javadoc. Comparison is case-insensitive (input is lower-cased
     * first) so a stray upper-cased value fails safe into {@code UNSUPPORTED_DISCOUNT_TYPE} rather
     * than silently matching nothing and returning zero.
     *
     * <p>{@code "fixed"} discounts are capped at {@code orderAmount} (never discount more than the
     * order is worth), matching the spec's {@code .min(orderAmount)} clamp. {@code "percentage"}
     * discounts round half-up to 2 decimal places (matching {@code order_amount}/{@code
     * discount_applied}'s {@code DECIMAL(12,2)} column precision).
     *
     * @throws ApiException {@code UNSUPPORTED_DISCOUNT_TYPE} (500) if {@code discountType} is
     *     neither {@code "percentage"} nor {@code "fixed"} -- this indicates a data-integrity problem
     *     (a coupon was persisted with a discount type this service doesn't know how to price), not a
     *     caller input error, hence 500 rather than 400.
     */
    private BigDecimal calculateDiscount(CouponCode coupon, BigDecimal orderAmount) {
        String discountType = coupon.getDiscountType() == null ? "" : coupon.getDiscountType().toLowerCase();
        return switch (discountType) {
            case "percentage" ->
                    orderAmount
                            .multiply(coupon.getDiscountValue())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case "fixed" -> coupon.getDiscountValue().min(orderAmount);
            default ->
                    throw new ApiException(
                            "UNSUPPORTED_DISCOUNT_TYPE",
                            "Coupon has an unrecognized discount type: " + coupon.getDiscountType(),
                            HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }

    /** Coupon codes are generated/stored upper-cased (see {@code CouponCodeService}); normalize input. */
    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }
}
