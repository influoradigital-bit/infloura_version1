package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.AdminEmailSendLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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
 * T-ADMINMAIL-0903 round 3, B1 (REVIEW-R2.md ship-blocker): real Hibernate + H2 proof that {@link
 * AdminEmailSendLockRepository#lockForUpdate} actually SERIALIZES two concurrent holders, not just
 * that the {@code @Lock(PESSIMISTIC_WRITE)} annotation is present on the method.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST. A mocked repository can be told whatever order the
 * test wants, which proves nothing about whether {@code SELECT ... FOR UPDATE} actually blocks a
 * second real transaction on a real database — exactly the class of bug (a query string that looks
 * right but was never exercised) REVIEW-R2.md called out {@code EmailWorkerTest}'s C2 case for.
 * Only two real transactions, on two real connections, racing against each other can show this.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} at the class level overrides {@code
 * @DataJpaTest}'s own default (single shared test transaction, rolled back at the end) — each
 * thread below needs to open and COMMIT its own real, independent transaction against the shared
 * H2 in-memory database ({@code DB_CLOSE_DELAY=-1} keeps that database alive across connections;
 * {@code LOCK_TIMEOUT=15000} gives H2's own row-lock wait a generous bound so a genuinely-blocked
 * second holder doesn't get an H2 lock-timeout exception mid-test).
 *
 * <p>Falsification (per REVIEW-R2.md's testing note): removing {@code
 * @Lock(LockModeType.PESSIMISTIC_WRITE)} from {@code lockForUpdate} turns this test red — the
 * second holder's {@code lockForUpdate} call returns immediately instead of blocking, so
 * {@code "second-locked"} appears in {@code events} before {@code releaseFirst.countDown()} is
 * ever called, failing the "must be BLOCKED" assertion. Verified directly.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = AdminEmailSendLock.class)
@EnableJpaRepositories(
        basePackageClasses = AdminEmailSendLockRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!AdminEmailSendLockRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url="
                    + "jdbc:h2:mem:admin_email_send_lock_test;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=15000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminEmailSendLockRepositoryConcurrencyTest {

    @Autowired private AdminEmailSendLockRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedSingletonRow() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.executeWithoutResult(
                status -> {
                    if (repository.findById(AdminEmailSendLock.SINGLETON_ID).isEmpty()) {
                        repository.saveAndFlush(AdminEmailSendLock.singleton());
                    }
                });
    }

    @Test
    @Timeout(20)
    @DisplayName(
            "lockForUpdate: a second concurrent holder only acquires the lock AFTER the first"
                    + " holder's transaction commits")
    void secondHolderWaitsForFirstToCommit() throws InterruptedException {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Thread first =
                new Thread(
                        () -> {
                            TransactionTemplate tt = new TransactionTemplate(transactionManager);
                            tt.executeWithoutResult(
                                    status -> {
                                        repository.lockForUpdate(AdminEmailSendLock.SINGLETON_ID);
                                        events.add("first-locked");
                                        firstHoldsLock.countDown();
                                        // Hold the lock (and the transaction) open until the test
                                        // explicitly says to let go.
                                        awaitUninterruptibly(releaseFirst, 15);
                                    });
                            // The transaction — and with it, the row lock — is only actually
                            // released here, on return from executeWithoutResult (commit).
                            events.add("first-committed");
                        },
                        "first-holder");

        Thread second =
                new Thread(
                        () -> {
                            awaitUninterruptibly(firstHoldsLock, 10);
                            events.add("second-attempting");
                            TransactionTemplate tt = new TransactionTemplate(transactionManager);
                            tt.executeWithoutResult(
                                    status -> {
                                        repository.lockForUpdate(AdminEmailSendLock.SINGLETON_ID);
                                        events.add("second-locked");
                                    });
                        },
                        "second-holder");

        first.start();
        assertTrue(firstHoldsLock.await(10, TimeUnit.SECONDS), "first holder never acquired the lock");
        second.start();

        // Give the second holder time to actually attempt (and, if the lock is not real, wrongly
        // succeed) before the first holder is allowed to let go.
        Thread.sleep(500);
        assertTrue(
                events.contains("second-attempting") && !events.contains("second-locked"),
                "second holder must be BLOCKED waiting for the lock at this point, not already"
                        + " past it: "
                        + events);

        releaseFirst.countDown();
        first.join(15_000);
        second.join(15_000);

        assertTrue(events.contains("first-committed"), "first holder never committed: " + events);
        assertTrue(events.contains("second-locked"), "second holder never acquired the lock: " + events);
        assertTrue(
                events.indexOf("first-committed") < events.indexOf("second-locked"),
                "second holder acquired the lock BEFORE the first holder committed — the lock did"
                        + " not serialize the two transactions: "
                        + events);
    }

    private static void awaitUninterruptibly(CountDownLatch latch, long timeoutSeconds) {
        try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
