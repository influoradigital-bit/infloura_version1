package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.UsageMetric;
import com.influora.repository.UsageCounterDetailRepository;
import com.influora.repository.UsageCounterRepository;
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
 * Closes the blind spot that {@code BillingControllerUsagePeriodTest} cannot reach: that test
 * mocks {@code UsageCounterService}, so it proves the controller REPORTS whatever anchor the
 * service returns, but not that the service returns the right one. Mutating {@link
 * UsageCounterService#getCurrentPeriodStart} to re-derive "first of the current UTC calendar
 * month" was verified to leave every other test in this module GREEN before this class existed.
 *
 * <p>What is proven here against the real service object (repositories mocked one layer down):
 *
 * <ol>
 *   <li>with a Subscription row, the anchor is that row's {@code currentPeriodStart} — a mid-month
 *       date no calendar-month derivation can produce;
 *   <li>the counts read by {@link UsageCounterService#getUsageForCurrentPeriod} are queried
 *       against that SAME anchor, which is what makes it honest for {@code GET /billing/usage} to
 *       label them with it;
 *   <li>with no Subscription row, the documented calendar-month fallback still applies.
 * </ol>
 *
 * <p>Lives in its own class rather than in {@code UsageCounterServiceTest} because that class's
 * {@code setUp} stubs {@code getByWorkspaceId -> Optional.empty()} for every test, which a
 * subscription-present case would contradict under strict stubbing.
 */
@ExtendWith(MockitoExtension.class)
class UsageCounterServicePeriodAnchorTest {

    private static final String WORKSPACE_ID = "ws-comp-1";

    /** Mid-month, past year: unreachable by any "first of the current month" derivation. */
    private static final LocalDate SUBSCRIPTION_ANCHOR = LocalDate.of(2025, 3, 17);

    @Mock private UsageCounterRepository usageCounterRepository;
    @Mock private UsageCounterDetailRepository usageCounterDetailRepository;
    @Mock private SubscriptionService subscriptionService;

    private UsageCounterService service;

    @BeforeEach
    void setUp() {
        service =
                new UsageCounterService(
                        usageCounterRepository, usageCounterDetailRepository, subscriptionService);
    }

    @Test
    @DisplayName("With a Subscription row, the period anchor is its currentPeriodStart -- not the calendar month")
    void anchorFollowsSubscriptionCurrentPeriodStart() {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getCurrentPeriodStart())
                .thenReturn(SUBSCRIPTION_ANCHOR.atStartOfDay(ZoneOffset.UTC).toInstant());
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(subscription));

        assertEquals(
                SUBSCRIPTION_ANCHOR,
                service.getCurrentPeriodStart(WORKSPACE_ID),
                "a Razorpay/comp/renewed workspace is anchored mid-month, not on the 1st");
    }

    @Test
    @DisplayName("The counts really are read against that same anchor -- the reported period describes them")
    void countsAreReadAgainstTheReportedAnchor() {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getCurrentPeriodStart())
                .thenReturn(SUBSCRIPTION_ANCHOR.atStartOfDay(ZoneOffset.UTC).toInstant());
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(subscription));
        when(usageCounterRepository.findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.TRACKED_CREATOR, SUBSCRIPTION_ANCHOR))
                .thenReturn(Optional.empty());

        LocalDate reported = service.getCurrentPeriodStart(WORKSPACE_ID);
        service.getUsageForCurrentPeriod(WORKSPACE_ID, UsageMetric.TRACKED_CREATOR);

        assertEquals(SUBSCRIPTION_ANCHOR, reported);
        verify(usageCounterRepository)
                .findByWorkspaceIdAndMetricAndPeriodStart(
                        WORKSPACE_ID, UsageMetric.TRACKED_CREATOR, reported);
    }

    @Test
    @DisplayName("With no Subscription row, the documented calendar-month fallback still applies")
    void noSubscriptionFallsBackToCalendarMonth() {
        when(subscriptionService.getByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertEquals(
                LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).withDayOfMonth(1),
                service.getCurrentPeriodStart(WORKSPACE_ID));
    }
}
