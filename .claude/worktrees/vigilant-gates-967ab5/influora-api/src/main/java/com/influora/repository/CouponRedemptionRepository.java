package com.influora.repository;

import com.influora.domain.entity.CouponRedemption;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code coupon_redemptions} has no {@code workspace_id} column of its own -- isolation for any
 * future brand-facing read must go through {@code coupon_id} -> {@code coupon_codes.workspace_id}
 * (mirrors how {@code utm_campaigns}-adjacent tables without a direct workspace column are scoped
 * in this codebase). Not yet called by anything -- redemption processing ({@code
 * ConversionTrackingService}/{@code RedemptionService}) is a deliberately deferred follow-up; see
 * {@code CouponRedemption} javadoc.
 */
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, String> {

    /** [SEC: Kabir] Idempotency check -- the caller MUST consult this before inserting a redemption. */
    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<CouponRedemption> findByIdempotencyKey(String idempotencyKey);

    List<CouponRedemption> findByCouponId(String couponId);

    List<CouponRedemption> findByOrderId(String orderId);
}
