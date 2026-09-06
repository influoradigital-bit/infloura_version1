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
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
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
 * CouponRedemptionRepository}, {@link AuditLogService}, and an {@code ApplicationEventPublisher}) —
 * {@code RedemptionService} keeps only what its own orchestration (replay-check, idempotency
 * reservation) needs.
 *
 * <p><b>The commission is NOT recorded in this transaction.</b> {@link #doRedeem} publishes {@link
 * CouponRedeemedEvent}, and {@link AffiliateEarningRecordingListener} records the earning at {@code
 * AFTER_COMMIT}. It was briefly a direct in-transaction call (per the ruling's Part B), which meant
 * a failure in commission bookkeeping rolled back the SALE — and the reconciliation cron could not
 * recover it, because that cron sweeps redemptions that exist without an earning, and a rolled-back
 * redemption does not exist. See the publish site and the listener for the full reasoning.
 */
@Component
public class RedemptionWriter {

    private final CouponRedemptionRepository redemptionRepository;
    private final CouponCodeRepository couponCodeRepository;
    private final AuditLogService auditLogService;

    /**
     * Publishes {@link CouponRedeemedEvent} so the commission is recorded at {@code AFTER_COMMIT}
     * rather than inside this write's transaction — see the note at the publish site, and
     * {@link AffiliateEarningRecordingListener}, for why that ordering is the fix and not a
     * weakening.
     */
    private final ApplicationEventPublisher eventPublisher;

    public RedemptionWriter(
            CouponRedemptionRepository redemptionRepository,
            CouponCodeRepository couponCodeRepository,
            AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher) {
        this.redemptionRepository = redemptionRepository;
        this.couponCodeRepository = couponCodeRepository;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
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

        // [T-FESTIVALBOX-0905 phase 4, task brief "landmine 3"] Map.of(...) throws NullPointerException
        // on a null VALUE (not just a null key) -- coupon.getCreatorId() is null for a brand-level
        // coupon since V20260905160000, which would have turned every brand-level redemption's audit
        // write into an uncaught NPE inside this @Transactional method (rolling back the whole
        // redemption -- the exact "sale silently dropped" failure mode the brief warns about, just at
        // this call site instead of AffiliateEarningsService). Built as a plain mutable map instead,
        // with an explicit brandLevel flag and creatorId only present when there is one.
        Map<String, Object> auditDetail = new HashMap<>();
        auditDetail.put("couponId", coupon.getId());
        auditDetail.put("code", coupon.getCode());
        auditDetail.put("orderId", orderId == null ? "" : orderId);
        auditDetail.put("brandLevel", coupon.isBrandLevel());
        if (!coupon.isBrandLevel()) {
            auditDetail.put("creatorId", coupon.getCreatorId());
        }

        auditLogService.recordMoneyEvent(
                coupon.getWorkspaceId(), "COUPON_REDEEMED", discountApplied, null, null, idempotencyKey, auditDetail);

        // [Wave D task D4, as revised by Kabir H-4]
        //
        // Recording the creator's commission promptly at redemption time is the intended design —
        // wiki/tech/tracking-subsystem-ruling.md Q1 (Priya, CTO) ruled its absence a P0 regression,
        // because before it the hourly AffiliateEarningReconciliationJob was the ONLY thing creating
        // commissions: every creator's earnings lagged ~90 minutes, and a disabled cron meant nobody
        // was paid at all. That part of the ruling stands and is what the event below delivers.
        //
        // What was revised is HOW. See the note at the publish call.
        //
        // NOTE ON THE RULING'S "Part B": it also asked for an @Lazy self-reference so @Transactional
        // would stop being a silent no-op under same-bean self-invocation. That is already solved,
        // and solved better — the write was extracted onto THIS class, a genuinely separate bean
        // that RedemptionService calls through the Spring proxy, so @Transactional above really
        // applies. Do not add an @Lazy self here; it would re-fix a fixed problem.
        //
        // PUBLISHED, NOT CALLED — and the difference is the whole point.
        //
        // [Kabir H-4] This was a direct affiliateEarningsService.recordEarning(redemption) call
        // right here, inside this @Transactional method, per the ruling's Part B ("commit or roll
        // back as one unit"). That made a bookkeeping failure destroy a real sale:
        // recordEarning throws IDEMPOTENCY_KEY_IN_PROGRESS whenever its inner reservation cannot be
        // taken, and from inside this transaction that rolled back the redemption row, the coupon
        // usage increment AND the COUPON_REDEEMED money-audit event above.
        //
        // The ruling justified that coupling by saying a failure would be "swept by the cron". It
        // would not: AffiliateEarningReconciliationJob sweeps redemptions that EXIST but have no
        // earning — a rolled-back redemption does not exist, so the cron could never recover it.
        //
        // Publishing instead defers the commission to AFTER_COMMIT (see
        // AffiliateEarningRecordingListener). The sale commits first and is never at risk from
        // commission accounting; the commission still lands in milliseconds rather than the ~90
        // minutes the cron-only era cost; and if it does fail, the redemption exists WITHOUT an
        // earning — exactly the state the cron was built to sweep.
        //
        // This event only fires because this method is genuinely transactional (a separate bean,
        // called through its Spring proxy). @TransactionalEventListener(AFTER_COMMIT) is silently
        // never invoked when no transaction is active — if @Transactional is ever removed from
        // doRedeem, commissions stop being recorded promptly and only the cron catches them.
        eventPublisher.publishEvent(new CouponRedeemedEvent(redemption.getId()));

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
