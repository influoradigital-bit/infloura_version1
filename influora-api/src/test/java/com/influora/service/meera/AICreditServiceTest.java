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
import com.influora.domain.enums.PlanCode;
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

    /**
     * T-CREDITCLOCK-0918 [vikram · 2026-09-18]: real Mockito mocks of {@code
     * BrandAiCreditRepository}'s atomic {@code @Modifying} queries do nothing to {@code credit} on
     * their own. These stubs simulate each real query's SQL semantics against the SAME in-memory
     * {@code credit} object so unit tests can still assert on its resulting state, mirroring
     * {@code AICreditResetJobTest}'s "stubbed repository that mutates a single in-memory row"
     * precedent (see that test's class javadoc for why a mock that only records calls would not
     * prove the resulting numbers are right).
     */
    private void stubAtomicWrites(BrandAiCredit credit) {
        lenient()
                .when(creditRepository.syncPlanAllotment(eq(WORKSPACE_ID), anyInt(), any()))
                .thenAnswer(
                        invocation -> {
                            credit.setPlanAllotment(invocation.getArgument(1));
                            return 1;
                        });
        // refillForBillingPeriod: SETs creditsRemaining from the row's OWN (already-synced)
        // monthlyAllotment, guarded on creditGrantPeriodEnd, also stamps lastReset.
        lenient()
                .when(creditRepository.refillForBillingPeriod(eq(WORKSPACE_ID), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Instant periodEnd = invocation.getArgument(1);
                            LocalDate today = invocation.getArgument(2);
                            boolean guardBlocks =
                                    credit.getCreditGrantPeriodEnd() != null
                                            && credit.getCreditGrantPeriodEnd().equals(periodEnd);
                            if (guardBlocks) {
                                return 0;
                            }
                            credit.setCreditsRemaining(credit.getMonthlyAllotment());
                            credit.setCreditGrantPeriodEnd(periodEnd);
                            credit.setLastReset(today);
                            return 1;
                        });
        // topUpOnJoinCalendarClock: raises creditsRemaining to monthlyAllotment (never lowers),
        // guarded to fire at most once per UTC calendar month.
        lenient()
                .when(creditRepository.topUpOnJoinCalendarClock(eq(WORKSPACE_ID), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            LocalDate today = invocation.getArgument(1);
                            LocalDate firstOfMonth = invocation.getArgument(2);
                            if (!(credit.getLastReset() == null || credit.getLastReset().isBefore(firstOfMonth))) {
                                return 0;
                            }
                            if (credit.getCreditsRemaining() < credit.getMonthlyAllotment()) {
                                credit.setCreditsRemaining(credit.getMonthlyAllotment());
                            }
                            credit.setLastReset(today);
                            return 1;
                        });
        // calendarReset: unconditional SET creditsRemaining = monthlyAllotment, lastReset = today.
        lenient()
                .when(creditRepository.calendarReset(eq(WORKSPACE_ID), any(), any()))
                .thenAnswer(
                        invocation -> {
                            credit.setCreditsRemaining(credit.getMonthlyAllotment());
                            credit.setLastReset(invocation.getArgument(1));
                            return 1;
                        });
        // applyEscrowFundedReset: conditionally earns the loyalty bonus (first funded campaign
        // only), refills to planAllotment + loyaltyBonus, opens the unlimited window -- never
        // touches creditGrantPeriodEnd or lastReset (F-0894).
        lenient()
                .when(creditRepository.applyEscrowFundedReset(eq(WORKSPACE_ID), anyInt(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            int loyaltyBonus = invocation.getArgument(1);
                            Instant now = invocation.getArgument(2);
                            Instant unlimitedUntil = invocation.getArgument(3);
                            if (credit.getFirstCampaignAt() == null) {
                                credit.setLoyaltyBonus(loyaltyBonus);
                                credit.setFirstCampaignAt(now);
                            }
                            credit.setCreditsRemaining(credit.getPlanAllotment() + credit.getLoyaltyBonus());
                            credit.setUnlimitedUntil(unlimitedUntil);
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
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), anyInt(), any())).thenReturn(0); // Decrement fails

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
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), eq(1), any())).thenReturn(1); // Decrement succeeds

        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        verify(creditRepository).tryDecrement(eq(WORKSPACE_ID), eq(1), any());
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
        verify(creditRepository, never()).tryDecrement(any(), anyInt(), any());
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
        verify(creditRepository).bumpDailyActions(eq(WORKSPACE_ID), eq(LocalDate.now(ZoneOffset.UTC)), any());
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
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), eq(1), any())).thenReturn(1);

        assertDoesNotThrow(() -> creditService.tryConsume(WORKSPACE_ID, 1));

        verify(creditRepository, never()).save(any(BrandAiCredit.class));
        verify(creditRepository, times(1)).bumpDailyActions(eq(WORKSPACE_ID), any(LocalDate.class), any());
        verify(creditRepository, times(1)).tryDecrement(eq(WORKSPACE_ID), eq(1), any());
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
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), anyInt(), any())).thenReturn(0);

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
        when(creditRepository.tryDecrement(eq(WORKSPACE_ID), eq(1), any())).thenReturn(1);
        stubExecuteOnceRunsSupplier();

        assertDoesNotThrow(() -> creditService.tryConsumeForTurn(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, times(1)).tryDecrement(eq(WORKSPACE_ID), eq(1), any());
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

        verify(creditRepository, times(1)).refundCredits(eq(WORKSPACE_ID), eq(1), any());
        verify(creditRepository, times(1)).refundDailyActions(eq(WORKSPACE_ID), eq(1), eq(today), any());
    }

    @Test
    @DisplayName("release: turnId was NEVER charged -> no-op, no refund")
    void testReleaseNoOpWhenNeverCharged() {
        when(idempotencyService.isCompleted(TURN_ID, WORKSPACE_ID, "meera.turn_charged")).thenReturn(false);
        stubExecuteOnceRunsSupplier();

        creditService.release(WORKSPACE_ID, 1, TURN_ID);

        verify(creditRepository, never()).refundCredits(any(), anyInt(), any());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any(), any());
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

        verify(creditRepository, never()).refundCredits(any(), anyInt(), any());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any(), any());
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

        verify(creditRepository, never()).refundCredits(any(), anyInt(), any());
        verify(creditRepository, times(1)).refundDailyActions(eq(WORKSPACE_ID), eq(1), eq(today), any());
    }

    @Test
    @DisplayName("release: double release (already completed under RELEASE_SCOPE) is a no-op, not an error")
    void testDoubleReleaseIsNoOp() {
        doThrow(new IdempotencyService.AlreadyCompletedException(TURN_ID))
                .when(idempotencyService)
                .executeOnce(eq(TURN_ID), eq(WORKSPACE_ID), eq("meera.turn_released"), any());

        assertDoesNotThrow(() -> creditService.release(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, never()).refundCredits(any(), anyInt(), any());
        verify(creditRepository, never()).refundDailyActions(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("release: a racing in-flight release (AlreadyInProgressException) is also a no-op, not an error")
    void testRacingReleaseIsNoOp() {
        doThrow(new IdempotencyService.AlreadyInProgressException(TURN_ID))
                .when(idempotencyService)
                .executeOnce(eq(TURN_ID), eq(WORKSPACE_ID), eq("meera.turn_released"), any());

        assertDoesNotThrow(() -> creditService.release(WORKSPACE_ID, 1, TURN_ID));

        verify(creditRepository, never()).refundCredits(any(), anyInt(), any());
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
    @DisplayName(
            "T-CREDITCLOCK-0918: applyEscrowFundedReset bumps allotment to 150 on first campaign,"
                    + " atomically -- never a full-row save()")
    void testEscrowFundedResetBumpsLoyaltyAllotment() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.FREE, 100));
        stubAtomicWrites(credit);

        Instant unlimitedUntil = Instant.now().plusSeconds(86400 * 7); // 7 days
        creditService.applyEscrowFundedReset(WORKSPACE_ID, unlimitedUntil);

        assertEquals(150, credit.getMonthlyAllotment()); // Loyalty bump
        assertEquals(150, credit.getCreditsRemaining()); // Reset to new allotment
        assertEquals(unlimitedUntil, credit.getUnlimitedUntil());
        assertNotNull(credit.getFirstCampaignAt());
        verify(creditRepository, never()).save(any(BrandAiCredit.class));
        verify(creditRepository).syncPlanAllotment(eq(WORKSPACE_ID), eq(100), any());
        verify(creditRepository).applyEscrowFundedReset(eq(WORKSPACE_ID), eq(50), any(), eq(unlimitedUntil));
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
        stubAtomicWrites(credit);

        Instant unlimitedUntil = Instant.now().plusSeconds(86400 * 7);
        creditService.applyEscrowFundedReset(WORKSPACE_ID, unlimitedUntil);

        assertEquals(450, credit.getMonthlyAllotment(), "Pro (400) + loyalty bonus (50) must be 450");
        assertEquals(450, credit.getCreditsRemaining());
        assertEquals(50, credit.getLoyaltyBonus());
        assertEquals(400, credit.getPlanAllotment(), "planAllotment itself must be untouched by the bonus");
    }

    @Test
    @DisplayName(
            "T-CREDITCLOCK-0918 (F-0894): applyEscrowFundedReset never touches creditGrantPeriodEnd"
                    + " or lastReset -- a funded launch is on NEITHER clock, so it must never revert"
                    + " a concurrent billing-period or calendar-clock refill's own marker")
    void testEscrowFundedResetNeverTouchesClockMarkers() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        Instant existingMarker = Instant.parse("2026-10-15T00:00:00Z");
        credit.setCreditGrantPeriodEnd(existingMarker);
        LocalDate existingLastReset = LocalDate.of(2026, 9, 1);
        credit.setLastReset(existingLastReset);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan(PlanCode.PRO, 400));
        stubAtomicWrites(credit);

        creditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));

        assertEquals(existingMarker, credit.getCreditGrantPeriodEnd(), "must not touch the billing-period marker");
        assertEquals(existingLastReset, credit.getLastReset(), "must not touch lastReset");
    }

    @Test
    @DisplayName("F-3: applyPlanAllotment syncs planAllotment only, never the loyalty bonus, never creditsRemaining")
    void testApplyPlanAllotmentDoesNotTouchLoyaltyBonus() {
        BrandAiCredit credit = createCredit(150, 100, null, 0);
        credit.setLoyaltyBonus(50); // workspace already earned the bonus on Free (100 + 50 = 150)
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 400); // upgrades to Pro

        assertEquals(400, credit.getPlanAllotment());
        assertEquals(50, credit.getLoyaltyBonus(), "upgrading plan must not wipe an already-earned bonus");
        assertEquals(450, credit.getMonthlyAllotment(), "derived total must reflect both writers");
        assertEquals(150, credit.getCreditsRemaining(), "T-CREDITCLOCK-0918: applyPlanAllotment is sync-only now");
        verify(creditRepository).syncPlanAllotment(eq(WORKSPACE_ID), eq(400), any());
        verify(creditRepository, never()).refillForBillingPeriod(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "T-CREDITCLOCK-0918: a plan DOWNGRADE (allotment decrease) never claws back"
                    + " creditsRemaining mid-cycle -- applyPlanAllotment never even attempts a grant"
                    + " any more (that moved entirely to refillForBillingPeriod)")
    void testApplyPlanAllotmentDoesNotClawBackOnDecrease() {
        BrandAiCredit credit = createCredit(380, 400, null, 0); // Pro, 380 of 400 left
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.applyPlanAllotment(WORKSPACE_ID, 100); // Pro -> Free downgrade

        assertEquals(100, credit.getMonthlyAllotment());
        assertEquals(
                380,
                credit.getCreditsRemaining(),
                "a downgrade must not claw back creditsRemaining mid-cycle (existing documented intent)");
        verify(creditRepository, never()).refillForBillingPeriod(any(), any(), any(), any());
    }

    // -----------------------------------------------------------------------------------------
    // T-CREDITCLOCK-0918 [vikram · 2026-09-18]: refillForBillingPeriod -- the billing-clock refill
    // primitive (wiki/decisions/2026-09-18-ai-credit-clock.md §2/§4), now called directly rather
    // than via applyPlanAllotment's old (retired) increase-detection. Guarded on the subscription's
    // OWN currentPeriodEnd, never the calendar month; fails CLOSED on a null periodEnd.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "refillForBillingPeriod: SETs creditsRemaining to the row's OWN monthlyAllotment, once"
                    + " per billing period, then again in a NEW period")
    void testRefillForBillingPeriodGrantsOncePerBillingPeriodThenAgainNextPeriod() {
        Instant period1End = Instant.parse("2026-10-15T00:00:00Z");
        Instant period2End = Instant.parse("2026-11-15T00:00:00Z");
        BrandAiCredit credit = createCredit(0, 400, null, 0); // Pro, 0 credits
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        // 1st refill this billing period -- grants the full 400.
        creditService.refillForBillingPeriod(WORKSPACE_ID, period1End);
        assertEquals(400, credit.getCreditsRemaining());

        // Brand spends credits.
        credit.setCreditsRemaining(37);

        // A SECOND refill call lands in the SAME period (e.g. a duplicate webhook, or a repeat
        // reconcile call) -- the atomic guard must block it: no-op.
        creditService.refillForBillingPeriod(WORKSPACE_ID, period1End);
        assertEquals(
                37, credit.getCreditsRemaining(), "a repeat refill inside the SAME billing period must change nothing");

        // A THIRD call, but the subscription has now renewed into a NEW billing period -- must
        // grant again, restoring the full allotment.
        creditService.refillForBillingPeriod(WORKSPACE_ID, period2End);
        assertEquals(
                400,
                credit.getCreditsRemaining(),
                "a NEW billing period must refill again, even to the same target allotment");
    }

    @Test
    @DisplayName(
            "refillForBillingPeriod: PAST_DUE<->ACTIVE flap calling refill repeatedly for the SAME"
                    + " period (status-only events carry no new period) grants exactly once"
                    + " (kabir's 'granted=300 three times' probe, now guarded)")
    void testRefillForBillingPeriodFlapGrantsExactlyOnce() {
        Instant periodEnd = Instant.parse("2026-10-15T00:00:00Z");
        BrandAiCredit credit = createCredit(200, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.refillForBillingPeriod(WORKSPACE_ID, periodEnd); // ACTIVE
        assertEquals(400, credit.getCreditsRemaining());
        credit.setCreditsRemaining(120);

        creditService.refillForBillingPeriod(WORKSPACE_ID, periodEnd); // flap: ACTIVE again, same period
        creditService.refillForBillingPeriod(WORKSPACE_ID, periodEnd); // flap again
        assertEquals(
                120,
                credit.getCreditsRemaining(),
                "repeat calls for the SAME period must ALL no-op -- exactly one grant for the whole"
                        + " flap sequence, not three (kabir's probe)");

        verify(creditRepository, times(3)).refillForBillingPeriod(eq(WORKSPACE_ID), eq(periodEnd), any(), any());
    }

    @Test
    @DisplayName(
            "refillForBillingPeriod: a null periodEnd fails CLOSED -- logs and refuses to refill,"
                    + " never calls the repository (RULING-upgrade-grant.md update, replacing the"
                    + " old fail-OPEN grantAllotmentIncrease behavior)")
    void testRefillForBillingPeriodFailsClosedOnNullPeriodEnd() {
        BrandAiCredit credit = createCredit(50, 400, null, 0);
        // findByWorkspaceId intentionally NOT stubbed here -- a null periodEnd must return before
        // ever touching the credit row at all.

        creditService.refillForBillingPeriod(WORKSPACE_ID, null);

        verify(creditRepository, never()).refillForBillingPeriod(any(), any(), any(), any());
        verify(creditRepository, never()).findByWorkspaceId(any());
    }

    @Test
    @DisplayName("refillForBillingPeriod: truncates periodEnd to whole seconds before binding (§4 precision)")
    void testRefillForBillingPeriodTruncatesToSeconds() {
        BrandAiCredit credit = createCredit(50, 400, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);
        Instant withNanos = Instant.parse("2026-10-15T00:00:00.123456789Z");

        creditService.refillForBillingPeriod(WORKSPACE_ID, withNanos);

        verify(creditRepository)
                .refillForBillingPeriod(eq(WORKSPACE_ID), eq(Instant.parse("2026-10-15T00:00:00Z")), any(), any());
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
        stubAtomicWrites(credit);

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
        stubAtomicWrites(credit);

        creditService.applyEscrowFundedReset(WORKSPACE_ID, Instant.now().plusSeconds(86_400 * 7));

        InOrder order = inOrder(subscriptionService, creditRepository);
        order.verify(subscriptionService).getActivePlanForWorkspace(WORKSPACE_ID);
        order.verify(creditRepository).syncPlanAllotment(eq(WORKSPACE_ID), eq(100), any());
        order.verify(creditRepository).applyEscrowFundedReset(eq(WORKSPACE_ID), anyInt(), any(), any());
    }

    @Test
    @DisplayName(
            "T-CREDITCLOCK-0918: resetForNewCycle is an atomic, unconditional calendar reset -- no"
                    + " full-row save(), no billing-period guard (the F-0884/F-0893 guard and"
                    + " currentBillingPeriodEnd are deleted entirely; AICreditResetJob skips"
                    + " BILLING_PERIOD workspaces before this is ever called for one)")
    void testResetForNewCycleResetsCredits() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining()); // Reset to allotment
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getLastReset());
        verify(creditRepository, never()).save(any(BrandAiCredit.class));
        verify(creditRepository).calendarReset(eq(WORKSPACE_ID), eq(LocalDate.now(ZoneOffset.UTC)), any());
    }

    @Test
    @DisplayName(
            "S3 item 3 'Reset runs twice': resetForNewCycleIfDue actually resets the FIRST time this"
                    + " UTC month")
    void testResetForNewCycleIfDueResetsFirstTimeThisMonth() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1));
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.resetForNewCycleIfDue(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining());
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getLastReset());
        verify(creditRepository, times(1)).calendarReset(eq(WORKSPACE_ID), any(), any());
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
        stubAtomicWrites(credit);

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
        verify(creditRepository, times(1)).calendarReset(eq(WORKSPACE_ID), any(), any()); // only the FIRST call fired
    }

    // -----------------------------------------------------------------------------------------
    // F-0893 gate (.proof-os/gates/free-refill-after-cancel.sh) [vikram · 2026-09-18]: these two
    // tests are pinned BY NAME in that gate script. T-CREDITCLOCK-0918 deletes the F-0884/F-0893
    // periodEnd guard and currentBillingPeriodEnd entirely (not just disables them) -- the ORIGINAL
    // F-0893 scenario ("a CANCELLED/HALTED ex-Pro workspace's frozen currentPeriodEnd must not
    // block the monthly reset from refilling a Free workspace") is now trivially true, because
    // resetForNewCycle no longer reads any Subscription state at all. Kept as regression coverage,
    // same method names, so the gate keeps passing and a future re-introduction of a
    // Subscription-period-based guard on this method would have to consciously break these.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0893: resetForNewCycle refills a CANCELLED ex-Pro workspace to Free's 100 -- no"
                    + " Subscription state can block it any more (the periodEnd guard is deleted, not"
                    + " just disabled)")
    void testResetForNewCycleRefillsDespiteFrozenPeriodEndOnCancelledSubscription() {
        BrandAiCredit credit = createCredit(5, 100, null, 0); // Free, spent down to 5
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);
        // subscriptionService is never consulted by resetForNewCycle any more -- left unstubbed on
        // purpose (a CANCELLED subscription with a frozen currentPeriodEnd would have blocked the
        // old, now-deleted guard; this method cannot see it at all any more).

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(
                100,
                credit.getCreditsRemaining(),
                "monthly reset must refill a Free workspace to its 100 allotment regardless of any"
                        + " CANCELLED subscription's frozen currentPeriodEnd");
    }

    @Test
    @DisplayName(
            "F-0893: resetForNewCycle refills a HALTED ex-Pro workspace to Free's 100 -- no"
                    + " Subscription state can block it any more")
    void testResetForNewCycleRefillsDespiteFrozenPeriodEndOnHaltedSubscription() {
        BrandAiCredit credit = createCredit(5, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(
                100,
                credit.getCreditsRemaining(),
                "monthly reset must refill a Free workspace to its 100 allotment regardless of any"
                        + " HALTED subscription's frozen currentPeriodEnd");
    }

    // -----------------------------------------------------------------------------------------
    // T-CREDITCLOCK-0918 [vikram · 2026-09-18]: topUpOnJoinCalendarClock -- the §3 handover top-up,
    // fired by SubscriptionService#reconcileAiCreditAllotment on every reconcile for a workspace on
    // the calendar clock. Raises but never lowers, fires at most once per UTC calendar month.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("topUpOnJoinCalendarClock: raises creditsRemaining up to monthlyAllotment when it was below")
    void testTopUpOnJoinCalendarClockRaisesBelowAllotment() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1)); // last touched before this month
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.topUpOnJoinCalendarClock(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining());
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getLastReset());
    }

    @Test
    @DisplayName("topUpOnJoinCalendarClock: never LOWERS creditsRemaining when it is already above monthlyAllotment")
    void testTopUpOnJoinCalendarClockNeverLowersAboveAllotment() {
        BrandAiCredit credit = createCredit(300, 100, null, 0); // e.g. surplus left from a prior Pro period
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1));
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.topUpOnJoinCalendarClock(WORKSPACE_ID);

        assertEquals(300, credit.getCreditsRemaining(), "must never claw back a surplus balance");
    }

    @Test
    @DisplayName(
            "topUpOnJoinCalendarClock: a SECOND call in the SAME UTC calendar month is a no-op"
                    + " (renewed-and-cancelled in the same month gets no top-up, per §3)")
    void testTopUpOnJoinCalendarClockSecondCallSameMonthIsNoOp() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC).minusMonths(1));
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));
        stubAtomicWrites(credit);

        creditService.topUpOnJoinCalendarClock(WORKSPACE_ID); // 1st call this month -- tops up to 100
        assertEquals(100, credit.getCreditsRemaining());

        credit.setCreditsRemaining(17); // spent some
        creditService.topUpOnJoinCalendarClock(WORKSPACE_ID); // 2nd call, SAME month -- no-op

        assertEquals(17, credit.getCreditsRemaining(), "a second call in the same month must change nothing");
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
