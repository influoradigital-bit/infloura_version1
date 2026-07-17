package com.influora.service.billing;

import com.influora.common.Ulids;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.UsageCounter;
import com.influora.domain.entity.UsageCounterDetail;
import com.influora.domain.enums.UsageMetric;
import com.influora.repository.UsageCounterDetailRepository;
import com.influora.repository.UsageCounterRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-workspace, per-metric, per-billing-cycle usage counters (tracked creators, creator-analytics
 * views, exports). Task 18 subscription-billing functional core, Phase 1. Task 22's {@code
 * PlanGateFilter} (Phase 3) will read {@link #getUsageForCurrentPeriod} before allowing a gated
 * operation and call {@link #incrementUsage} after.
 *
 * <p><b>Per-creator-lookup dedup (Task 22 Flag #1 fix, Rohan CFO ruling in SHARED_CONTEXT.md
 * 2026-07-14):</b> {@link #recordCreatorLookup} is the cap-checked entry point for metrics where
 * "one unit of usage" means one distinct entity looked at (e.g. one creator), not one API call.
 * It backs {@code AnalyticsUsageCapInterceptor}, which calls {@code getUsageForCurrentPeriod}/
 * {@code incrementUsage} directly ONLY for unlimited (Pro) plans.
 */
@Service
public class UsageCounterService {

    private final UsageCounterRepository usageCounterRepository;
    private final UsageCounterDetailRepository usageCounterDetailRepository;
    private final SubscriptionService subscriptionService;

    public UsageCounterService(
            UsageCounterRepository usageCounterRepository,
            UsageCounterDetailRepository usageCounterDetailRepository,
            SubscriptionService subscriptionService) {
        this.usageCounterRepository = usageCounterRepository;
        this.usageCounterDetailRepository = usageCounterDetailRepository;
        this.subscriptionService = subscriptionService;
    }

    public int getUsageForCurrentPeriod(String workspaceId, UsageMetric metric) {
        LocalDate periodStart = resolvePeriodStart(workspaceId);
        return getUsageForPeriod(workspaceId, metric, periodStart);
    }

    private int getUsageForPeriod(String workspaceId, UsageMetric metric, LocalDate periodStart) {
        return usageCounterRepository
                .findByWorkspaceIdAndMetricAndPeriodStart(workspaceId, metric, periodStart)
                .map(UsageCounter::getUsed)
                .orElse(0);
    }

    /**
     * Cap-checked, per-distinct-entity usage gate. One "unit" of {@code metric} is counted per
     * distinct {@code dedupKey} (e.g. creatorId) per billing period, not per call -- so a caller
     * hitting several sub-endpoints for the SAME entity (e.g. a brand's metrics/scores/
     * demographics/media calls for one creator) only ever consumes one unit of quota.
     *
     * <p>Ordering (matches the existing check-then-increment discipline in {@link
     * #incrementUsage}, extended with a dedup lookup first):
     *
     * <ol>
     *   <li>If {@code dedupKey} was already recorded this period, allow unconditionally -- no new
     *       charge, regardless of how close to/over the limit the workspace already is.
     *   <li>Otherwise this is a genuinely NEW entity for this period -- check {@code used >=
     *       limit} BEFORE recording/counting it, so a rejected lookup never consumes quota.
     *   <li>If under the limit, attempt to insert the dedup row. {@code usage_counter_details}'s
     *       unique constraint (workspace_id, metric, period_start, dedup_key) makes this the
     *       concurrency-safety mechanism: if two requests race to record the SAME new {@code
     *       dedupKey}, only one insert can win. The loser's {@link
     *       DataIntegrityViolationException} is caught and treated as "already recorded by the
     *       concurrent winner" -- allowed, but it must NOT also increment {@link
     *       UsageCounter#getUsed()}, or one creator-lookup would double-count.
     *   <li>Only the genuine winner of that race increments the counter.
     * </ol>
     *
     * @return true if the call is allowed (already-seen entity, or a genuinely new one that's
     *     under {@code limit}); false if this is a new {@code dedupKey} that would push usage
     *     to/over {@code limit} -- the caller must reject the request and must not have consumed
     *     any quota.
     */
    @Transactional
    public boolean recordCreatorLookup(
            String workspaceId, UsageMetric metric, String dedupKey, int limit) {
        LocalDate periodStart = resolvePeriodStart(workspaceId);

        if (usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                workspaceId, metric, periodStart, dedupKey)) {
            // Already-seen entity this period -- always allow, never charge again.
            return true;
        }

        int used = getUsageForPeriod(workspaceId, metric, periodStart);
        if (used >= limit) {
            // Genuinely new entity, but recording it would exceed the limit -- reject BEFORE
            // recording/counting so no quota is consumed on a rejected lookup.
            return false;
        }

        UsageCounterDetail detail =
                UsageCounterDetail.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .metric(metric)
                        .periodStart(periodStart)
                        .dedupKey(dedupKey)
                        .build();
        try {
            usageCounterDetailRepository.save(detail);
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request for this same dedupKey won the race and already
            // recorded/counted it -- treat this call as already-seen too. Do NOT increment again.
            return true;
        }

        // We genuinely won the race to record this NEW entity for this period -- count it.
        incrementUsageForPeriod(workspaceId, metric, periodStart, 1);
        return true;
    }

    /**
     * Upsert-increment: atomically bumps an existing counter row (mirrors {@code
     * BrandAiCreditRepository.tryDecrement}'s race-safe {@code UPDATE ... WHERE} pattern via
     * {@code UsageCounterRepository.tryIncrement}); if no row exists yet for this (workspace,
     * metric, period) triple, creates one. The create-then-race-loses path is handled by catching
     * a unique-constraint violation (V54's {@code uk_workspace_metric_period}) and retrying the
     * atomic update, since a concurrent request must have won the insert.
     */
    @Transactional
    public void incrementUsage(String workspaceId, UsageMetric metric, int amount) {
        incrementUsageForPeriod(workspaceId, metric, resolvePeriodStart(workspaceId), amount);
    }

    private void incrementUsageForPeriod(
            String workspaceId, UsageMetric metric, LocalDate periodStart, int amount) {
        int updated = usageCounterRepository.tryIncrement(workspaceId, metric, periodStart, amount);
        if (updated > 0) {
            return;
        }
        UsageCounter counter =
                UsageCounter.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .metric(metric)
                        .periodStart(periodStart)
                        .used(amount)
                        .build();
        try {
            usageCounterRepository.save(counter);
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created the row between our failed UPDATE and this INSERT — retry
            // the atomic increment now that the row exists.
            usageCounterRepository.tryIncrement(workspaceId, metric, periodStart, amount);
        }
    }

    /**
     * Derives the current billing-cycle period start: the workspace's subscription {@code
     * currentPeriodStart} if one exists, else the start of the current UTC calendar month (Free
     * tier with no subscription row yet).
     */
    private LocalDate resolvePeriodStart(String workspaceId) {
        Optional<Subscription> subscription = subscriptionService.getByWorkspaceId(workspaceId);
        if (subscription.isPresent()) {
            return subscription.get().getCurrentPeriodStart().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }
}
