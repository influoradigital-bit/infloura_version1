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
}
