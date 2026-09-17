package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [Kabir S2R3 item 1 — BLOCKING] Proves {@link SubscriptionRenewalResetJob}'s billing emails are
 * actually DELIVERED, with a REAL {@link ApplicationEventPublisher}, a REAL {@link
 * PlatformTransactionManager} (a minimal resourceless one — no DB needed, since what's under test
 * is transaction SYNCHRONIZATION, not persistence), and a listener shaped exactly like the real
 * {@code NotificationListener} ones: {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * with NO {@code fallbackExecution}.
 *
 * <p>Mocking {@link ApplicationEventPublisher} (as {@link SubscriptionRenewalResetJobTest} does
 * throughout) cannot catch this bug class at all — a mock records the {@code publishEvent} call
 * happened and stops there; it never exercises Spring's real transactional-event machinery, which
 * is exactly where the delivery was silently being dropped. Kabir proved the drop with a real
 * Spring context (his probe: {@code KABIR_PROBE outside=0 inside=1}) before this fix; this class
 * is the test that makes that provable, and stays this way, in the mainline test suite.
 *
 * <p>{@link #jobHaltedSyncActuallyDeliversEmailEndToEnd()} is the load-bearing test: it runs the
 * REAL {@link SubscriptionRenewalResetJob#runRenewalSafetyNet()} (only {@link
 * SubscriptionRepository}/{@link SubscriptionService}/{@link RazorpayClient}/{@link
 * BrandContextService}/{@link AuditLogService} are mocked — the event-publishing side is 100%
 * real) and asserts the listener actually received the event. Removing the {@code
 * transactionTemplate.executeWithoutResult(...)} wrap from {@code syncStatusOne} makes this test
 * go red (falsified as part of the S2R3 report).
 */
class SubscriptionRenewalResetJobEmailDeliveryTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";

    private AnnotationConfigApplicationContext context;
    private ApplicationEventPublisher eventPublisher;
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private ReceivedEvents receivedEvents;

    @BeforeEach
    void setUp() {
        // Defensive: TransactionSynchronizationManager's state is thread-local, not tied to the
        // Spring context, so a leftover synchronization from an earlier test in this same JVM
        // thread (or a differently-ordered run) must never leak in and make
        // publishOutsideTransactionIsDropped see a "transaction" that was never actually opened
        // by this test.
        TransactionSynchronizationManager.clear();
        context = new AnnotationConfigApplicationContext(Config.class);
        eventPublisher = context;
        transactionManager = context.getBean(PlatformTransactionManager.class);
        transactionTemplate = new TransactionTemplate(transactionManager);
        receivedEvents = context.getBean(ReceivedEvents.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
        TransactionSynchronizationManager.clear();
    }

    /**
     * Establishes the baseline this whole test class exists to guard: the general Spring
     * mechanism {@code SubscriptionRenewalResetJob} depends on. If this test ever goes green
     * unexpectedly (i.e. the event IS delivered with no transaction), something more fundamental
     * changed and the job-level fix's reasoning needs re-checking.
     */
    @Test
    @DisplayName("baseline: publishing SubscriptionHaltedEvent with NO active transaction is silently dropped")
    void publishOutsideTransactionIsDropped() {
        eventPublisher.publishEvent(
                new SubscriptionHaltedEvent("01HUSER0000000000000001", WORKSPACE_ID, "sub_test", "brand@example.com"));

        assertEquals(0, receivedEvents.haltedCount.get());
    }

    @Test
    @DisplayName("baseline: publishing SubscriptionHaltedEvent inside a transactionTemplate IS delivered after commit")
    void publishInsideTransactionTemplateIsDelivered() {
        transactionTemplate.executeWithoutResult(
                status ->
                        eventPublisher.publishEvent(
                                new SubscriptionHaltedEvent(
                                        "01HUSER0000000000000001", WORKSPACE_ID, "sub_test", "brand@example.com")));

        assertEquals(1, receivedEvents.haltedCount.get());
    }

    /**
     * The actual regression test: runs the real job end to end (only its non-transactional
     * collaborators mocked) against a real event publisher + transaction manager + listener, and
     * asserts the halted email is genuinely delivered — not just that {@code publishEvent} was
     * called on a mock.
     */
    @Test
    @DisplayName("S2R3 item 1: a job-driven HALTED sync actually delivers SubscriptionHaltedEvent through a real AFTER_COMMIT listener")
    void jobHaltedSyncActuallyDeliversEmailEndToEnd() {
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        RazorpayClient razorpayClient = mock(RazorpayClient.class);
        BrandContextService brandContextService = mock(BrandContextService.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub =
                Subscription.builder()
                        .id("01HWXYZSUBEMAILTEST001")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_email_test")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_email_test"))
                .thenReturn(new RazorpayClient.SubscriptionSnapshot("halted", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BrandContextService.BillingRecipient("01HUSER0000000000000001", "brand@example.com"));

        SubscriptionRenewalResetJob job =
                new SubscriptionRenewalResetJob(
                        subscriptionRepository,
                        subscriptionService,
                        razorpayClient,
                        brandContextService,
                        eventPublisher,
                        auditLog,
                        transactionManager);

        job.runRenewalSafetyNet();

        assertEquals(
                1,
                receivedEvents.haltedCount.get(),
                "SubscriptionHaltedEvent must actually be delivered to a real AFTER_COMMIT"
                        + " listener, not merely recorded as a call on a mocked publisher");
    }

    @Test
    @DisplayName("S2R3 item 1: a job-driven PAST_DUE sync actually delivers SubscriptionPaymentFailedEvent through a real AFTER_COMMIT listener")
    void jobPendingSyncActuallyDeliversEmailEndToEnd() {
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        RazorpayClient razorpayClient = mock(RazorpayClient.class);
        BrandContextService brandContextService = mock(BrandContextService.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        Instant oldEnd = Instant.now().minusSeconds(86400);
        Subscription sub =
                Subscription.builder()
                        .id("01HWXYZSUBEMAILTEST002")
                        .workspaceId(WORKSPACE_ID)
                        .planId(PRO_PLAN_ID)
                        .status(SubscriptionStatus.ACTIVE)
                        .razorpaySubscriptionId("sub_email_test_2")
                        .currentPeriodStart(oldEnd.minusSeconds(2592000))
                        .currentPeriodEnd(oldEnd)
                        .build();

        when(subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE)).thenReturn(List.of(sub));
        when(razorpayClient.fetchSubscription("sub_email_test_2"))
                .thenReturn(new RazorpayClient.SubscriptionSnapshot("pending", null, null, PRO_PLAN_ID));
        when(subscriptionService.applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BrandContextService.BillingRecipient("01HUSER0000000000000001", "brand@example.com"));

        SubscriptionRenewalResetJob job =
                new SubscriptionRenewalResetJob(
                        subscriptionRepository,
                        subscriptionService,
                        razorpayClient,
                        brandContextService,
                        eventPublisher,
                        auditLog,
                        transactionManager);

        job.runRenewalSafetyNet();

        assertEquals(1, receivedEvents.paymentFailedCount.get());
    }

    /** Plain holder bean the probe listener increments into — no mocking involved. */
    static final class ReceivedEvents {
        final AtomicInteger haltedCount = new AtomicInteger();
        final AtomicInteger paymentFailedCount = new AtomicInteger();
    }

    /**
     * Shaped exactly like {@code NotificationListener.on(SubscriptionHaltedEvent)}/{@code
     * on(SubscriptionPaymentFailedEvent)}: {@code @TransactionalEventListener(phase =
     * AFTER_COMMIT)}, no {@code fallbackExecution}. Deliberately NOT {@code @Async} (unlike the
     * real listener) so the assertion right after {@code job.runRenewalSafetyNet()} doesn't race
     * the listener's own thread — {@code @Async} is an orthogonal concern to the transactional-
     * delivery bug this test guards.
     */
    static final class ProbeListener {
        private final ReceivedEvents receivedEvents;

        ProbeListener(ReceivedEvents receivedEvents) {
            this.receivedEvents = receivedEvents;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onHalted(SubscriptionHaltedEvent event) {
            receivedEvents.haltedCount.incrementAndGet();
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onPaymentFailed(SubscriptionPaymentFailedEvent event) {
            receivedEvents.paymentFailedCount.incrementAndGet();
        }
    }

    /**
     * Minimal resourceless {@link PlatformTransactionManager} — there is no real datasource in
     * this test, and none is needed: {@code @TransactionalEventListener}'s delivery mechanism
     * hooks into {@code TransactionSynchronizationManager}'s synchronization registry, which
     * {@link AbstractPlatformTransactionManager}'s {@code getTransaction()}/{@code commit()}
     * machinery drives regardless of what (if anything) the concrete {@code doBegin}/{@code
     * doCommit}/{@code doRollback} hooks actually do.
     */
    static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no real resource to begin
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no real resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no real resource to roll back
        }
    }

    @Configuration
    static class Config {
        /**
         * WITHOUT this bean, {@code @TransactionalEventListener} methods are silently treated as
         * plain, unconditional {@code @EventListener}s (fired immediately, transaction or not) —
         * {@code EventListenerMethodProcessor} dispatches based on whichever {@link
         * org.springframework.context.event.EventListenerFactory} beans are registered, and the
         * ONE that actually understands {@code @TransactionalEventListener}'s phase semantics
         * ({@link TransactionalEventListenerFactory}) is auto-registered by Spring Boot's own
         * autoconfiguration in a real application, NOT by a bare {@code
         * AnnotationConfigApplicationContext} — the exact gap that let {@code
         * publishOutsideTransactionIsDropped} fail against a broken "always fires" listener the
         * first time this test was written. {@code static} per Spring's own convention for
         * infrastructure beans that {@code EventListenerMethodProcessor} needs available very
         * early in context refresh.
         */
        @Bean
        static TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        ReceivedEvents receivedEvents() {
            return new ReceivedEvents();
        }

        @Bean
        ProbeListener probeListener(ReceivedEvents receivedEvents) {
            return new ProbeListener(receivedEvents);
        }
    }
}
