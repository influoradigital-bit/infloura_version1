package com.influora.repository;

import com.influora.domain.entity.MeeraInteractionLog;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage-abstraction repository for {@code meera_interaction_log} (V20260721160000) — Phase 2
 * item 2.3's flywheel event log. Write-only for reads for this pass ({@code save}, inherited from
 * {@link JpaRepository}) — no read/analytics query yet, matching the build design's explicit scope
 * cut ("that's a fast-follow once there's data to look at"). {@link #deleteByCreatedAtBefore} is
 * the one deliberate exception: an age-only bulk DELETE for the retention purge (Priya's PARTIAL-2
 * ruling, wiki/build/partials-resolution-plan.md; Kabir L1, wiki/build/phase2-kabir-security.md).
 * It reads/matches on {@code created_at} alone — no other column, no join — so it does not create a
 * read/analytics query surface and does not weaken the write-only info-barrier invariant. Add
 * finder methods here when the funnel read ("options presented -> tapped -> draft -> funded") is
 * actually built.
 */
public interface MeeraInteractionLogRepository extends JpaRepository<MeeraInteractionLog, String> {

    /**
     * Retention purge — bulk age-based delete, NOT load-then-delete (no entities are ever
     * materialized). {@code created_at} is only the trailing column of {@code
     * idx_meera_interaction_workspace_time (workspace_id, created_at)} (V20260721160000), so this
     * age-only predicate does not get a leading-column index seek from that composite index; it was
     * ruled acceptable as-is (not a full-table scan concern at current/near-term row volumes — see
     * wiki/build/partials-resolution-plan.md's "early traffic will not overflow this table" note).
     * If purge volume grows enough to matter, add a dedicated single-column index on {@code
     * created_at}.
     *

     * <p>{@code clearAutomatically = true} evicts the persistence context after the bulk delete —
     * same convention as {@code RefreshTokenRepository#revokeAllForUser} — so a stale in-memory
     * reference to a just-deleted row is never possible within the same transaction. {@code
     * @Transactional} is declared here (not left to the caller) for the same reason {@code
     * RefreshTokenRepository} declares it on the repository method itself: a {@code @Modifying}
     * query requires an active transaction, and this repository has no service-layer wrapper today.
     *
     * @return the number of rows deleted, per Spring Data's bulk-{@code @Modifying} return-count
     *     convention (see {@code IdempotencyKeyRecordRepository#reclaimFailedForRetry}).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM MeeraInteractionLog l WHERE l.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
