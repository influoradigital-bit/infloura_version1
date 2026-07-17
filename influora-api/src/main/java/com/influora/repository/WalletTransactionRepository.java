package com.influora.repository;

import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.WalletTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, String> {

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);

    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(String walletId);

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(String walletId, Pageable pageable);

    long countByWalletIdAndTypeAndCreatedAtAfter(
            String walletId, WalletTransactionType type, Instant since);

    List<WalletTransaction> findByGroupId(String groupId);

    /**
     * Sums the amount of all ledger legs of the given {@code direction} posted against
     * {@code walletId} with {@code createdAt >= since}. Used by {@code WalletService} to derive
     * the trailing-window average daily burn for runway math (P0 — see MEMORY / brand-audit
     * backend build task). Returns {@code null} (via COALESCE-free SUM) when there are no
     * matching rows — callers must null-check.
     */
    @Query(
            "SELECT SUM(t.amount) FROM WalletTransaction t "
                    + "WHERE t.walletId = :walletId AND t.direction = :direction AND t.createdAt >= :since")
    BigDecimal sumAmountByWalletIdAndDirectionSince(
            @Param("walletId") String walletId,
            @Param("direction") TxnDirection direction,
            @Param("since") Instant since);

    /**
     * Sums {@code amount} across every ledger row of the given {@code type} posted with {@code
     * createdAt <= asOf} — the platform-revenue formula (Rohan, CFO, signed off 2026-07-15):
     * {@code revenue = SUM(wallet_transactions.amount) WHERE type = 'PLATFORM_FEE' AND created_at
     * BETWEEN [period]}, applied here with an open lower bound (since inception) and {@code asOf}
     * as the upper bound. That mirrors how {@code EscrowHoldRepository.sumAmountByStatusIn} computes
     * {@code gmv}/{@code escrowFloat} in the same {@code AdminDashboardStatsCache} snapshot — an
     * unwindowed cumulative total, not a rolling period — since no {@code kpi_daily_snapshot} table
     * exists yet to diff against (that lands in a later phase; see {@code AdminDashboardStatsCache}
     * TODOs for the *Change deltas).
     *
     * <p>Both fee-recognition points post {@code PLATFORM_FEE} rows here and neither is ever
     * reversed: the brand-side publish fee ({@code BrandCampaignFeeService.chargeOnPublish},
     * non-refundable by design) and the creator-side release fee ({@code
     * PlatformFeeService.deductAtRelease}, fired only from escrow release/split-release, never from
     * a refund path). {@code WalletTransactionType} has no reversal variant, so summing this type
     * alone is already net of refunds by construction — nothing to subtract. Do NOT also sum {@code
     * campaign_service_invoices.gross_amount} (that's creator gross service value, GMV-adjacent, and
     * would double-count against {@code gmv}) or {@code platform_commission_invoices} on top of this
     * (Rohan confirms the two mirror 1:1 — use one, not both).
     *
     * <p>Returns {@code BigDecimal.ZERO}, never {@code null}, when there are no matching rows (unlike
     * {@link #sumAmountByWalletIdAndDirectionSince}) so callers don't need a null-check for this
     * always-real KPI.
     */
    @Query(
            "SELECT COALESCE(SUM(t.amount), 0) FROM WalletTransaction t "
                    + "WHERE t.type = :type AND t.createdAt <= :asOf")
    BigDecimal sumAmountByTypeAndCreatedAtBefore(
            @Param("type") WalletTransactionType type, @Param("asOf") Instant asOf);
}
