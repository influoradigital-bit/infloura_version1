package com.influora.repository;

import com.influora.domain.entity.IdempotencyKeyRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRecordRepository extends JpaRepository<IdempotencyKeyRecord, String> {

    Optional<IdempotencyKeyRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * [Red-team F2 fix — stale IN_PROGRESS reservation reaper] Backing query for {@code
     * IdempotencyReservationReaperJob} — rows still {@code IN_PROGRESS} older than the reaper's
     * grace period. Nothing in {@link com.influora.service.IdempotencyService} ever transitions a
     * row OUT of {@code IN_PROGRESS} except {@code markCompletedTransactional}/{@code
     * markFailedTransactional}, both called from {@code executeOnce}'s own try/finally-equivalent
     * (try/catch) — a hard crash or kill between the reservation committing and the guarded action
     * returning skips both, leaving the row {@code IN_PROGRESS} forever with nothing else in the
     * system watching for it. Generic across every {@code executeOnce} caller/scope in the
     * codebase, not payout-retry-specific — the same failure mode applies to any of them.
     */
    List<IdempotencyKeyRecord> findByStatusAndCreatedAtBefore(
            IdempotencyKeyRecord.Status status, Instant threshold);

    /**
     * [Red-team F2 fix] Atomically reclaims a stale {@code IN_PROGRESS} row back to {@code FAILED}
     * so {@code IdempotencyService#executeOnce}'s OWN {@code reclaimFailedForRetry} can pick it up
     * on the next legitimate call for that key — reuses that existing, already-tested reclaim path
     * rather than a second one. Same WHERE-clause-is-the-arbiter discipline as {@link
     * #reclaimFailedForRetry}: the {@code createdAt < :threshold} guard means a row a genuinely
     * still-running (not crashed) caller is mid-way through can never be stolen out from under it —
     * only rows old enough that "still legitimately in flight" is implausible are matched.
     *
     * @return {@code 1} if this call reclaimed the row, {@code 0} if it was no longer {@code
     *     IN_PROGRESS} (already completed/failed/reclaimed by a concurrent run) or not yet past the
     *     threshold
     */
    @Modifying
    @Query(
            "UPDATE IdempotencyKeyRecord r SET r.status = :to WHERE r.idempotencyKey = :key AND"
                    + " r.status = :from AND r.createdAt < :threshold")
    int reclaimStaleInProgress(
            @Param("key") String idempotencyKey,
            @Param("from") IdempotencyKeyRecord.Status from,
            @Param("to") IdempotencyKeyRecord.Status to,
            @Param("threshold") Instant threshold);

    /**
     * [SEC: Kabir, E2 HIGH-1 -- fixed] Atomically flips a {@code FAILED} row back to {@code
     * IN_PROGRESS} so its caller can safely retry the guarded effect -- the {@code WHERE ...
     * AND status = :from} clause is the concurrency arbiter (same insert-first-wins discipline as
     * the initial reservation, just expressed as a conditional UPDATE instead of an INSERT): if two
     * callers race to reclaim the SAME failed key, exactly one UPDATE matches a row still in the
     * {@code FAILED} state (return value 1); the loser's UPDATE runs against a row already flipped
     * to {@code IN_PROGRESS} and matches zero rows (return value 0), so it is correctly told "still
     * in progress" rather than being allowed to re-run the effect a second time concurrently.
     * {@code COMPLETED} rows are never matched -- this WHERE clause is FAILED-only by construction,
     * so a completed key can never be "reclaimed" back into a re-runnable state.
     *
     * @return the number of rows updated -- {@code 1} if this caller won the reclaim, {@code 0} if
     *     the row was not in {@code FAILED} state (already reclaimed by a concurrent caller, still
     *     {@code IN_PROGRESS}, or already {@code COMPLETED})
     */
    @Modifying
    @Query(
            "UPDATE IdempotencyKeyRecord r SET r.status = :to WHERE r.idempotencyKey = :key AND r.status = :from")
    int reclaimFailedForRetry(
            @Param("key") String idempotencyKey,
            @Param("from") IdempotencyKeyRecord.Status from,
            @Param("to") IdempotencyKeyRecord.Status to);
}
