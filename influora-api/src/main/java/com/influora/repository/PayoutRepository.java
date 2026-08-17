package com.influora.repository;

import com.influora.domain.entity.Payout;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, String> {

    /**
     * CR-77 — backing query for {@code GET /wallet/payouts}, the creator-facing payout history.
     *
     * <p>Tenant-scoped by {@code creatorUserId} in the derived query itself, not by a filter
     * applied after a broad read: the caller's own id comes straight off the authenticated
     * principal, so a payout belonging to another creator can never enter the result set. (Same
     * discipline as CR-111 — a scoping predicate that lives in the query, not in the service.)
     *
     * <p>Ordered newest-first because this feeds a reverse-chronological history list; {@code
     * createdAt} is the request time, which is the order a creator experienced these in, whereas
     * {@code confirmedAt} is null until settlement and would sort unsettled rows unpredictably.
     *
     * <p><b>Do not put an {@code @Query} on this method.</b> It carried one until F-0234:
     * {@code "select p from Payout p order by p.createdAt desc"} — no {@code where} clause. An
     * explicit {@code @Query} REPLACES derivation from the method name outright, so
     * {@code creatorUserId} was accepted and silently ignored, and {@code GET /wallet/payouts}
     * returned every creator's payouts to any authenticated creator. The three javadoc paragraphs
     * above, and {@code WalletService#getPayoutsForCreator}'s own comment, all described a scoping
     * predicate that was not in the executed SQL. Leaving the method name to derive the query is
     * what makes the tenancy guarantee structural: the name IS the where clause, and it cannot
     * drift from a hand-written string. {@code PayoutRepositoryCreatorScopingTest} executes real
     * SQL against H2 precisely so this class of defect cannot be green again.
     */
    Page<Payout> findByCreatorUserIdOrderByCreatedAtDesc(String creatorUserId, Pageable pageable);

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
