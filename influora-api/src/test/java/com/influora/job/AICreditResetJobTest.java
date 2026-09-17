package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
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
}
