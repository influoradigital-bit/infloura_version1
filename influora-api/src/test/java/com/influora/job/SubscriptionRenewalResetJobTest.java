package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.billing.SubscriptionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MP-1 wiring tests for {@link SubscriptionRenewalResetJob} — the missed-webhook safety net (Task
 * 24, mandatory Kabir gate). Verifies the job only catches genuinely stale subscriptions (query
 * naturally excludes already-renewed ones), and that it delegates the actual period-advance +
 * credit-sync work to {@link SubscriptionService#applyRenewalSafetyNet} as a single atomic call
 * ([SEC: Kabir red-team Phase 4a MEDIUM-2] fix — see that method's javadoc). The period-advance
 * math and the Pro/Free allotment-sync branching are covered by {@code SubscriptionServiceTest}
 * now that they live inside the transactional method, not this job.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionRenewalResetJobTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private AuditLogService auditLog;

    private SubscriptionRenewalResetJob job;

    @BeforeEach
    void setUp() {
        job = new SubscriptionRenewalResetJob(subscriptionRepository, subscriptionService, auditLog);
    }

    @Test
    @DisplayName("wiring: a subscription whose period ended (webhook missed) triggers ONE atomic applyRenewalSafetyNet call and the audit log")
    void testStalePeriodEndTriggersResync() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400); // ended yesterday
        Subscription sub = activeSubscription(oldStart, oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        // The wiring assertion: the job hands the period-advance + credit-sync sequence to
        // SubscriptionService as ONE call (its @Transactional boundary), rather than performing
        // the steps itself as separately auto-committing calls.
        ArgumentCaptor<Instant> newStartCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> newEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(subscriptionService)
                .applyRenewalSafetyNet(eq(sub), newStartCaptor.capture(), newEndCaptor.capture());
        assertTrue(newStartCaptor.getValue().equals(oldEnd));
        assertTrue(newEndCaptor.getValue().isAfter(Instant.now()));

        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID),
                        eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    @DisplayName("a subscription whose webhook already renewed it (currentPeriodEnd in the future) is left untouched")
    void testAlreadyRenewedSubscriptionIsNotTouched() {
        Instant futureEnd = Instant.now().plusSeconds(2592000);
        Subscription sub = activeSubscription(Instant.now().minusSeconds(86400), futureEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never()).applyRenewalSafetyNet(any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("failure isolation: one subscription's unexpected failure does not abort the rest of the batch, and its audit log is skipped")
    void testOneFailureDoesNotAbortBatch() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        // Distinct explicit ids (not derived from periodEnd.hashCode() like the shared helper
        // below) so the two subscriptions are unambiguously distinguishable in both Mockito's
        // eq() matching and the audit-log idempotency-key assertions.
        Subscription badSub =
                Subscription.builder()
                        .id("01HWXYZSUBBAD00000000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_test_bad")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .build();
        Subscription goodSub =
                Subscription.builder()
                        .id("01HWXYZSUBGOOD0000000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_test_good")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(badSub, goodSub));
        doThrow(new RuntimeException("simulated DB failure"))
                .when(subscriptionService)
                .applyRenewalSafetyNet(eq(badSub), any(), any());

        job.runRenewalSafetyNet();

        // [SEC: Kabir red-team Phase 4a MEDIUM-2] badSub's simulated failure inside the atomic
        // applyRenewalSafetyNet call means its period-advance never took effect either (no
        // separate, already-committed subscriptionRepository.save() to worry about) — its audit
        // log is correctly never written, unlike the old three-separate-calls design where a
        // failure here could follow an already-committed period advance.
        verify(subscriptionService, times(1)).applyRenewalSafetyNet(eq(goodSub), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), contains(badSub.getId()), any());
        verify(auditLog)
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), contains(goodSub.getId()), any());
    }

    /**
     * BL-2 fix (BrandF.md §98) tests — a cancel-at-period-end row past its lapsed period must be
     * routed to {@link SubscriptionService#finalizeLapsedCancellation}, NEVER to {@link
     * SubscriptionService#applyRenewalSafetyNet} (which would silently undo the cancellation by
     * re-renewing the period and re-allotting Pro AI credits).
     */
    @Test
    @DisplayName("BL-2: a cancel-at-period-end subscription whose period has lapsed is finalized to CANCELLED, NOT renewed")
    void testCancelAtPeriodEndLapsedSubscriptionIsFinalizedNotRenewed() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400); // ended yesterday
        Subscription sub = activeSubscription(oldStart, oldEnd);
        sub.setCancelAtPeriodEnd(true);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        // The BL-2 assertion: this row must NEVER reach the renewal path.
        verify(subscriptionService, never()).applyRenewalSafetyNet(any(), any(), any());
        verify(subscriptionService).finalizeLapsedCancellation(sub);

        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID),
                        eq("SUBSCRIPTION_CANCELLATION_FINALIZED"),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    @DisplayName("BL-2: a cancel-at-period-end subscription still WITHIN its period is left untouched by both paths")
    void testCancelAtPeriodEndWithinPeriodIsNotTouched() {
        Instant futureEnd = Instant.now().plusSeconds(2592000);
        Subscription sub = activeSubscription(Instant.now().minusSeconds(86400), futureEnd);
        sub.setCancelAtPeriodEnd(true);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never()).applyRenewalSafetyNet(any(), any(), any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("BL-2: a NOT-cancelled ACTIVE subscription past its period still renews normally (no regression) alongside a cancelled one in the same batch")
    void testNonCancelledSubscriptionStillRenewsAlongsideCancelledOneInSameBatch() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription renewMe =
                Subscription.builder()
                        .id("01HWXYZSUBRENEW000000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_renew")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(false)
                        .build();
        Subscription cancelMe =
                Subscription.builder()
                        .id("01HWXYZSUBCANCEL0000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_cancel")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(true)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(renewMe, cancelMe));

        job.runRenewalSafetyNet();

        // renewMe: exactly the pre-existing renewal path, untouched by the BL-2 fix.
        verify(subscriptionService).applyRenewalSafetyNet(eq(renewMe), any(), any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(renewMe);
        // cancelMe: routed to the new terminal path instead.
        verify(subscriptionService).finalizeLapsedCancellation(cancelMe);
        verify(subscriptionService, never()).applyRenewalSafetyNet(eq(cancelMe), any(), any());

        verify(auditLog)
                .recordMoneyEvent(
                        any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), contains(renewMe.getId()), any());
        verify(auditLog)
                .recordMoneyEvent(
                        any(), eq("SUBSCRIPTION_CANCELLATION_FINALIZED"), any(), any(), any(), contains(cancelMe.getId()), any());
    }

    @Test
    @DisplayName("BL-2: a failure finalizing one cancelled subscription does not abort the rest of the batch")
    void testCancellationFinalizeFailureDoesNotAbortBatch() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription badSub =
                Subscription.builder()
                        .id("01HWXYZSUBCANCELBAD0001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_cancel_bad")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(true)
                        .build();
        Subscription goodSub =
                Subscription.builder()
                        .id("01HWXYZSUBCANCELGOOD001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_cancel_good")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(true)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(badSub, goodSub));
        doThrow(new RuntimeException("simulated optimistic-lock failure"))
                .when(subscriptionService)
                .finalizeLapsedCancellation(badSub);

        job.runRenewalSafetyNet();

        verify(subscriptionService, times(1)).finalizeLapsedCancellation(goodSub);
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_CANCELLATION_FINALIZED"), any(), any(), any(), contains(badSub.getId()), any());
        verify(auditLog)
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_CANCELLATION_FINALIZED"), any(), any(), any(), contains(goodSub.getId()), any());
    }

    private Subscription activeSubscription(Instant periodStart, Instant periodEnd) {
        return Subscription.builder()
                .id("01HWXYZSUB000000000000" + Math.abs(periodEnd.hashCode() % 10))
                .workspaceId(WORKSPACE_ID)
                .planId(PRO_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .razorpaySubscriptionId("sub_test")
                .currentPeriodStart(periodStart)
                .currentPeriodEnd(periodEnd)
                .build();
    }
}
