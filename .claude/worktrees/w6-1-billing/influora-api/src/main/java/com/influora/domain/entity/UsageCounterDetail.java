package com.influora.domain.entity;

import com.influora.domain.enums.UsageMetric;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Per-(workspace, metric, period) distinct-entity dedup row -- backs
 * {@link com.influora.service.billing.UsageCounterService#recordCreatorLookup} for per-creator-
 * lookup counting. V58__usage_counter_creator_dedup.sql, Task 22 Flag #1 fix (Rohan CFO ruling,
 * SHARED_CONTEXT.md 2026-07-14).
 *
 * <p>One row is inserted the FIRST time a given {@code dedupKey} (e.g. a creatorId) is looked at
 * within a (workspace, metric, period) triple; its existence is what lets
 * {@code AnalyticsUsageCapInterceptor} tell "already-seen creator this period, allow for free"
 * apart from "genuinely new creator lookup, check against the plan limit before counting it."
 *
 * <p>Unique constraint on (workspace_id, metric, period_start, dedup_key) -- the concurrency-
 * safety mechanism: two requests racing to record the SAME new creator can only insert one
 * winning row; the loser's insert fails with a duplicate-key violation, caught by
 * {@code UsageCounterService} and treated as "already recorded by the concurrent winner," never
 * a double increment of {@link UsageCounter#getUsed()}.
 */
@Entity
@Table(name = "usage_counter_details")
public class UsageCounterDetail {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UsageMetric metric;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "dedup_key", nullable = false, length = 50)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UsageCounterDetail() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public UsageMetric getMetric() {
        return metric;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final UsageCounterDetail d = new UsageCounterDetail();

        public Builder id(String id) {
            d.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            d.workspaceId = workspaceId;
            return this;
        }

        public Builder metric(UsageMetric metric) {
            d.metric = metric;
            return this;
        }

        public Builder periodStart(LocalDate periodStart) {
            d.periodStart = periodStart;
            return this;
        }

        public Builder dedupKey(String dedupKey) {
            d.dedupKey = dedupKey;
            return this;
        }

        public UsageCounterDetail build() {
            d.createdAt = Instant.now();
            return d;
        }
    }
}
