package com.influora.repository;

import com.influora.domain.entity.Payout;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, String> {

    Optional<Payout> findByRazorpayPayoutId(String razorpayPayoutId);

    Optional<Payout> findByIdempotencyKey(String idempotencyKey);

    Optional<Payout> findByMilestoneId(String milestoneId);

    /**
     * [P1 fix, SEC: Kabir 2b] Backing query for {@code PayoutOrphanedDebitSweepJob} -- finds
     * {@link Payout} rows still sitting in {@link Payout#STATUS_PENDING} (persisted BEFORE the
     * wallet debit and RazorpayX call by {@code PayoutService#doQueuePayout}) that are older than
     * the sweep's grace period, i.e. never advanced past PENDING by either a successful gateway
     * response or a prior sweep run.
     */
    List<Payout> findByStatusAndCreatedAtBefore(String status, Instant threshold);
}
