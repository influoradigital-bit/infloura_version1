package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.repository.BrandAiCreditRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P7: Unit tests for AICreditService (16-VIKRAM-REMAINING-TASKS.md).
 * Priority: circuit-breaker gate, monthly reset, atomic decrement, 500/day hard cap (P4).
 */
@ExtendWith(MockitoExtension.class)
class AICreditServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";

    @Mock private BrandAiCreditRepository creditRepository;

    private AICreditService creditService;

    @BeforeEach
    void setUp() {
        creditService = new AICreditService(creditRepository);
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
