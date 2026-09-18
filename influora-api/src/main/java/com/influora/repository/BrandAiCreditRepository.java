package com.influora.repository;

import com.influora.domain.entity.BrandAiCredit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BrandAiCreditRepository extends JpaRepository<BrandAiCredit, String> {

    /** 1:1 workspace lookup — the PK is the workspaceId, so this is inherently tenant-scoped. */
    Optional<BrandAiCredit> findByWorkspaceId(String workspaceId);

    /**
     * Atomic, race-safe decrement: only succeeds (returns 1) if credits are still available.
     * Mirrors the {@code UPDATE ... WHERE credits_remaining > 0} pattern mandated by Guardrail 5
     * so concurrent turns can never drive the balance negative.
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.creditsRemaining - :cost, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId AND c.creditsRemaining >= :cost")
    int tryDecrement(@Param("workspaceId") String workspaceId, @Param("cost") int cost);

    /**
     * Refund counterpart to {@link #tryDecrement} — used by {@code AICreditService#release} to
     * un-charge a turn whose provider call failed after the send-time charge. The caller (guarded
     * by the release ledger) only ever refunds an amount it already knows was actually decremented
     * for this exact turn, but the add is still clamped to {@code monthlyAllotment} (CASE WHEN,
     * matching the {@code GREATEST}-style pattern in {@link #refundDailyActions} below — plain
     * JPQL has no {@code LEAST} function): a monthly reset landing between a turn's send-time
     * charge and its later release would otherwise let this unconditional add push
     * {@code creditsRemaining} above the workspace's allotment.
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = "
                    + "CASE WHEN c.creditsRemaining + :amount > c.monthlyAllotment THEN c.monthlyAllotment "
                    + "ELSE c.creditsRemaining + :amount END, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId")
    int refundCredits(@Param("workspaceId") String workspaceId, @Param("amount") int amount);

    /**
     * Refund counterpart to the daily-action-counter bump in {@code AICreditService#tryConsume} —
     * floors at 0 (via {@code GREATEST}) and only applies if {@code dailyActionsDate} is still the
     * SAME day the charge was recorded against; a day rollover already reset the counter for real,
     * so refunding a stale day's counter would be meaningless (and could under/overflow the new
     * day's count).
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.dailyActionsUsed = "
                    + "CASE WHEN c.dailyActionsUsed > :amount THEN c.dailyActionsUsed - :amount ELSE 0 END, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId AND c.dailyActionsDate = :today")
    int refundDailyActions(
            @Param("workspaceId") String workspaceId,
            @Param("amount") int amount,
            @Param("today") LocalDate today);

    /**
     * T-S3-F0879-0917 [vikram · 2026-09-17] -- credit-race fix. Atomically bumps the P4 daily
     * action counter (rolling over to 1 if {@code dailyActionsDate} is not {@code :today}) WITHOUT
     * touching {@code creditsRemaining} or any other column. This replaces the old {@code
     * AICreditService#tryConsume} pattern of mutating the managed {@code BrandAiCredit} entity's
     * daily-action fields and calling {@code save(credit)} -- that blind full-row save wrote back
     * EVERY mapped column using the entity's IN-MEMORY state as read at the start of the
     * transaction, including {@code creditsRemaining}. Under concurrency, that stale write could
     * land AFTER a concurrent transaction's {@link #tryDecrement} had already correctly decremented
     * {@code creditsRemaining}, silently reverting it back to the pre-decrement value -- letting a
     * second turn spend a credit that was already spent (two turns at 1 remaining credit both
     * succeeding). Isolating the daily-action bump to its own single-column-scoped UPDATE, and never
     * calling a setter on the managed entity for these fields in {@code tryConsume} any more, means
     * nothing in that hot path ever writes {@code creditsRemaining} except {@link #tryDecrement}
     * itself.
     *   Source: assignments-0917-subscription.md S3 "Credit race" (tech N4, T-5)
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.dailyActionsUsed = "
                    + "CASE WHEN c.dailyActionsDate = :today THEN c.dailyActionsUsed + 1 ELSE 1 END, "
                    + "c.dailyActionsDate = :today, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId")
    int bumpDailyActions(@Param("workspaceId") String workspaceId, @Param("today") LocalDate today);

    /**
     * T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18] -- F-0885 (lost update) on the grant
     * path. Syncs {@code planAllotment} (and the derived {@code monthlyAllotment}, recomputed
     * server-side against the row's CURRENT {@code loyaltyBonus} rather than a possibly-stale
     * in-memory value) via a single-scoped UPDATE that never touches {@code creditsRemaining} or
     * any other column. Replaces {@code AICreditService#applyPlanAllotment}'s old blind full-row
     * {@code save(credit)}, which could clobber a concurrent {@link #tryDecrement} the same way
     * the credit-race fix closed for {@link #bumpDailyActions} -- see that method's javadoc for
     * the shape this mirrors.
     *   Source: F-0885 repair round (Kabir MEDIUM), RULING-upgrade-grant.md
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.planAllotment = :planAllotment, "
                    + "c.monthlyAllotment = :planAllotment + c.loyaltyBonus, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId")
    int syncPlanAllotment(@Param("workspaceId") String workspaceId, @Param("planAllotment") int planAllotment);

    /**
     * T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18] -- the grant itself (F-0881 ruling +
     * F-0883 + F-0885). SETs {@code creditsRemaining} to the full new allotment, atomically
     * guarded so a repeat grant for the SAME billing period (identified by the subscription's
     * {@code currentPeriodEnd}, passed in as {@code periodEnd}) is a no-op (returns 0 rows
     * updated) -- this is the authoritative guard, not just a pre-check in the service layer:
     * two concurrent callers race on the same InnoDB row lock, so only one can ever win for a
     * given {@code periodEnd}. {@code periodEnd IS NULL} (no resolvable subscription/billing
     * period) intentionally disables the guard rather than blocking the grant -- see
     * {@code AICreditService#applyPlanAllotment} javadoc for why failing open here is the safer
     * default than silently withholding a paid brand's allowance.
     *   Source: F-0881/F-0883/F-0885 repair round, RULING-upgrade-grant.md
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = :newAllotment, "
                    + "c.creditGrantPeriodEnd = :periodEnd, "
                    + "c.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE c.workspaceId = :workspaceId "
                    + "AND (:periodEnd IS NULL OR c.creditGrantPeriodEnd IS NULL "
                    + "OR c.creditGrantPeriodEnd <> :periodEnd)")
    int grantAllotmentIncrease(
            @Param("workspaceId") String workspaceId,
            @Param("newAllotment") int newAllotment,
            @Param("periodEnd") Instant periodEnd);
}
