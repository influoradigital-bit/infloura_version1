package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.common.ApiException;
import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.IdempotencyKeyRecordRepository;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.IdempotencyReservationOps;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * MP-1 wiring tests for Task 24's AI-credit reconciliation fix (Priya's Phase 3 sign-off
 * finding) — the highest-value item in this task. Per MP-1 (wiki/tech/security.md), these tests
 * assert the {@code AICreditService#applyPlanAllotment} call actually FIRES from the subscription
 * state transitions that should trigger it, not just that the allotment numbers are correct in
 * isolation (that calculation is already covered by {@code AICreditServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String RAZORPAY_SUB_ID = "sub_test123";
    private static final String RAZORPAY_PLAN_ID = "plan_test_pro";
    private static final String FREE_PLAN_ID = "01HWXYZPLANFREE000000001";
    private static final String PRO_PLAN_ID = "01HWXYZPLANPRO0000000001";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private PlanService planService;
    @Mock private RazorpayClient razorpayClient;
    @Mock private AICreditService aiCreditService;

    /**
     * [BL-3 fix] Backing repository for the REAL {@link IdempotencyService} instance used by the
     * checkout-concurrency tests below — a plain Mockito mock with {@code thenAnswer} stubs backed
     * by a genuinely thread-safe {@link java.util.concurrent.ConcurrentHashMap}, so {@code save}'s
     * "insert, throw on duplicate" and {@code reclaimFailedForRetry}'s "conditional replace" mirror
     * the real DB {@code UNIQUE(idempotency_key)} constraint's atomicity under REAL concurrent
     * threads (not just sequential Mockito stub sequencing) — required for {@link
     * #testConcurrentCheckoutCallsCreateExactlyOneRazorpaySubscription} to be a meaningful test of
     * the actual concurrency guarantee, not just an assertion about mock call counts.
     */
    @Mock private IdempotencyKeyRecordRepository idempotencyKeyRecordRepository;

    private final java.util.concurrent.ConcurrentHashMap<String, IdempotencyKeyRecord> idempotencyRows =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SubscriptionService subscriptionService;
    private IdempotencyService idempotencyService;

    private Plan freePlan;
    private Plan proPlan;

    @BeforeEach
    void setUp() {
        wireInMemoryIdempotencyRepository();
        // [SEC: Vikram, round-4 fix] IdempotencyService now delegates reservation/completion
        // writes to a separate IdempotencyReservationOps collaborator -- constructed directly here
        // (not a Spring proxy) is fine for this Mockito-based test, since what's under test in
        // this class is SubscriptionService's checkout-concurrency wiring, not
        // IdempotencyReservationOps's own transactional-boundary behavior (that is covered by
        // IdempotencyServicePersistenceTest against a real DB instead).
        idempotencyService =
                new IdempotencyService(
                        idempotencyKeyRecordRepository,
                        new IdempotencyReservationOps(idempotencyKeyRecordRepository));
        subscriptionService =
                new SubscriptionService(
                        subscriptionRepository,
                        planRepository,
                        planService,
                        razorpayClient,
                        aiCreditService,
                        idempotencyService);

        freePlan =
                Plan.builder()
                        .id(FREE_PLAN_ID)
                        .code(PlanCode.FREE)
                        .name("Free")
                        .priceInr(0)
                        .aiMonthlyAllotment(100)
                        .active(true)
                        .build();
        proPlan =
                Plan.builder()
                        .id(PRO_PLAN_ID)
                        .code(PlanCode.PRO)
                        .name("Pro")
                        .priceInr(499900)
                        .razorpayPlanId(RAZORPAY_PLAN_ID)
                        .aiMonthlyAllotment(400)
                        .active(true)
                        .build();
    }

    /**
     * [BL-3 fix] Wires {@link #idempotencyKeyRecordRepository}'s {@code save}/{@code
     * findByIdempotencyKey}/{@code reclaimFailedForRetry}/{@code deleteById} to a single shared
     * {@link #idempotencyRows} map with the same atomicity guarantees the real {@code
     * idempotency_keys} table's {@code UNIQUE} constraint (V15) and conditional {@code UPDATE ...
     * WHERE status = :from} provide — {@code ConcurrentHashMap#putIfAbsent} is what makes {@code
     * save} a genuine insert-first-wins race arbiter under real concurrent threads. All stubs are
     * {@code lenient()} since most tests in this class never touch checkout/idempotency at all.
     */
    private void wireInMemoryIdempotencyRepository() {
        idempotencyRows.clear();
        // [SEC: Vikram, round-4 fix] IdempotencyReservationOps#tryReserve now calls
        // repository.saveAndFlush(...) (not save()) -- see that class's javadoc for why a plain
        // save() is not enough once REQUIRES_NEW is a REAL transaction boundary (a separate bean,
        // not self-invocation): saveAndFlush forces the INSERT to execute synchronously, inside
        // the try/catch, instead of being deferred to a flush this method's own try/catch would
        // never see.
        lenient()
                .when(idempotencyKeyRecordRepository.saveAndFlush(any(IdempotencyKeyRecord.class)))
                .thenAnswer(
                        inv -> {
                            IdempotencyKeyRecord record = inv.getArgument(0);
                            IdempotencyKeyRecord existing =
                                    idempotencyRows.putIfAbsent(record.getIdempotencyKey(), record);
                            if (existing != null) {
                                throw new DataIntegrityViolationException(
                                        "duplicate idempotency key: " + record.getIdempotencyKey());
                            }
                            return record;
                        });
        lenient()
                .when(idempotencyKeyRecordRepository.findByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        idempotencyRows.get(inv.getArgument(0, String.class))));
        lenient()
                .when(
                        idempotencyKeyRecordRepository.reclaimFailedForRetry(
                                org.mockito.ArgumentMatchers.anyString(),
                                eq(IdempotencyKeyRecord.Status.FAILED),
                                eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0);
                            synchronized (idempotencyRows) {
                                IdempotencyKeyRecord existing = idempotencyRows.get(key);
                                if (existing != null
                                        && existing.getStatus() == IdempotencyKeyRecord.Status.FAILED) {
                                    IdempotencyKeyRecord reclaimed =
                                            IdempotencyKeyRecord.builder()
                                                    .idempotencyKey(key)
                                                    .workspaceId(existing.getWorkspaceId())
                                                    .scope(existing.getScope())
                                                    .build(); // defaults to IN_PROGRESS, mirrors the real UPDATE
                                    idempotencyRows.put(key, reclaimed);
                                    return 1;
                                }
                                return 0;
                            }
                        });
        lenient()
                .doAnswer(
                        inv -> {
                            idempotencyRows.remove(inv.getArgument(0, String.class));
                            return null;
                        })
                .when(idempotencyKeyRecordRepository)
                .deleteById(org.mockito.ArgumentMatchers.anyString());
        // [SEC: Priya round-2 fix] IdempotencyService#markCompletedTransactional/
        // markFailedTransactional now delegate to these two real @Modifying UPDATE methods
        // instead of find-then-mutate-in-memory (that old pattern happened to "work" against this
        // fake because find returned the SAME stored object reference, mutated in place — the exact
        // false confidence Priya's review flagged; it never modeled real JPA detachment). Wire both
        // here so this fake's status transitions stay faithful to the real UPDATE ... WHERE
        // idempotency_key = :key semantics: unconditional on an existing row, 0-affected-rows if the
        // key is unknown.
        lenient()
                .when(
                        idempotencyKeyRecordRepository.markCompleted(
                                org.mockito.ArgumentMatchers.anyString(),
                                eq(IdempotencyKeyRecord.Status.COMPLETED),
                                org.mockito.ArgumentMatchers.nullable(String.class),
                                any(Instant.class)))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            String digest = inv.getArgument(2, String.class);
                            synchronized (idempotencyRows) {
                                IdempotencyKeyRecord existing = idempotencyRows.get(key);
                                if (existing == null) {
                                    return 0;
                                }
                                existing.markCompleted(digest);
                                return 1;
                            }
                        });
        lenient()
                .when(
                        idempotencyKeyRecordRepository.markFailed(
                                org.mockito.ArgumentMatchers.anyString(),
                                eq(IdempotencyKeyRecord.Status.FAILED),
                                any(Instant.class)))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            synchronized (idempotencyRows) {
                                IdempotencyKeyRecord existing = idempotencyRows.get(key);
                                if (existing == null) {
                                    return 0;
                                }
                                existing.markFailed();
                                return 1;
                            }
                        });
    }

    // ------------------------------------------------------------------
    // BL-3 fix (BrandF.md §99): checkout double-submit / concurrency tests
    // ------------------------------------------------------------------

    /**
     * The headline BL-3 regression test: two genuinely concurrent threads both call {@link
     * SubscriptionService#initiateCheckout} for the SAME workspace at (as close as the JVM allows
     * to) the same instant. {@link RazorpayClient#createSubscription} is stubbed with a {@link
     * CountDownLatch} rendezvous that forces both threads to be mid-call simultaneously before
     * either can return — without the {@code IdempotencyService#runExclusive} fix, both threads
     * would sail past the (row-less, first-checkout) {@code ALREADY_SUBSCRIBED} guard and both
     * reach this stub, so this test would fail (2 invocations) on the pre-fix code and pass (1
     * invocation, 1 clean {@code CHECKOUT_IN_PROGRESS} rejection) on the fixed code.
     */
    @Test
    @DisplayName(
            "BL-3 fix: two concurrent initiateCheckout calls for the same workspace result in"
                    + " exactly ONE Razorpay createSubscription call; the loser gets a clean"
                    + " CHECKOUT_IN_PROGRESS, never a second real subscription")
    void testConcurrentCheckoutCallsCreateExactlyOneRazorpaySubscription() throws Exception {
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(planService.getProPlan()).thenReturn(proPlan);

        AtomicInteger razorpayCallCount = new AtomicInteger();
        // Only the lock WINNER ever reaches the Razorpay call (the loser is rejected by
        // runExclusive before this point) -- this latch's job is purely to hold the winner
        // inside the call, mid-flight, until the test has confirmed both threads actually
        // started and raced, proving the two initiateCheckout calls genuinely overlapped in
        // time rather than just running sequentially-but-fast.
        CountDownLatch winnerInsideRazorpayCall = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        when(razorpayClient.createSubscription(
                        org.mockito.ArgumentMatchers.eq(RAZORPAY_PLAN_ID),
                        anyInt(),
                        any()))
                .thenAnswer(
                        inv -> {
                            razorpayCallCount.incrementAndGet();
                            winnerInsideRazorpayCall.countDown();
                            releaseWinner.await(5, TimeUnit.SECONDS);
                            return new RazorpayClient.SubscriptionResult(
                                    RAZORPAY_SUB_ID, "created", "https://rzp.io/i/" + RAZORPAY_SUB_ID);
                        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothThreadsStarting = new CountDownLatch(2);
            Future<Object> call1 =
                    pool.submit(
                            () -> {
                                bothThreadsStarting.countDown();
                                bothThreadsStarting.await();
                                try {
                                    return (Object) subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO);
                                } catch (ApiException ex) {
                                    return ex;
                                }
                            });
            Future<Object> call2 =
                    pool.submit(
                            () -> {
                                bothThreadsStarting.countDown();
                                bothThreadsStarting.await();
                                try {
                                    return (Object) subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO);
                                } catch (ApiException ex) {
                                    return ex;
                                }
                            });

            // Wait for the winner to actually be inside the Razorpay call before releasing it.
            boolean winnerEnteredRazorpayCall = winnerInsideRazorpayCall.await(5, TimeUnit.SECONDS);
            assertTrue(winnerEnteredRazorpayCall, "expected the lock winner to reach the Razorpay call");

            // Critical ordering guard: the loser's rejection is a synchronous, in-memory check with
            // no blocking I/O, so it resolves essentially immediately once both threads have
            // started -- but thread scheduling is not instantaneous, so poll (briefly) for EITHER
            // future to already be a CHECKOUT_IN_PROGRESS rejection BEFORE releasing the winner.
            // Without this guard, releasing the winner immediately can let it finish and release
            // its OWN reservation before the (merely slow-to-be-scheduled) loser ever attempts its
            // own reserve -- the loser would then legitimately succeed on a fresh reservation
            // (correct runExclusive behavior for a NON-concurrent retry, but not what this test
            // means to exercise), producing a false failure that looks like "2 Razorpay calls" but
            // is actually just an artifact of releasing too early, not a real double-submit.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean loserRejectedBeforeRelease = false;
            while (System.nanoTime() < deadline) {
                if (isCheckoutInProgressRejection(call1) || isCheckoutInProgressRejection(call2)) {
                    loserRejectedBeforeRelease = true;
                    break;
                }
                Thread.sleep(5);
            }
            assertTrue(
                    loserRejectedBeforeRelease,
                    "expected the concurrent loser to already be rejected as CHECKOUT_IN_PROGRESS"
                            + " before the winner's Razorpay call is allowed to return -- proves the"
                            + " two calls genuinely overlapped, not just raced sequentially");

            releaseWinner.countDown();

            Object result1 = call1.get(5, TimeUnit.SECONDS);
            Object result2 = call2.get(5, TimeUnit.SECONDS);

            // Exactly one Razorpay subscription was ever created.
            assertEquals(1, razorpayCallCount.get(), "createSubscription must be called exactly once for a concurrent double-submit");

            // Exactly one call succeeded (got the checkout URL) and exactly one was rejected as
            // CHECKOUT_IN_PROGRESS -- never both succeeding, never both failing.
            java.util.List<Object> results = java.util.List.of(result1, result2);
            long successCount = results.stream().filter(r -> r instanceof String).count();
            long rejectedCount =
                    results.stream()
                            .filter(
                                    r ->
                                            r instanceof ApiException apiEx
                                                    && "CHECKOUT_IN_PROGRESS".equals(apiEx.getCode()))
                            .count();
            assertEquals(1, successCount, "exactly one concurrent call should succeed with a checkout URL");
            assertEquals(1, rejectedCount, "exactly one concurrent call should be rejected as CHECKOUT_IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
    }

    /** True if {@code future} is already done AND resolved to a CHECKOUT_IN_PROGRESS rejection. Never blocks -- only ever called on an already-completed-or-still-running future. */
    private static boolean isCheckoutInProgressRejection(Future<Object> future) {
        if (!future.isDone()) {
            return false;
        }
        try {
            Object result = future.get();
            return result instanceof ApiException apiEx && "CHECKOUT_IN_PROGRESS".equals(apiEx.getCode());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A genuinely failed Razorpay call (network error, gateway timeout, etc.) must NOT leave the
     * workspace permanently stuck -- the reservation is left FAILED (reclaimable), not deleted, but
     * a subsequent call for the SAME workspace must still be able to retry and succeed.
     */
    @Test
    @DisplayName(
            "BL-3 fix: a failed first checkout attempt (Razorpay call throws) is cleanly"
                    + " retryable by a subsequent call -- the workspace is never permanently stuck")
    void testFailedCheckoutAttemptCanBeRetried() {
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(planService.getProPlan()).thenReturn(proPlan);

        when(razorpayClient.createSubscription(
                        org.mockito.ArgumentMatchers.eq(RAZORPAY_PLAN_ID), anyInt(), any()))
                .thenThrow(new RuntimeException("simulated Razorpay gateway timeout"))
                .thenReturn(
                        new RazorpayClient.SubscriptionResult(
                                RAZORPAY_SUB_ID, "created", "https://rzp.io/i/" + RAZORPAY_SUB_ID));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO));

        // The retry -- must succeed, not be rejected as CHECKOUT_IN_PROGRESS/ALREADY stuck.
        String checkoutUrl = subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO);
        assertEquals("https://rzp.io/i/" + RAZORPAY_SUB_ID, checkoutUrl);

        verify(razorpayClient, org.mockito.Mockito.times(2))
                .createSubscription(org.mockito.ArgumentMatchers.eq(RAZORPAY_PLAN_ID), anyInt(), any());
    }

    /**
     * A workspace that completed one checkout episode successfully must be able to check out
     * again later (e.g. after a genuine cancel + re-subscribe) -- {@code runExclusive} releases
     * (deletes) the reservation on success rather than leaving it permanently COMPLETED, unlike a
     * naive static-key {@code executeOnce} would.
     */
    @Test
    @DisplayName(
            "BL-3 fix: a workspace that successfully checked out once is NOT permanently locked"
                    + " out of a later, non-concurrent checkout call")
    void testSuccessfulCheckoutDoesNotPermanentlyLockOutFutureCheckout() {
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(planService.getProPlan()).thenReturn(proPlan);
        when(razorpayClient.createSubscription(
                        org.mockito.ArgumentMatchers.eq(RAZORPAY_PLAN_ID), anyInt(), any()))
                .thenReturn(
                        new RazorpayClient.SubscriptionResult(
                                RAZORPAY_SUB_ID, "created", "https://rzp.io/i/" + RAZORPAY_SUB_ID));

        String first = subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO);
        assertEquals("https://rzp.io/i/" + RAZORPAY_SUB_ID, first);

        // Sequential, non-concurrent second call -- e.g. workspace abandoned the first checkout and
        // is trying again, or (per SubscriptionService's own webhook-upsert javadoc) previously
        // cancelled and is now re-subscribing. Must succeed, not throw CHECKOUT_IN_PROGRESS.
        String second = subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO);
        assertEquals("https://rzp.io/i/" + RAZORPAY_SUB_ID, second);

        verify(razorpayClient, org.mockito.Mockito.times(2))
                .createSubscription(org.mockito.ArgumentMatchers.eq(RAZORPAY_PLAN_ID), anyInt(), any());
    }

    /** The pre-existing ALREADY_SUBSCRIBED guard must still fire, unweakened, for a workspace genuinely already on Pro -- the BL-3 lock is never even reached. */
    @Test
    @DisplayName("BL-3 fix does not weaken ALREADY_SUBSCRIBED: a workspace already ACTIVE on Pro is rejected before the lock/Razorpay call")
    void testAlreadySubscribedGuardStillFiresAheadOfLock() {
        Subscription existingProSub = proSubscriptionRow();
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));
        when(planService.getProPlan()).thenReturn(proPlan);

        ApiException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () -> subscriptionService.initiateCheckout(WORKSPACE_ID, PlanCode.PRO));

        assertEquals("ALREADY_SUBSCRIBED", ex.getCode());
        verify(razorpayClient, org.mockito.Mockito.never()).createSubscription(any(), anyInt(), any());
    }

    @Test
    @DisplayName("wiring: brand-new subscription row activated straight to Pro reconciles AI-credit allotment to 400 immediately")
    void testNewSubscriptionActivatedReconcilesToProAllotment() {
        // findByWorkspaceId is called TWICE by production code for this path: once to resolve
        // "does a row already exist" (must be empty to hit the new-row branch), and again by
        // reconcileAiCreditAllotment's getActivePlanForWorkspace re-read AFTER the new row is
        // saved (must see the just-created row, exactly like a real repository would within the
        // same transaction). AtomicReference + thenAnswer models that instead of a static stub.
        AtomicReference<Subscription> savedRef = new AtomicReference<>();
        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(
                        inv -> {
                            Subscription s = inv.getArgument(0);
                            savedRef.set(s);
                            return s;
                        });
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID,
                WORKSPACE_ID,
                RAZORPAY_PLAN_ID,
                SubscriptionStatus.ACTIVE,
                Instant.now(),
                Instant.now().plusSeconds(2592000),
                Instant.now());

        // The wiring assertion MP-1 requires: the call actually fires, not just that 400 is the
        // "right" number somewhere in isolation.
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: Free-tier row upgraded to Pro mid-cycle reconciles allotment to 400 on THIS webhook, not deferred to the monthly cron")
    void testMidCycleUpgradeReconcilesImmediately() {
        Subscription existingFreeSub = freeSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingFreeSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID,
                WORKSPACE_ID,
                RAZORPAY_PLAN_ID,
                SubscriptionStatus.ACTIVE,
                Instant.now(),
                Instant.now().plusSeconds(2592000),
                Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: Pro subscription cancelled reconciles allotment back down to Free's 100 immediately, not deferred to the monthly cron")
    void testCancellationReconcilesDownToFreeImmediately() {
        Subscription existingProSub = proSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        // After setStatus(CANCELLED) mutates the row in place, getActivePlanForWorkspace's
        // re-read must see it as no-longer-ACTIVE and fall back to Free — this is the same
        // in-memory row Mockito returns both times, mirroring how the real repository would
        // return the just-flushed row within the same transaction.
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);
        // Mutation-testing fix (Priya's 2nd-round review): without this stub, findById(PRO_PLAN_ID)
        // is an unstubbed mock returning Optional.empty(), so getActivePlanForWorkspace's
        // `.filter(sub -> sub.getStatus() == ACTIVE)` could be deleted entirely and this test would
        // still pass (it falls through to Free either way). lenient() because WITH the real filter
        // present, findById is never reached for a CANCELLED row — a strict stub would throw
        // UnnecessaryStubbing.
        lenient().when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.CANCELLED, null, null, Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 100);
    }

    @Test
    @DisplayName("wiring: Pro subscription halted (dunning-exhausted) reconciles allotment back down to Free's 100 immediately")
    void testHaltedReconcilesDownToFreeImmediately() {
        Subscription existingProSub = proSubscriptionRow();

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.HALTED, null, null, Instant.now());

        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 100);
    }

    @Test
    @DisplayName("reconciliation failure is caught, logged, and does NOT roll back or fail the already-applied subscription status write")
    void testReconciliationFailureDoesNotBreakWebhookProcessing() {
        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planService.getFreePlan()).thenReturn(freePlan);
        doThrow(new RuntimeException("simulated credit-service outage"))
                .when(aiCreditService)
                .applyPlanAllotment(eq(WORKSPACE_ID), anyInt());

        assertDoesNotThrow(
                () ->
                        subscriptionService.applySubscriptionWebhookUpdate(
                                RAZORPAY_SUB_ID,
                                WORKSPACE_ID,
                                RAZORPAY_PLAN_ID,
                                SubscriptionStatus.ACTIVE,
                                Instant.now(),
                                Instant.now().plusSeconds(2592000),
                                Instant.now()));

        verify(subscriptionRepository).save(any(Subscription.class));
    }

    @Test
    @DisplayName("stale out-of-order webhook delivery is skipped entirely — no reconciliation call fires for a no-op")
    void testStaleWebhookSkipDoesNotReconcile() {
        Subscription existingProSub = proSubscriptionRow();
        Instant lastApplied = Instant.now();
        existingProSub.markWebhookApplied(lastApplied);

        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID))
                .thenReturn(Optional.of(existingProSub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));

        Instant staleEventAt = lastApplied.minusSeconds(3600);
        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID, WORKSPACE_ID, RAZORPAY_PLAN_ID, SubscriptionStatus.PAST_DUE, null, null, staleEventAt);

        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName(
            "wiring [SEC: Kabir red-team Phase 4a MEDIUM-2]: applyRenewalSafetyNet advances the"
                    + " period AND syncs Pro allotment AND resets credits in one call")
    void testApplyRenewalSafetyNetSyncsProAllotmentAndResetsCredits() {
        Subscription sub = proSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd);

        // The MP-1 wiring assertion: all three steps actually fire from ONE call, matching what
        // used to be three separately auto-committing calls made directly by the job.
        verify(subscriptionRepository).save(sub);
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
        verify(aiCreditService).resetForNewCycle(WORKSPACE_ID);
    }

    @Test
    @DisplayName("wiring: applyRenewalSafetyNet on a Free-tier workspace resets credits but does NOT call applyPlanAllotment")
    void testApplyRenewalSafetyNetFreeTierSkipsAllotmentSync() {
        Subscription sub = freeSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(FREE_PLAN_ID)).thenReturn(Optional.of(freePlan));

        subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd);

        verify(subscriptionRepository).save(sub);
        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
        verify(aiCreditService).resetForNewCycle(WORKSPACE_ID);
    }

    @Test
    @DisplayName(
            "wiring [SEC: Kabir red-team Phase 4a MEDIUM-2]: a credit-sync failure propagates"
                    + " (NOT swallowed) so @Transactional rolls back the period-advance too")
    void testApplyRenewalSafetyNetPropagatesCreditSyncFailure() {
        Subscription sub = proSubscriptionRow();
        Instant newStart = sub.getCurrentPeriodEnd();
        Instant newEnd = newStart.plusSeconds(2592000);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));
        doThrow(new RuntimeException("simulated credit-service outage"))
                .when(aiCreditService)
                .resetForNewCycle(WORKSPACE_ID);

        // Unlike reconcileAiCreditAllotment (deliberately swallows failures — a best-effort side
        // effect of an already-committed webhook write), applyRenewalSafetyNet must NOT swallow:
        // this method IS the atomic unit of work, so the exception must propagate for Spring's
        // @Transactional proxy to roll back the period-advance save() alongside it, and for the
        // job's per-subscription catch to correctly skip the audit-log call for this subscription.
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> subscriptionService.applyRenewalSafetyNet(sub, newStart, newEnd));
    }

    /**
     * BL-2 fix (BrandF.md §98) wiring tests for {@link SubscriptionService#finalizeLapsedCancellation}
     * — the terminal write {@code SubscriptionRenewalResetJob} now makes for a cancel-at-period-end
     * row whose period has lapsed, instead of silently re-renewing it forever.
     */
    @Test
    @DisplayName("wiring [BL-2]: finalizeLapsedCancellation flips status to CANCELLED via saveAndFlush and reconciles AI-credit allotment down to Free's 100 immediately")
    void testFinalizeLapsedCancellationSetsCancelledAndReconcilesToFree() {
        Subscription sub = proSubscriptionRow();
        sub.setCancelAtPeriodEnd(true);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planService.getFreePlan()).thenReturn(freePlan);

        subscriptionService.finalizeLapsedCancellation(sub);

        assertEquals(SubscriptionStatus.CANCELLED, sub.getStatus());
        verify(subscriptionRepository).saveAndFlush(sub);
        verify(subscriptionRepository, never()).save(sub);
        // getActivePlanForWorkspace only ever resolves the plan for an ACTIVE row — now CANCELLED,
        // it falls back to Free, so reconciliation must re-sync the allotment down to 100, not 400.
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 100);
    }

    @Test
    @DisplayName("wiring [BL-2]: getActivePlanForWorkspace returns Free (not Pro) for the SAME row immediately after finalizeLapsedCancellation — the entitlement actually lapses")
    void testGetActivePlanForWorkspaceReturnsFreeAfterFinalizeLapsedCancellation() {
        Subscription sub = proSubscriptionRow();
        sub.setCancelAtPeriodEnd(true);

        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planService.getFreePlan()).thenReturn(freePlan);
        // Mutation-testing fix (Priya's 2nd-round review): same hole as
        // testCancellationReconcilesDownToFreeImmediately — without this stub, findById(PRO_PLAN_ID)
        // is unstubbed (Optional.empty()), so getActivePlanForWorkspace's ACTIVE filter could be
        // deleted and this test would still see Free. lenient() since the real filter never reaches
        // findById for this now-CANCELLED row.
        lenient().when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));

        subscriptionService.finalizeLapsedCancellation(sub);

        Plan resolved = subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID);
        assertEquals(PlanCode.FREE, resolved.getCode());
    }

    /**
     * Regression test (Priya's 2nd-round review, BL-2 follow-up): re-subscribe latch bug.
     * {@code cancelAtPeriodEnd} was set {@code true} by {@link SubscriptionService#cancel} but
     * never cleared back to {@code false} anywhere when a cancelled subscription later
     * re-activates via the UPDATE branch of {@link
     * SubscriptionService#applySubscriptionWebhookUpdate} (only the new-row/builder branch, for
     * brand-new rows, cleared it). Full sequence:
     *
     * <ol>
     *   <li>Brand cancels Pro — {@code cancelAtPeriodEnd=true}, status stays ACTIVE.
     *   <li>Period lapses — {@code SubscriptionRenewalResetJob} finalizes to CANCELLED.
     *   <li>Brand re-subscribes — a {@code subscription.activated} webhook hits the UPDATE branch,
     *       status flips back to ACTIVE with a fresh period. WITHOUT this fix, {@code
     *       cancelAtPeriodEnd} stays {@code true}.
     *   <li>Simulates what {@code SubscriptionRenewalResetJob#doRun} would see for a LATER
     *       missed/delayed webhook: it reads {@code findByStatus(ACTIVE)} rows and partitions on
     *       {@link Subscription#isCancelAtPeriodEnd()} alone (no separate service call — see that
     *       job's class javadoc) to decide {@code finalizeLapsedCancellation} vs {@code
     *       applyRenewalSafetyNet}. Asserting the flag is {@code false} on this exact row/state is
     *       therefore asserting the job would route it to the renewal path, not the cancellation
     *       one — i.e. NOT wrongly revoking a currently-paying customer's Pro entitlement while
     *       Razorpay keeps charging them.
     * </ol>
     */
    @Test
    @DisplayName(
            "regression: cancel -> lapse finalized CANCELLED -> re-subscribe (ACTIVE) clears"
                    + " cancelAtPeriodEnd so a LATER missed webhook does not wrongly finalize a"
                    + " currently-paying re-subscribed customer back to CANCELLED")
    void testReSubscribeAfterLapsedCancellationClearsCancelAtPeriodEndFlag() {
        Subscription sub = proSubscriptionRow();

        // 1. Brand cancels Pro (mirrors SubscriptionService#cancel's own write).
        sub.setCancelAtPeriodEnd(true);

        // 2. Period lapses; SubscriptionRenewalResetJob routes this row to finalizeLapsedCancellation.
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(sub));
        when(planService.getFreePlan()).thenReturn(freePlan);
        subscriptionService.finalizeLapsedCancellation(sub);
        assertEquals(SubscriptionStatus.CANCELLED, sub.getStatus());
        // Confirms the bug's precondition: finalizeLapsedCancellation only ever touches status,
        // never the flag — cancelAtPeriodEnd is still true here, exactly as production leaves it.
        assertTrue(sub.isCancelAtPeriodEnd());

        // 3. Brand re-subscribes: subscription.activated webhook arrives for the SAME
        // razorpaySubscriptionId, taking the UPDATE branch (existing row found by
        // findByRazorpaySubscriptionId), not the new-row/builder branch.
        when(subscriptionRepository.findByRazorpaySubscriptionId(RAZORPAY_SUB_ID)).thenReturn(Optional.of(sub));
        when(planRepository.findByRazorpayPlanId(RAZORPAY_PLAN_ID)).thenReturn(Optional.of(proPlan));
        when(planRepository.findById(PRO_PLAN_ID)).thenReturn(Optional.of(proPlan));
        Instant newPeriodStart = Instant.now();
        Instant newPeriodEnd = newPeriodStart.plusSeconds(2592000);

        subscriptionService.applySubscriptionWebhookUpdate(
                RAZORPAY_SUB_ID,
                WORKSPACE_ID,
                RAZORPAY_PLAN_ID,
                SubscriptionStatus.ACTIVE,
                newPeriodStart,
                newPeriodEnd,
                Instant.now());

        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        // THE FIX under test: without it, this is still true and step 4 below would be exactly the
        // failure scenario Priya proved — a later missed webhook wrongly finalizes this row.
        assertFalse(
                sub.isCancelAtPeriodEnd(),
                "cancelAtPeriodEnd must be cleared on re-activation, or a later missed/delayed"
                        + " webhook would wrongly finalize this currently-paying re-subscribed row"
                        + " to CANCELLED while Razorpay keeps charging the customer");

        // 4. Simulates the exact state SubscriptionRenewalResetJob#doRun reads for a LATER
        // missed/delayed webhook (an ACTIVE row, partitioned solely on isCancelAtPeriodEnd()):
        // this row must land in the "toRenew" partition (applyRenewalSafetyNet), never "toCancel"
        // (finalizeLapsedCancellation) — reasserting the same flag confirms that routing outcome.
        assertFalse(
                sub.isCancelAtPeriodEnd(),
                "a currently-paying re-subscribed customer must route to the renewal safety net,"
                        + " never finalizeLapsedCancellation, on the job's very next stale-period"
                        + " partition");
    }

    /**
     * MP-1 wiring tests for Task 25 ({@code AdminBillingController}'s {@code grantAdminPlan} —
     * the shared primitive behind {@code POST /admin/billing/comp} and {@code
     * POST /admin/billing/override}). Same discipline as the webhook tests above: assert the
     * {@code AICreditService#applyPlanAllotment} call actually fires, and that comp metadata is
     * genuinely persisted, not just that the "right" numbers are computed somewhere in isolation.
     */
    @Test
    @DisplayName("wiring: comp grant to a brand-new workspace creates an ACTIVE, comp-flagged Pro subscription and reconciles AI-credit allotment to 400 immediately")
    void testGrantAdminPlanNewWorkspaceCreatesActiveCompSubscription() {
        String adminId = "01HWXYZADMIN000000000001";
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result =
                subscriptionService.grantAdminPlan(WORKSPACE_ID, proPlan, adminId, "Q3 partnership comp — 90 days", null);

        assertTrue(result.isComp());
        assertEquals("Q3 partnership comp — 90 days", result.getCompReason());
        assertEquals(adminId, result.getCompGrantedBy());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertEquals(PRO_PLAN_ID, result.getPlanId());
        assertNull(result.getRazorpaySubscriptionId());

        verify(subscriptionRepository).save(any(Subscription.class));
        // The MP-1 assertion: the SAME reconciliation call a real Razorpay webhook activation
        // fires, not a parallel/duplicated "give them Pro benefits" code path.
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("wiring: comp grant to a workspace with an existing Free row upgrades that SAME row in place and reconciles credits")
    void testGrantAdminPlanUpgradesExistingFreeRowInPlace() {
        Subscription existingFreeSub = freeSubscriptionRow();
        String adminId = "01HWXYZADMIN000000000001";
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingFreeSub));

        Subscription result =
                subscriptionService.grantAdminPlan(
                        WORKSPACE_ID, proPlan, adminId, "Support-team goodwill grant", Instant.now().plusSeconds(86400));

        assertEquals(existingFreeSub, result);
        assertTrue(result.isComp());
        assertEquals(PRO_PLAN_ID, result.getPlanId());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        verify(subscriptionRepository).save(existingFreeSub);
        verify(aiCreditService).applyPlanAllotment(WORKSPACE_ID, 400);
    }

    @Test
    @DisplayName("a workspace with a REAL Razorpay-backed subscription cannot be comp'd/overridden — ALREADY_PAID_SUBSCRIBER, no save, no reconciliation")
    void testGrantAdminPlanRejectsRealPaidSubscriber() {
        Subscription existingProSub = proSubscriptionRow(); // has razorpaySubscriptionId set
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.of(existingProSub));

        com.influora.common.ApiException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.influora.common.ApiException.class,
                        () ->
                                subscriptionService.grantAdminPlan(
                                        WORKSPACE_ID, freePlan, "admin1", "accidental downgrade attempt", null));

        org.junit.jupiter.api.Assertions.assertEquals("ALREADY_PAID_SUBSCRIBER", ex.getCode());
        verify(subscriptionRepository, never()).save(any());
        verify(aiCreditService, never()).applyPlanAllotment(any(), anyInt());
    }

    private Subscription freeSubscriptionRow() {
        return Subscription.builder()
                .id("01HWXYZSUB0000000000001")
                .workspaceId(WORKSPACE_ID)
                .planId(FREE_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(Instant.now().minusSeconds(86400))
                .currentPeriodEnd(Instant.now().plusSeconds(2592000))
                .build();
    }

    private Subscription proSubscriptionRow() {
        return Subscription.builder()
                .id("01HWXYZSUB0000000000002")
                .workspaceId(WORKSPACE_ID)
                .planId(PRO_PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .razorpaySubscriptionId(RAZORPAY_SUB_ID)
                .currentPeriodStart(Instant.now().minusSeconds(86400))
                .currentPeriodEnd(Instant.now().plusSeconds(2592000))
                .build();
    }
}
