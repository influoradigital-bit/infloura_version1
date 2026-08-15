package com.influora.job;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.billing.SubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Renewal safety net — Task 24 subscription-billing Phase 4a. Money-lifecycle code: mandatory
 * Kabir gate after Kavya, same discipline as Phase 2/3a.
 *
 * <p><b>This is NOT the primary renewal mechanism.</b> The primary mechanism is Razorpay's own
 * {@code subscription.charged} webhook ({@code RazorpayWebhookController}), which advances {@code
 * Subscription.currentPeriodStart}/{@code currentPeriodEnd} into the future on every successful
 * recurring charge. This job exists purely to catch the case where that webhook was missed or
 * delayed — a workspace whose subscription is still nominally {@code ACTIVE} but whose {@code
 * currentPeriodEnd} has already passed is stuck with a stale billing-cycle anchor: {@code
 * UsageCounterService#resolvePeriodStart} derives the "current period" straight from that column,
 * so a stale {@code currentPeriodStart} means usage caps (tracked creators, analytics views,
 * exports) never reset even though the workspace should be in a fresh billing cycle.
 *
 * <p><b>Query naturally excludes already-renewed subscriptions</b> (per the task breakdown):
 * {@code currentPeriodEnd < now()} is only ever true for a subscription the webhook has NOT yet
 * advanced. The moment the real webhook fires (before or after this job runs), {@code
 * currentPeriodEnd} moves into the future and this job's query stops matching that row on every
 * subsequent day — no separate idempotency/dedup bookkeeping is needed beyond this condition
 * itself.
 *
 * <p><b>Why no explicit {@code UsageCounter} row mutation:</b> {@code usage_counters} is keyed on
 * {@code (workspace_id, metric, period_start)} (V54) and {@link
 * com.influora.service.billing.UsageCounterService#resolvePeriodStart} always derives {@code
 * period_start} from the subscription's own {@code currentPeriodStart}. Advancing that column
 * (below) is what "resets" usage: the very next {@code getUsageForCurrentPeriod}/{@code
 * incrementUsage} call for this workspace resolves a period with no existing counter row, i.e.
 * zero used — old rows are simply no longer queried, exactly like month-over-month history for
 * every other workspace. No UPDATE/DELETE against {@code usage_counters} is required or performed.
 *
 * <p><b>Cycle-length heuristic:</b> since the real webhook was missed, this job cannot know the
 * authoritative next period boundary Razorpay would have sent — it estimates one by re-applying
 * the subscription's own previous period length ({@code currentPeriodEnd - currentPeriodStart}),
 * falling back to 30 days if that previous length is degenerate. This is a safety-net estimate
 * only, good enough to unblock usage/credit resets without leaving the workspace stuck; the
 * eventual real webhook (whenever it arrives) is still what re-establishes the authoritative
 * boundary going forward.
 *
 * <p><b>BL-2 fix (BrandF.md §98):</b> the stale-period query above (any {@code ACTIVE} row with a
 * lapsed {@code currentPeriodEnd}) matches a cancel-at-period-end subscription EXACTLY as well as
 * a genuinely missed-renewal one — {@link com.influora.service.billing.SubscriptionService#cancel}
 * deliberately leaves status {@code ACTIVE} at cancel time (correct — access continues until the
 * period ends) and only sets {@code cancelAtPeriodEnd = true}. Left unguarded, this job would
 * treat that exact row as "webhook missed" and re-renew it via {@link
 * com.influora.service.billing.SubscriptionService#applyRenewalSafetyNet} — advancing the period
 * and re-allotting Pro AI credits — silently undoing the customer's cancellation every single day,
 * forever, since no other code path in this system ever wrote {@link SubscriptionStatus#CANCELLED}
 * for this flow ({@code SubscriptionDunningJob} only ever looks at {@code PAST_DUE} rows, and
 * {@code RazorpayWebhookController} previously discarded {@code subscription.cancelled} entirely).
 * {@link #doRun} now partitions the stale batch on {@link Subscription#isCancelAtPeriodEnd()}:
 * rows WITHOUT the flag go through the pre-existing {@link #resyncOne} renewal path unchanged; rows
 * WITH the flag go through {@link #cancelOne} instead, which flips them to {@code CANCELLED} via
 * {@link com.influora.service.billing.SubscriptionService#finalizeLapsedCancellation} rather than
 * renewing them.
 */
@Component
public class SubscriptionRenewalResetJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRenewalResetJob.class);

    private static final Duration FALLBACK_CYCLE_LENGTH = Duration.ofDays(30);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final AuditLogService auditLog;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SubscriptionRenewalResetJob(
            SubscriptionRepository subscriptionRepository,
            SubscriptionService subscriptionService,
            AuditLogService auditLog) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.auditLog = auditLog;
    }

    /** Daily at 3:30am UTC — after {@code SubscriptionDunningJob} (3am), before {@code StaleTokenCleanupJob} (4am). */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "SubscriptionRenewalResetJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void runRenewalSafetyNet() {
        if (!running.compareAndSet(false, true)) {
            log.warn("SubscriptionRenewalResetJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            doRun();
        } catch (Exception e) {
            log.error("SubscriptionRenewalResetJob: crashed mid-run", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private void doRun() {
        Instant now = Instant.now();
        List<Subscription> stale =
                subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE).stream()
                        .filter(sub -> sub.getCurrentPeriodEnd().isBefore(now))
                        .toList();

        // [BL-2 fix, BrandF.md §98] Split the stale batch: a cancel-at-period-end row must be
        // finalized to CANCELLED, never handed to the renewal path — see class javadoc.
        List<Subscription> toRenew = stale.stream().filter(sub -> !sub.isCancelAtPeriodEnd()).toList();
        List<Subscription> toCancel = stale.stream().filter(Subscription::isCancelAtPeriodEnd).toList();

        int resynced = 0;
        int cancelled = 0;
        int failed = 0;

        for (Subscription subscription : toRenew) {
            try {
                resyncOne(subscription);
                resynced++;
            } catch (Exception e) {
                failed++;
                log.error(
                        "SubscriptionRenewalResetJob: unexpected failure resyncing subscription {}",
                        subscription.getId(),
                        e);
            }
        }

        for (Subscription subscription : toCancel) {
            try {
                cancelOne(subscription);
                cancelled++;
            } catch (Exception e) {
                failed++;
                log.error(
                        "SubscriptionRenewalResetJob: unexpected failure finalizing lapsed cancellation"
                                + " for subscription {}",
                        subscription.getId(),
                        e);
            }
        }

        if (resynced > 0 || cancelled > 0 || failed > 0) {
            // Only genuinely noteworthy when this safety net actually caught something — a missed
            // webhook is not the steady state, so logging every no-op day at this level would be
            // noise (mirrors AICreditResetJob's completion-summary-only logging discipline).
            log.warn(
                    "SubscriptionRenewalResetJob: safety net caught {} subscription(s) with a stale"
                            + " period (webhook missed/delayed) — resynced={}, cancelled(lapsed"
                            + " cancel-at-period-end)={}, failed={}",
                    stale.size(), resynced, cancelled, failed);
        }
    }

    private void resyncOne(Subscription subscription) {
        Instant oldStart = subscription.getCurrentPeriodStart();
        Instant oldEnd = subscription.getCurrentPeriodEnd();

        Duration cycleLength = Duration.between(oldStart, oldEnd);
        if (cycleLength.isZero() || cycleLength.isNegative()) {
            cycleLength = FALLBACK_CYCLE_LENGTH;
        }

        Instant newStart = oldEnd;
        Instant newEnd = oldEnd.plus(cycleLength);
        String workspaceId = subscription.getWorkspaceId();

        // [SEC: Kabir red-team Phase 4a MEDIUM-2] Period-advance + plan-allotment sync +
        // credit-cycle reset now run inside ONE transaction (SubscriptionService
        // #applyRenewalSafetyNet), not three separately auto-committing calls. If any step
        // throws, the period-advance rolls back too, so this same subscription still matches the
        // job's stale-period query on its very next run instead of being silently skipped for a
        // full cycle. See that method's javadoc for the full before/after trace.
        subscriptionService.applyRenewalSafetyNet(subscription, newStart, newEnd);

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_RENEWAL_SAFETY_NET",
                null,
                null,
                null,
                "subscription-renewal-safetynet:" + subscription.getId() + ":" + oldEnd.getEpochSecond(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousPeriodEnd", oldEnd.toString(),
                        "newPeriodStart", newStart.toString(),
                        "newPeriodEnd", newEnd.toString()));

        log.warn(
                "SubscriptionRenewalResetJob: safety-net renewed subscription {} (workspace {}) —"
                        + " stale period end {} was never advanced by a webhook, estimated new period"
                        + " {}..{}",
                subscription.getId(), workspaceId, oldEnd, newStart, newEnd);
    }

    /**
     * [BL-2 fix, BrandF.md §98] Finalizes a subscription the customer already cancelled ({@link
     * Subscription#isCancelAtPeriodEnd()}) whose paid period has now lapsed — flips it to {@code
     * CANCELLED} via {@link SubscriptionService#finalizeLapsedCancellation} instead of letting
     * {@link #resyncOne}'s renewal path re-renew it. See class javadoc for why this row would
     * otherwise match the same stale-period query as a genuinely missed webhook.
     */
    private void cancelOne(Subscription subscription) {
        String workspaceId = subscription.getWorkspaceId();
        Instant lapsedPeriodEnd = subscription.getCurrentPeriodEnd();

        subscriptionService.finalizeLapsedCancellation(subscription);

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_CANCELLATION_FINALIZED",
                null,
                null,
                null,
                "subscription-cancellation-finalized:" + subscription.getId() + ":" + lapsedPeriodEnd.getEpochSecond(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "lapsedPeriodEnd", lapsedPeriodEnd.toString()));

        log.warn(
                "SubscriptionRenewalResetJob: finalized lapsed cancellation for subscription {}"
                        + " (workspace {}) — cancelAtPeriodEnd was true and currentPeriodEnd {} has"
                        + " passed with no subscription.cancelled webhook ever applying it; status set"
                        + " to CANCELLED",
                subscription.getId(), workspaceId, lapsedPeriodEnd);
    }
}
