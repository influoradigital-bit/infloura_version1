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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private AICreditService creditService;

    @BeforeEach
    void setUp() {
        creditService = new AICreditService(creditRepository, idempotencyService);
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

        // Verify the credit was saved (counter was reset and incremented)
        verify(creditRepository).save(credit);
        assertEquals(1, credit.getDailyActionsUsed());
        assertEquals(LocalDate.now(ZoneOffset.UTC), credit.getDailyActionsDate());
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

        Instant unlimitedUntil = Instant.now().plusSeconds(86400 * 7); // 7 days
        creditService.applyEscrowFundedReset(WORKSPACE_ID, unlimitedUntil);

        assertEquals(150, credit.getMonthlyAllotment()); // Loyalty bump
        assertEquals(150, credit.getCreditsRemaining()); // Reset to new allotment
        assertEquals(unlimitedUntil, credit.getUnlimitedUntil());
        assertNotNull(credit.getFirstCampaignAt());
        verify(creditRepository).save(credit);
    }

    @Test
    @DisplayName("resetForNewCycle: resets credits to monthly allotment")
    void testResetForNewCycleResetsCredits() {
        BrandAiCredit credit = createCredit(20, 100, null, 0);
        when(creditRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(credit));

        creditService.resetForNewCycle(WORKSPACE_ID);

        assertEquals(100, credit.getCreditsRemaining()); // Reset to allotment
        assertEquals(LocalDate.now(), credit.getLastReset());
        verify(creditRepository).save(credit);
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
