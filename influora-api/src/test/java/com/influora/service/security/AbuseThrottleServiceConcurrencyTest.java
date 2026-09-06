package com.influora.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.common.Ulids;
import com.influora.domain.entity.AbuseThrottleCounter;
import com.influora.repository.AbuseThrottleCounterRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REAL Hibernate + H2 proof for T-FESTIVALBOX-0905 phase 9 part (c) — the exact TOCTOU
 * {@code FestivalEnquiryService#enforceThrottle} used to have (two {@code SELECT COUNT}s then
 * {@code save()}, all inside one {@code @Transactional} method at REPEATABLE READ) is fixed by
 * {@link AbuseThrottleService#tryConsume}'s atomic {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * upsert. This proves the cap holds under REAL concurrent, independently-committed transactions —
 * not a mocked "the repository was called N times" assertion, and not a single-threaded happy path.
 *
 * <p>Same {@code @DataJpaTest} + H2-in-MySQL-compatibility-mode approach as {@code
 * FestivalCouponCopyRepositoryConcurrencyTest} (T-FESTIVALBOX-0905 phase 6) — see that class's
 * javadoc for why a Mockito test cannot show this and why {@code MODE=MySQL} matters (H2 supports
 * {@code ON DUPLICATE KEY UPDATE} directly in that mode, exercising the exact statement shape MySQL
 * runs in production).
 *
 * <p><b>FALSIFICATION (required by task brief) — performed and reverted, not left in this file:</b>
 * {@link AbuseThrottleCounterRepository#increment}'s {@code @Query} was temporarily replaced with a
 * find-then-increment-then-save (a plain {@code SELECT}, mutate a detached copy via reflection since
 * the entity has no setters, then {@code save()}), and {@code manyConcurrentAttempts_capHoldsExactly}
 * below was re-run against it. It failed — {@code totalAllowed} landed ABOVE {@code MAX_PER_WINDOW}
 * because concurrent threads read the same stale count and each independently decided "I'm under the
 * cap", a classic lost-update/TOCTOU (exactly the bug this phase fixes). The atomic upsert was then
 * restored and this test passed again. This class always contains the real, atomic implementation —
 * see the task's verification report for the actual failing-run output.
 */
@DataJpaTest
// [SEC red-team finding while writing this test] Replace.ANY (the value copied from the
// FestivalCouponCopyRepositoryConcurrencyTest this class was modelled on) tells Spring Boot to
// substitute ANY configured DataSource with its OWN auto-configured embedded one — which silently
// DISCARDS the MODE=MySQL (and every other) parameter on the @TestPropertySource URL below and
// reconnects to a default-mode H2 instead. Default-mode H2 does NOT understand
// "ON DUPLICATE KEY UPDATE" (plain syntax error), so under Replace.ANY every increment() call
// silently fails inside its background Thread (an uncaught exception there never fails the test
// directly) and this test would report a false "the cap holds" result for the wrong reason — or,
// as happened while writing it, an outright syntax error once a synchronous path surfaced it.
// Replace.NONE keeps the exact H2 URL/MODE configured below. (The sibling
// FestivalCouponCopyRepositoryConcurrencyTest this pattern was copied from has this SAME bug —
// out of scope to fix here, flagged separately.)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackageClasses = AbuseThrottleCounter.class)
@EnableJpaRepositories(
        basePackageClasses = AbuseThrottleCounterRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!AbuseThrottleCounterRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url="
                    + "jdbc:h2:mem:abuse_throttle_concurrency_test;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=15000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AbuseThrottleServiceConcurrencyTest {

    private static final int THREAD_COUNT = 20;
    private static final long MAX_PER_WINDOW = 6;

    @Autowired private AbuseThrottleCounterRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private AbuseThrottleService service() {
        return new AbuseThrottleService(repository);
    }

    /**
     * A fresh, per-TEST-METHOD key — never reused across test methods. This H2 database is shared
     * for the whole test class (Hibernate's {@code ddl-auto=create-drop} schema is created once per
     * Spring context, and {@code DB_CLOSE_DELAY=-1} keeps the in-memory instance alive across
     * methods), so two tests sharing one literal throttle key would silently pollute each other's
     * counter row — exactly the bug that first made {@code manyConcurrentAttempts_capHoldsExactly}
     * flake when it hard-coded one shared key: whichever test happened to run second inherited the
     * first one's already-incremented count for that (key, hour-bucket) row, and its "allowed" tally
     * came out lower than expected for a reason that had nothing to do with atomicity.
     */
    private static String freshThrottleKey() {
        return "festival-ip:concurrency-test-key-" + Ulids.newUlid();
    }

    @Test
    @Timeout(30)
    @DisplayName(
            THREAD_COUNT
                    + " concurrent attempts for the SAME key, cap="
                    + MAX_PER_WINDOW
                    + ": exactly "
                    + MAX_PER_WINDOW
                    + " are allowed and the rest are refused — never more than the cap, proving the"
                    + " upsert is atomic and not a lost-update-prone read-then-write")
    void manyConcurrentAttempts_capHoldsExactly() throws InterruptedException {
        String throttleKey = freshThrottleKey();
        List<Boolean> results = runConcurrentAttempts(throttleKey, THREAD_COUNT);

        long allowed = results.stream().filter(Boolean::booleanValue).count();
        long refused = results.size() - allowed;

        assertEquals(
                MAX_PER_WINDOW,
                allowed,
                "exactly "
                        + MAX_PER_WINDOW
                        + " of "
                        + THREAD_COUNT
                        + " concurrent attempts must be allowed — a lost update here (allowing MORE"
                        + " than the cap) means the throttle is not actually atomic, and allowing"
                        + " FEWER means attempts were dropped instead of refused");
        assertEquals(THREAD_COUNT - MAX_PER_WINDOW, refused);
        assertEquals(
                (long) THREAD_COUNT,
                readRequestCount(throttleKey),
                "every attempt must still increment the counter, allowed or not");
    }

    @Test
    @Timeout(30)
    @DisplayName("two concurrent attempts for the same key both increment — count is 2, not 1")
    void twoConcurrentAttempts_bothIncrement() throws InterruptedException {
        String throttleKey = freshThrottleKey();
        runConcurrentAttempts(throttleKey, 2);
        assertEquals(2L, readRequestCount(throttleKey));
    }

    /** Fires {@code attempts} concurrent, independently-committed calls to {@code tryConsume}. */
    private List<Boolean> runConcurrentAttempts(String throttleKey, int attempts)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[attempts];
        List<Boolean> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AbuseThrottleService service = service();

        for (int i = 0; i < attempts; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                ready.countDown();
                                awaitUninterruptibly(start, 10);
                                TransactionTemplate tt = new TransactionTemplate(transactionManager);
                                try {
                                    Boolean allowed =
                                            tt.execute(
                                                    status ->
                                                            service.tryConsume(
                                                                    throttleKey,
                                                                    Duration.ofHours(1),
                                                                    MAX_PER_WINDOW));
                                    results.add(allowed);
                                } catch (Throwable t) {
                                    // Recorded, not swallowed — a transient failure here (e.g. a
                                    // lock-wait timeout under 20-way contention on one row) must be
                                    // visible in the assertion below, not silently dropped from the
                                    // results list the way an uncaught Thread exception would be.
                                    failures.add(t);
                                }
                            },
                            "abuse-throttle-" + i);
        }

        for (Thread t : threads) {
            t.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "not all threads reached the start gate");
        // Release every thread at (as close to) the same instant as possible, to maximize the
        // chance a naive read-then-write implementation would actually interleave and lose updates.
        start.countDown();
        for (Thread t : threads) {
            t.join(20_000);
        }
        assertTrue(
                failures.isEmpty(),
                failures.size()
                        + " of "
                        + attempts
                        + " concurrent attempts threw instead of returning true/false: "
                        + failures.stream().map(Throwable::toString).toList());
        return results;
    }

    private long readRequestCount(String throttleKey) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        return tt.execute(
                status ->
                        repository
                                .findAll()
                                .stream()
                                .filter(c -> throttleKey.equals(c.getThrottleKey()))
                                .findFirst()
                                .map(AbuseThrottleCounter::getRequestCount)
                                .orElseThrow(
                                        () ->
                                                new AssertionError(
                                                        "expected exactly one counter row for key="
                                                                + throttleKey)));
    }

    private static void awaitUninterruptibly(CountDownLatch latch, long timeoutSeconds) {
        try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
