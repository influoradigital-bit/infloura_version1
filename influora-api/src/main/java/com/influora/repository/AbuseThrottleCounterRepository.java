package com.influora.repository;

import com.influora.domain.entity.AbuseThrottleCounter;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link AbuseThrottleCounter} (T-FESTIVALBOX-0905 phase 9). See the
 * {@code V20260905190000__abuse_throttle_counters.sql} migration header for the full rationale.
 */
@Repository
public interface AbuseThrottleCounterRepository extends JpaRepository<AbuseThrottleCounter, String> {

    /**
     * Atomically records one attempt against {@code (throttleKey, windowStart)}, creating the
     * bucket row on the first attempt of the window and incrementing {@code request_count} on
     * every one after that — in ONE statement, with no read step in application code.
     *
     * <p><b>Why a native {@code INSERT ... ON DUPLICATE KEY UPDATE} and not
     * find-then-increment-then-save, and not a {@code PESSIMISTIC_WRITE} row lock:</b> exactly the
     * same reasoning {@code FestivalCouponCopyRepository#recordCopy} already documents — a counter
     * has no read-modify-DECIDE step to protect, only "add one", so MySQL's own atomic upsert does
     * the whole job in the statement that locates the row, with no lock held across a round trip to
     * application code and no TOCTOU gap for a second concurrent attempt to fall into. This is the
     * fix for the exact gap {@code FestivalEnquiryService#enforceThrottle} used to have: a
     * find-then-increment-then-save version of this method would lose updates under concurrent
     * attempts for the same key, which is the scenario the concurrency test for this table drives.
     *
     * <p>{@code id} is only used on the INSERT branch; a concurrent call that instead matches the
     * unique key takes the UPDATE branch and the freshly-generated id it was called with is simply
     * discarded by MySQL.
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "INSERT INTO abuse_throttle_counters "
                            + "(id, throttle_key, window_start, request_count, created_at, updated_at) "
                            + "VALUES (:id, :throttleKey, :windowStart, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                            + "ON DUPLICATE KEY UPDATE "
                            + "request_count = request_count + 1, "
                            + "updated_at = CURRENT_TIMESTAMP",
            nativeQuery = true)
    void increment(
            @Param("id") String id,
            @Param("throttleKey") String throttleKey,
            @Param("windowStart") Instant windowStart);

    /**
     * Reads the current count for one bucket, immediately after calling {@link #increment} in the
     * SAME transaction. This is a plain (non-locking) read, not a second atomic step — it is safe
     * only because a transaction always sees its own uncommitted writes regardless of the
     * transaction's snapshot start time (InnoDB's "read your own writes" guarantee holds under
     * REPEATABLE READ), so this never observes a value older than the increment this same
     * transaction just performed. {@link com.influora.service.security.AbuseThrottleService} is the
     * only intended caller of this pair — do not call this alone to make a cap decision without
     * having called {@link #increment} first in the same transaction, since a stand-alone read here
     * is exactly the stale-snapshot read the old {@code SELECT COUNT} throttle had.
     */
    Optional<AbuseThrottleCounter> findByThrottleKeyAndWindowStart(
            String throttleKey, Instant windowStart);
}
