package com.influora.repository;

import com.influora.domain.entity.UsageCounter;
import com.influora.domain.enums.UsageMetric;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-workspace, per-metric, per-billing-cycle usage counters for plan-limit gating. Task 17
 * subscription-billing functional core, Phase 1. Unique constraint on (workspace_id, metric,
 * period_start) — V54.
 */
public interface UsageCounterRepository extends JpaRepository<UsageCounter, String> {

    Optional<UsageCounter> findByWorkspaceIdAndMetricAndPeriodStart(
            String workspaceId, UsageMetric metric, LocalDate periodStart);

    List<UsageCounter> findByWorkspaceIdAndPeriodStart(String workspaceId, LocalDate periodStart);

    /**
     * Atomic, race-safe increment of an existing counter row — mirrors {@code
     * BrandAiCreditRepository.tryDecrement}'s {@code UPDATE ... WHERE} pattern. Returns the number
     * of rows updated (0 or 1): 0 means no counter row exists yet for this (workspace, metric,
     * period) triple, and the caller ({@code UsageCounterService.incrementUsage}) must create one.
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE UsageCounter u SET u.used = u.used + :amount, u.updatedAt = CURRENT_TIMESTAMP "
                    + "WHERE u.workspaceId = :workspaceId AND u.metric = :metric AND u.periodStart = :periodStart")
    int tryIncrement(
            @Param("workspaceId") String workspaceId,
            @Param("metric") UsageMetric metric,
            @Param("periodStart") LocalDate periodStart,
            @Param("amount") int amount);
}
