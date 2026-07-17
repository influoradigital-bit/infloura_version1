package com.influora.repository;

import com.influora.domain.entity.CouponRedemption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Redemptions older than the grace period with no corresponding {@code affiliate_earnings} row
     * yet — backs {@code AffiliateEarningReconciliationJob}. Anchored to {@code
     * coupon_redemptions.redeemed_at} (V24) + the {@code UNIQUE(redemption_id)} guard on {@code
     * affiliate_earnings} (V28).
     */
    @Query(
            "SELECT r FROM CouponRedemption r WHERE r.redeemedAt < :olderThan "
                    + "AND NOT EXISTS (SELECT 1 FROM AffiliateEarning e WHERE e.redemptionId = r.id)")
    List<CouponRedemption> findOrphanedWithoutAffiliateEarning(
            @Param("olderThan") Instant olderThan);
}
