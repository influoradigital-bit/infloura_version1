package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.integration.razorpay.RazorpayClient.SubscriptionSnapshot;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.BrandContextService.BillingRecipient;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * MP-1 wiring tests for {@link SubscriptionRenewalResetJob} — the missed-webhook safety net (Task
 * 24, mandatory Kabir gate). Verifies the job routes a stale-ACTIVE row by what actually produced
 * it (F-0859 comp / F-0860 Razorpay-backed Pro / F-0861 Free, plus the pre-existing BL-2
 * cancel-at-period-end routing) rather than treating every stale row as "renew it", and that each
 * branch delegates to the correct {@link SubscriptionService}/{@link RazorpayClient} call rather
 * than re-implementing the transition locally.
 *
 * <p>Extended for Kabir's S2 review: {@code active} only renews when Razorpay reports a genuinely
 * NEWER period (HIGH — repeated free credit refills otherwise), the fetch-to-write race is closed
 * via a pre-fetch timestamp and a guarded re-check (MEDIUM), {@code authenticated} is no longer a
 * renewal trigger (LOW), and job-driven PAST_DUE/HALTED transitions now publish the same emails the
 * real webhook would (LOW). The period-advance math, credit-sync branching, and webhook-transition
 * semantics themselves are covered by {@code SubscriptionServiceTest} and {@code RazorpayClientTest}
 * now that they live in those classes, not this job.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionRenewalResetJobTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";
    private static final String FREE_PLAN_ID = "01HWXYZPLANFREE000000001";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private RazorpayClient razorpayClient;
    @Mock private BrandContextService brandContextService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditLogService auditLog;
    @Mock private PlatformTransactionManager transactionManager;

    private SubscriptionRenewalResetJob job;

    @BeforeEach
    void setUp() {
        job =
                new SubscriptionRenewalResetJob(
                        subscriptionRepository,
                        subscriptionService,
                        razorpayClient,
                        brandContextService,
                        eventPublisher,
                        auditLog,
                        transactionManager);
    }

    @Test
    @DisplayName("wiring: a Razorpay-confirmed-active subscription with a genuinely newer current_end triggers ONE applyRenewalSafetyNetIfUnchanged call, using Razorpay's own period, and the audit log")
    void testStalePeriodEndWithNewerRazorpayPeriodTriggersResync() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400); // ended yesterday
        Subscription sub = activeSubscription(oldStart, oldEnd);

        Instant confirmedStart = oldEnd;
        Instant confirmedEnd = oldEnd.plusSeconds(2592000);
        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("active", confirmedStart, confirmedEnd, PRO_PLAN_ID));
        when(subscriptionService.applyRenewalSafetyNetIfUnchanged(
                        sub.getId(), oldEnd, confirmedStart, confirmedEnd))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService)
                .applyRenewalSafetyNetIfUnchanged(sub.getId(), oldEnd, confirmedStart, confirmedEnd);
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());

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
    @DisplayName("a subscription whose webhook already renewed it (currentPeriodEnd in the future) is left untouched, never reaching Razorpay")
    void testAlreadyRenewedSubscriptionIsNotTouched() {
        Instant futureEnd = Instant.now().plusSeconds(2592000);
        Subscription sub = activeSubscription(Instant.now().minusSeconds(86400), futureEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(razorpayClient, never()).fetchSubscription(anyString());
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
        Instant confirmedEnd = oldEnd.plusSeconds(2592000);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(badSub, goodSub));
        when(razorpayClient.fetchSubscription("sub_test_bad"))
                .thenReturn(new SubscriptionSnapshot("active", oldEnd, confirmedEnd, PRO_PLAN_ID));
        when(razorpayClient.fetchSubscription("sub_test_good"))
                .thenReturn(new SubscriptionSnapshot("active", oldEnd, confirmedEnd, PRO_PLAN_ID));
        doThrow(new RuntimeException("simulated DB failure"))
                .when(subscriptionService)
                .applyRenewalSafetyNetIfUnchanged(eq(badSub.getId()), eq(oldEnd), any(), any());
        when(subscriptionService.applyRenewalSafetyNetIfUnchanged(
                        eq(goodSub.getId()), eq(oldEnd), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService, times(1))
                .applyRenewalSafetyNetIfUnchanged(eq(goodSub.getId()), eq(oldEnd), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), contains(badSub.getId()), any());
        verify(auditLog)
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), contains(goodSub.getId()), any());
    }

    /**
     * BL-2 fix (BrandF.md §98) tests — a cancel-at-period-end row past its lapsed period must be
     * routed to {@link SubscriptionService#finalizeLapsedCancellation}, NEVER to the renewal path
     * (which would silently undo the cancellation by re-renewing the period and re-allotting Pro AI
     * credits), and must never even reach Razorpay.
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

        // The BL-2 assertion: this row must NEVER reach the renewal path or Razorpay.
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(razorpayClient, never()).fetchSubscription(anyString());
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

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
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
        Instant confirmedEnd = oldEnd.plusSeconds(2592000);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(renewMe, cancelMe));
        when(razorpayClient.fetchSubscription("sub_renew"))
                .thenReturn(new SubscriptionSnapshot("active", oldEnd, confirmedEnd, PRO_PLAN_ID));
        when(subscriptionService.applyRenewalSafetyNetIfUnchanged(
                        eq(renewMe.getId()), eq(oldEnd), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        // renewMe: exactly the pre-existing renewal path, untouched by the BL-2 fix.
        verify(subscriptionService)
                .applyRenewalSafetyNetIfUnchanged(eq(renewMe.getId()), eq(oldEnd), any(), any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(renewMe);
        // cancelMe: routed to the new terminal path instead, never reaching Razorpay.
        verify(subscriptionService).finalizeLapsedCancellation(cancelMe);
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(eq(cancelMe.getId()), any(), any(), any());
        verify(razorpayClient, never()).fetchSubscription("sub_cancel");

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

    /**
     * F-0859 — an admin comp/override row past its {@code compExpiresAt} must be demoted to Free
     * (via {@link SubscriptionService#expireComp}), never renewed and never sent to Razorpay (comp
     * rows are never Razorpay-backed — {@link Subscription#linkRazorpaySubscription} clears
     * {@code comp} the moment a real subscription id is ever linked).
     */
    @Test
    @DisplayName("F-0859: a comp subscription past its compExpiresAt is expired to Free, not renewed")
    void testCompSubscriptionPastExpiryIsExpiredToFree() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub =
                Subscription.builder()
                        .id("01HWXYZSUBCOMP0000000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .currentPeriodStart(oldStart)
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(false)
                        .comp(true)
                        .compReason("beta tester")
                        .compGrantedBy("01HADMIN0000000000000001")
                        .compExpiresAt(oldEnd)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));

        job.runRenewalSafetyNet();

        verify(subscriptionService).expireComp(sub);
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(any());
        verify(subscriptionService, never()).advanceFreePeriod(any());
        verify(razorpayClient, never()).fetchSubscription(anyString());
        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID), eq("SUBSCRIPTION_COMP_EXPIRED"), any(), any(), any(), anyString(), any());
    }

    /**
     * F-0861 — a lapsed Free-tier row (not comp, no razorpaySubscriptionId) must have its period
     * anchor advanced only, via {@link SubscriptionService#advanceFreePeriod}, and must never be
     * swept into the Pro renewal/credit-reset path or sent to Razorpay.
     */
    @Test
    @DisplayName("F-0861: a lapsed Free-tier subscription has its period advanced only, never swept into the Pro renewal path")
    void testFreePlanLapsedSubscriptionAdvancesPeriodOnly() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub =
                Subscription.builder()
                        .id("01HWXYZSUBFREE0000000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(FREE_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .currentPeriodStart(oldStart)
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(false)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(subscriptionService.isFreePlan(FREE_PLAN_ID)).thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService).advanceFreePeriod(sub);
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never()).expireComp(any());
        verify(razorpayClient, never()).fetchSubscription(anyString());
        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID), eq("SUBSCRIPTION_FREE_PERIOD_ADVANCED"), any(), any(), any(), anyString(), any());
    }

    /**
     * A stale ACTIVE Pro row with no comp flag and no razorpaySubscriptionId is not a state any
     * known code path produces (see the job's own class javadoc for the call-site audit) — it must
     * never be extended for free, and must never reach Razorpay (there is no id to fetch with).
     */
    @Test
    @DisplayName("an unverifiable stale Pro row (no razorpaySubscriptionId, not comp) is never extended")
    void testProNoRazorpayIdNoCompIsNeverExtended() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub =
                Subscription.builder()
                        .id("01HWXYZSUBORPHAN0000001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .currentPeriodStart(oldStart)
                        .currentPeriodEnd(oldEnd)
                        .cancelAtPeriodEnd(false)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(subscriptionService.isFreePlan(PRO_PLAN_ID)).thenReturn(false);

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never()).advanceFreePeriod(any());
        verify(subscriptionService, never()).expireComp(any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(any());
        verify(razorpayClient, never()).fetchSubscription(anyString());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2 HIGH] Razorpay reports {@code active} but {@code current_end} is the SAME already-
     * lapsed period the job already knows about — routine for UPI autopay/eMandate subscriptions
     * between the due date and the actual debit. Renewing here (as this job used to) calls {@code
     * resetForNewCycle} against a cycle Razorpay has not actually charged, refilling Pro AI credits
     * for free on every run. Run TWICE to prove this isn't a one-off ordering fluke.
     */
    @Test
    @DisplayName("S2-HIGH: active with an unchanged period never renews, even across two runs")
    void testRazorpayActiveUnchangedPeriodNeverRenewsAcrossTwoRuns() {
        Instant oldStart = Instant.now().minusSeconds(2592000 + 86400);
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldStart, oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        // SAME period Razorpay already reported before -- current_end equals the local
        // currentPeriodEnd, not a genuinely newer one.
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("active", oldStart, oldEnd, PRO_PLAN_ID));

        job.runRenewalSafetyNet();
        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("S2-HIGH: active with no period returned at all (current_end missing) does not renew or estimate")
    void testRazorpayActiveWithNoPeriodReturnedDoesNotRenew() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("active", null, null, PRO_PLAN_ID));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2R3 item 4] Razorpay reports {@code active} with a {@code current_end} that IS newer
     * than the local {@code currentPeriodEnd} but has ALREADY ELAPSED by the time this job runs
     * (i.e. {@code oldEnd < current_end <= now}) — a distinct case from both "unchanged period"
     * (above) and the happy path (current_end after {@code now}). Without the {@code
     * current_end.isAfter(now)} half of the guard, this would incorrectly renew using an
     * already-stale period. Distinct from {@code testRazorpayActiveUnchangedPeriodNeverRenewsAcrossTwoRuns}:
     * that test's {@code current_end} equals {@code oldEnd}; this one's is strictly BETWEEN {@code
     * oldEnd} and {@code now}.
     */
    @Test
    @DisplayName("S2R3 item 4: active with a newer-than-oldEnd Razorpay period that has ALSO already ended (before now) does not renew")
    void testRazorpayActiveWithAlreadyElapsedNewerPeriodDoesNotRenew() {
        Instant now = Instant.now();
        Instant oldEnd = now.minusSeconds(86400); // ended yesterday
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        // Newer than oldEnd, but still in the past relative to "now" -- Razorpay's own period has
        // already elapsed too, just not as far back as the job's stale local one.
        Instant razorpayStart = oldEnd.minusSeconds(2592000).plusSeconds(1);
        Instant razorpayEnd = now.minusSeconds(3600);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("active", razorpayStart, razorpayEnd, PRO_PLAN_ID));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2 LOW / S2R3 item 5] The real webhook never maps {@code authenticated} to ACTIVE —
     * this job must not treat it as a renewal trigger either.
     */
    @Test
    @DisplayName("S2-LOW: a Razorpay 'authenticated' status is NOT a renewal trigger and changes nothing")
    void testRazorpayAuthenticatedStatusIsNotARenewalTrigger() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("authenticated", null, null, PRO_PLAN_ID));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2R3 item 5] The previous {@code authenticated} test supplied NO period at all, so
     * even if a mutant re-adds {@code authenticated} to the renewal case, {@link
     * #renewFromRazorpayOne}'s "no newer period" guard would still refuse to renew — the test
     * couldn't distinguish "correctly routed to default" from "incorrectly routed to renewal but
     * harmlessly declined". This variant supplies a genuinely CONFIRMED newer period, so a mutant
     * that treats {@code authenticated} as a renewal trigger would actually call {@code
     * applyRenewalSafetyNetIfUnchanged} here — only the correct routing (straight to the default,
     * no-op branch) keeps this assertion green.
     */
    @Test
    @DisplayName("S2R3 item 5: 'authenticated' with a genuinely confirmed newer period still does not renew")
    void testRazorpayAuthenticatedStatusWithConfirmedNewerPeriodDoesNotRenew() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        Instant confirmedStart = oldEnd;
        Instant confirmedEnd = oldEnd.plusSeconds(2592000);
        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("authenticated", confirmedStart, confirmedEnd, PRO_PLAN_ID));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2 MEDIUM] A real webhook lands between this job's Razorpay fetch and its own write —
     * the guarded re-check ({@code applyRenewalSafetyNetIfUnchanged} returning {@code false}) must
     * skip the renewal rather than clobber whatever that webhook applied.
     */
    @Test
    @DisplayName("S2-MEDIUM: a race where the row changed between fetch and write skips the renewal instead of clobbering it")
    void testRazorpayActiveRenewalSkippedWhenRowChangedDuringRace() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        Instant confirmedStart = oldEnd;
        Instant confirmedEnd = oldEnd.plusSeconds(2592000);
        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("active", confirmedStart, confirmedEnd, PRO_PLAN_ID));
        // Simulates a real webhook landing between the fetch and this job's write: the guarded
        // re-check finds the row no longer matches what the job read, and refuses to write.
        when(subscriptionService.applyRenewalSafetyNetIfUnchanged(
                        sub.getId(), oldEnd, confirmedStart, confirmedEnd))
                .thenReturn(false);

        job.runRenewalSafetyNet();

        verify(subscriptionService)
                .applyRenewalSafetyNetIfUnchanged(sub.getId(), oldEnd, confirmedStart, confirmedEnd);
        verify(auditLog, never())
                .recordMoneyEvent(any(), eq("SUBSCRIPTION_RENEWAL_SAFETY_NET"), any(), any(), any(), anyString(), any());
    }

    /**
     * [Kabir S2R3 item 3] The instant passed as {@code webhookEventAt} must be captured BEFORE the
     * Razorpay fetch call, not after — otherwise an intervening real webhook (created between the
     * fetch and this job's write) looks OLDER than this job's own write and gets silently
     * overwritten; and a real webhook delivered AFTER this job's write, but created before it
     * finished, would wrongly look stale and get dropped.
     *
     * <p>The original version of this test asserted {@code !eventAt.isAfter(fetchInvokedAt)},
     * which a mutant that captures {@code Instant.now()} AFTER the fetch (instead of before it)
     * can still satisfy whenever both instants land in the same clock tick — {@code isAfter} is
     * false for equal instants too. Made deterministic by forcing a REAL, measurable gap between
     * "fetch invoked" and "fetch returned" (a short sleep inside the mocked answer) and asserting
     * strict {@code isBefore} against the POST-fetch instant: the correct implementation always
     * captures {@code webhookEventAt} before the fetch even starts, so it is always strictly
     * before the (measurably later) return instant; the "after the fetch" mutant captures {@code
     * Instant.now()} only once {@code fetchSubscription(...)} has already returned, so it can
     * never be strictly before that same instant.
     */
    @Test
    @DisplayName("S2R3 item 3: webhookEventAt is captured BEFORE the Razorpay fetch call, proven with a real, measurable clock gap")
    void testWebhookEventAtCapturedBeforeRazorpayFetch() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        AtomicReference<Instant> fetchReturnedAt = new AtomicReference<>();
        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenAnswer(
                        invocation -> {
                            // Forces a real, measurable gap so "captured before the fetch" and
                            // "captured after the fetch" can never land in the same clock tick.
                            Thread.sleep(20);
                            SubscriptionSnapshot snapshot = new SubscriptionSnapshot("pending", null, null, PRO_PLAN_ID);
                            fetchReturnedAt.set(Instant.now());
                            return snapshot;
                        });
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        ArgumentCaptor<Instant> eventAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        any(), any(), any(), any(), isNull(), isNull(), eventAtCaptor.capture());

        assertTrue(
                eventAtCaptor.getValue().isBefore(fetchReturnedAt.get()),
                "webhookEventAt must be captured BEFORE the Razorpay fetch call (and therefore"
                        + " strictly before it returns), not one captured after");
    }

    /**
     * F-0860 — Razorpay reports {@code pending} (its own dunning retries not yet exhausted): the
     * job must apply the SAME transition {@code RazorpayWebhookController} would for a real
     * {@code subscription.pending} webhook, via {@code applySubscriptionWebhookUpdate}, and must
     * NOT touch the period or call the renewal path.
     */
    @Test
    @DisplayName("F-0860: a Razorpay 'pending' status applies the same PAST_DUE transition the webhook would, without touching the period")
    void testRazorpayPendingStatusAppliesPastDueTransition() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("pending", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq("sub_test"),
                        eq(WORKSPACE_ID),
                        eq(PRO_PLAN_ID),
                        eq(SubscriptionStatus.PAST_DUE),
                        isNull(),
                        isNull(),
                        any());
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID),
                        eq("SUBSCRIPTION_RENEWAL_JOB_RAZORPAY_STATUS_SYNCED"),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any());
    }

    /**
     * [Kabir S2R3 item 2 — BLOCKING] A stale/raced write must not be audited or emailed as if it
     * happened. {@code applySubscriptionWebhookUpdate} returns {@code false} here to simulate a
     * newer delivery (a real webhook) having already won the race for this row — the job must
     * treat that as a no-op: no audit log entry, and (once item 1's transaction wrap is in place)
     * no event published either.
     */
    @Test
    @DisplayName("S2R3 item 2: a skipped (raced) status sync writes no audit log and publishes no event")
    void testRazorpayStatusSyncSkippedByServiceWritesNoAuditAndNoEvent() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("pending", null, null, PRO_PLAN_ID));
        // A newer delivery (real webhook) already won the race for this row -- the service
        // reports the write was NOT applied.
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        job.runRenewalSafetyNet();

        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * F-0860 — Razorpay reports {@code halted} (its own dunning retries exhausted): same
     * transition-reuse contract as {@code pending}, targeting HALTED.
     */
    @Test
    @DisplayName("F-0860: a Razorpay 'halted' status applies the same HALTED transition the webhook would")
    void testRazorpayHaltedStatusAppliesHaltedTransition() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("halted", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq("sub_test"),
                        eq(WORKSPACE_ID),
                        eq(PRO_PLAN_ID),
                        eq(SubscriptionStatus.HALTED),
                        isNull(),
                        isNull(),
                        any());
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
    }

    /**
     * F-0860 — Razorpay's three terminal statuses ({@code cancelled}/{@code completed}/{@code
     * expired}) all map to local CANCELLED, exactly matching {@code
     * RazorpayWebhookController}'s own {@code subscription.cancelled}/{@code .completed} routing,
     * and — matching the real webhook — publish no email (only HALTED/PAST_DUE do).
     */
    @Test
    @DisplayName("F-0860: a Razorpay 'cancelled' status applies the same CANCELLED transition the webhook would")
    void testRazorpayCancelledStatusAppliesCancelledTransition() {
        assertRazorpayStatusAppliesCancelled("cancelled");
    }

    @Test
    @DisplayName("F-0860: a Razorpay 'completed' status applies the same CANCELLED transition the webhook would")
    void testRazorpayCompletedStatusAppliesCancelledTransition() {
        assertRazorpayStatusAppliesCancelled("completed");
    }

    @Test
    @DisplayName("F-0860: a Razorpay 'expired' status applies the same CANCELLED transition the webhook would")
    void testRazorpayExpiredStatusAppliesCancelledTransition() {
        assertRazorpayStatusAppliesCancelled("expired");
    }

    private void assertRazorpayStatusAppliesCancelled(String razorpayStatus) {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot(razorpayStatus, null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        job.runRenewalSafetyNet();

        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq("sub_test"),
                        eq(WORKSPACE_ID),
                        eq(PRO_PLAN_ID),
                        eq(SubscriptionStatus.CANCELLED),
                        isNull(),
                        isNull(),
                        any());
        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * [Kabir S2 LOW] Job-driven PAST_DUE/HALTED transitions previously sent no email at all —
     * {@code RazorpayWebhookController} publishes these on the real webhook path. These tests prove
     * the job now does too, via the shared {@code SubscriptionBillingEmailPublisher}.
     */
    @Test
    @DisplayName("S2-LOW: a pending sync publishes the payment-failed event, same as the real webhook")
    void testPendingSyncPublishesPaymentFailedEvent() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("pending", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("01HUSER0000000000000001", "brand@example.com"));

        job.runRenewalSafetyNet();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof SubscriptionPaymentFailedEvent);
        SubscriptionPaymentFailedEvent event = (SubscriptionPaymentFailedEvent) eventCaptor.getValue();
        assertEquals(WORKSPACE_ID, event.workspaceId());
        assertEquals("sub_test", event.entityId());
        assertEquals("brand@example.com", event.recipientEmail());
    }

    @Test
    @DisplayName("S2-LOW: a halted sync publishes the halted event, same as the real webhook")
    void testHaltedSyncPublishesHaltedEvent() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("halted", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("01HUSER0000000000000001", "brand@example.com"));

        job.runRenewalSafetyNet();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof SubscriptionHaltedEvent);
        SubscriptionHaltedEvent event = (SubscriptionHaltedEvent) eventCaptor.getValue();
        assertEquals(WORKSPACE_ID, event.workspaceId());
        assertEquals("sub_test", event.entityId());
        assertEquals("brand@example.com", event.recipientEmail());
    }

    @Test
    @DisplayName("S2-LOW: no billing recipient resolvable -> no email published, but the status transition still applies")
    void testHaltedSyncWithNoResolvableRecipientPublishesNoEmail() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("halted", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        // brandContextService.resolveBillingRecipient(...) left unstubbed -> returns null.

        job.runRenewalSafetyNet();

        verify(eventPublisher, never()).publishEvent(any());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq("sub_test"),
                        eq(WORKSPACE_ID),
                        eq(PRO_PLAN_ID),
                        eq(SubscriptionStatus.HALTED),
                        isNull(),
                        isNull(),
                        any());
    }

    /**
     * F-0860 — a fetch failure must change nothing (no renewal, no status transition) and must not
     * write an audit event or publish any email; the row stays stale ACTIVE and is retried on the
     * job's next run.
     */
    @Test
    @DisplayName("F-0860: a Razorpay fetch failure changes nothing and is retried on the next run")
    void testRazorpayFetchFailureChangesNothing() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenThrow(new RuntimeException("simulated Razorpay API failure"));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never()).applyRenewalSafetyNet(any(), any(), any());
        verify(subscriptionService, never()).expireComp(any());
        verify(subscriptionService, never()).advanceFreePeriod(any());
        verify(subscriptionService, never()).finalizeLapsedCancellation(any());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
        assertEquals(oldEnd, sub.getCurrentPeriodEnd());
    }

    /**
     * F-0860 — an unrecognized/unmapped Razorpay status must change nothing rather than guess.
     */
    @Test
    @DisplayName("F-0860: an unrecognized Razorpay status changes nothing and is retried on the next run")
    void testRazorpayUnknownStatusChangesNothing() {
        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub = activeSubscription(oldEnd.minusSeconds(2592000), oldEnd);

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_test"))
                .thenReturn(new SubscriptionSnapshot("paused", null, null, PRO_PLAN_ID));

        job.runRenewalSafetyNet();

        verify(subscriptionService, never())
                .applyRenewalSafetyNetIfUnchanged(any(), any(), any(), any());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
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
