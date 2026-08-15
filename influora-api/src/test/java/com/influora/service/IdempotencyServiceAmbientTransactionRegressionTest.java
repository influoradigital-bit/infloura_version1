package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [Priya, BL-3 round-5 review] Real-DB regression coverage for four ambient-transaction /
 * key-validation paths that {@code IdempotencyServicePersistenceTest} does NOT reach. Added during
 * the round-5 review after confirming each one is load-bearing: reverting {@code
 * IdempotencyService}'s reclaim back to {@code repository.reclaimFailedForRetry(...)} (the round-4
 * shape) makes the first two below fail with {@code PessimisticLockingFailureException} / a
 * lock-timeout, and they pass only with the reclaim routed through {@link
 * IdempotencyReservationOps}'s {@code REQUIRES_NEW} boundary.
 *
 * <ul>
 *   <li>{@code runExclusive} retrying a FAILED key from inside a caller's ambient transaction —
 *       the shipped suite covers this shape only for {@code executeOnce}, and only for the
 *       markCompleted path, never for {@code runExclusive}'s reclaim → {@code release} (DELETE).
 *   <li>{@code executeOnce} retrying a FAILED key whose retried action ALSO throws — the {@code
 *       markFailed} path. Under the round-4 shape the lock timeout MASKED the caller's real
 *       business exception, so the caller saw a confusing {@code PessimisticLockingFailureException}
 *       instead of its own failure; this asserts the original exception propagates.
 *   <li>The round-3 Finding-3 composite-key length guard ({@code MAX_RESERVATION_KEY_LENGTH}),
 *       which had no test at all in either existing suite.
 *   <li>A composite key at EXACTLY the 128-char column limit — the boundary the guard allows
 *       through — must round-trip intact rather than being silently truncated.
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = IdempotencyKeyRecord.class)
@EnableJpaRepositories(
        basePackageClasses = IdempotencyKeyRecordRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!IdempotencyKeyRecordRepository$).*"))
@Import(IdempotencyReservationOps.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:idempotency_ambient_tx_regression;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServiceAmbientTransactionRegressionTest {

    private static final String WORKSPACE_ID = "01HWORKSPACEIDEMPO0001";
    private static final String SCOPE = "probe.scope";

    @Autowired private IdempotencyKeyRecordRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private IdempotencyReservationOps reservationOps;
    @Autowired private PlatformTransactionManager transactionManager;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, reservationOps);
        repository.deleteAll();
    }

    /** GAP A: runExclusive retrying a FAILED key from inside an ambient tx -> reclaim then release. */
    @Test
    void runExclusiveFailedKeyRetryUnderAmbientTransactionDoesNotDeadlock() {
        String key = "probe-run-exclusive-failed-retry";
        String reservationKey = SCOPE + ":" + WORKSPACE_ID + ":" + key;

        repository.save(
                IdempotencyKeyRecord.builder()
                        .idempotencyKey(reservationKey)
                        .workspaceId(WORKSPACE_ID)
                        .scope(SCOPE)
                        .build());
        repository.markFailed(reservationKey, IdempotencyKeyRecord.Status.FAILED, Instant.now());
        entityManager.clear();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        AtomicInteger runs = new AtomicInteger();

        String result =
                txTemplate.execute(
                        status ->
                                service.runExclusive(
                                        key,
                                        WORKSPACE_ID,
                                        SCOPE,
                                        () -> {
                                            runs.incrementAndGet();
                                            return "reclaimed-and-released";
                                        }));

        assertEquals("reclaimed-and-released", result);
        assertEquals(1, runs.get());
        entityManager.clear();
        assertTrue(
                repository.findByIdempotencyKey(reservationKey).isEmpty(),
                "runExclusive success under ambient tx must DELETE the row (release), leaving it reusable");
    }

    /** GAP B: executeOnce FAILED-retry under ambient tx where the retried action ALSO throws -> markFailed path. */
    @Test
    void executeOnceFailedKeyRetryThatFailsAgainUnderAmbientTransactionDoesNotDeadlock() {
        String key = "probe-retry-fails-again";
        String reservationKey = SCOPE + ":" + WORKSPACE_ID + ":" + key;

        repository.save(
                IdempotencyKeyRecord.builder()
                        .idempotencyKey(reservationKey)
                        .workspaceId(WORKSPACE_ID)
                        .scope(SCOPE)
                        .build());
        repository.markFailed(reservationKey, IdempotencyKeyRecord.Status.FAILED, Instant.now());
        entityManager.clear();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        RuntimeException boom = new RuntimeException("second failure");
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                txTemplate.execute(
                                        status ->
                                                service.executeOnce(
                                                        key,
                                                        WORKSPACE_ID,
                                                        SCOPE,
                                                        () -> {
                                                            throw boom;
                                                        })));
        assertEquals(
                "second failure",
                thrown.getMessage(),
                "the ORIGINAL action exception must propagate -- not a lock-timeout masking it");

        entityManager.clear();
        assertEquals(
                IdempotencyKeyRecord.Status.FAILED,
                repository.findByIdempotencyKey(reservationKey).orElseThrow().getStatus(),
                "must be FAILED again, i.e. still reclaimable");
    }

    /** GAP C: the round-3 key-length guard, never unit-tested. */
    @Test
    void overlongCompositeKeyIsRejectedWithTypedExceptionBeforeAnyDbCall() {
        String longKey = "x".repeat(200);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.executeOnce(longKey, WORKSPACE_ID, SCOPE, () -> "should-not-run"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.runExclusive(longKey, WORKSPACE_ID, SCOPE, () -> "should-not-run"));
        assertEquals(0, repository.count(), "no row may be written for a rejected over-long key");
    }

    /** GAP D: a composite key of EXACTLY 128 chars must be accepted and must actually fit the column. */
    @Test
    void compositeKeyAtExactlyTheColumnLimitIsAcceptedAndPersists() {
        String prefix = SCOPE + ":" + WORKSPACE_ID + ":";
        String rawKey = "y".repeat(128 - prefix.length());
        String reservationKey = prefix + rawKey;
        assertEquals(128, reservationKey.length());

        String result = service.executeOnce(rawKey, WORKSPACE_ID, SCOPE, () -> "ok-at-limit");
        assertEquals("ok-at-limit", result);

        entityManager.clear();
        assertEquals(
                IdempotencyKeyRecord.Status.COMPLETED,
                repository.findByIdempotencyKey(reservationKey).orElseThrow().getStatus(),
                "a 128-char key is within VARCHAR(128) and must round-trip intact, not be truncated");
    }
}
