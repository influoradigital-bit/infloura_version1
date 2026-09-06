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
     *
     * <p><b>[T-FESTIVALBOX-0905 phase 4]</b> Excludes redemptions against a brand-level coupon
     * ({@code CouponCode.creatorId IS NULL}, V20260905160000) via an explicit join to {@code
     * coupon_codes} -- {@code AffiliateEarningsService#recordEarning} deliberately never creates an
     * {@code AffiliateEarning} for those (there is no creator to pay), so without this exclusion
     * every brand-level redemption would permanently, silently satisfy the "no matching earning"
     * predicate and get re-swept as "orphaned"/"backfilled" on every single hourly run forever —
     * a false-positive signal, not a real gap, that would defeat this job's own WARN-on-nonzero
     * monitoring (see that job's javadoc: "a nonzero backfill count ... is a real defect signal").
     * The cross-entity {@code JOIN CouponCode c ON r.couponId = c.id} mirrors the established
     * pattern already used elsewhere in this codebase for a plain-string FK field with no {@code
     * @ManyToOne} mapping (see {@code DisputeRepository}'s {@code JOIN Collaboration c ON
     * d.collaborationId = c.id}).
     */
    @Query(
            "SELECT r FROM CouponRedemption r JOIN CouponCode c ON r.couponId = c.id "
                    + "WHERE r.redeemedAt < :olderThan AND c.creatorId IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM AffiliateEarning e WHERE e.redemptionId = r.id)")
    List<CouponRedemption> findOrphanedWithoutAffiliateEarning(
            @Param("olderThan") Instant olderThan);
}
