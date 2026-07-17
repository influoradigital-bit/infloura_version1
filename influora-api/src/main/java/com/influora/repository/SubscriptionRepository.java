package com.influora.repository;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Workspace subscriptions — 1:1 with workspaces. Task 17 subscription-billing functional core,
 * Phase 1. {@link JpaSpecificationExecutor} added Task 25 ({@code AdminBillingController}'s
 * paginated/filterable subscriptions list) — same pattern as {@code WorkspaceRepository}/{@code
 * AdminAuditLogRepository}, see {@code SubscriptionAdminSpecs}.
 */
public interface SubscriptionRepository
        extends JpaRepository<Subscription, String>, JpaSpecificationExecutor<Subscription> {

    /** 1:1 workspace lookup — {@code workspace_id} is UNIQUE at the schema level (V54). */
    Optional<Subscription> findByWorkspaceId(String workspaceId);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    List<Subscription> findByStatus(SubscriptionStatus status);

    /**
     * MRR count (AdminBillingService#getMetrics) — ACTIVE, on the given plan, and NOT an
     * admin-granted comp (comp'd Pro subscriptions generate zero revenue and must not inflate
     * MRR).
     */
    long countByStatusAndPlanIdAndCompFalse(SubscriptionStatus status, String planId);

    /**
     * Churn-window numerator (AdminBillingService#getMetrics) — same ACTIVE/plan/non-comp scope
     * as {@link #countByStatusAndPlanIdAndCompFalse}, but for {@code CANCELLED} status within a
     * trailing window, keyed off {@code updatedAt} (set by {@link Subscription#setStatus} via
     * {@code touch()} on every transition, so this is "cancelled within the last N days").
     */
    long countByStatusAndPlanIdAndCompFalseAndUpdatedAtAfter(
            SubscriptionStatus status, String planId, Instant after);
}
