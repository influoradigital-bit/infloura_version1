package com.influora.repository;

import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** T-CREATOR-CREDITS-V2 (SPEC.md §5.4) — cloned from {@code WalletTopUpRepository}. */
public interface CreatorCreditOrderRepository extends JpaRepository<CreatorCreditOrder, String> {

    /** Row-locked read for {@code confirmPaid} — the webhook, {@code /verify} and the reconciliation job can all race on the same order (K-10). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT o FROM CreatorCreditOrder o WHERE o.id = :id")
    Optional<CreatorCreditOrder> findByIdForUpdate(@Param("id") String id);

    Optional<CreatorCreditOrder> findByCreatorUserIdAndIdempotencyKey(String creatorUserId, String idempotencyKey);

    /** Ownership-scoped fetch — another creator's order id is indistinguishable from one that does not exist (K-21). */
    Optional<CreatorCreditOrder> findByIdAndCreatorUserId(String id, String creatorUserId);

    List<CreatorCreditOrder> findByCreatorUserIdOrderByCreatedAtDesc(String creatorUserId);

    /** Windowed sweep query for {@code CreatorCreditOrderReconciliationJob} — same "grace period + give-up horizon" shape as {@code WalletTopUpRepository#findByStatusAndCreatedAtBetween}. */
    List<CreatorCreditOrder> findByStatusAndCreatedAtBetween(
            CreatorCreditOrderStatus status, Instant after, Instant before);

    /**
     * F-6: the give-up side of the same sweep — orders OLDER than the give-up horizon that never
     * got picked up by {@link #findByStatusAndCreatedAtBetween}'s window (which stops at that same
     * horizon) and are still PENDING. Used only to log an ERROR alert; {@code
     * CreatorCreditOrderReconciliationJob} never attempts to credit these automatically.
     */
    List<CreatorCreditOrder> findByStatusAndCreatedAtBefore(CreatorCreditOrderStatus status, Instant before);
}
