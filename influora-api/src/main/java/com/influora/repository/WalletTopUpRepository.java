package com.influora.repository;

import com.influora.domain.entity.WalletTopUp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, String> {

    Optional<WalletTopUp> findByIdempotencyKey(String idempotencyKey);

    /**
     * [Admin finance console, reconciliation] Backing query for {@code
     * AdminFinanceService#getReconciliation} — every {@link WalletTopUp} whose {@code createdAt}
     * falls on the admin-requested date, compared against RazorpayX's own record for the same
     * order id.
     */
    List<WalletTopUp> findByCreatedAtBetween(Instant start, Instant end);
}
