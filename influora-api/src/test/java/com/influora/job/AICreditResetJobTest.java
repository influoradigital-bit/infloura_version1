package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.meera.AICreditService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * F-0836 + audit F-3 [vikram · 2026-09-17]: {@link AICreditResetJob} must sync {@code
 * BrandAiCredit.planAllotment} to the workspace's CURRENT active plan on every reset, not only
 * when that plan is Pro — otherwise a brand who was ever on Pro keeps the Pro allotment forever
 * after cancelling, because nothing else in this job's own loop ever writes it back down.
 *
 * <p>Uses a REAL {@link AICreditService} (backed by a stubbed {@link BrandAiCreditRepository} that
 * mutates a single in-memory {@link BrandAiCredit} row, mirroring how a real UPDATE would behave)
 * rather than a mocked one — a mock would only prove the job calls the right methods, not that the
 * stored allotment/credits actually end up at the right numbers, which is the entire point of
 * F-0836 and the SM-0.2 stacking rule.
 *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, §7 SM-0.2, F-0836
 */
@ExtendWith(MockitoExtension.class)
class AICreditResetJobTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandAiCreditRepository creditRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private IdempotencyService idempotencyService;

    private AICreditService aiCreditService;
    private AICreditResetJob job;
    private BrandAiCredit credit;

    private Logger jobLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        jobLogger = (Logger) LoggerFactory.getLogger(AICreditResetJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
        aiCreditService = new AICreditService(creditRepository, idempotencyService);
        job = new AICreditResetJob(workspaceRepository, aiCreditService, subscriptionService);

        when(workspaceRepository.findIdsByType(WorkspaceType.BRAND)).thenReturn(List.of(WORKSPACE_ID));

        // Single in-memory row, mutated in place by the real AICreditService — save() is a no-op
        // (the object is already the one findByWorkspaceId hands back) so state survives across
        // the job's repeated resetAllCreditsForNewMonth() calls within one test, exactly like a
        // real UPDATE ... WHERE workspace_id = ? would.
        credit =
                BrandAiCredit.builder()
                        .workspaceId(WORKSPACE_ID)
                        .monthlyAllotment(100) // starts Free, no loyalty bonus
                        .cycleStart(LocalDate.now())
                        .lastReset(LocalDate.now())
                        .build();
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logAppender);
    }

    private static Plan plan(PlanCode code, int aiMonthlyAllotment) {
        return Plan.builder().id(code.name()).code(code).aiMonthlyAllotment(aiMonthlyAllotment).build();
    }

    @Test
    @DisplayName(
            "F-0836: a workspace that was on Pro and cancels drops to the Free allotment on its"
                    + " very next reset (no loyalty bonus earned)")
    void cancelledProWorkspaceDropsToFreeAllotmentOnNextReset() {
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.PRO, 400));
        job.resetAllCreditsForNewMonth();
        assertEquals(400, credit.getMonthlyAllotment(), "Pro reset must land at Pro's 400");
        assertEquals(400, credit.getCreditsRemaining());

        // Brand cancels -> getActivePlanForWorkspace now falls back to Free.
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.FREE, 100));
        job.resetAllCreditsForNewMonth();

        assertEquals(
                100,
                credit.getMonthlyAllotment(),
                "cancelled-Pro workspace's allowance must drop to the Free value on the next reset,"
                        + " not stay stuck at the old Pro value forever");
        assertEquals(100, credit.getCreditsRemaining());
    }

    @Test
    @DisplayName(
            "F-0836 + SM-0.2: a cancelled-Pro workspace with an earned loyalty bonus drops to Free"
                    + " + bonus (150), never stays at the stale Pro value, and never loses the bonus")
    void cancelledProWorkspaceDropsToFreePlusKeptLoyaltyBonusOnNextReset() {
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.PRO, 400));
        job.resetAllCreditsForNewMonth();

        // Workspace funds a campaign while on Pro -> earns the sticky +50 loyalty bonus.
        aiCreditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));
        assertEquals(450, credit.getMonthlyAllotment(), "Pro + funded campaign must be 450 (SM-0.2)");

        job.resetAllCreditsForNewMonth();
        assertEquals(450, credit.getMonthlyAllotment(), "still Pro -- reset must not disturb 450");
        assertEquals(450, credit.getCreditsRemaining());

        // Brand cancels.
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.FREE, 100));
        job.resetAllCreditsForNewMonth();

        assertEquals(
                150,
                credit.getMonthlyAllotment(),
                "allowance must drop to the Free value (100) while the earned +50 loyalty bonus is"
                        + " kept -- 150, not the stale Pro 450 and not a bonus-wiped 100");
        assertEquals(150, credit.getCreditsRemaining());
        assertEquals(50, credit.getLoyaltyBonus(), "loyalty bonus itself must survive a downgrade");
    }

    @Test
    @DisplayName("SM-0.2 matrix via the job: Pro without a funded campaign resets to exactly 400")
    void proWithoutFundedCampaignResetsTo400() {
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.PRO, 400));

        job.resetAllCreditsForNewMonth();

        assertEquals(400, credit.getMonthlyAllotment());
        assertEquals(400, credit.getCreditsRemaining());
    }

    @Test
    @DisplayName("SM-0.2 matrix via the job: Pro with a funded campaign resets to exactly 450")
    void proWithFundedCampaignResetsTo450() {
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.PRO, 400));
        aiCreditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));

        job.resetAllCreditsForNewMonth();

        assertEquals(450, credit.getMonthlyAllotment());
        assertEquals(450, credit.getCreditsRemaining());
    }

    // REPAIR ROUND [vikram · 2026-09-17] -- kabir's probe ("PROBE nullPlan -> monthly=400
    // credits=400" for a workspace that had been on Pro) showed a null resolved plan silently
    // skipped the planAllotment sync with no signal to ops, then resetForNewCycle re-applied the
    // stale stored allotment unchanged -- the same staleness bug class F-0836 closes for the
    // Pro-only-guard case, just reached via a null plan instead. Fix: log a WARN naming the
    // workspace when getActivePlanForWorkspace returns null, so the silently-stuck allotment is
    // now visible; the reset itself still proceeds unchanged (this method must not abort the
    // per-workspace loop).
    //   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, F-0836 repair round (kabir)
    @Test
    @DisplayName(
            "REPAIR ROUND (kabir): a null resolved plan logs a WARN naming the workspace instead of"
                    + " silently skipping the sync, and the reset still re-applies the stored"
                    + " allotment without throwing")
    void nullResolvedPlan_logsWarnInsteadOfSilentSkip() {
        // First reset while Pro, establishing a stored 400 allotment (mirrors kabir's probe setup:
        // "a workspace that had been on Pro").
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID))
                .thenReturn(plan(PlanCode.PRO, 400));
        job.resetAllCreditsForNewMonth();
        assertEquals(400, credit.getMonthlyAllotment());

        // Plan resolver now returns null (unexpected resolver state, not a normal Free fallback).
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(null);

        // Falsifiable core assertion: does not throw, and the per-workspace loop still completes.
        job.resetAllCreditsForNewMonth();

        // Old (pre-repair) behaviour, unchanged: sync is skipped, so the stale 400 is re-applied by
        // resetForNewCycle rather than corrected -- this method never fixes staleness on its own,
        // it only makes the skip visible.
        assertEquals(400, credit.getMonthlyAllotment());
        assertEquals(400, credit.getCreditsRemaining());

        // New (repair-round) behaviour: the skip is no longer silent -- a WARN log names the
        // affected workspace, exactly the assertion that fails against the pre-repair code (which
        // logs nothing at all for a null plan).
        List<ILoggingEvent> warnEvents =
                logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertFalse(
                warnEvents.isEmpty(),
                "expected a WARN log when getActivePlanForWorkspace returns null, but nothing was"
                        + " logged at all");
        boolean namesTheWorkspace =
                warnEvents.stream().anyMatch(e -> e.getFormattedMessage().contains(WORKSPACE_ID));
        assertTrue(
                namesTheWorkspace,
                "expected the WARN log to identify the affected workspace (id=" + WORKSPACE_ID + ")"
                        + " but got: "
                        + warnEvents.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
