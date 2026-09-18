package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.billing.SubscriptionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P7: Unit tests for AICreditService (16-VIKRAM-REMAINING-TASKS.md).
 * Priority: circuit-breaker gate, monthly reset, atomic decrement, 500/day hard cap (P4).
 *
 * <p>Wave 2 round 2 (Kabir red-team FAILs #1/#2): also covers the money-path matrix for
 * charge-at-send ({@link AICreditService#tryConsumeForTurn}) and refund-on-provider-failure
 * ({@link AICreditService#release}) — see the "Money-path" section below.
 */
@ExtendWith(MockitoExtension.class)
class AICreditServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String TURN_ID = "01HTURN0000000000000AA";

    @Mock private BrandAiCreditRepository creditRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private SubscriptionService subscriptionService;

    private AICreditService creditService;

    @BeforeEach
    void setUp() {
        creditService = new AICreditService(creditRepository, idempotencyService, subscriptionService);
    }

    private static Plan plan(PlanCode code, int aiMonthlyAllotment) {
        return Plan.builder().id(code.name()).code(code).aiMonthlyAllotment(aiMonthlyAllotment).build();
    }

    private static Subscription subscriptionWithPeriodEnd(Instant periodEnd) {
        return Subscription.builder().workspaceId(WORKSPACE_ID).currentPeriodEnd(periodEnd).build();
    }

    /**
     * T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18]: F-0885 closed applyPlanAllotment's lost
     * update by replacing its full-row save() with two targeted atomic UPDATEs
     * ({@code syncPlanAllotment}, {@code grantAllotmentIncrease}) -- the service method itself no
     * longer mutates the managed {@code BrandAiCredit} at all. This stub simulates those two real
     * atomic queries' SQL semantics against the SAME in-memory {@code credit} object so unit tests
     * can still assert on its resulting state, mirroring {@code AICreditResetJobTest}'s "stubbed
     * repository that mutates a single in-memory row" precedent (see that test's class javadoc for
     * why a mock that only records calls would not prove the resulting numbers are right).
     * {@code grantAllotmentIncrease}'s guard is reproduced faithfully: a call whose
     * {@code periodEnd} equals the credit's CURRENT {@code creditGrantPeriodEnd} (non-null) is a
     * no-op returning 0, exactly like the real WHERE clause.
     */
    private void stubAtomicPlanAllotmentWrites(BrandAiCredit credit) {
        lenient()
                .when(creditRepository.syncPlanAllotment(eq(WORKSPACE_ID), anyInt()))
                .thenAnswer(
                        invocation -> {
                            credit.setPlanAllotment(invocation.getArgument(1));
                            return 1;
                        });
        lenient()
                .when(creditRepository.grantAllotmentIncrease(eq(WORKSPACE_ID), anyInt(), any()))
                .thenAnswer(
                        invocation -> {
                            int newAllotment = invocation.getArgument(1);
                            Instant periodEnd = invocation.getArgument(2);
                            boolean guardBlocks =
                                    periodEnd != null && periodEnd.equals(credit.getCreditGrantPeriodEnd());
                            if (guardBlocks) {
                                return 0;
                            }
                            credit.setCreditsRemaining(newAllotment);
                            credit.setCreditGrantPeriodEnd(periodEnd);
                            return 1;
                        });
    }

    /** Stubs {@code idempotencyService.executeOnce(...)} (4-arg, no digest) to just run the supplier. */
    private void stubExecuteOnceRunsSupplier() {
        lenient()
                .when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<?> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    @Test
    @DisplayName("tryConsume: credits exhausted -> 402 CREDITS_EXHAUSTED")
    void testCreditsExhaustedThrows402() {
        BrandAiCredit credit = createCredit(0, 100, null, 0); // No credits remaining
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), anyInt())).thenReturn(0); // Decrement fails

        ApiException ex = assertThrows(ApiException.class, () ->
                creditService.tryConsume(WORKSPACE_ID, 1));

        assertEquals("CREDITS_EXHAUSTED", ex.getCode());
        assertEquals(402, ex.getStatus().value());
    }

    @Test
    @DisplayName("tryConsume: sufficient credits -> atomic decrement called")
    void testSufficientCreditsDecrements() {
        BrandAiCredit credit = createCredit(50, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(creditRepository.tryDecrement(WORKSPACE_ID, 1)).thenReturn(1); // Decrement succeeds

        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        verify(creditRepository).tryDecrement(WORKSPACE_ID, 1);
    }

    @Test
    @DisplayName("tryConsume: unlimited window (funded campaign) -> no credit decrement")
    void testUnlimitedWindowNoDecrement() {
        // Unlimited until tomorrow
        Instant unlimitedUntil = Instant.now().plusSeconds(86400);
        BrandAiCredit credit = createCredit(50, 100, unlimitedUntil, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        // No decrement should occur for unlimited tier
        verify(creditRepository, never()).tryDecrement(any(), anyInt());
    }

    @Test
    @DisplayName("P4: 500/day hard cap -> 429 DAILY_ACTION_LIMIT_EXCEEDED")
    void testDailyActionCapBlocks() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant unlimitedUntil = Instant.now().plusSeconds(86400); // In unlimited window
        BrandAiCredit credit = createCredit(50, 100, unlimitedUntil, 500); // Already at 500 actions
        credit.setDailyActionsDate(today);

        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        ApiException ex = assertThrows(ApiException.class, () ->
                creditService.tryConsume(WORKSPACE_ID, 1));

        assertEquals("DAILY_ACTION_LIMIT_EXCEEDED", ex.getCode());
        assertEquals(429, ex.getStatus().value());
    }

    @Test
    @DisplayName("P4: daily counter resets at midnight UTC")
    void testDailyCounterResetsAtMidnight() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant unlimitedUntil = Instant.now().plusSeconds(86400);
        BrandAiCredit credit = createCredit(50, 100, unlimitedUntil, 500); // 500 actions yesterday
        credit.setDailyActionsDate(yesterday);

        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        // Should NOT throw because the counter resets for a new day
        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        // T-S3-F0879-0917: the daily-action bump is now a single atomic, single-column-scoped
        // UPDATE (bumpDailyActions) -- NOT a full-entity save(credit) -- so this no longer asserts
        // against the in-memory entity's mutated fields (tryConsume never mutates them any more;
        // see AICreditService#tryConsume javadoc for why that mutation was the root cause of the
        // credit-race finding).
        verify(creditRepository).bumpDailyActions(WORKSPACE_ID, LocalDate.now(ZoneOffset.UTC));
        verify(creditRepository, never()).save(any(BrandAiCredit.class));
    }

    @Test
    @DisplayName(
            "T-5/tech N4: tryConsume never calls the full-row save() for the daily-action bump --"
                    + " only the atomic, single-column-scoped bumpDailyActions and the atomic"
                    + " tryDecrement ever touch the row, so nothing can blind-overwrite a concurrent"
                    + " decrement (unit-level proof the decrement path is conditional; the real"
                    + " cross-transaction race is falsified in AICreditRaceIntegrationTest, Docker CI"
                    + " only, NOT PROVEN locally)")
    void testTryConsumeNeverFullRowSaves() {
        BrandAiCredit credit = createCredit(1, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(creditRepository.tryDecrement(WORKSPACE_ID, 1)).thenReturn(1);

        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        verify(creditRepository, never()).save(any(BrandAiCredit.class));
        verify(creditRepository, times(1)).bumpDailyActions(eq(WORKSPACE_ID), any(LocalDate.class));
        verify(creditRepository, times(1)).tryDecrement(WORKSPACE_ID, 1);
    }

    // ---------------------------------------------------------------------------------------
    // Money-path (Wave 2 round 2, Kabir red-team FAILs #1/#2): tryConsumeForTurn / wasCharged /
    // release. assertAvailable is GONE -- the non-decrementing pre-check it provided is replaced
    // by tryConsumeForTurn actually charging at send (see MeeraSessionService#doSendTurn).
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "tryConsumeForTurn: 0 credits -> rejected (CREDITS_EXHAUSTED), no charge marker recorded,"
                    + " daily counter not incremented")
    void testTryConsumeForTurnZeroCreditsRejectedAndNoChargeMarker() {
        BrandAiCredit credit = createCredit(0, 100, null, 0);
        credit.setCreditsRemaining(0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), anyInt())).thenReturn(0);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> creditService.tryConsumeForTurn(WORKSPACE_ID, 1, TURN_ID));

        assertEquals("CREDITS_EXHAUSTED", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        // tryConsume's own daily-counter bump precedes the tryDecrement check in the real
        // implementation, but the CHARGE_SCOPE marker (what release() consults) must never be
        // written when the charge itself failed.
        verify(idempotencyService, never())
                .executeOnce(anyString(), anyString(), eq("meera.turn_charged"), any());
    }

    @Test
    @DisplayName(
            "tryConsumeForTurn: sufficient credits -> decrements exactly once AND records the"
                    + " charge-ledger marker keyed on turnId")
    void testTryConsumeForTurnChargesAndRecordsMarker() {
        BrandAiCredit credit = createCredit(50, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(creditRepository.tryDecrement(WORKSPACE_ID, 1)).thenReturn(1);
        stubExecuteOnceRunsSupplier();

        assertDoesNotThrow(() -> creditService.tryConsumeForTurn(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, times(1)).tryDecrement(WORKSPACE_ID, 1);
        verify(idempotencyService, times(1))
                .executeOnce(eq(TURN_ID), eq(WORKSPACE_ID), eq("meera.turn_charged"), any());
    }

    @Test
    @DisplayName("wasCharged: true iff the charge-ledger marker for turnId is COMPLETED")
    void testWasChargedReflectsChargeLedger() {
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged"))
                .thenReturn(true, false);

        assertTrue(creditService.wasCharged(WORKSPACE_ID, TURN_ID));
        assertFalse(creditService.wasCharged(WORKSPACE_ID, TURN_ID));
    }

    @Test
    @DisplayName(
            "release: turnId was charged and has NO persisted reply yet -> refunds credit AND the"
                    + " daily counter")
    void testReleaseRefundsChargeAndDailyCounterWhenEligible() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BrandAiCredit credit = createCredit(49, 100, null, 1);
        credit.setDailyActionsDate(today);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged")).thenReturn(true);
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.persist_writeback"))
                .thenReturn(false);
        stubExecuteOnceRunsSupplier();

        creditService.release(WORKSPACE_ID, 1, TURN_ID);

        verify(creditRepository, times(1)).refundCredits(WORKSPACE_ID, 1);
        verify(creditRepository, times(1)).refundDailyActions(WORKSPACE_ID, 1, today);
    }

    @Test
    @DisplayName("release: turnId was NEVER charged -> no-op, no refund")
    void testReleaseNoOpWhenNeverCharged() {
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged")).thenReturn(false);
        stubExecuteOnceRunsSupplier();

        creditService.release(WORKSPACE_ID, 1, TURN_ID);

        verify(creditRepository, never()).refundCredits(any(), anyInt());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any());
        // Never even needs to look up the write-back ledger once the charge check fails.
        verify(idempotencyService, never()).isCompleted(TURN_ID, WORKSPACE_ID, "meera.persist_writeback");
    }

    @Test
    @DisplayName(
            "release: turnId's assistant reply already persisted -> no-op (never refund-and-keep-the-reply)")
    void testReleaseNoOpWhenReplyAlreadyPersisted() {
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged")).thenReturn(true);
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.persist_writeback"))
                .thenReturn(true);
        stubExecuteOnceRunsSupplier();

        creditService.release(WORKSPACE_ID, 1, TURN_ID);

        verify(creditRepository, never()).refundCredits(any(), anyInt());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any());
        // Never even needs to load the credit row once the "already replied" guard trips.
        verify(creditRepository, never()).findByWorkspaceId(any());
    }

    @Test
    @DisplayName("release: unlimited-tier workspace -> refunds the daily counter but NOT creditsRemaining")
    void testReleaseSkipsCreditRefundForUnlimitedTier() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant unlimitedUntil = Instant.now().plusSeconds(86400);
        BrandAiCredit credit = createCredit(0, 100, unlimitedUntil, 1);
        credit.setDailyActionsDate(today);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged")).thenReturn(true);
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.persist_writeback"))
                .thenReturn(false);
        stubExecuteOnceRunsSupplier();

        creditService.release(WORKSPACE_ID, 1, TURN_ID);

        verify(creditRepository, never()).refundCredits(any(), anyInt());
        verify(creditRepository, times(1)).refundDailyActions(WORKSPACE_ID, 1, today);
    }

    @Test
    @DisplayName("release: double release (already completed under RELEASE_SCOPE) is a no-op, not an error")
    void testDoubleReleaseIsNoOp() {
        doThrow(new IdempotencyService.AlreadyCompletedException(TURN_ID))
                .when(idempotencyService)
                .executeOnce(eq(TURN_ID), eq(WORKSPACE_ID), eq("meera.turn_released"), any());

        assertDoesNotThrow(() -> creditService.release(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, never()).refundCredits(any(), anyInt());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any());
    }

    @Test
    @DisplayName("release: a racing in-flight release (AlreadyInProgressException) is also a no-op, not an error")
    void testRacingReleaseIsNoOp() {
        doThrow(new IdempotencyService.AlreadyInProgressException(TURN_ID))
                .when(idempotencyService)
                .executeOnce(eq(TURN_ID), eq(WORKSPACE_ID), eq("meera.turn_released"), any());

        assertDoesNotThrow(() -> creditService.release(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, never()).refundCredits(any(), anyInt());
    }

    @Test
    @DisplayName("release: null/blank turnId is rejected before touching any repository")
    void testReleaseRejectsBlankTurnId() {
        ApiException ex =
                assertThrows(ApiException.class, () -> creditService.release(WORKSPACE_ID, 1, "   "));

        assertEquals("RELEASE_TURN_ID_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("ensureInitialized: creates default credit row if missing")
    void testEnsureInitializedCreatesDefault() {
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(creditRepository.save(any(BrandAiCredit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BrandAiCredit result = creditService.ensureInitialized(WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(100, result.getCreditsRemaining());
        assertEquals(100, result.getMonthlyAllotment());
        verify(creditRepository).save(any(BrandAiCredit.class));
    }

    @Test
    @DisplayName("applyEscrowFundedReset: bumps allotment to 150 on first campaign")
    void testEscrowFundedResetBumpsLoyaltyAllotment() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.FREE, 100));

        Instant unlimitedUntil = Instant.now().plusSeconds(86400 * 7); // 7 days
        creditService.applyEscrowFundedReset(WORKSPACE_ID, unlimitedUntil);

        assertEquals(150, credit.getMonthlyAllotment()); // Loyalty bump
        assertEquals(150, credit.getCreditsRemaining()); // Reset to new allotment
        assertEquals(unlimitedUntil, credit.getUnlimitedUntil());
        assertNotNull(credit.getFirstCampaignAt());
        verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName(
            "F-3/SM-0.2: the loyalty bonus STACKS on whatever planAllotment is current -- Pro +"
                    + " funded campaign = 450, not a flat 150 that clobbers the Pro allotment")
    void testLoyaltyBonusStacksOnProPlanAllotment() {
        // Workspace is on Pro (planAllotment synced to 400 by AICreditResetJob/SubscriptionService
        // before this ever runs in production) -- simulate that via applyPlanAllotment directly.
        BrandAiCredit credit = createCredit(400, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.PRO, 400));

        Instant unlimitedUntil = Instant.now().plusSeconds(86400 * 7);
        creditService.applyEscrowFundedReset(WORKSPACE_ID, unlimitedUntil);

        assertEquals(450, credit.getMonthlyAllotment(), "Pro (400) + loyalty bonus (50) must be 450");
        assertEquals(450, credit.getCreditsRemaining());
        assertEquals(50, credit.getLoyaltyBonus());
        assertEquals(400, credit.getPlanAllotment(), "planAllotment itself must be untouched by the bonus");
    }

    @Test
    @DisplayName("F-3: applyPlanAllotment syncs planAllotment only, never the loyalty bonus")
    void testApplyPlanAllotmentDoesNotTouchLoyaltyBonus() {
        BrandAiCredit credit = createCredit(150, 100, null, 0);
        credit.setLoyaltyBonus(50); // workspace already earned the bonus on Free (100 + 50 = 150)
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // upgrades to Pro

        assertEquals(400, credit.getPlanAllotment());
        assertEquals(50, credit.getLoyaltyBonus(), "upgrading plan must not wipe an already-earned bonus");
        assertEquals(450, credit.getMonthlyAllotment(), "derived total must reflect both writers");
        verify(creditRepository).syncPlanAllotment(WORKSPACE_ID, 400);
    }

    // -----------------------------------------------------------------------------------------
    // REPAIR ROUND [vikram · 2026-09-18], RULING-upgrade-grant.md: on an allotment INCREASE,
    // creditsRemaining is now SET to the full new monthlyAllotment (never topped up by just the
    // delta), granted at most once per BILLING PERIOD (Subscription.currentPeriodEnd, not the
    // calendar month). This replaces the old S3 top-up rule -- see the two tests below for the
    // exact behavior the old top-up-by-delta test (testApplyPlanAllotmentTopsUpByIncreaseOnly
    // CappedAtNewAllotment, asserting 30+300=330) got wrong per the ruling: an upgrade now always
    // grants the FULL new allotment, regardless of usage.
    //
    // F-0882 note: createCredit(0, ...) below now builds a GENUINE 0-credit row (see
    // BrandAiCredit.Builder#creditsRemainingExplicitlySet / BrandAiCreditTest) -- pre-repair, this
    // exact fixture silently held monthlyAllotment (100) instead of 0, so the pre-repair version
    // of this test "passed" without ever proving the 0-credit case at all.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0881 ruling: Free-at-0-credits upgrades to Pro -> creditsRemaining is SET to the"
                    + " full new allotment (400) immediately")
    void testApplyPlanAllotmentSetsCreditsRemainingToFullAllotmentOnIncrease() {
        BrandAiCredit credit = createCredit(0, 100, null, 0); // genuinely 0 credits (F-0882 fixed)
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // Free -> Pro upgrade webhook

        assertEquals(400, credit.getMonthlyAllotment());
        assertEquals(
                400,
                credit.getCreditsRemaining(),
                "upgrading at 0 credits must leave the new Pro allotment available immediately");
        verify(creditRepository).grantAllotmentIncrease(eq(WORKSPACE_ID), eq(400), any());
    }

    @Test
    @DisplayName(
            "F-0881 ruling: an upgrade SETS creditsRemaining to the full new allotment (400), even"
                    + " for a brand that had already used some of its old allotment -- it does NOT"
                    + " top up by just the delta (30 + 300 = 330 is the OLD, now-wrong rule)")
    void testApplyPlanAllotmentSetsFullAllotmentRegardlessOfPriorUsage() {
        BrandAiCredit credit = createCredit(30, 100, null, 0); // Free, 30 of 100 left (70 used)
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // Free(100) -> Pro(400)

        assertEquals(400, credit.getMonthlyAllotment());
        assertEquals(
                400,
                credit.getCreditsRemaining(),
                "the ruling replaces the top-up-by-delta rule -- an upgrade always grants the FULL"
                        + " new allotment, not creditsRemaining + the increase");
    }

    @Test
    @DisplayName(
            "F-0881 ruling, SM-0.2: Free-at-0-credits upgrades to Pro WITH an earned loyalty bonus"
                    + " -> creditsRemaining is SET to 450 (400 + the 50 bonus), not 400")
    void testApplyPlanAllotmentSetsFullAllotmentIncludingLoyaltyBonusOnIncrease() {
        BrandAiCredit credit = createCredit(0, 100, null, 0);
        credit.setLoyaltyBonus(50); // already earned on Free (100 + 50 = 150 before the upgrade)
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // Free+bonus(150) -> Pro+bonus(450)

        assertEquals(450, credit.getMonthlyAllotment());
        assertEquals(450, credit.getCreditsRemaining());
        verify(creditRepository).grantAllotmentIncrease(eq(WORKSPACE_ID), eq(450), any());
    }

    @Test
    @DisplayName("S3 item 1: a plan DOWNGRADE (allotment decrease) never claws back creditsRemaining mid-cycle")
    void testApplyPlanAllotmentDoesNotClawBackOnDecrease() {
        BrandAiCredit credit = createCredit(380, 400, null, 0); // Pro, 380 of 400 left
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 100); // Pro -> Free downgrade

        assertEquals(100, credit.getMonthlyAllotment());
        assertEquals(
                380,
                credit.getCreditsRemaining(),
                "a downgrade must not claw back creditsRemaining mid-cycle (existing documented intent)");
        verify(creditRepository, never()).grantAllotmentIncrease(any(), anyInt(), any());
    }

    // -----------------------------------------------------------------------------------------
    // F-0883 REPAIR ROUND [vikram · 2026-09-18]: the once-per-billing-period grant guard. A
    // PAST_DUE<->ACTIVE flap re-syncs the SAME plan allotment on every reactivation (via
    // SubscriptionService#reconcileAiCreditAllotment, out of scope here) -- without this guard,
    // kabir's probe showed "granted=300 three times" under the OLD top-up rule; under the ruling's
    // new SET-to-full rule an unguarded repeat would be worse, not better (a full re-grant on
    // every flap instead of a partial one). The guard is keyed on the subscription's OWN
    // currentPeriodEnd (Subscription.java:46-49), never the calendar month.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0883: a second allotment increase inside the SAME billing period grants nothing;"
                    + " one in a NEW billing period grants again")
    void testApplyPlanAllotmentGrantsOncePerBillingPeriodThenAgainNextPeriod() {
        Instant period1End = Instant.parse("2026-10-15T00:00:00Z");
        Instant period2End = Instant.parse("2026-11-15T00:00:00Z");
        BrandAiCredit credit = createCredit(0, 100, null, 0); // Free, 0 credits
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicPlanAllotmentWrites(credit);

        // 1st increase this billing period -- grants the full 400.
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionWithPeriodEnd(period1End)));
        creditService.applyPlanAllotment(WORKSPACE_ID, 400);
        assertEquals(400, credit.getCreditsRemaining());

        // Brand spends some credits, then flaps down (a decrease, e.g. PAST_DUE) so the next call
        // registers as a genuine increase again -- otherwise applyPlanAllotment would not even
        // ATTEMPT a grant (old == new is not an increase), which would prove nothing about the
        // guard itself.
        credit.setCreditsRemaining(37);
        creditService.applyPlanAllotment(WORKSPACE_ID, 100); // decrease, never touches credits
        assertEquals(37, credit.getCreditsRemaining());

        // A SECOND increase call lands in the SAME period (e.g. a duplicate webhook, or a repeat
        // reconcile call) -- the atomic guard must block it: no-op.
        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // same period, same target allotment
        assertEquals(
                37,
                credit.getCreditsRemaining(),
                "a repeat increase-grant inside the SAME billing period must change nothing");

        // A THIRD call, but the subscription has now renewed into a NEW billing period -- must
        // grant again, restoring the full allotment.
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionWithPeriodEnd(period2End)));
        creditService.applyPlanAllotment(WORKSPACE_ID, 100); // decrease again first
        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // increase in the NEW period
        assertEquals(
                400,
                credit.getCreditsRemaining(),
                "a NEW billing period must grant again, even for the same target allotment");
    }

    @Test
    @DisplayName(
            "F-0883: PAST_DUE -> ACTIVE -> PAST_DUE -> ACTIVE inside ONE billing period grants"
                    + " exactly once (kabir's 'granted=300 three times' probe, now guarded)")
    void testApplyPlanAllotmentPastDueActiveFlapGrantsExactlyOnce() {
        Instant periodEnd = Instant.parse("2026-10-15T00:00:00Z");
        BrandAiCredit credit = createCredit(100, 100, null, 0); // starts Free-equivalent
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionWithPeriodEnd(periodEnd)));
        stubAtomicPlanAllotmentWrites(credit);

        // ACTIVE (Pro) -- 1st increase this period, grants the full 400.
        creditService.applyPlanAllotment(WORKSPACE_ID, 400);
        assertEquals(400, credit.getCreditsRemaining());

        // Brand spends credits, then flaps to PAST_DUE (reconcileAiCreditAllotment syncs down to
        // Free's 100 -- a decrease, never touches creditsRemaining).
        credit.setCreditsRemaining(120);
        creditService.applyPlanAllotment(WORKSPACE_ID, 100);
        assertEquals(120, credit.getCreditsRemaining(), "a decrease must never claw back");

        // Flaps back to ACTIVE (Pro) -- an increase again, but SAME billing period -> must no-op.
        creditService.applyPlanAllotment(WORKSPACE_ID, 400);
        assertEquals(120, credit.getCreditsRemaining(), "repeat #1 in the same period must no-op");

        // PAST_DUE again, then ACTIVE again -- still the SAME billing period -> still a no-op.
        creditService.applyPlanAllotment(WORKSPACE_ID, 100);
        creditService.applyPlanAllotment(WORKSPACE_ID, 400);
        assertEquals(
                120,
                credit.getCreditsRemaining(),
                "repeat #2 in the same period must ALSO no-op -- exactly one grant for the whole"
                        + " flap sequence, not three (kabir's probe)");

        verify(creditRepository, times(3)).grantAllotmentIncrease(eq(WORKSPACE_ID), eq(400), eq(periodEnd));
    }

    @Test
    @DisplayName(
            "F-0879 invariant: applyEscrowFundedReset syncs planAllotment from the workspace's"
                    + " CURRENT active plan BEFORE refilling -- a Free workspace stuck at a stale Pro"
                    + " planAllotment=400 funding its first campaign is refilled to 150 (100 + 50 loyalty"
                    + " bonus), not the stale 450")
    void testEscrowFundedResetResyncsStalePlanAllotmentBeforeRefill() {
        // Workspace downgraded Pro -> Free, but nothing has synced planAllotment since (F-0879
        // scenario: it is stale at Pro's 400 the moment the campaign gets funded).
        BrandAiCredit credit = createCredit(10, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.FREE, 100));

        creditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));

        assertEquals(100, credit.getPlanAllotment(), "planAllotment must be re-synced to the CURRENT plan");
        assertEquals(50, credit.getLoyaltyBonus());
        assertEquals(150, credit.getMonthlyAllotment(), "Free (100) + loyalty bonus (50) = 150, not stale 450");
        assertEquals(150, credit.getCreditsRemaining(), "refill must use the RE-SYNCED allotment, not the stale one");
    }

    @Test
    @DisplayName(
            "F-0879 invariant, InOrder: applyEscrowFundedReset consults the active plan BEFORE"
                    + " persisting the refill -- the invariant that every refill-from-monthlyAllotment"
                    + " path syncs planAllotment first")
    void testEscrowFundedResetSyncsPlanAllotmentBeforeSave() {
        BrandAiCredit credit = createCredit(10, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.FREE, 100));

        creditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));

        InOrder order = inOrder(subscriptionService, creditRepository);
        order.verify(subscriptionService).getActivePlanForWorkspace(WORKSPACE_ID);
        order.verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName("resetForNewCycle: resets credits to monthly allotment (lastReset written in UTC)")
    void testResetForNewCycleResetsCredits() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining()); // Reset to allotment
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getLastReset());
        verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName(
            "S3 item 3 'Reset runs twice': resetForNewCycleIfDue actually resets the FIRST time this"
                    + " UTC month")
    void testResetForNewCycleIfDueResetsFirstTimeThisMonth() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1));
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        creditService.resetForNewCycleIfDue(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining());
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getLastReset());
        verify(creditRepository, times(1)).save(credit);
    }

    @Test
    @DisplayName(
            "S3 item 3 'Reset runs twice': a SECOND resetForNewCycleIfDue call in the same UTC month"
                    + " is a no-op -- credits already spent this cycle are not blown back up to full"
                    + " allotment")
    void testResetForNewCycleIfDueSecondCallSameMonthIsNoOp() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1));
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        creditService.resetForNewCycleIfDue(WORKSPACE_ID); // 1st run this month -- resets to 100
        assertEquals(100, credit.getCreditsRemaining());

        // Workspace spends some credits after the reset.
        credit.setCreditsRemaining(37);

        creditService.resetForNewCycleIfDue(WORKSPACE_ID); // 2nd run, SAME UTC month -- must no-op

        assertEquals(
                37,
                credit.getCreditsRemaining(),
                "a duplicate run in the same UTC month must change nothing -- it must not blow the"
                        + " already-spent-down balance back up to the full allotment");
        verify(creditRepository, times(1)).save(credit); // only the FIRST call persisted a change
    }

    // -----------------------------------------------------------------------------------------
    // F-0893 STOPGAP [vikram · 2026-09-18]: the F-0884 repair round added a billing-period guard
    // to resetForNewCycle (comparing currentBillingPeriodEnd's Subscription lookup, which reads
    // ANY status, to the stored lastResetPeriodEnd). SubscriptionRenewalResetJob only advances
    // currentPeriodEnd for ACTIVE rows, so a CANCELLED/HALTED row's currentPeriodEnd freezes
    // forever, and that guard then no-op'd EVERY later monthly reset permanently (Kabir probe:
    // reset as Free wanting 100, stayed at 5). Approved by Swapnil 2026-09-18 as a stopgap: the
    // guard is removed and resetForNewCycle is unconditional again. F-0884 itself is RE-OPENED
    // pending Priya's credit-clock ruling (Option A). The two tests below replace the removed
    // guard's coverage: they now assert what e35d583 got WRONG -- a frozen currentPeriodEnd from a
    // CANCELLED/HALTED subscription must NOT block a legitimate monthly refill.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0893: a CANCELLED ex-Pro subscription's frozen currentPeriodEnd (equal to the stored"
                    + " lastResetPeriodEnd) must NOT block the monthly reset from refilling a Free"
                    + " workspace -- the F-0884 guard treated this as an already-reset period"
                    + " forever")
    void testResetForNewCycleRefillsDespiteFrozenPeriodEndOnCancelledSubscription() {
        Instant frozenPeriodEnd = Instant.parse("2026-08-15T00:00:00Z"); // month(s) in the past
        BrandAiCredit credit = createCredit(5, 100, null, 0); // Free, spent down to 5
        credit.setLastResetPeriodEnd(frozenPeriodEnd); // last reset already "saw" this period end
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(
                        Subscription.builder()
                                .workspaceId(WORKSPACE_ID)
                                .status(SubscriptionStatus.CANCELLED)
                                .currentPeriodEnd(frozenPeriodEnd) // frozen: no ACTIVE job advances it
                                .build()));

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(
                100,
                credit.getCreditsRemaining(),
                "monthly reset must refill a Free workspace to its 100 allotment even though the"
                        + " CANCELLED subscription's currentPeriodEnd matches the stored"
                        + " lastResetPeriodEnd -- that match is a frozen-clock artifact, not proof"
                        + " this period was already reset");
        verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName(
            "F-0893: a HALTED ex-Pro subscription's frozen currentPeriodEnd (equal to the stored"
                    + " lastResetPeriodEnd) must NOT block the monthly reset from refilling a Free"
                    + " workspace")
    void testResetForNewCycleRefillsDespiteFrozenPeriodEndOnHaltedSubscription() {
        Instant frozenPeriodEnd = Instant.parse("2026-08-15T00:00:00Z");
        BrandAiCredit credit = createCredit(5, 100, null, 0);
        credit.setLastResetPeriodEnd(frozenPeriodEnd);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(
                        Subscription.builder()
                                .workspaceId(WORKSPACE_ID)
                                .status(SubscriptionStatus.HALTED)
                                .currentPeriodEnd(frozenPeriodEnd)
                                .build()));

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(
                100,
                credit.getCreditsRemaining(),
                "monthly reset must refill a Free workspace to its 100 allotment even though the"
                        + " HALTED subscription's currentPeriodEnd matches the stored"
                        + " lastResetPeriodEnd");
        verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName(
            "F-0884: resetForNewCycle called again after the billing period genuinely ADVANCES"
                    + " (a real renewal) resets again, restoring the full allotment")
    void testResetForNewCycleResetsAgainOnceBillingPeriodAdvances() {
        Instant period1End = Instant.parse("2026-10-15T00:00:00Z");
        Instant period2End = Instant.parse("2026-11-15T00:00:00Z");
        BrandAiCredit credit = createCredit(20, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionWithPeriodEnd(period1End)));

        creditService.resetForNewCycle(WORKSPACE_ID);
        assertEquals(400, credit.getCreditsRemaining());

        credit.setCreditsRemaining(12); // spent down over the period

        // Subscription genuinely renews (SubscriptionService#applyRenewalSafetyNet always calls
        // subscription.renewPeriod BEFORE resetForNewCycle) -- currentPeriodEnd now differs.
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionWithPeriodEnd(period2End)));
        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(
                400,
                credit.getCreditsRemaining(),
                "a genuinely NEW billing period must still reset normally");
        verify(creditRepository, times(2)).save(credit);
    }

    @Test
    @DisplayName(
            "F-0884: a workspace with no resolvable Subscription row (periodEnd null) disables the"
                    + " guard -- unchanged unconditional-reset behavior for a plain Free workspace")
    void testResetForNewCycleGuardDisabledWithoutSubscription() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        // subscriptionService.getByWorkspaceId left unstubbed -> Optional.empty() -> periodEnd null

        creditService.resetForNewCycle(WORKSPACE_ID);
        credit.setCreditsRemaining(9);
        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining(), "both calls must reset -- no period to guard on");
        verify(creditRepository, times(2)).save(credit);
    }

    private BrandAiCredit createCredit(int remaining, int allotment, Instant unlimitedUntil, int dailyActions) {
        BrandAiCredit credit = BrandAiCredit.builder()
                .workspaceId(WORKSPACE_ID)
                .creditsRemaining(remaining)
                .monthlyAllotment(allotment)
                .cycleStart(LocalDate.now())
                .lastReset(LocalDate.now())
                .build();
        credit.setUnlimitedUntil(unlimitedUntil);
        credit.setDailyActionsUsed(dailyActions);
        return credit;
    }
}
