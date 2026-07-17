package com.influora.repository;

import com.influora.domain.entity.IdempotencyKeyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRecordRepository extends JpaRepository<IdempotencyKeyRecord, String> {

    Optional<IdempotencyKeyRecord> findByIdempotencyKey(String idempotencyKey);

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
