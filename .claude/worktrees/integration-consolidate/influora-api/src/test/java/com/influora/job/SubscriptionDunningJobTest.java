package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.BrandContextService.BillingRecipient;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import java.time.Instant;
import java.util.List;
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
 * MP-1 wiring tests for {@link SubscriptionDunningJob} — money-lifecycle code (Task 24, mandatory
 * Kabir gate). Verifies the grace-period boundary is respected in both directions (does NOT
 * transition before the grace period elapses, DOES transition after) and that the HALTED write
 * actually fires, not just that the grace-period math is correct in isolation.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionDunningJobTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private AuditLogService auditLog;
    @Mock private BrandContextService brandContext;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SubscriptionService subscriptionService;
    @Mock private PlatformTransactionManager transactionManager;

    private SubscriptionDunningJob job;

    @BeforeEach
    void setUp() {
        job =
                new SubscriptionDunningJob(
                        subscriptionRepository,
                        auditLog,
                        brandContext,
                        eventPublisher,
                        subscriptionService,
                        transactionManager);
    }

    @Test
    @DisplayName("wiring: PAST_DUE subscription past the grace period IS transitioned to HALTED, and the audit call actually fires")
    void testPastGracePeriodTransitionsToHalted() {
        Subscription sub = pastDueSubscription(SubscriptionDunningJob.DUNNING_GRACE_PERIOD.plusDays(1));
        when(subscriptionRepository.findByStatus(SubscriptionStatus.PAST_DUE)).thenReturn(List.of(sub));
        when(brandContext.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("user_1", "brand@example.com"));

        job.runDunning();

        ArgumentCaptor<Subscription> savedCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedCaptor.capture());
        assertEquals(SubscriptionStatus.HALTED, savedCaptor.getValue().getStatus());

        // The MP-1 assertion: the audit call actually fires from this transition.
        verify(auditLog)
                .recordMoneyEvent(
                        eq(WORKSPACE_ID), eq("SUBSCRIPTION_HALTED"), any(), any(), any(), anyString(), any());
        // [SEC: Kabir red-team Phase 4a MEDIUM-1 fix] explicit AI-credit reconciliation on the
        // HALTED transition — no longer relying on the implicit PAST_DUE/HALTED Free-fallback
        // coupling.
        verify(subscriptionService).reconcileAiCreditAllotment(WORKSPACE_ID);
        // Explicit type witness on any(): ApplicationEventPublisher is overloaded
        // (publishEvent(ApplicationEvent) / publishEvent(Object)) — SubscriptionHaltedEvent is a
        // plain record (not an ApplicationEvent), so production code resolves to the Object
        // overload at compile time; an untyped any() here would resolve verify() against the
        // OTHER (ApplicationEvent) overload and always report "not invoked" despite the real call
        // having fired, as confirmed by the recorded-interaction diagnostic Mockito prints.
        verify(eventPublisher).publishEvent(any(SubscriptionHaltedEvent.class));
    }

    @Test
    @DisplayName("wiring: PAST_DUE subscription still WITHIN the grace period is NOT transitioned")
    void testWithinGracePeriodDoesNotTransition() {
        Subscription sub = pastDueSubscription(SubscriptionDunningJob.DUNNING_GRACE_PERIOD.minusDays(1));
        when(subscriptionRepository.findByStatus(SubscriptionStatus.PAST_DUE)).thenReturn(List.of(sub));

        job.runDunning();

        verify(subscriptionRepository, never()).save(any());
        verify(auditLog, never())
                .recordMoneyEvent(any(), anyString(), any(), any(), any(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(subscriptionService, never()).reconcileAiCreditAllotment(anyString());
    }

    @Test
    @DisplayName("failure isolation: one subscription's unexpected failure does not abort the rest of the batch")
    void testOneFailureDoesNotAbortBatch() {
        Subscription badSub = pastDueSubscription(SubscriptionDunningJob.DUNNING_GRACE_PERIOD.plusDays(1));
        Subscription goodSub = pastDueSubscription(SubscriptionDunningJob.DUNNING_GRACE_PERIOD.plusDays(1));
        when(subscriptionRepository.findByStatus(SubscriptionStatus.PAST_DUE))
                .thenReturn(List.of(badSub, goodSub));
        when(subscriptionRepository.save(badSub)).thenThrow(new RuntimeException("simulated DB failure"));
        when(brandContext.resolveBillingRecipient(WORKSPACE_ID)).thenReturn(null);

        job.runDunning();

        verify(subscriptionRepository, times(1)).save(goodSub);
        // badSub throws on save() before haltOne ever reaches the reconciliation call, so only
        // goodSub's transition should trigger it.
        verify(subscriptionService, times(1)).reconcileAiCreditAllotment(WORKSPACE_ID);
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        when(subscriptionRepository.findByStatus(SubscriptionStatus.PAST_DUE))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(100);
                            return List.of();
                        });

        Thread t1 = new Thread(job::runDunning);
        t1.start();
        Thread.sleep(10);
        job.runDunning();
        t1.join();

        verify(subscriptionRepository, times(1)).findByStatus(SubscriptionStatus.PAST_DUE);
    }

    private Subscription pastDueSubscription(java.time.Duration age) {
        Instant periodEnd = Instant.now().minus(age);
        return Subscription.builder()
                .id("01HWXYZSUB000000000000" + Math.abs(age.hashCode() % 10))
                .workspaceId(WORKSPACE_ID)
                .planId("01HWXYZPLANPRO0000000001")
                .status(SubscriptionStatus.PAST_DUE)
                .razorpaySubscriptionId("sub_test")
                .currentPeriodStart(periodEnd.minusSeconds(2592000))
                .currentPeriodEnd(periodEnd)
                .build();
    }
}
