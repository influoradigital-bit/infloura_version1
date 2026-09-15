package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.Entitlement;
import com.influora.domain.enums.UsageMetric;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.billing.UsageCounterService;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The one service any caller enforcing an {@link Entitlement} should touch — per {@code
 * wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md} §3.2.
 *
 * <p><b>The {@link Entitlement} argument is load-bearing on every primitive below.</b> It is the
 * thing that decides WHICH limit is enforced: every primitive resolves the limit itself, via
 * {@link Entitlement#limitIn(Plan)}, from the {@link Plan} it is handed. Pass a different constant
 * and a different plan column is compared against; make that constant resolve to "unlimited" and
 * the gate is disabled. Neither is possible to do silently — {@code EntitlementConformanceTest}
 * asserts both the call-site wiring (bytecode) and the resolution itself (behaviour, against
 * distinct sentinel plan values).
 *
 * <p><b>History (why this class was reworked on 2026-09-12).</b> The first cut of this class
 * carried two {@code static} overloads that took the already-resolved limit — {@code
 * requireCapacity(Entitlement, OptionalInt, long, Supplier)} and {@code consume(Entitlement,
 * UsageCounterService, String, UsageMetric, String, int)} — and never read their {@code
 * entitlement} parameter at all. Both production call sites used exactly those, so the constant
 * was a marker sitting next to a call, and changing {@code OptionalInt.of(plan.getSeatLimit())} to
 * {@code OptionalInt.empty()} at the seat call site disabled seat enforcement entirely while the
 * conformance gate stayed green. Those overloads are gone; the compare-and-throw / dedup-and-count
 * helpers they held are now private and reachable only after the entitlement has chosen the limit.
 *
 * <p><b>Why static overloads still exist at all.</b> The two existing call sites — {@code
 * WorkspaceMemberService.enforceSeatLimit} and {@code AnalyticsUsageCapInterceptor.preHandle} —
 * already hold a resolved {@link Plan} (and, for the interceptor, an already-injected {@link
 * UsageCounterService}), and their unit tests construct the class under test with a fixed-arity
 * {@code new X(...)} and a {@code mock(Plan.class)}. The static overloads take those two objects
 * as plain parameters so no constructor dependency has to be added; the limit is still resolved
 * from the {@link Entitlement}, which is the part that matters. A caller that does NOT already
 * hold a {@link Plan} should use the instance overloads, which resolve it from {@link
 * SubscriptionService} and then delegate to the very same static bodies.
 */
@Service
public class EntitlementService {

    private final SubscriptionService subscriptionService;
    private final UsageCounterService usageCounterService;

    public EntitlementService(
            SubscriptionService subscriptionService, UsageCounterService usageCounterService) {
        this.subscriptionService = subscriptionService;
        this.usageCounterService = usageCounterService;
    }

    // ------------------------------------------------------------------ CAPACITY --------------

    /**
     * Full CAPACITY primitive for a caller that does not already hold a {@link Plan}: resolves the
     * workspace's plan, then defers to the static overload below. Throws {@code 402
     * UPGRADE_REQUIRED} if {@code currentCount} is already at or over this entitlement's limit; a
     * no-op if the plan has no limit for it (unlimited).
     */
    public void requireCapacity(String workspaceId, Entitlement entitlement, LongSupplier currentCount) {
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        requireCapacity(
                entitlement, plan, currentCount.getAsLong(), () -> defaultCapacityMessage(entitlement, plan));
    }

    /**
     * CAPACITY primitive for a caller that already holds the workspace's {@link Plan}. The limit
     * compared against is {@code entitlement.limitIn(plan)} — the {@link Entitlement} argument
     * chooses it; the caller cannot supply one.
     *
     * @throws UnsupportedOperationException if {@code entitlement} is not limit-shaped (FLAG/RATE)
     */
    public static void requireCapacity(
            Entitlement entitlement, Plan plan, long currentCount, Supplier<String> upgradeMessage) {
        enforce(entitlement.limitIn(plan), currentCount, upgradeMessage);
    }

    /** The actual compare-and-throw. Private: unreachable without an {@link Entitlement} first. */
    private static void enforce(OptionalInt limit, long currentCount, Supplier<String> upgradeMessage) {
        if (limit.isEmpty()) {
            return;
        }
        if (currentCount >= limit.getAsInt()) {
            throw new ApiException("UPGRADE_REQUIRED", upgradeMessage.get(), HttpStatus.PAYMENT_REQUIRED);
        }
    }

    private static String defaultCapacityMessage(Entitlement entitlement, Plan plan) {
        String label = entitlement.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return "Workspace is at its "
                + label
                + " limit ("
                + entitlement.limitIn(plan).getAsInt()
                + ") for the "
                + plan.getName()
                + " plan — upgrade for more";
    }

    // ------------------------------------------------------------------- METERED ---------------

    /**
     * Full METERED primitive for a caller that does not already hold a {@link Plan}: resolves the
     * workspace's plan, then defers to the static overload below. Returns {@code false} (never
     * throws) if this is a genuinely new {@code dedupKey} that would push usage to/over the limit
     * — mirrors {@code UsageCounterService.recordCreatorLookup}'s own contract, since that is
     * exactly what this delegates to.
     *
     * <p>Only entitlements backed by {@link UsageCounterService}'s generic per-entity counter are
     * supported here — see {@link #meteredMetricFor}. {@link Entitlement#AI_CREDITS} is METERED in
     * shape but is NOT one of them: it has its own mechanism ({@code AICreditService} /
     * {@code BrandAiCredit}, with a daily hard cap and refund/release semantics {@code
     * UsageCounterService} does not have) and must not be routed through this method.
     */
    public boolean consume(String workspaceId, Entitlement entitlement, String dedupKey) {
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        return consume(entitlement, usageCounterService, workspaceId, plan, dedupKey);
    }

    /**
     * METERED primitive for a caller that already holds the workspace's {@link Plan} and a {@link
     * UsageCounterService}. BOTH the metric counted and the limit enforced come from the {@link
     * Entitlement} argument ({@link #meteredMetricFor} / {@link Entitlement#limitIn}) — the caller
     * supplies neither.
     *
     * @throws IllegalArgumentException if {@code entitlement} has no UsageCounterService-backed
     *     metric (e.g. {@link Entitlement#AI_CREDITS}, which has its own ledger)
     */
    public static boolean consume(
            Entitlement entitlement,
            UsageCounterService usageCounterService,
            String workspaceId,
            Plan plan,
            String dedupKey) {
        UsageMetric metric = meteredMetricFor(entitlement);
        OptionalInt limit = entitlement.limitIn(plan);
        if (limit.isEmpty()) {
            // Unlimited — no cap to enforce, so no dedup bookkeeping needed; still tracked for
            // observability, mirroring AnalyticsUsageCapInterceptor's original Pro-tier branch.
            usageCounterService.incrementUsage(workspaceId, metric, 1);
            return true;
        }
        return usageCounterService.recordCreatorLookup(workspaceId, metric, dedupKey, limit.getAsInt());
    }

    private static UsageMetric meteredMetricFor(Entitlement entitlement) {
        if (entitlement == Entitlement.CREATOR_ANALYTICS_VIEWS) {
            return UsageMetric.CREATOR_ANALYTICS_VIEW;
        }
        throw new IllegalArgumentException(
                entitlement
                        + " has no UsageCounterService-backed metric. If it is METERED, check whether"
                        + " it uses a different enforcement mechanism instead (e.g. AI_CREDITS uses"
                        + " AICreditService's own credit ledger, not this generic per-entity counter)"
                        + " before routing it through EntitlementService.consume.");
    }
}
