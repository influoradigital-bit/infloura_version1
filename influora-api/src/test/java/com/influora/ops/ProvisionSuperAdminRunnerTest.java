package com.influora.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.AdminUserRepository;
import com.influora.security.TotpService;
import com.influora.service.admin.AdminMfaSecretCipher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * ADMIN-BOOTSTRAP-0829 permanent fix #1 — {@link ProvisionSuperAdminRunner}. Exercises {@link
 * ProvisionSuperAdminRunner#provisionAdmin} directly (not {@code run()}/{@code provision()}, which
 * read {@code System.getenv()} and call {@code System.exit} — not safely mockable/observable from a
 * unit test; {@code provisionAdmin} is the actual row-creation logic those wrap).
 *
 * <p>Proves: exactly one correctly-shaped {@code SUPER_ADMIN} row is saved (real BCrypt hash from
 * the injected {@link PasswordEncoder}, MFA already enrolled via the injected {@link TotpService}/
 * {@link AdminMfaSecretCipher} — never {@code mfaEnabled=false}, which is the exact state that
 * caused ADMIN-BOOTSTRAP-0829's login lockout); and the script refuses to run a second time once
 * {@code admin_users} already has a row.
 *
 * <p><b>F-0650 (unlocked-check-then-insert race) coverage:</b> {@link
 * #acquiresLockBeforeCheckingAndReleasesAfterInserting()} proves the named-lock mechanism is
 * actually exercised — not just present in source — by asserting, via {@link InOrder}, that {@code
 * GET_LOCK} is called before {@code adminUserRepository.count()} and {@code RELEASE_LOCK} is called
 * after {@code adminUserRepository.save()}, on every code path (success, "already exists", and lock
 * contention). {@link #refusesToProceedWhenLockIsHeldByAnotherRun()} proves that when the lock is
 * NOT acquired (simulating a concurrent run already holding it), this run never touches {@code
 * count()}/{@code save()} at all — the gate that makes a duplicate row structurally impossible.
 * {@link #twoConcurrentInvocationsProduceExactlyOneAdminRow()} is the closest this Mockito-only
 * harness (no real Spring context, no real MySQL — see the existing class javadoc above) can come
 * to a genuine concurrency proof: it runs two REAL threads against one {@link
 * ProvisionSuperAdminRunner}, backing the mocked {@code GET_LOCK}/{@code RELEASE_LOCK} calls with a
 * real {@link ReentrantLock} and the mocked repository's {@code count()}/{@code save()} with a real
 * shared counter, so the runner's own acquire-check-insert-release ORDERING is genuinely raced by
 * two threads with no synchronization added by the test itself.
 *
 * <p><b>F-0650 correction coverage (2026-09-04 — release-before-commit, CTO review finding):</b>
 * {@link #releasesLockOnlyAfterRowIsDurablyCommitted()} is the test that actually distinguishes the
 * fixed lock ordering (acquire/release wrapping the whole {@code transactionTemplate.execute(...)}
 * call) from the earlier, broken ordering (release inside the transaction callback, before commit).
 * The suite's other tests stub {@code adminUserRepository.save()} to make its effect instantly
 * "visible" (an incrementing counter or {@code rowCount} field updated the moment {@code save()} is
 * called) — exactly the weakness that let the FIRST cut of this fix pass every test here while still
 * releasing the lock before the row was even flushed, let alone committed ({@link AdminUser} has an
 * assigned {@code @Id}, so {@code save()} defers the actual {@code INSERT} to flush time). This new
 * test instead models persist-defers-to-flush explicitly: {@code save()} only marks the row
 * "staged", and a separate stub on the mocked {@code PlatformTransactionManager#commit} is what
 * marks it "durably committed" — mirroring a real flush + {@code COMMIT}. The mocked {@code
 * RELEASE_LOCK} native query records whether the row was already durably committed AT THE MOMENT it
 * runs. Against the release-before-commit shape, that recorded value is {@code false} (commit()
 * hasn't fired yet when the callback's own {@code finally} releases the lock); against the fix, it
 * is {@code true} (commit() — and therefore {@code transactionTemplate.execute()} returning — has
 * already happened by the time the OUTER {@code finally} in {@link
 * ProvisionSuperAdminRunner#provisionAdmin} releases the lock). A regression back to
 * release-inside-the-callback genuinely fails this assertion, not merely goes unchecked by it.
 *
 * <p><b>What this DOES prove:</b> {@link ProvisionSuperAdminRunner}'s own logic correctly
 * serializes two concurrent callers such that only one ever sees {@code count() == 0} and only one
 * ever calls {@code save()} — i.e. the check-then-insert race described in F-0650 cannot happen
 * inside this class's control flow, given a lock that behaves like a real mutual-exclusion lock; AND
 * that, within this class's own control flow, {@code RELEASE_LOCK} is never invoked before the
 * transaction that inserted the row has committed (per the mocked {@code
 * PlatformTransactionManager}'s {@code commit()} call, which is what {@code
 * transactionTemplate.execute()} genuinely waits on before returning).
 *
 * <p><b>What this does NOT prove:</b> that MySQL's actual {@code GET_LOCK}/{@code RELEASE_LOCK}
 * behaves as a real cross-connection, cross-process mutual-exclusion lock — that is MySQL's own
 * documented guarantee, not something a JVM-local unit test can exercise. This suite also cannot
 * exercise two separate OS processes (the real F-0650 scenario — two operators launching the
 * bootstrap shell script concurrently), only two threads within one test JVM sharing one mocked
 * {@link EntityManager}/{@link AdminUserRepository}. Nor does it prove that a real Hibernate {@code
 * flush()} + MySQL {@code COMMIT} makes a row invisible to a concurrent connection's {@code
 * SELECT COUNT(*)} until commit — that is standard InnoDB transaction-isolation behavior (not
 * exercisable without a real database), not something this mocked {@code
 * PlatformTransactionManager#commit} call can itself demonstrate; the test only proves that THIS
 * class waits for {@code commit()} before releasing the lock, which is the piece actually within
 * this file's control and the piece the CTO review's finding was about.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionSuperAdminRunnerTest {

    private static final String EMAIL = "ops@influora.in";
    private static final String PASSWORD = "a-strong-bootstrap-passphrase";
    private static final String HASHED = "$2a$12$mockedBcryptHashValue........................";
    private static final String RAW_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String OTP_URI = "otpauth://totp/Influora%20Admin:ops%40influora.in?secret=JBSWY3DPEHPK3PXP";
    private static final String ENCRYPTED_SECRET = "base64-ciphertext-not-the-real-secret";

    @Mock private AdminUserRepository adminUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TotpService totpService;
    @Mock private AdminMfaSecretCipher mfaSecretCipher;
    @Mock private ConfigurableApplicationContext applicationContext;
    @Mock private EntityManager entityManager;
    @Mock private PlatformTransactionManager transactionManager;

    private ProvisionSuperAdminRunner runner;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute() needs a real TransactionStatus back from getTransaction();
        // SimpleTransactionStatus is Spring's own no-op implementation, exactly what a mocked
        // PlatformTransactionManager should hand back — commit()/rollback() on the manager mock are
        // plain no-ops (Mockito's default for void methods), same as a real single-resource
        // transaction manager committing a transaction this test never actually needs to observe.
        // lenient(): F-0650 correction moved the lock acquire OUTSIDE transactionTemplate.execute(),
        // so a test whose GET_LOCK fails (refusesToProceedWhenLockIsHeldByAnotherRun) now returns
        // before a transaction is ever opened and never calls getTransaction() at all — this
        // shared setUp() stub is legitimately unused on that path, not a sign of a stale/wrong stub.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        runner =
                new ProvisionSuperAdminRunner(
                        adminUserRepository,
                        passwordEncoder,
                        totpService,
                        mfaSecretCipher,
                        applicationContext,
                        entityManager,
                        transactionManager);
    }

    /**
     * Stubs entityManager.createNativeQuery(...) so GET_LOCK resolves to {@code getLockResult}.
     * Only stubs RELEASE_LOCK when the lock was actually acquired (result 1) — {@code
     * provisionAdmin} never calls it otherwise (see the "F-0650" javadoc), and stubbing an
     * interaction that then never happens trips Mockito's strict-stubs UnnecessaryStubbingException.
     */
    private void stubLock(int getLockResult) {
        Query lockQuery = mock(Query.class, RETURNS_SELF);
        when(lockQuery.getSingleResult()).thenReturn(getLockResult);
        when(entityManager.createNativeQuery("SELECT GET_LOCK(?1, ?2)")).thenReturn(lockQuery);
        if (getLockResult == 1) {
            Query releaseQuery = mock(Query.class, RETURNS_SELF);
            when(releaseQuery.getSingleResult()).thenReturn(1);
            when(entityManager.createNativeQuery("SELECT RELEASE_LOCK(?1)")).thenReturn(releaseQuery);
        }
    }

    @Test
    @DisplayName("creates exactly one correctly-shaped SUPER_ADMIN row, MFA already enrolled")
    void createsExactlyOneCorrectlyShapedSuperAdminRow() {
        stubLock(1);
        when(adminUserRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED);
        when(totpService.generateSecret()).thenReturn(RAW_SECRET);
        when(totpService.buildOtpAuthUri(anyString(), anyString())).thenReturn(OTP_URI);
        when(mfaSecretCipher.encrypt(RAW_SECRET)).thenReturn(ENCRYPTED_SECRET);

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);

        assertEquals(0, exitCode);
        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserRepository, times(1)).save(captor.capture());

        AdminUser saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals(EMAIL, saved.getEmail());
        assertEquals(AdminRole.SUPER_ADMIN, saved.getRole());
        assertEquals(HASHED, saved.getPasswordHash());
        assertTrue(saved.isActive());
        // The whole point of ADMIN-BOOTSTRAP-0829's fix: never persisted at mfaEnabled=false.
        assertTrue(saved.isMfaEnabled(), "row must be created with MFA already enrolled");
        assertEquals(ENCRYPTED_SECRET, saved.getEncryptedMfaSecret());
    }

    @Test
    @DisplayName("refuses to run when admin_users already has a row — no save() call")
    void refusesWhenAdminAlreadyExists() {
        stubLock(1);
        when(adminUserRepository.count()).thenReturn(1L);

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);

        assertEquals(1, exitCode);
        verify(adminUserRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        verify(totpService, never()).generateSecret();
    }

    /**
     * F-0650: falsifies against the OLD (pre-fix) behaviour — before this fix, {@code
     * provisionAdmin} never touched {@code entityManager} at all, so this test would fail with zero
     * interactions on the {@code GET_LOCK}/{@code RELEASE_LOCK} native queries. Proves the lock
     * mechanism is real and correctly scoped: acquired before the existence check, released after
     * the insert, on the success path.
     */
    @Test
    @DisplayName("F-0650: acquires the named lock before count(), releases it after save()")
    void acquiresLockBeforeCheckingAndReleasesAfterInserting() {
        stubLock(1);
        when(adminUserRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED);
        when(totpService.generateSecret()).thenReturn(RAW_SECRET);
        when(totpService.buildOtpAuthUri(anyString(), anyString())).thenReturn(OTP_URI);
        when(mfaSecretCipher.encrypt(RAW_SECRET)).thenReturn(ENCRYPTED_SECRET);

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);
        assertEquals(0, exitCode);

        InOrder inOrder = Mockito.inOrder(entityManager, adminUserRepository);
        inOrder.verify(entityManager).createNativeQuery("SELECT GET_LOCK(?1, ?2)");
        inOrder.verify(adminUserRepository).count();
        inOrder.verify(adminUserRepository).save(any());
        inOrder.verify(entityManager).createNativeQuery("SELECT RELEASE_LOCK(?1)");
    }

    /**
     * F-0650 correction: the test that actually distinguishes the fixed lock ordering from the
     * broken one — see the class javadoc's "F-0650 correction coverage" section for the full
     * reasoning. Models {@link AdminUser}'s persist-defers-to-flush behavior explicitly (unlike the
     * other tests' instantly-visible {@code save()} stub): {@code save()} only marks the row
     * "staged"; the mocked {@code PlatformTransactionManager#commit} call — which {@code
     * transactionTemplate.execute()} genuinely does not return without — is what marks it "durably
     * committed". The mocked {@code RELEASE_LOCK} query records whether the row was already durably
     * committed at the exact moment it fires.
     */
    @Test
    @DisplayName("F-0650: releases the named lock only after the row is durably committed, not merely staged")
    void releasesLockOnlyAfterRowIsDurablyCommitted() {
        when(adminUserRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED);
        when(totpService.generateSecret()).thenReturn(RAW_SECRET);
        when(totpService.buildOtpAuthUri(anyString(), anyString())).thenReturn(OTP_URI);
        when(mfaSecretCipher.encrypt(RAW_SECRET)).thenReturn(ENCRYPTED_SECRET);

        // GET_LOCK: always succeeds immediately -- this test is about release timing, not
        // contention.
        Query lockQuery = mock(Query.class, RETURNS_SELF);
        when(lockQuery.getSingleResult()).thenReturn(1);
        when(entityManager.createNativeQuery("SELECT GET_LOCK(?1, ?2)")).thenReturn(lockQuery);

        // save(): mirrors persist() deferring the actual INSERT to flush time for an entity with an
        // assigned (non-generated) @Id -- staged only, NOT yet durable. Deliberately does NOT flip
        // rowCommitted -- only a real flush+COMMIT (modeled below via transactionManager.commit())
        // does that.
        AtomicBoolean rowStaged = new AtomicBoolean(false);
        AtomicBoolean rowCommitted = new AtomicBoolean(false);
        when(adminUserRepository.save(any(AdminUser.class)))
                .thenAnswer(
                        invocation -> {
                            rowStaged.set(true);
                            return invocation.getArgument(0);
                        });

        // transactionManager.commit(...): the ONLY point at which the staged row becomes durable --
        // this is what a real flush + MySQL COMMIT does. TransactionTemplate.execute() calls this
        // synchronously before it returns to provisionAdmin().
        doAnswer(
                        invocation -> {
                            assertTrue(rowStaged.get(), "commit() observed before save() -- broken test setup");
                            rowCommitted.set(true);
                            return null;
                        })
                .when(transactionManager)
                .commit(any());

        // RELEASE_LOCK: records whether the row was durably committed AT THE MOMENT the lock is
        // released -- the crux of the CTO review's finding. Against the release-before-commit
        // (broken) shape, rowCommitted is still false here; against the fix, it is already true.
        AtomicBoolean rowCommittedAtRelease = new AtomicBoolean(false);
        Query releaseQuery = mock(Query.class, RETURNS_SELF);
        when(releaseQuery.getSingleResult())
                .thenAnswer(
                        invocation -> {
                            rowCommittedAtRelease.set(rowCommitted.get());
                            return 1;
                        });
        when(entityManager.createNativeQuery("SELECT RELEASE_LOCK(?1)")).thenReturn(releaseQuery);

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);

        assertEquals(0, exitCode);
        assertTrue(rowStaged.get(), "row must have been saved");
        assertTrue(rowCommitted.get(), "transaction must have committed by the time provisionAdmin() returns");
        assertTrue(
                rowCommittedAtRelease.get(),
                "F-0650: RELEASE_LOCK must not fire until the row is durably committed -- a second"
                        + " process woken from GET_LOCK at this instant must be able to see the row via"
                        + " count()");
    }

    /**
     * F-0650: same ordering proof as above, but on the "already exists" abort path — the lock must
     * still be released even though no row was inserted (a leaked lock here would permanently wedge
     * every future run on whichever pooled connection reuses this session).
     */
    @Test
    @DisplayName("F-0650: releases the named lock even when aborting on an existing row")
    void releasesLockWhenAbortingOnExistingRow() {
        stubLock(1);
        when(adminUserRepository.count()).thenReturn(1L);

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);
        assertEquals(1, exitCode);

        InOrder inOrder = Mockito.inOrder(entityManager, adminUserRepository);
        inOrder.verify(entityManager).createNativeQuery("SELECT GET_LOCK(?1, ?2)");
        inOrder.verify(adminUserRepository).count();
        inOrder.verify(entityManager).createNativeQuery("SELECT RELEASE_LOCK(?1)");
        verify(adminUserRepository, never()).save(any());
    }

    /**
     * F-0650: the core race-closing behaviour — when the named lock is NOT acquired (GET_LOCK
     * returns 0, i.e. another run currently holds it), this run must never read or write
     * admin_users at all. This is what makes "two rows created" structurally impossible: a run that
     * loses the race for the lock cannot reach its own count()/save() until the winner has released
     * the lock (proven separately in {@link #twoConcurrentInvocationsProduceExactlyOneAdminRow()}).
     */
    @Test
    @DisplayName("F-0650: never checks or inserts when the lock is held by another run")
    void refusesToProceedWhenLockIsHeldByAnotherRun() {
        stubLock(0); // GET_LOCK timed out — another session holds influora_provision_super_admin

        int exitCode = runner.provisionAdmin(EMAIL, PASSWORD);

        assertEquals(1, exitCode);
        verify(adminUserRepository, never()).count();
        verify(adminUserRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        // RELEASE_LOCK must NOT be called for a lock this run never actually acquired.
        verify(entityManager, never()).createNativeQuery("SELECT RELEASE_LOCK(?1)");
    }

    /**
     * F-0650's central claim, made concrete with real concurrency: two threads call {@code
     * provisionAdmin} on the SAME {@link ProvisionSuperAdminRunner} at (as close as a JVM can get
     * to) the same instant. The mocked {@code GET_LOCK}/{@code RELEASE_LOCK} native queries are
     * backed by a real {@link ReentrantLock} standing in for MySQL's named lock, and the mocked
     * repository's {@code count()}/{@code save()} are backed by a real shared counter standing in
     * for the {@code admin_users} table — so the only thing under test is whether {@link
     * ProvisionSuperAdminRunner}'s OWN acquire → check → insert → release sequencing, not any
     * synchronization added by this test, is what keeps the two threads from both inserting.
     *
     * <p>Against the pre-fix code (no lock at all) this exact harness — real threads, a
     * {@link java.util.concurrent.CyclicBarrier}-style simultaneous start, a shared counter standing
     * in for the table — would routinely produce two saves. See the class javadoc's "What this does
     * NOT prove" note for the limits of this harness.
     */
    @Test
    @DisplayName("F-0650: two concurrent invocations produce exactly one admin row, not two")
    void twoConcurrentInvocationsProduceExactlyOneAdminRow() throws Exception {
        ReentrantLock fakeNamedLock = new ReentrantLock();
        AtomicInteger rowCount = new AtomicInteger(0);

        // GET_LOCK stand-in: block (like MySQL would) until the lock is free, then report success.
        Query lockQuery = mock(Query.class, RETURNS_SELF);
        when(lockQuery.getSingleResult())
                .thenAnswer(
                        invocation -> {
                            boolean acquired = fakeNamedLock.tryLock(LOCK_WAIT_TIMEOUT_SECONDS_FOR_TEST, TimeUnit.SECONDS);
                            return acquired ? 1 : 0;
                        });
        Query releaseQuery = mock(Query.class, RETURNS_SELF);
        when(releaseQuery.getSingleResult())
                .thenAnswer(
                        invocation -> {
                            fakeNamedLock.unlock();
                            return 1;
                        });
        when(entityManager.createNativeQuery("SELECT GET_LOCK(?1, ?2)")).thenReturn(lockQuery);
        when(entityManager.createNativeQuery("SELECT RELEASE_LOCK(?1)")).thenReturn(releaseQuery);

        // admin_users stand-in: count() reflects the shared counter; save() increments it.
        when(adminUserRepository.count()).thenAnswer(invocation -> (long) rowCount.get());
        when(adminUserRepository.save(any(AdminUser.class)))
                .thenAnswer(
                        invocation -> {
                            rowCount.incrementAndGet();
                            return invocation.getArgument(0);
                        });
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED);
        when(totpService.generateSecret()).thenReturn(RAW_SECRET);
        when(totpService.buildOtpAuthUri(anyString(), anyString())).thenReturn(OTP_URI);
        when(mfaSecretCipher.encrypt(RAW_SECRET)).thenReturn(ENCRYPTED_SECRET);
        // getTransaction() is called once per provisionAdmin() invocation, from two threads here.
        // The single SimpleTransactionStatus stubbed in setUp() is safely shared: the mocked
        // transactionManager.commit(status) it's later passed to is a no-op (Mockito default for a
        // void method) that never inspects the status object, so there is no real contention.

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> runner.provisionAdmin(EMAIL + ".a", PASSWORD));
            Future<Integer> second = pool.submit(() -> runner.provisionAdmin(EMAIL + ".b", PASSWORD));

            int exitA = first.get(10, TimeUnit.SECONDS);
            int exitB = second.get(10, TimeUnit.SECONDS);

            // Exactly one of the two invocations must have succeeded (exit 0) and the other must
            // have lost the race and aborted (exit 1) — never both 0, never both 1.
            assertEquals(1, (exitA == 0 ? 1 : 0) + (exitB == 0 ? 1 : 0), "exactly one invocation must succeed");
            assertEquals(1, rowCount.get(), "exactly one admin row must have been created");
            verify(adminUserRepository, times(1)).save(any());
        } finally {
            pool.shutdownNow();
        }
    }

    private static final int LOCK_WAIT_TIMEOUT_SECONDS_FOR_TEST = 10;
}
