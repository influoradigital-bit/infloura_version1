package com.influora.repository;

import com.influora.domain.entity.AffiliateEarning;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code affiliate_earnings} (V27) -- Wave D task D4. Mirrors {@code CouponRedemptionRepository}'s
 * shape: {@link #findByIdempotencyKey} backs {@code AffiliateEarningsService}'s replay guard (same
 * pattern as {@code CouponRedemptionRepository#findByIdempotencyKey}/{@code
 * RedemptionService#replayIfPresent}), and {@link #findByRedemptionId} backs the structural
 * double-credit guard on {@code UNIQUE(redemption_id)}.
 */
public interface AffiliateEarningRepository extends JpaRepository<AffiliateEarning, String> {

    /** [SEC: Kabir] Idempotency check -- the caller MUST consult this before inserting an earning. */
    Optional<AffiliateEarning> findByIdempotencyKey(String idempotencyKey);

    /** Structural double-credit guard -- backs {@code UNIQUE(redemption_id)} (V27). */
    Optional<AffiliateEarning> findByRedemptionId(String redemptionId);

    /** All PENDING or FAILED (retryable) earnings for one creator -- the settlement job's per-creator sweep. */
    List<AffiliateEarning> findByCreatorIdAndStatusIn(String creatorId, List<AffiliateEarning.Status> statuses);

    /**
     * Distinct creator ids with at least one settleable (PENDING/FAILED) earning -- drives the
     * settlement job's per-creator loop. A derived-query method name (e.g. {@code
     * findDistinctCreatorIdByStatusIn}) cannot express "distinct scalar projection of one field" in
     * Spring Data JPA, hence the explicit JPQL projection here.
     */
    @Query("SELECT DISTINCT e.creatorId FROM AffiliateEarning e WHERE e.status IN :statuses")
    List<String> findDistinctCreatorIdByStatusIn(@Param("statuses") List<AffiliateEarning.Status> statuses);

    /** Workspace-scoped earnings history read (e.g. a future brand-facing view) -- direct column, no join needed. */
    List<AffiliateEarning> findByWorkspaceIdAndCreatorId(String workspaceId, String creatorId);

    /** Creator-scoped earnings history (V-GA-7) — newest first for the creator dashboard. */
    List<AffiliateEarning> findByCreatorIdOrderByCreatedAtDesc(String creatorId);

    /** All earnings settled under one batch -- for settlement-batch audit/read-back. */
    List<AffiliateEarning> findBySettlementBatchId(String settlementBatchId);
}
