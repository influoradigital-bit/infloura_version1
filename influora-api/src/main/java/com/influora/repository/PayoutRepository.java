package com.influora.repository;

import com.influora.domain.entity.Payout;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * [V71, manual payout rail] Looks a payout up by its bank UTR / transaction reference.
     *
     * <p>Backs the pre-insert duplicate check in {@code AdminFinanceService#recordManualPayout}: a
     * UTR identifies exactly one real bank transfer, so a second row carrying the same one means
     * either the money was sent twice or an admin double-submitted the form. The unique index on
     * the column is the actual guarantee — this exists so the caller can return a clean 409 instead
     * of surfacing a constraint violation.
     */
    Optional<Payout> findByBankReference(String bankReference);

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

    /**
     * [F-0281/F-0336] Sum of {@link Payout} amounts for one creator that are still IN FLIGHT to
     * their bank — i.e. {@code confirmedAt IS NULL}. Powers the creator wallet summary's {@code
     * pendingPayouts} figure ({@code WalletService#getSummaryForUser}).
     *
     * <p>{@code confirmedAt} is the right predicate, not a status list, because of how {@link
     * Payout} actually stamps it: {@link Payout#confirmStatus} sets it on EVERY terminal webhook —
     * {@code processed} (money landed, no longer "pending"), and {@code
     * reversed}/{@code rejected}/{@code cancelled} ({@link
     * com.influora.service.PayoutReconciliationService#FAILURE_STATUSES} — money never landed and
     * was already re-credited to the wallet by {@code reCreditReversedPayout}, so it must not be
     * double-counted here as still in flight). Only the pre-terminal states — {@code queued},
     * {@code pending}, {@code processing}, and this entity's own {@link
     * Payout#STATUS_PENDING} pre-gateway marker — leave {@code confirmedAt} null, and every one of
     * those genuinely means "debited from the wallet, not yet resolved by the gateway", which is
     * exactly the money a creator would call "pending". {@code COALESCE(...,0)} matches this
     * repository's sibling sum-query convention (see {@code EscrowHoldRepository}/{@code
     * PaymentMilestoneRepository}) so a creator with nothing in flight gets a non-null zero.
     */
    @Query(
            "SELECT COALESCE(SUM(p.amount), 0) FROM Payout p "
                    + "WHERE p.creatorUserId = :creatorUserId AND p.confirmedAt IS NULL")
    BigDecimal sumAmountByCreatorUserIdAndConfirmedAtIsNull(
            @Param("creatorUserId") String creatorUserId);
}
