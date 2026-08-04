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

    /**
     * [Admin finance console, payout-retry] Rows in any of the given RazorpayX statuses — the
     * admin retry surface's natural "which payouts are currently retryable" listing query (e.g.
     * {@code findByStatusIn(List.of("rejected", "cancelled"))}). {@code PayoutReconciliationService
     * #retryFailedPayout} itself resolves a single row via {@link #findById} + a status check
     * (the id comes straight off the {@code POST /admin/finance/payouts/{id}/retry} path), so this
     * is the bulk-listing counterpart for a future "failed payouts" admin table.
     */
    List<Payout> findByStatusIn(List<String> statuses);

    /**
     * [Admin finance console, reconciliation] Backing query for {@code
     * AdminFinanceService#getReconciliation} — every {@link Payout} whose {@code createdAt} falls
     * on the admin-requested date, compared against RazorpayX's own record for the same id.
     */
    List<Payout> findByCreatedAtBetween(Instant start, Instant end);

    /**
     * [Red-team F2 fix, payout-retry stuck-debit sweep] Backing query for {@code
     * PayoutOrphanedDebitSweepJob}'s retry-path counterpart to {@link
     * #findByStatusAndCreatedAtBefore} — finds {@link Payout} rows sitting in one of {@code
     * PayoutReconciliationService#FAILURE_STATUSES} whose {@code updatedAt} is older than the
     * sweep's grace period, i.e. candidates whose most recent state transition (a failure webhook,
     * or a prior retry attempt's own failure) is old enough that a currently-in-flight retry is not
     * a plausible explanation. {@code PayoutReconciliationService#reconcileFailedPayoutRetry} then
     * checks each candidate for an actually-orphaned retry debit before touching anything.
     */
    List<Payout> findByStatusInAndUpdatedAtBefore(List<String> statuses, Instant threshold);
}
