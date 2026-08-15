package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.IdempotencyKeyRecord;
import com.influora.repository.IdempotencyKeyRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [SEC: Priya round-2 IdempotencyService review, ROUND-2 FIX] REAL (non-Mockito) Hibernate + H2
 * coverage for {@link IdempotencyService} and {@link IdempotencyKeyRecordRepository} — the exact
 * gap Priya's review flagged: {@code IdempotencyServiceTest} and the BL-3
 * {@code SubscriptionServiceTest} checkout-concurrency tests are Mockito-only, backed by a fake
 * repository (a {@code ConcurrentHashMap}) that does not model real JPA {@code merge()} vs {@code
 * persist()} semantics or real entity detachment — so neither test could have caught either
 * defect below even though both defects were present.
 *
 * <p>This class runs against a genuine embedded H2 database (fresh schema per test, generated
 * from the entity annotations — Flyway is disabled here on purpose since the app's migrations are
 * MySQL-specific DDL; the entity mapping itself, which IS what's under test, is what {@code
 * ddl-auto=validate} normally checks against those migrations in the real MySQL profile).
 * {@code @Transactional(propagation = NOT_SUPPORTED)} at the class level deliberately turns OFF
 * {@code @DataJpaTest}'s default per-test transaction-and-rollback wrapping: every repository call
 * below runs in its OWN top-level transaction, matching production for the callers that invoke
 * {@code executeOnce}/{@code runExclusive} with NO ambient transaction of their own.
 *
 * <p><b>[SEC: Vikram, round-4 correction] {@code NOT_SUPPORTED} at the class level does
 * NOT mirror production for the (at least) 6 callers that invoke {@code executeOnce}/{@code
 * runExclusive} from inside their OWN {@code @Transactional} method</b> — {@code
 * WalletService#requestCreatorWithdrawal}, {@code DealService#accept}/{@code #reject}, {@code
 * ContractService#recordSignature}/{@code #recordSignatureForCreator}, {@code
 * AICreditService#release}. A prior version of this javadoc incorrectly claimed {@code
 * NOT_SUPPORTED} was representative for those callers too, on the theory that {@code
 * IdempotencyService}'s self-invocation meant every actual DB write was already its own
 * independent transaction regardless of what the test wrapper did. That reasoning held for the
 * repository/self-invocation shape that existed at the time, but not for what those 6 callers
 * actually do: they hold an AMBIENT transaction open around the entire {@code executeOnce} call.
 * {@link #ambientTransactionCallerStillGetsRealReservationSemantics} below is what specifically
 * covers that shape (via {@link TransactionTemplate}, simulating the caller's own {@code
 * @Transactional} boundary) — see {@link IdempotencyReservationOps}'s javadoc for why a
 * SEPARATE bean (not just adding {@code REQUIRES_NEW}) was required to make it actually work
 * correctly, once self-invocation was ruled out as accidentally-safe-enough.
 *
 * <p>Proves, against the real DB:
 *
 * <ul>
 *   <li>Defect 1 (save() silently upserting instead of locking): a genuine second reservation for
 *       the same key now throws {@link DataIntegrityViolationException} instead of silently
 *       overwriting the first — both at the repository level directly, and end-to-end through
 *       {@link IdempotencyService#executeOnce}/{@link IdempotencyService#runExclusive} under REAL
 *       concurrent threads.
 *   <li>Defect 2 (status transitions never persisting): {@code markCompleted}/{@code markFailed}
 *       mutations are actually visible on a FRESH read (a brand-new {@link EntityManager} query,
 *       not the same in-memory Java object) after the call returns.
 *   <li>{@code reclaimFailedForRetry} does not throw {@code TransactionRequiredException} when
 *       invoked with no surrounding test-managed transaction (task item 3) — this test is what
 *       ORIGINALLY caught that it DID throw prior to giving {@code reclaimFailedForRetry}/{@code
 *       reclaimStaleInProgress}/{@code markCompleted}/{@code markFailed} their own explicit
 *       {@code @Transactional} (see {@code IdempotencyKeyRecordRepository}).
 *   <li>[round-4] The reservation genuinely fails/is caught even when {@code executeOnce} is
 *       called from INSIDE an already-active ambient transaction (the real shape of the 6 callers
 *       named above) — {@link #ambientTransactionCallerStillGetsRealReservationSemantics}.
 *   <li>[round-4, Finding 2] A {@code @Modifying} UPDATE's {@code clearAutomatically = true} keeps
 *       an entity already loaded earlier in the SAME transaction from staying stale — {@link
 *       #markCompletedClearsStaleEntityInSameAmbientTransaction}.
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = IdempotencyKeyRecord.class)
// basePackageClasses alone would scan (and try to validate) every repository in
// com.influora.repository, including ones with MySQL-specific native/JPQL queries that don't
// validate against H2 (e.g. UsageCounterRepository's tryIncrement) -- exclude everything in that
// package except exactly the one repository under test.
@EnableJpaRepositories(
        basePackageClasses = IdempotencyKeyRecordRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!IdempotencyKeyRecordRepository$).*"))
// [SEC: Vikram, round-4] @DataJpaTest does not component-scan @Component/@Service beans by
// default -- IdempotencyReservationOps must be explicitly imported so it is a REAL Spring-proxied
// bean in this context (required for @Transactional(REQUIRES_NEW) on it to mean anything; see its
// own javadoc for why that distinction is the entire point of this round's fix).
@Import(IdempotencyReservationOps.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:idempotency_persistence_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServicePersistenceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACEIDEMPO0001";
    private static final String SCOPE = "test.persistence.scope";

    @Autowired private IdempotencyKeyRecordRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private IdempotencyReservationOps reservationOps;
    @Autowired private PlatformTransactionManager transactionManager;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        // [SEC: Vikram, round-4] reservationOps is the AUTOWIRED (real, Spring-proxied) bean --
        // unlike the old self-invoked helper methods this replaces, going through this proxy is
        // what makes @Transactional(REQUIRES_NEW) on IdempotencyReservationOps a genuine boundary.
        // Constructing IdempotencyService itself directly (not as a Spring bean) still matches how
        // every real caller ends up using it -- a plain @Service singleton -- the difference from
        // the pre-round-4 test is that the COLLABORATOR it delegates to is now the real proxy.
        service = new IdempotencyService(repository, reservationOps);
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Defect 1: genuine duplicate reservation must be REJECTED, not upserted
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "Defect 1: a real second repository.save() for an already-reserved key throws"
                    + " DataIntegrityViolationException instead of silently overwriting the first row")
    void duplicateReservationAtRepositoryLevelIsRejected() {
        String key = "repo-level-dup-key";

        repository.save(
                IdempotencyKeyRecord.builder().idempotencyKey(key).workspaceId(WORKSPACE_ID).scope(SCOPE).build());

        // A second, independent, freshly-built instance (a genuinely new object, isNew()==true --
        // this is not the same Java instance being re-saved) attempting to reserve the SAME key.
        IdempotencyKeyRecord secondReservation =
                IdempotencyKeyRecord.builder().idempotencyKey(key).workspaceId(WORKSPACE_ID).scope(SCOPE).build();

        assertThrows(DataIntegrityViolationException.class, () -> repository.save(secondReservation));

        // The first reservation must be untouched -- not overwritten by the rejected second one.
        List<IdempotencyKeyRecord> rows = repository.findAll();
        assertEquals(1, rows.size(), "the duplicate must not have created or replaced a second row");
        assertEquals(IdempotencyKeyRecord.Status.IN_PROGRESS, rows.get(0).getStatus());
    }

    @Test
    @DisplayName(
            "Defect 1, end-to-end: two REAL concurrent threads call executeOnce for the SAME key --"
                    + " exactly one reserves and runs the action, the other gets AlreadyInProgressException")
    void concurrentExecuteOnceForSameKeyExactlyOneWinnerRealThreads() throws Exception {
        String key = "concurrent-execute-once-key";
        AtomicInteger actionRunCount = new AtomicInteger();
        CountDownLatch bothStarting = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> t1 =
                    pool.submit(
                            () -> {
                                bothStarting.countDown();
                                bothStarting.await();
                                try {
                                    return (Object)
                                            service.executeOnce(
                                                    key,
                                                    WORKSPACE_ID,
                                                    SCOPE,
                                                    () -> {
                                                        actionRunCount.incrementAndGet();
                                                        return "done";
                                                    });
                                } catch (RuntimeException ex) {
                                    return ex;
                                }
                            });
            Future<Object> t2 =
                    pool.submit(
                            () -> {
                                bothStarting.countDown();
                                bothStarting.await();
                                try {
                                    return (Object)
                                            service.executeOnce(
                                                    key,
                                                    WORKSPACE_ID,
                                                    SCOPE,
                                                    () -> {
                                                        actionRunCount.incrementAndGet();
                                                        return "done";
                                                    });
                                } catch (RuntimeException ex) {
                                    return ex;
                                }
                            });

            Object r1 = t1.get(10, TimeUnit.SECONDS);
            Object r2 = t2.get(10, TimeUnit.SECONDS);

            long successes = List.of(r1, r2).stream().filter(r -> "done".equals(r)).count();
            // The loser may observe either exception depending on exactly how the two threads
            // interleave: if it loses the reservation race while the winner's action is still
            // running, it sees AlreadyInProgressException; if the winner's (trivial, fast) action
            // has already reached COMPLETED by the time the loser checks, it legitimately sees
            // AlreadyCompletedException instead. Both are a correct rejection -- what matters is
            // that the loser is NEVER silently let through to re-run the effect.
            long rejections =
                    List.of(r1, r2).stream()
                            .filter(
                                    r ->
                                            r instanceof IdempotencyService.AlreadyInProgressException
                                                    || r instanceof IdempotencyService.AlreadyCompletedException)
                            .count();

            assertEquals(1, successes, "exactly one concurrent caller's action must actually run");
            assertEquals(1, rejections, "the other caller must be cleanly rejected, not silently allowed through");
            assertEquals(1, actionRunCount.get(), "the guarded effect itself must fire exactly once");
        } finally {
            pool.shutdownNow();
        }

        // The winner's row must have actually reached COMPLETED in the DB (Defect 2 check, via the
        // real end-to-end path).
        entityManager.clear();
        Optional<IdempotencyKeyRecord> row =
                repository.findByIdempotencyKey(SCOPE + ":" + WORKSPACE_ID + ":" + key);
        assertTrue(row.isPresent());
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, row.get().getStatus());
    }

    // ------------------------------------------------------------------
    // Defect 2: markCompleted/markFailed must actually persist, visible on a fresh read
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "Defect 2: markCompleted actually persists -- a FRESH read (new query, not the same Java"
                    + " object) after the call returns sees COMPLETED + the result digest")
    void markCompletedIsVisibleOnFreshRead() {
        String key = "mark-completed-key";
        repository.save(
                IdempotencyKeyRecord.builder().idempotencyKey(key).workspaceId(WORKSPACE_ID).scope(SCOPE).build());

        int updated = repository.markCompleted(key, IdempotencyKeyRecord.Status.COMPLETED, "digest-abc-123", Instant.now());
        assertEquals(1, updated);

        // Force a genuinely fresh read: clear the persistence context so this is not just handing
        // back the same detached-then-mutated Java object from the first-level cache.
        entityManager.clear();

        IdempotencyKeyRecord fresh = repository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, fresh.getStatus());
        assertEquals("digest-abc-123", fresh.getResultDigest());
        assertNotNull(fresh.getCompletedAt());
    }

    @Test
    @DisplayName(
            "Defect 2: markFailed actually persists -- a FRESH read after the call returns sees FAILED,"
                    + " and the row is then genuinely reclaimable")
    void markFailedIsVisibleOnFreshReadAndReclaimable() {
        String key = "mark-failed-key";
        repository.save(
                IdempotencyKeyRecord.builder().idempotencyKey(key).workspaceId(WORKSPACE_ID).scope(SCOPE).build());

        int updated = repository.markFailed(key, IdempotencyKeyRecord.Status.FAILED, Instant.now());
        assertEquals(1, updated);

        entityManager.clear();
        IdempotencyKeyRecord fresh = repository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.FAILED, fresh.getStatus());

        // If the FAILED transition hadn't actually persisted (the pre-fix bug), this WHERE
        // status = FAILED reclaim would match zero rows.
        int reclaimed =
                repository.reclaimFailedForRetry(
                        key, IdempotencyKeyRecord.Status.FAILED, IdempotencyKeyRecord.Status.IN_PROGRESS);
        assertEquals(1, reclaimed, "a genuinely-persisted FAILED row must be reclaimable");
    }

    @Test
    @DisplayName(
            "executeOnce end-to-end: action throws -> markFailedTransactional's write is real, so the"
                    + " SAME key can be retried and succeeds on the next call")
    void executeOnceFailureIsPersistedAndRetryable() {
        String key = "retry-after-real-failure-key";
        RuntimeException boom = new RuntimeException("simulated gateway timeout");

        assertThrows(
                RuntimeException.class,
                () ->
                        service.executeOnce(
                                key,
                                WORKSPACE_ID,
                                SCOPE,
                                () -> {
                                    throw boom;
                                }));

        entityManager.clear();
        IdempotencyKeyRecord failedRow =
                repository.findByIdempotencyKey(SCOPE + ":" + WORKSPACE_ID + ":" + key).orElseThrow();
        assertEquals(
                IdempotencyKeyRecord.Status.FAILED,
                failedRow.getStatus(),
                "the FAILED transition must have actually reached the DB, not just an in-memory object");

        // Pre-fix, this row would have stayed IN_PROGRESS forever (the mutation never persisted),
        // so this retry would incorrectly throw AlreadyInProgressException instead of succeeding.
        String result = service.executeOnce(key, WORKSPACE_ID, SCOPE, () -> "retried-ok");
        assertEquals("retried-ok", result);
    }

    // ------------------------------------------------------------------
    // Item 3: reclaimFailedForRetry must not require an ambient transaction
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "reclaimFailedForRetry does not throw TransactionRequiredException when called with no"
                    + " surrounding transaction -- its own explicit @Transactional (task item 3 fix)"
                    + " gives it one, same as markCompleted/markFailed")
    void reclaimFailedForRetryWorksWithNoAmbientTransaction() {
        String key = "reclaim-no-ambient-tx-key";
        repository.save(
                IdempotencyKeyRecord.builder().idempotencyKey(key).workspaceId(WORKSPACE_ID).scope(SCOPE).build());
        repository.markFailed(key, IdempotencyKeyRecord.Status.FAILED, Instant.now());
        entityManager.clear();

        // This whole test class runs with propagation = NOT_SUPPORTED -- there is genuinely no
        // ambient Spring-managed transaction active at this call site.
        int reclaimed =
                repository.reclaimFailedForRetry(
                        key, IdempotencyKeyRecord.Status.FAILED, IdempotencyKeyRecord.Status.IN_PROGRESS);

        assertEquals(1, reclaimed);
        entityManager.clear();
        assertEquals(
                IdempotencyKeyRecord.Status.IN_PROGRESS, repository.findByIdempotencyKey(key).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // runExclusive (BL-3): same real-persistence guarantees apply
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "runExclusive end-to-end: two REAL concurrent threads -- exactly one runs the action and"
                    + " releases (deletes) the row on success, so a later call is free to run again")
    void concurrentRunExclusiveExactlyOneWinnerThenReusable() throws Exception {
        String key = "concurrent-run-exclusive-key";
        AtomicInteger actionRunCount = new AtomicInteger();
        CountDownLatch bothStarting = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> t1 =
                    pool.submit(
                            () -> {
                                bothStarting.countDown();
                                bothStarting.await();
                                try {
                                    return (Object)
                                            service.runExclusive(
                                                    key, WORKSPACE_ID, SCOPE, () -> actionRunCount.incrementAndGet());
                                } catch (RuntimeException ex) {
                                    return ex;
                                }
                            });
            Future<Object> t2 =
                    pool.submit(
                            () -> {
                                bothStarting.countDown();
                                bothStarting.await();
                                try {
                                    return (Object)
                                            service.runExclusive(
                                                    key, WORKSPACE_ID, SCOPE, () -> actionRunCount.incrementAndGet());
                                } catch (RuntimeException ex) {
                                    return ex;
                                }
                            });

            t1.get(10, TimeUnit.SECONDS);
            t2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, actionRunCount.get());

        entityManager.clear();
        // Success path deletes the row outright -- so it must be gone, and a later call must succeed
        // again (not permanently locked out).
        assertTrue(repository.findByIdempotencyKey(SCOPE + ":" + WORKSPACE_ID + ":" + key).isEmpty());

        Integer secondRunResult = service.runExclusive(key, WORKSPACE_ID, SCOPE, () -> actionRunCount.incrementAndGet());
        assertEquals(2, secondRunResult);
        assertEquals(2, actionRunCount.get());
    }

    // ------------------------------------------------------------------
    // [round-4] Finding 1: ambient-transaction callers must get real reservation semantics
    // ------------------------------------------------------------------

    /**
     * [SEC: Vikram, round-4 fix, Priya's ambient-transaction finding] Reproduces the EXACT shape
     * of {@code WalletService#requestCreatorWithdrawal}/{@code DealService#accept}/{@code
     * ContractService#recordSignature} etc: {@code executeOnce} called from INSIDE a method that
     * already holds its own {@code @Transactional} boundary open — simulated here with {@link
     * TransactionTemplate} wrapping each {@code executeOnce} call, exactly as Priya suggested.
     *
     * <p>Pre-fix (self-invoked {@code tryReserveTransactional}/{@code markCompletedTransactional}
     * on {@code IdempotencyService} itself), the SAME key reserved a second time from inside its
     * own ambient transaction would NOT be rejected: {@code repository.save()}'s own
     * {@code @Transactional} (REQUIRED propagation) joins — rather than opens a new transaction
     * for — the already-active ambient one, so the INSERT is deferred to that ambient
     * transaction's own flush/commit, long after {@code tryReserveTransactional}'s try/catch has
     * already returned {@code true}. The guarded action then runs a SECOND time for a key that
     * was already reserved/completed — exactly the double-execution this whole service exists to
     * prevent — and the eventual constraint violation (if it surfaces at all) comes out as a raw,
     * uncaught {@code DataIntegrityViolationException}, not {@link
     * IdempotencyService.AlreadyCompletedException}, bypassing the caller's typed catch block.
     *
     * <p>Post-fix, {@code reservationOps.tryReserve} goes through the real {@link
     * IdempotencyReservationOps} PROXY bean, so its {@code @Transactional(REQUIRES_NEW)} is a real
     * boundary — it suspends this test's ambient transaction, runs its own INSERT via {@code
     * saveAndFlush} (synchronously, inside the try/catch), commits independently, and resumes the
     * ambient transaction — so the second call's reservation attempt genuinely, synchronously
     * fails right there, is caught, and correctly reported as already-completed.
     */
    @Test
    @DisplayName(
            "round-4 Finding 1: executeOnce called from inside an ALREADY-ACTIVE ambient transaction"
                    + " (TransactionTemplate, mirroring WalletService/DealService/ContractService/"
                    + "AICreditService) still rejects a duplicate reservation with the typed exception,"
                    + " and the guarded action never runs twice")
    void ambientTransactionCallerStillGetsRealReservationSemantics() {
        String key = "ambient-tx-key";
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        AtomicInteger actionRunCount = new AtomicInteger();

        // First call: reserves, runs the action, and completes -- all while ITS OWN ambient
        // transaction (the TransactionTemplate callback) is open, exactly like
        // WalletService#requestCreatorWithdrawal's @Transactional method body.
        String firstResult =
                txTemplate.execute(
                        status ->
                                service.executeOnce(
                                        key,
                                        WORKSPACE_ID,
                                        SCOPE,
                                        () -> {
                                            actionRunCount.incrementAndGet();
                                            return "first-run";
                                        }));
        assertEquals("first-run", firstResult);
        entityManager.clear();
        assertEquals(
                IdempotencyKeyRecord.Status.COMPLETED,
                repository.findByIdempotencyKey(SCOPE + ":" + WORKSPACE_ID + ":" + key).orElseThrow().getStatus());

        // Second call, SAME key, again from inside its OWN fresh ambient transaction. This is the
        // scenario Priya proved broken: pre-fix, this must-fail reservation attempt was silently
        // let through.
        IdempotencyService.AlreadyCompletedException thrown =
                assertThrows(
                        IdempotencyService.AlreadyCompletedException.class,
                        () ->
                                txTemplate.execute(
                                        status ->
                                                service.executeOnce(
                                                        key,
                                                        WORKSPACE_ID,
                                                        SCOPE,
                                                        () -> {
                                                            actionRunCount.incrementAndGet();
                                                            return "second-run";
                                                        })));
        assertNotNull(thrown);

        assertEquals(
                1,
                actionRunCount.get(),
                "the guarded action must run exactly once even though BOTH calls were made from"
                        + " inside their own ambient transaction -- a second run here means the"
                        + " ambient-transaction reservation gap is back");
    }

    /**
     * [SEC: Vikram, round-4 fix] {@code runExclusive} counterpart of {@link
     * #ambientTransactionCallerStillGetsRealReservationSemantics} — a concurrent SECOND caller for
     * the same key while the first is still IN_PROGRESS, both from inside their own ambient
     * transaction, must be rejected with {@link IdempotencyService.AlreadyInProgressException}
     * rather than being let through.
     */
    @Test
    @DisplayName(
            "round-4 Finding 1: runExclusive from inside an ambient transaction still rejects a"
                    + " concurrent duplicate reservation while the first is IN_PROGRESS")
    void ambientTransactionRunExclusiveStillRejectsConcurrentDuplicate() {
        String key = "ambient-tx-run-exclusive-key";
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Reserve the key and leave it IN_PROGRESS (don't let the action complete/release) by
        // reserving directly through the real proxy bean, from inside an ambient transaction --
        // mirrors the reservation half of runExclusive without needing a second live thread.
        txTemplate.execute(
                status -> reservationOps.tryReserve(SCOPE + ":" + WORKSPACE_ID + ":" + key, WORKSPACE_ID, SCOPE));

        assertThrows(
                IdempotencyService.AlreadyInProgressException.class,
                () ->
                        txTemplate.execute(
                                status ->
                                        service.runExclusive(
                                                key, WORKSPACE_ID, SCOPE, () -> "should-not-run")));
    }

    // ------------------------------------------------------------------
    // [round-5] Priya's fresh review: reclaimFailedForRetry must not deadlock when called from
    // inside a caller's own ambient transaction (WalletService's real shape)
    // ------------------------------------------------------------------

    /**
     * [SEC: Vikram, BL-3 round-5 fix — Priya's fresh review] Reproduces the EXACT deadlock Priya
     * found: {@code executeOnce} retrying a FAILED key, called from INSIDE an already-active
     * ambient transaction — the real shape of {@code WalletService#requestCreatorWithdrawal}
     * retrying a withdrawal with the SAME idempotency key (deliberately reused by {@code
     * creator-wallet.tsx} across retries).
     *
     * <p>Pre-fix, {@code reclaimFailedForRetry} was called on {@code repository} directly from
     * {@code IdempotencyService}, NOT through {@link IdempotencyReservationOps} — its bare {@code
     * @Transactional} (REQUIRED propagation) JOINED this test's ambient transaction (the {@link
     * TransactionTemplate} callback below) and held the row's write lock for that transaction's
     * remaining lifetime. {@code reservationOps.markCompleted} then tried to update that SAME row
     * from its own independent {@code REQUIRES_NEW} transaction/connection a moment later —
     * genuine deadlock: two transactions, two connections, each unable to proceed until the other
     * releases a lock it never will before this method returns. Against H2 that surfaces as
     * {@link org.springframework.dao.PessimisticLockingFailureException} once the lock-wait
     * timeout elapses (MySQL's default 50s {@code innodb_lock_wait_timeout} would hold an HTTP
     * thread and pooled connection that long in production before failing the same way).
     *
     * <p>Post-fix, the reclaim goes through {@link IdempotencyReservationOps#reclaimFailedForRetry},
     * a REAL proxy call with its own {@code REQUIRES_NEW} boundary — it suspends this test's
     * ambient transaction, reclaims the row in its own short-lived transaction, and resumes —
     * exactly like {@code tryReserve}/{@code markCompleted}/{@code markFailed}/{@code release}
     * already did. The retry must complete cleanly: no deadlock, no lock-timeout, no {@link
     * org.springframework.transaction.UnexpectedRollbackException}, no {@link
     * org.springframework.dao.PessimisticLockingFailureException}.
     */
    @Test
    @DisplayName(
            "round-5: executeOnce retrying a FAILED key from inside an ALREADY-ACTIVE ambient"
                    + " transaction (TransactionTemplate, mirroring WalletService's own"
                    + " @Transactional) completes successfully with no deadlock/lock-timeout")
    void ambientTransactionFailedKeyRetryDoesNotDeadlock() {
        String key = "ambient-tx-failed-retry-key";
        String reservationKey = SCOPE + ":" + WORKSPACE_ID + ":" + key;

        // Set up a row already FAILED from a prior (unrelated, already-finished) attempt -- no
        // ambient transaction here, matching how a genuinely prior, completed request would have
        // left it.
        repository.save(
                IdempotencyKeyRecord.builder()
                        .idempotencyKey(reservationKey)
                        .workspaceId(WORKSPACE_ID)
                        .scope(SCOPE)
                        .build());
        repository.markFailed(reservationKey, IdempotencyKeyRecord.Status.FAILED, Instant.now());
        entityManager.clear();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        AtomicInteger actionRunCount = new AtomicInteger();

        // The retry itself, wrapped in its OWN ambient transaction -- exactly like
        // WalletService#requestCreatorWithdrawal's @Transactional method body calling
        // executeOnce internally. This must complete without throwing ANY exception -- a
        // deadlock/lock-timeout, UnexpectedRollbackException, or
        // PessimisticLockingFailureException here would mean the round-5 fix regressed.
        String result =
                txTemplate.execute(
                        status ->
                                service.executeOnce(
                                        key,
                                        WORKSPACE_ID,
                                        SCOPE,
                                        () -> {
                                            actionRunCount.incrementAndGet();
                                            return "retried-under-ambient-tx";
                                        }));

        assertEquals("retried-under-ambient-tx", result);
        assertEquals(1, actionRunCount.get(), "the reclaimed retry's action must run exactly once");

        entityManager.clear();
        IdempotencyKeyRecord fresh = repository.findByIdempotencyKey(reservationKey).orElseThrow();
        assertEquals(
                IdempotencyKeyRecord.Status.COMPLETED,
                fresh.getStatus(),
                "the reclaimed-and-retried row must reach COMPLETED, visible on a fresh read");
    }

    // ------------------------------------------------------------------
    // [round-4] Finding 2: clearAutomatically -- stale entity in the SAME ambient transaction
    // ------------------------------------------------------------------

    /**
     * [SEC: Vikram, round-4 fix, Priya's clearAutomatically finding] A {@code @Modifying} bulk
     * UPDATE bypasses the Hibernate first-level (persistence-context) cache — without {@code
     * clearAutomatically = true}, an entity for the SAME row already loaded earlier in the SAME
     * transaction stays stale in memory even though the UPDATE is correctly visible at the DB
     * level. Priya proved this with {@code isCompleted()} reading {@code false} immediately after
     * a commit-visible {@code COMPLETED} write, in the same ambient transaction.
     *
     * <p>Reproduced here by doing everything inside ONE {@link TransactionTemplate} callback: load
     * the row (populating the persistence context), call {@code repository.markCompleted(...)},
     * then re-check via a repository read WITHOUT manually clearing the {@link EntityManager} —
     * simulating a caller (like {@code IdempotencyService#isCompleted}) that has no reason to know
     * it needs to clear anything itself.
     */
    @Test
    @DisplayName(
            "round-4 Finding 2: markCompleted (clearAutomatically = true) is visible to a"
                    + " findByIdempotencyKey call in the SAME ambient transaction, even though that"
                    + " entity was already loaded (and cached) earlier in that same transaction")
    void markCompletedClearsStaleEntityInSameAmbientTransaction() {
        String key = "clear-automatically-key";
        String reservationKey = SCOPE + ":" + WORKSPACE_ID + ":" + key;
        repository.save(
                IdempotencyKeyRecord.builder()
                        .idempotencyKey(reservationKey)
                        .workspaceId(WORKSPACE_ID)
                        .scope(SCOPE)
                        .build());
        entityManager.clear();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        IdempotencyKeyRecord.Status statusAfterUpdateSameTransaction =
                txTemplate.execute(
                        status -> {
                            // Load the row FIRST -- this is what populates the first-level cache
                            // with the PRE-update (IN_PROGRESS) entity instance.
                            IdempotencyKeyRecord loadedBeforeUpdate =
                                    repository.findByIdempotencyKey(reservationKey).orElseThrow();
                            assertEquals(IdempotencyKeyRecord.Status.IN_PROGRESS, loadedBeforeUpdate.getStatus());

                            repository.markCompleted(
                                    reservationKey, IdempotencyKeyRecord.Status.COMPLETED, null, Instant.now());

                            // No manual entityManager.clear() here -- clearAutomatically = true on
                            // the @Modifying query itself must be what makes this next read fresh.
                            return repository.findByIdempotencyKey(reservationKey).orElseThrow().getStatus();
                        });

        assertEquals(
                IdempotencyKeyRecord.Status.COMPLETED,
                statusAfterUpdateSameTransaction,
                "a read in the SAME ambient transaction, after markCompleted, must see COMPLETED --"
                        + " not a stale cached IN_PROGRESS entity");
    }
}
