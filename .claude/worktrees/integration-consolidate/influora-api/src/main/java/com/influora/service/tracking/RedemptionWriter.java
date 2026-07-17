package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [W1-7 / H15/H16] Extracted from {@link RedemptionService#doRedeem} — this is the actual mutating
 * write, called by {@link RedemptionService#redeem} FROM INSIDE the {@link
 * com.influora.service.IdempotencyService#executeOnce} supplier lambda it passes in. When that
 * lambda lived in {@code RedemptionService} itself and called {@code this.doRedeem(...)} directly,
 * the call bypassed Spring's transactional proxy entirely (a lambda captures the enclosing
 * instance's raw {@code this}, exactly like an anonymous inner class would) — {@code
 * @Transactional} on that method was a documented-looking but silently inert no-op, so a failure
 * partway through (e.g. the audit-log call throwing after the redemption row and usage counter
 * were already saved) would NOT roll back the partial write. Moving the write to a genuinely
 * separate {@code @Component} means {@link RedemptionService} now calls it through this bean's real
 * Spring proxy, so {@code @Transactional} actually demarcates a transaction.
 *
 * <p>Holds exactly the dependencies the write itself needs ({@link CouponCodeRepository}, {@link
 * CouponRedemptionRepository}, {@link AuditLogService}) — {@code RedemptionService} keeps only
 * what its own orchestration (replay-check, idempotency reservation) needs.
 */
@Component
public class RedemptionWriter {

    private final CouponRedemptionRepository redemptionRepository;
    private final CouponCodeRepository couponCodeRepository;
    private final AuditLogService auditLogService;

    public RedemptionWriter(
            CouponRedemptionRepository redemptionRepository,
            CouponCodeRepository couponCodeRepository,
            AuditLogService auditLogService) {
        this.redemptionRepository = redemptionRepository;
        this.couponCodeRepository = couponCodeRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Runs ONLY inside {@code executeOnce} (called from {@link RedemptionService#redeem}) — see
     * class javadoc. Identical logic to the pre-extraction {@code RedemptionService#doRedeem}.
     */
    @Transactional
    public CouponRedemption doRedeem(
            String workspaceId,
            String code,
            String orderId,
            BigDecimal orderAmount,
            String customerId,
            String idempotencyKey) {

        if (orderAmount == null || orderAmount.signum() < 0) {
            throw new ApiException(
                    "ORDER_AMOUNT_INVALID", "orderAmount must be a non-negative amount", HttpStatus.BAD_REQUEST);
        }

        CouponCode coupon = validateCode(code, workspaceId);

        // Per-user limit: NOT implemented -- coupon_codes has no max-uses-per-user column. See
        // RedemptionService's class javadoc gap note. Only the total usage_limit (checked in
        // validateCode) is enforced today.

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

        // Update coupon usage stats. addRevenue(...) has no backing column -- see
        // RedemptionService's class javadoc gap note; only the usage counter is incremented.
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
     * Validates a coupon code is redeemable right now: exists, not expired, under its usage
     * limit. See {@code RedemptionService#validateCode}'s original javadoc (moved here verbatim
     * alongside the write it exclusively supports) for the spec-adaptation notes.
     */
    private CouponCode validateCode(String code, String workspaceId) {
        CouponCode coupon =
                couponCodeRepository
                        .findByCode(normalizeCode(code))
                        .orElseThrow(
                                () -> new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));

        // [SEC: Kabir Wave D1/E4] A coupon resolved by the global findByCode lookup that belongs to
        // a DIFFERENT workspace than the caller's proven identity is indistinguishable from "does
        // not exist" -- same INVALID_CODE 404, no new enumeration signal. workspaceId == null
        // preserves the legacy unscoped behavior for callers with no workspace identity to check.
        if (workspaceId != null && !workspaceId.equals(coupon.getWorkspaceId())) {
            throw new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND);
        }

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
     * discountType}/{@code discountValue}. See {@code RedemptionService#calculateDiscount}'s
     * original javadoc (moved here verbatim) for the spec-adaptation notes.
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
