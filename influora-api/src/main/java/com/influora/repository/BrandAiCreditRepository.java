package com.influora.repository;

import com.influora.domain.entity.BrandAiCredit;
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
}
