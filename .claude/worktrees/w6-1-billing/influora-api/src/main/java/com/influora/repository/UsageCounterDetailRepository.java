package com.influora.repository;

import com.influora.domain.entity.UsageCounterDetail;
import com.influora.domain.enums.UsageMetric;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Per-(workspace, metric, period) distinct-entity dedup rows -- Task 22 Flag #1 fix. Unique
 * constraint on (workspace_id, metric, period_start, dedup_key) -- V58.
 */
public interface UsageCounterDetailRepository extends JpaRepository<UsageCounterDetail, String> {

    boolean existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
            String workspaceId, UsageMetric metric, LocalDate periodStart, String dedupKey);
}
