package com.influora.repository;

import com.influora.domain.entity.IdempotencyKeyRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
    // [SEC: Vikram, round-4 Finding 2] clearAutomatically = true -- see #markCompleted's javadoc
    // for why a bulk @Modifying UPDATE needs this: without it, an entity already loaded earlier in
    // the SAME (ambient) persistence context stays stale after this UPDATE, even though the row is
    // correctly updated in the DB.
    @Modifying(clearAutomatically = true)
    @Transactional
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
    // [SEC: Vikram, round-4 Finding 2] clearAutomatically = true -- see #markCompleted's javadoc.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
            "UPDATE IdempotencyKeyRecord r SET r.status = :to WHERE r.idempotencyKey = :key AND r.status = :from")
    int reclaimFailedForRetry(
            @Param("key") String idempotencyKey,
            @Param("from") IdempotencyKeyRecord.Status from,
            @Param("to") IdempotencyKeyRecord.Status to);

    /**
     * [SEC: Priya round-2 review fix, Defect 2] Replaces {@code
     * IdempotencyService#markCompletedTransactional}'s old find-then-mutate-in-memory approach,
     * which never persisted anything: {@code executeOnce}/{@code runExclusive} invoke that method
     * via self-invocation ({@code this.markCompletedTransactional(...)}), which bypasses the
     * Spring AOP transactional proxy entirely (Spring's documented self-invocation limitation), so
     * the method's own {@code @Transactional} was a no-op; the entity returned by {@code
     * findByIdempotencyKey} was already detached by the time {@code record.markCompleted(...)}
     * mutated it in memory, and that mutation was never flushed to the DB — the row stayed {@code
     * IN_PROGRESS} forever (until the 15-minute reaper swept it to {@code FAILED}), so {@code
     * isCompleted}/{@code findCompletedResultDigest} could never resolve a freshly-completed key.
     * A single {@code @Modifying} UPDATE (same pattern as {@link #reclaimFailedForRetry}) fixes
     * that.
     *
     * <p><b>[Task item 3, verified] {@code @Transactional} is REQUIRED here, not optional.</b> A
     * {@code @Modifying} JPQL {@code UPDATE} calls {@code Query#executeUpdate()}, which Hibernate
     * refuses outright ({@code jakarta.persistence.TransactionRequiredException}, surfaced by
     * Spring as {@code InvalidDataAccessApiUsageException}) unless a transaction is ALREADY active
     * at the moment the repository method is invoked — confirmed empirically by a real (non-Mockito)
     * {@code @DataJpaTest} (see {@code IdempotencyServicePersistenceTest}) that reproduced exactly
     * this exception before this annotation was added, calling this method with no ambient
     * transaction (mirroring {@code IdempotencyService}'s actual self-invocation call sites, which
     * have none either). Unlike CRUD methods ({@code save}/{@code findById}/etc., which Spring Data
     *'s {@code SimpleJpaRepository} itself already annotates {@code @Transactional}), a bare custom
     * {@code @Query}+{@code @Modifying} method interface declaration does NOT reliably pick up a
     * transaction from "default repository transactions" in practice in this app — this repository's
     * sibling {@code UsageCounterRepository#tryIncrement} already established the working
     * convention (explicit {@code @Transactional} directly on the {@code @Modifying} method) that
     * this method and the three others below now follow. {@code reclaimFailedForRetry} above and
     * {@code reclaimStaleInProgress} were retroactively given the same annotation for the identical
     * reason — {@code reclaimFailedForRetry} was previously unreachable in the checkout path only
     * because Defect 1 made every reservation spuriously succeed, so this latent bug never actually
     * fired; fixing Defect 1 makes the reclaim path reachable, which is what surfaced this one.
     *
     * @return {@code 1} if the row was found and updated, {@code 0} if the key is unknown (should
     *     not happen on the real success path — the row was just reserved/reclaimed by this same
     *     caller — but is not itself a correctness issue if it does since there is nothing to mark)
     */
    // [SEC: Vikram, round-4 Finding 2, Priya's ambient-transaction review] clearAutomatically = true.
    // A bulk JPQL @Modifying UPDATE goes straight to the DB and bypasses the first-level (Hibernate
    // Session) persistence context entirely -- an entity for this SAME row, if already loaded
    // earlier in the calling transaction (e.g. a caller that read the row via findByIdempotencyKey
    // before calling isCompleted()/executeOnce() again in the same ambient transaction), stays
    // cached with its PRE-update in-memory state. Without clearAutomatically, a subsequent
    // findByIdempotencyKey()/isCompleted() call in that SAME transaction can return the stale
    // (pre-UPDATE) status even though the UPDATE already committed correctly at the DB level --
    // proved empirically (see IdempotencyServicePersistenceTest#markCompletedClearsStaleEntityInSameAmbientTransaction):
    // isCompleted() read false immediately after a commit-visible COMPLETED write, in the same
    // transaction, before this fix. clearAutomatically = true clears the persistence context after
    // the UPDATE executes, forcing the next read in the same transaction to hit the DB fresh.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
            "UPDATE IdempotencyKeyRecord r SET r.status = :status, r.resultDigest = :resultDigest,"
                    + " r.completedAt = :completedAt WHERE r.idempotencyKey = :key")
    int markCompleted(
            @Param("key") String idempotencyKey,
            @Param("status") IdempotencyKeyRecord.Status status,
            @Param("resultDigest") String resultDigest,
            @Param("completedAt") Instant completedAt);

    /**
     * [SEC: Priya round-2 review fix, Defect 2] {@code FAILED} counterpart to {@link
     * #markCompleted} — see that method's javadoc for why this replaces the old
     * find-then-mutate-in-memory {@code markFailedTransactional} body, and for why the explicit
     * {@code @Transactional} below is load-bearing, not decorative. A row that never actually
     * reaches {@code FAILED} in the DB is not just "not retryable promptly" — {@code
     * reclaimFailedForRetry}'s {@code WHERE status = FAILED} guard can never match it either, so
     * the row would only ever recover via the 15-minute stale-{@code IN_PROGRESS} reaper, not the
     * intended immediate reclaim-on-next-call path.
     *
     * @return {@code 1} if the row was found and updated, {@code 0} if the key is unknown
     */
    // [SEC: Vikram, round-4 Finding 2] clearAutomatically = true -- see #markCompleted's javadoc.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
            "UPDATE IdempotencyKeyRecord r SET r.status = :status, r.completedAt = :completedAt"
                    + " WHERE r.idempotencyKey = :key")
    int markFailed(
            @Param("key") String idempotencyKey,
            @Param("status") IdempotencyKeyRecord.Status status,
            @Param("completedAt") Instant completedAt);
}
