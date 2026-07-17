package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.UsageCounter;
import com.influora.domain.entity.UsageCounterDetail;
import com.influora.domain.enums.UsageMetric;
import com.influora.repository.UsageCounterDetailRepository;
import com.influora.repository.UsageCounterRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Task 22 Flag #1 fix (Rohan CFO ruling, SHARED_CONTEXT.md 2026-07-14, "HOLD for per-creator
 * dedup fix"): unit-proves {@link UsageCounterService#recordCreatorLookup}'s actual per-creator
 * dedup/increment/concurrency-race logic against its real repository-layer collaborators (mocked
 * only at the repository boundary, one layer down from the service under test -- MP-1 "real
 * objects one layer at a time" discipline).
 *
 * <p>{@code AnalyticsUsageCapInterceptor}'s reaction to this method's return value (402 vs.
 * allow) is separately proven in {@code PlanGateWiringTest}, which mocks {@code
 * UsageCounterService} itself per its own documented boundary. THIS class is where the actual
 * counting semantics are proven: "4 sub-endpoint calls for 1 creator = 1 used, not 4," "a 2nd
 * distinct creator over the limit is rejected before being recorded," "an already-seen creator
 * never double-charges," and "two near-simultaneous requests for the same new creator can't
 * double-count."
 */
@ExtendWith(MockitoExtension.class)
class UsageCounterServiceTest {

    private static final String WORKSPACE_ID = "ws-free-1";
    private static final LocalDate PERIOD_START = LocalDate.now().withDayOfMonth(1);

    @Mock private UsageCounterRepository usageCounterRepository;
    @Mock private UsageCounterDetailRepository usageCounterDetailRepository;
    @Mock private SubscriptionService subscriptionService;

    private UsageCounterService service;

    @BeforeEach
    void setUp() {
        service =
                new UsageCounterService(
                        usageCounterRepository, usageCounterDetailRepository, subscriptionService);
        // No subscription row for this workspace -- resolvePeriodStart falls back to "start of
        // the current UTC calendar month" (Free tier, no billing-cycle subscription row yet).
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("A genuinely NEW creator under the limit is recorded, allowed, and increments the counter exactly once")
    void newCreatorUnderLimitAllowedAndIncrementsOnce() {
        when(usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, "c1"))
                .thenReturn(false);
        when(usageCounterRepository.findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START))
                .thenReturn(Optional.empty()); // used = 0
        when(usageCounterRepository.tryIncrement(
                        eq(WORKSPACE_ID), eq(UsageMetric.CREATOR_ANALYTICS_VIEW), eq(PERIOD_START), eq(1)))
                .thenReturn(1);

        boolean allowed =
                service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);

        assertTrue(allowed);
        verify(usageCounterDetailRepository, times(1)).save(any(UsageCounterDetail.class));
        verify(usageCounterRepository, times(1))
                .tryIncrement(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, 1);
    }

    @Test
    @DisplayName("Free-tier brand hitting all 4 sub-endpoints for the SAME creator: the counter is only ever incremented once, not 4 times")
    void allFourSubEndpointsForSameCreatorIncrementCounterOnlyOnce() {
        // 1st call (e.g. /metrics): creator not yet recorded this period. Calls 2-4 (scores,
        // demographics, media): the 1st call's dedup row now exists.
        when(usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, "c1"))
                .thenReturn(false, true, true, true);
        when(usageCounterRepository.findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START))
                .thenReturn(Optional.empty());
        when(usageCounterRepository.tryIncrement(
                        eq(WORKSPACE_ID), eq(UsageMetric.CREATOR_ANALYTICS_VIEW), eq(PERIOD_START), eq(1)))
                .thenReturn(1);

        for (int i = 0; i < 4; i++) { // metrics, scores, demographics, media
            boolean allowed =
                    service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);
            assertTrue(allowed, "call #" + (i + 1) + " for the same creator must succeed");
        }

        // Only the FIRST of the 4 calls actually recorded/counted the creator.
        verify(usageCounterDetailRepository, times(1)).save(any(UsageCounterDetail.class));
        verify(usageCounterRepository, times(1))
                .tryIncrement(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, 1);
    }

    @Test
    @DisplayName("A 2nd DISTINCT creator that would exceed the limit is rejected and never recorded/counted")
    void secondDistinctCreatorOverLimitRejectedWithoutConsumingQuota() {
        when(usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, "c2"))
                .thenReturn(false); // "c2" is genuinely new this period
        UsageCounter existing = mock(UsageCounter.class);
        when(existing.getUsed()).thenReturn(1); // 1 distinct creator already recorded == the limit
        when(usageCounterRepository.findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START))
                .thenReturn(Optional.of(existing));

        boolean allowed =
                service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c2", 1);

        assertFalse(allowed);
        verify(usageCounterDetailRepository, never()).save(any(UsageCounterDetail.class));
        verify(usageCounterRepository, never()).tryIncrement(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("An already-seen creator later in the same period is always allowed and never double-charges")
    void alreadySeenCreatorAlwaysAllowedNeverDoubleCharges() {
        when(usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, "c1"))
                .thenReturn(true); // already recorded earlier this period

        boolean allowed =
                service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);

        assertTrue(allowed);
        verify(usageCounterRepository, never())
                .findByWorkspaceIdAndMetricAndPeriodStart(any(), any(), any());
        verify(usageCounterDetailRepository, never()).save(any(UsageCounterDetail.class));
        verify(usageCounterRepository, never()).tryIncrement(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("CONCURRENCY: two near-simultaneous requests for the SAME NEW creator only increment the counter once")
    void concurrentRequestsForSameNewCreatorOnlyIncrementOnce() {
        // Simulate the race window explicitly: BOTH requests' existence-check runs before either
        // has committed its insert, so both see "not yet recorded" for creator "c1" -- this is
        // exactly the race V58's uk_workspace_metric_period_dedup unique constraint (plus the
        // duplicate-key catch in recordCreatorLookup) exists to close.
        when(usageCounterDetailRepository.existsByWorkspaceIdAndMetricAndPeriodStartAndDedupKey(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, "c1"))
                .thenReturn(false);
        when(usageCounterRepository.findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START))
                .thenReturn(Optional.empty());
        when(usageCounterRepository.tryIncrement(
                        eq(WORKSPACE_ID), eq(UsageMetric.CREATOR_ANALYTICS_VIEW), eq(PERIOD_START), eq(1)))
                .thenReturn(1);
        // The first racer's insert wins; the second racer's insert hits the unique constraint --
        // exactly what a real concurrent DB-level race produces (DataIntegrityViolationException).
        Answer<UsageCounterDetail> secondCallLosesTheRace =
                new Answer<>() {
                    private int callCount = 0;

                    @Override
                    public UsageCounterDetail answer(InvocationOnMock invocation) {
                        callCount++;
                        if (callCount == 2) {
                            throw new DataIntegrityViolationException("uk_workspace_metric_period_dedup");
                        }
                        return null; // recordCreatorLookup never reads save()'s return value
                    }
                };
        doAnswer(secondCallLosesTheRace)
                .when(usageCounterDetailRepository)
                .save(any(UsageCounterDetail.class));

        boolean firstAllowed =
                service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);
        boolean secondAllowed =
                service.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);

        assertTrue(firstAllowed, "the race winner must be allowed");
        assertTrue(secondAllowed, "the race loser must still be allowed (already recorded by the winner)");
        // Exactly ONE increment despite two racing "new creator" attempts for the same creator.
        verify(usageCounterRepository, times(1))
                .tryIncrement(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, PERIOD_START, 1);
        verify(usageCounterDetailRepository, times(2)).save(any(UsageCounterDetail.class));
    }
}
