package com.influora.repository;

import com.influora.domain.entity.WalletTopUp;
import com.influora.domain.enums.WalletTopUpStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, String> {

    Optional<WalletTopUp> findByIdempotencyKey(String idempotencyKey);

    /**
     * Row-locked read for {@code WalletTopUpService#confirmCredited}. Two callers can now try to
     * credit the same top-up at once — the {@code payment.captured} webhook (including Razorpay's
     * own duplicate deliveries) and {@code WalletTopUpReconciliationJob} — and the PENDING check
     * in {@code confirmCredited} is a check-then-act. The ledger's idempotency key already stops a
     * double posting; this lock makes the second caller wait and then observe CREDITED, so the
     * check itself is authoritative instead of relying on a caught constraint violation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WalletTopUp t where t.id = :id")
    Optional<WalletTopUp> findByIdForUpdate(@Param("id") String id);

    /**
     * [Admin finance console, reconciliation] Backing query for {@code
     * AdminFinanceService#getReconciliation} — every {@link WalletTopUp} whose {@code createdAt}
     * falls on the admin-requested date, compared against RazorpayX's own record for the same
     * order id.
     */
    List<WalletTopUp> findByCreatedAtBetween(Instant start, Instant end);

    /**
     * Top-ups still in {@code status} whose {@code createdAt} falls inside a window — the sweep
     * query behind {@code WalletTopUpReconciliationJob}.
     *
     * <p>Windowed on purpose. A single {@code createdAt < cutoff} scan would grow without bound as
     * abandoned PENDING orders (a brand who opened checkout and never paid — the normal case)
     * accumulate forever, and the sweep would keep asking Razorpay about orders that will never be
     * paid. {@code after} is the give-up horizon, {@code before} the grace period.
     */
    List<WalletTopUp> findByStatusAndCreatedAtBetween(
            WalletTopUpStatus status, Instant after, Instant before);
}
