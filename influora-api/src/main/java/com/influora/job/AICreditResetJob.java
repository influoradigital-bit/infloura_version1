package com.influora.job;

import com.influora.domain.entity.Plan;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.meera.AICreditService;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly AI credit reset cron — resets non-live brands to their plan allotment.
 * Task 14/14 subscription billing prep batch 1 (subscription-billing-plan.md §1.3, rev. 3).
 *
 * <p>Audit confirmation: {@link AICreditService#resetForNewCycle} exists but has no scheduler
 * caller. This job wires the monthly reset, running on the 1st of each month at 2am UTC. Resets
 * every active workspace's {@code BrandAiCredit.creditsRemaining} to {@code monthlyAllotment}
 * (Free = 100→150 after first funded campaign; Pro = 400).
 *
 * <p>Pro tier 400/mo support (Task 21, wired): before resetting each workspace, {@code
 * aiCreditService.applyPlanAllotment} syncs {@code planAllotment} to whatever plan {@code
 * subscriptionService.getActivePlanForWorkspace(workspaceId)} currently resolves — Free's 100 or
 * Pro's 400 — before {@code resetForNewCycle} applies the (derived) result.
 *
 * <p><b>F-0836 fix [vikram · 2026-09-17]:</b> this used to only sync when the resolved plan was
 * PRO ({@code applyProAllotmentIfActive}), on the theory that Free-tier workspaces' allotment
 * "was already right". That is true the day a workspace is created, but false forever after a
 * brand has ever been Pro and then cancelled: {@code getActivePlanForWorkspace} correctly falls
 * back to Free, but nothing wrote Free's 100 back into {@code planAllotment} — the stale Pro 400
 * (or 450 with the loyalty bonus) survived every monthly reset indefinitely, since {@link
 * AICreditService#resetForNewCycle} only re-applies whatever allotment is already stored. Syncing
 * unconditionally (Free workspaces just get re-synced to the same 100 they already had — a no-op)
 * closes that gap without special-casing either direction.
 *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3 (audit F-3), F-0836
 */
@Component
public class AICreditResetJob {

    private static final Logger log = LoggerFactory.getLogger(AICreditResetJob.class);

    private final WorkspaceRepository workspaceRepository;
    private final AICreditService aiCreditService;
    private final SubscriptionService subscriptionService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public AICreditResetJob(
            WorkspaceRepository workspaceRepository,
            AICreditService aiCreditService,
            SubscriptionService subscriptionService) {
        this.workspaceRepository = workspaceRepository;
        this.aiCreditService = aiCreditService;
        this.subscriptionService = subscriptionService;
    }

    /** Monthly reset at 2am UTC on the 1st of each month. */
    @Scheduled(cron = "0 0 2 1 * ?", zone = "UTC")
    @SchedulerLock(name = "AICreditResetJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void resetAllCreditsForNewMonth() {
        if (!running.compareAndSet(false, true)) {
            log.warn("AICreditResetJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            runReset();
        } catch (Exception e) {
            // Kavya QA finding on V54 batch: the per-workspace catch below protects individual
            // workspace failures, but a crash outside that loop (e.g. findAllIds() query itself
            // failing, OOM) previously had no explicit signal to ops — it would only surface as
            // whatever generic stack trace Spring's scheduler logs, easy to miss. Log loudly here,
            // then rethrow so Spring's own scheduler-level handling still applies.
            log.error("AICreditResetJob: crashed mid-run, monthly reset did not complete", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private void runReset() {
        log.info("AICreditResetJob: starting monthly reset");

        // AI credits are a BRAND-only resource (BrandAiCredit is 1:1 with brand workspaces).
        // Kavya QA finding on V54 batch: the previous unfiltered findAllIds() call would also
        // sweep in AGENCY workspaces and silently create spurious 100-credit rows for them via
        // AICreditService#ensureInitialized. Scope to BRAND only.
        //
        // Every brand workspace gets a reset, and every workspace's planAllotment is synced to its
        // CURRENT active plan (Free or Pro) just before the reset applies it (Task 21; F-0836 fix
        // above — this sync used to be Pro-only).
        List<String> workspaceIds = workspaceRepository.findIdsByType(WorkspaceType.BRAND);

        int resetCount = 0;
        int failedCount = 0;

        for (String workspaceId : workspaceIds) {
            try {
                syncPlanAllotment(workspaceId);
                // T-S3-F0879-0917 [vikram · 2026-09-17]: was resetForNewCycle (unconditional) —
                // a second trigger of this job in the same UTC month (e.g. an ops re-run, or a
                // scheduler misfire) used to blow away credits already spent this cycle back up
                // to full allotment every time it ran. resetForNewCycleIfDue is a no-op if this
                // workspace's lastReset already falls in the current UTC year+month.
                //   Source: assignments-0917-subscription.md S3 "Reset runs twice" (tech N3)
                aiCreditService.resetForNewCycleIfDue(workspaceId);
                resetCount++;
            } catch (Exception e) {
                // Defensive catch-all: one workspace's failure must never abort the rest of the
                // batch, mirroring the pattern from StaleTokenCleanupJob/MetricsPollingJob.
                failedCount++;
                log.error(
                        "AICreditResetJob: unexpected failure resetting credits for workspace {}",
                        workspaceId,
                        e);
            }
        }

        log.info(
                "AICreditResetJob: completed monthly reset — {} workspaces reset, {} failed",
                resetCount,
                failedCount);
    }

    /**
     * Syncs {@code BrandAiCredit.planAllotment} to the workspace's CURRENT active plan (Free or
     * Pro), immediately before {@code resetForNewCycle} applies the resulting (derived)
     * allotment. {@code loyaltyBonus} is untouched here — {@link
     * AICreditService#applyPlanAllotment} only ever writes {@code planAllotment} — so an earned
     * bonus survives this sync in either direction.
     *
     * <p>F-0836 [vikram · 2026-09-17]: previously named {@code applyProAllotmentIfActive} and only
     * called {@code applyPlanAllotment} when {@code plan.getCode() == PlanCode.PRO}. A workspace
     * that had been Pro and then cancelled resolves back to the Free plan here (via {@code
     * getActivePlanForWorkspace}'s fallback), but the Pro-only guard meant this method did nothing
     * for it — so {@code planAllotment} (and therefore the derived {@code monthlyAllotment})
     * stayed at Pro's 400 forever, and every subsequent monthly reset re-applied that stale value.
     * Syncing unconditionally fixes it: a cancelled-Pro workspace's very next reset now writes
     * Free's 100 back into {@code planAllotment} before resetting credits to it.
     *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, F-0836
     *
     * <p>Intentionally NOT wrapped in its own try/catch — this runs inside the per-workspace catch
     * in {@link #runReset()} already, so a plan-resolution failure here is logged and skips just
     * this one workspace's reset for this cycle, same as any other failure in the loop; it does
     * not abort the batch.
     *
     * <p>REPAIR ROUND [vikram · 2026-09-17]: kabir's probe ("PROBE nullPlan -> monthly=400
     * credits=400" for a workspace that had been on Pro) showed a null {@code plan} was silently
     * skipping the sync and letting {@code resetForNewCycle} re-apply the stale stored allotment
     * with no signal to ops — exactly the F-0836 staleness bug this job exists to close, just
     * reached via a null plan resolution instead of a Pro-only guard. {@code
     * getActivePlanForWorkspace} is documented to fall back to Free rather than return null in
     * normal operation, so a null here means the resolver itself is in an unexpected state; log it
     * loudly (mirroring the {@link #resetAllCreditsForNewMonth} crash-log pattern above) so a
     * silently-stuck allotment is visible instead of indistinguishable from a correct sync.
     *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, F-0836 repair round (kabir)
     */
    private void syncPlanAllotment(String workspaceId) {
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        if (plan != null) {
            aiCreditService.applyPlanAllotment(workspaceId, plan.getAiMonthlyAllotment());
        } else {
            log.warn(
                    "AICreditResetJob: getActivePlanForWorkspace returned null for workspace {} --"
                            + " planAllotment sync skipped, resetForNewCycle will re-apply the"
                            + " stored allotment unchanged",
                    workspaceId);
        }
    }
}
