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
 * Per-workspace, per-metric, per-billing-cycle usage counters for plan limits.
 * V54__subscription_billing.sql, Task 11/14 subscription billing prep batch 1.
 *
 * <p>Unique constraint on (workspace_id, metric, period_start) — one counter per metric per cycle.
 * Task 15's {@code PlanGateFilter} will check these counters before allowing gated operations.
 */
@Entity
@Table(name = "usage_counters")
public class UsageCounter {

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

    @Column(nullable = false)
    private int used;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsageCounter() {}

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

    public int getUsed() {
        return used;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void increment() {
        this.used++;
        touch();
    }

    public void reset() {
        this.used = 0;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final UsageCounter u = new UsageCounter();

        public Builder id(String id) {
            u.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            u.workspaceId = workspaceId;
            return this;
        }

        public Builder metric(UsageMetric metric) {
            u.metric = metric;
            return this;
        }

        public Builder periodStart(LocalDate periodStart) {
            u.periodStart = periodStart;
            return this;
        }

        public Builder used(int used) {
            u.used = used;
            return this;
        }

        public UsageCounter build() {
            Instant now = Instant.now();
            u.createdAt = now;
            u.updatedAt = now;
            if (u.used == 0) {
                u.used = 0;
            }
            return u;
        }
    }
}
