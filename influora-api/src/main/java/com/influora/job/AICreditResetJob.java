package com.influora.job;

import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanCode;
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
 * <p>Pro tier 400/mo support (Task 21, wired): before resetting each workspace, checks {@code
 * subscriptionService.getActivePlanForWorkspace(workspaceId)} — if the workspace has an ACTIVE Pro
 * subscription, {@code aiCreditService.applyPlanAllotment} syncs {@code monthlyAllotment} to Pro's
 * 400 before {@code resetForNewCycle} applies it. Free-tier workspaces (the overwhelming majority)
 * skip that call entirely, so their existing 100/150 loyalty-bump allotment is left untouched.
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
        // Every brand workspace gets a reset; Pro-tier workspaces additionally get their
        // monthlyAllotment synced to the plan's 400/mo just before the reset applies it (Task 21).
        List<String> workspaceIds = workspaceRepository.findIdsByType(WorkspaceType.BRAND);

        int resetCount = 0;
        int failedCount = 0;

        for (String workspaceId : workspaceIds) {
            try {
                applyProAllotmentIfActive(workspaceId);
                aiCreditService.resetForNewCycle(workspaceId);
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
     * Syncs {@code BrandAiCredit.monthlyAllotment} to Pro's 400/mo for a workspace with an ACTIVE
     * Pro subscription, immediately before {@code resetForNewCycle} applies whatever allotment is
     * currently persisted. No-op for Free (the overwhelming majority) — {@code
     * getActivePlanForWorkspace} already falls back to the Free plan for no-subscription,
     * PAST_DUE/HALTED/CANCELLED, and deactivated-plan cases, so the {@code PRO} check here is
     * sufficient to leave every Free-tier workspace's existing 100/150 loyalty value untouched.
     *
     * <p>Intentionally NOT wrapped in its own try/catch — this runs inside the per-workspace catch
     * in {@link #runReset()} already, so a plan-resolution failure here is logged and skips just
     * this one workspace's reset for this cycle, same as any other failure in the loop; it does
     * not abort the batch.
     */
    private void applyProAllotmentIfActive(String workspaceId) {
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        if (plan != null && plan.getCode() == PlanCode.PRO) {
            aiCreditService.applyPlanAllotment(workspaceId, plan.getAiMonthlyAllotment());
        }
    }
}
