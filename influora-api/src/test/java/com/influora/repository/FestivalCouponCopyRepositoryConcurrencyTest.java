package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.common.Ulids;
import com.influora.domain.entity.FestivalCouponCopy;
import java.time.LocalDate;
import java.util.List;
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
 * REAL Hibernate + H2 proof for {@link FestivalCouponCopyRepository#recordCopy} (T-FESTIVALBOX-0905
 * phase 6): concurrent copies of the SAME (edition, sponsor_slug, day) key increment {@code
 * copy_count} correctly, with no lost update — proving the atomic {@code INSERT ... ON DUPLICATE
 * KEY UPDATE} upsert, not a mocked "the repository was called N times" assertion.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST — same reasoning as {@code
 * AdminEmailSendLockRepositoryConcurrencyTest}: a mocked repository proves nothing about whether
 * the actual SQL statement is race-safe. Only real concurrent transactions on a real database can
 * show that. {@code MODE=MySQL} is required (not just for syntax parsing): H2 in MySQL
 * compatibility mode supports {@code ON DUPLICATE KEY UPDATE} directly, so this exercises the exact
 * statement shape MySQL will run in production, not a hand-translated equivalent.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} at the class level overrides {@code
 * @DataJpaTest}'s default single-shared-rolled-back-transaction — each thread below needs its own
 * real, independently COMMITTED transaction against the shared H2 in-memory database ({@code
 * DB_CLOSE_DELAY=-1} keeps it alive across the many separate connections this test opens).
 *
 * <p><b>FALSIFICATION (required by task brief) — performed and reverted, not left in this file:</b>
 * {@code recordCopy}'s {@code @Query} was temporarily replaced with a find-then-increment-then-save
 * (a plain {@code SELECT}, then either {@code UPDATE} or {@code INSERT} from application code,
 * with a short sleep between the read and the write to force the race window open) and this exact
 * test was re-run. It failed — {@code finalCopyCount} landed well below {@code THREAD_COUNT} because
 * concurrent threads read the same stale {@code copy_count} and each wrote back {@code stale + 1},
 * clobbering each other's increments (a classic lost update). The native upsert was then restored
 * and this test passed again. See the task's verification report for the actual failing-run output
 * — this class always contains the real, atomic implementation.
 */
@DataJpaTest
/*
 * Replace.NONE, NOT Replace.ANY — and this single word is what decides whether this test can fail
 * at all.
 *
 * Replace.ANY swaps in Spring Boot's own default embedded datasource and DISCARDS the
 * spring.datasource.url declared in @TestPropertySource below. That silently threw away the
 * MODE=MySQL this test's whole design depends on (see the class javadoc: MySQL mode is what makes
 * H2 accept ON DUPLICATE KEY UPDATE, the exact statement production runs). With it, the test ran on
 * plain H2 against a schema with no unique constraint, so the upsert had nothing to collide on and
 * the concurrency assertion could never have caught a lost update.
 *
 * Replace.NONE honours the URL, so the test now runs the real statement shape against a schema that
 * actually has the constraint (declared on FestivalCouponCopy's @Table — see the note there).
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackageClasses = FestivalCouponCopy.class)
@EnableJpaRepositories(
        basePackageClasses = FestivalCouponCopyRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!FestivalCouponCopyRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url="
                    + "jdbc:h2:mem:festival_coupon_copy_concurrency_test;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=15000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FestivalCouponCopyRepositoryConcurrencyTest {

    private static final int THREAD_COUNT = 20;
    private static final String EDITION = "MUMBAI_FESTIVE_2026";
    private static final String SPONSOR_SLUG = "acme-corp";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);

    @Autowired private FestivalCouponCopyRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Timeout(30)
    @DisplayName(
            "two concurrent copies for the same (edition, sponsorSlug, day) both land — count is 2,"
                    + " not 1")
    void twoConcurrentCopies_bothIncrement() throws InterruptedException {
        // Each test uses its OWN sponsor slug. These tests share one H2 database
        // (DB_CLOSE_DELAY=-1) and run under @Transactional(NOT_SUPPORTED), so nothing rolls back
        // between them — with a shared slug the first test's rows leaked into the second's bucket
        // and it read 22 instead of 20. That is test pollution, not a lost update; distinct keys
        // remove the coupling entirely rather than depending on execution order or cleanup.
        runConcurrentCopies("acme-two", 2);
        assertEquals(
                2L, readCopyCount("acme-two"), "two concurrent copies must both be counted, not just one");
    }

    @Test
    @Timeout(30)
    @DisplayName(
            THREAD_COUNT
                    + " concurrent copies for the same key all land — proves the upsert is atomic,"
                    + " not a read-then-write that would lose updates under real concurrency")
    void manyConcurrentCopies_allIncrement() throws InterruptedException {
        runConcurrentCopies("acme-many", THREAD_COUNT);
        assertEquals(
                (long) THREAD_COUNT,
                readCopyCount("acme-many"),
                "every one of " + THREAD_COUNT + " concurrent copies must be counted — a lost update"
                        + " here means the upsert is not actually atomic");
    }

    /** Fires {@code copies} concurrent, independently-committed calls to {@code recordCopy}. */
    private void runConcurrentCopies(String sponsorSlug, int copies) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(copies);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[copies];

        for (int i = 0; i < copies; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                ready.countDown();
                                awaitUninterruptibly(start, 10);
                                TransactionTemplate tt = new TransactionTemplate(transactionManager);
                                tt.executeWithoutResult(
                                        status ->
                                                repository.recordCopy(
                                                        Ulids.newUlid(), EDITION, sponsorSlug, "FEST15", DAY));
                            },
                            "copy-" + i);
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
    }

    /**
     * Reads back the single bucket row for {@code sponsorSlug}.
     *
     * <p>Filters by sponsor rather than asserting the whole table holds exactly one row: the tests
     * share one database with no rollback (see the note in the first test), so the table legitimately
     * holds a bucket per test. What must be exactly one is the bucket for THIS key — that is the
     * unique constraint doing its job, and it is what this assertion pins.
     */
    private long readCopyCount(String sponsorSlug) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        return tt.execute(
                status -> {
                    List<FestivalCouponCopy> rows =
                            repository.findByEditionOrderByDayAscSponsorSlugAsc(EDITION).stream()
                                    .filter(r -> sponsorSlug.equals(r.getSponsorSlug()))
                                    .toList();
                    assertEquals(
                            1,
                            rows.size(),
                            "exactly one bucket row must exist for sponsor " + sponsorSlug);
                    return rows.get(0).getCopyCount();
                });
    }

    private static void awaitUninterruptibly(CountDownLatch latch, long timeoutSeconds) {
        try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
