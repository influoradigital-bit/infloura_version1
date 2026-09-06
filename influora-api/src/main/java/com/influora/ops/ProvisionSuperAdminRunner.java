package com.influora.ops;

import com.influora.common.Ulids;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.AdminUserRepository;
import com.influora.security.TotpService;
import com.influora.service.admin.AdminMfaSecretCipher;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADMIN-BOOTSTRAP-0829 permanent fix #1 — ops-only, one-shot provisioning of the very FIRST {@code
 * SUPER_ADMIN} row. Run via {@code scripts/provision-super-admin.sh}, never invoked during normal
 * app boot (see the {@link ConditionalOnProperty} below — this bean does not even get constructed
 * unless {@code ops.provision-super-admin=true} is passed on the command line).
 *
 * <p><b>Why a Spring {@code ApplicationRunner} instead of a shell/SQL script:</b> {@code
 * AdminUser.create}'s javadoc and this ticket both require using "the SAME password-hashing this
 * codebase already uses" (BCrypt, strength 12, {@code SecurityConfig}'s {@code PasswordEncoder}
 * bean) and the same AES-256-GCM MFA-secret encryption ({@link AdminMfaSecretCipher}, which needs
 * the real {@code influora.admin.mfa-secret-encryption-key} from {@code application.yml}). Neither
 * is reimplementable from a shell script without re-deriving cryptography by hand — exactly what
 * this ticket says not to do. Running inside the real Spring context and injecting the REAL beans
 * is the only way to guarantee byte-for-byte the same hash/ciphertext the running app would
 * produce and can verify.
 *
 * <p><b>The MFA-lockout trap this closes (ADMIN-BOOTSTRAP-0829 root cause):</b> {@code
 * AdminAuthService#login} rejects any SUPER_ADMIN/ADMIN login with {@code MFA_ENROLLMENT_REQUIRED}
 * when {@code mfaEnabled = false} and {@code ADMIN_MFA_ENFORCE_ON_LOGIN} defaults {@code true} —
 * and there is no way to reach {@code /mfa/setup} without first logging in. A script that inserted
 * a row with {@code mfaEnabled = false} would recreate that exact deadlock on its very first use.
 * This runner instead generates a real TOTP secret via the same {@link TotpService} the app uses,
 * encrypts it via the same {@link AdminMfaSecretCipher}, and calls {@link AdminUser#confirmMfa()}
 * itself BEFORE the row is ever saved — the row is born with MFA already enrolled. The plaintext
 * secret / {@code otpauth://} URI is printed to stdout exactly once (same one-time-visibility
 * discipline as {@code AdminAuthService#setupMfa}'s response body) so the operator can add it to an
 * authenticator app immediately; it is never logged anywhere else and is not recoverable from the
 * database afterward (only the AES-256-GCM ciphertext is persisted).
 *
 * <p><b>Scope guard:</b> refuses to run if {@code admin_users} already has ANY row — this is a
 * first-admin bootstrap tool only, not a general admin-creation mechanism (that gap — creating
 * ADMIN/SUPPORT rows once a SUPER_ADMIN already exists — is still open, tracked as a future {@code
 * AdminUserController} per {@code AdminAuthService}'s class javadoc; it can safely be an in-app,
 * MFA-gated SUPER_ADMIN endpoint once one exists, unlike this bootstrap case).
 *
 * <p><b>Operational note (deliberately flagged, not hidden):</b> this runner is launched through
 * the ordinary {@code InfluoraApiApplication} entry point (editing that class is out of scope for
 * this assignment — its file is not in this task's allowed list), so the FULL application context
 * boots, including {@code @EnableScheduling}/{@code @EnableAsync} beans like {@code EmailWorker}.
 * {@code scripts/provision-super-admin.sh} passes {@code -Dspring.main.lazy-initialization=true}
 * specifically to prevent that: with lazy init, a bean is only constructed when something actually
 * depends on it, and this runner's dependency graph never touches the scheduled-job beans, so they
 * are never instantiated (and therefore never have anything to schedule) during this run's brief
 * lifetime, which ends with {@link SpringApplication#exit}. Run this against an otherwise-idle
 * database (its natural timing anyway — before any admin has ever logged in).
 *
 * <p><b>F-0650 fix — atomic check-then-insert:</b> {@link #provisionAdmin} used to be a bare {@code
 * adminUserRepository.count() == 0 ? save() : abort} with no lock and no transaction boundary. Two
 * operators launching this script concurrently against a fresh install (the one scenario this tool
 * exists for — a brand-new environment, nobody has logged in yet, someone runs the bootstrap script
 * twice by mistake, e.g. from two terminals or a retried CI step) could both observe {@code count()
 * == 0} before either had inserted, and both save a {@code SUPER_ADMIN} row — {@code admin_users}
 * only enforces UNIQUE on {@code email}, not on "is there already a SUPER_ADMIN", so two rows with
 * two different emails both succeed. This codebase's existing pessimistic-lock idiom
 * ({@code EscrowHoldRepository#findByIdForUpdate}, {@code @Lock(LockModeType.PESSIMISTIC_WRITE)})
 * locks a specific, already-known row by id — it does not apply here as-is because a fresh
 * {@code admin_users} table has no row yet to lock, and this task's file boundary does not permit
 * adding one (no new repository method, no sentinel-row migration/entity).
 *
 * <p>The fix instead takes a MySQL named lock ({@code GET_LOCK}/{@code RELEASE_LOCK} — see
 * {@link #acquireBootstrapLock} / {@link #releaseBootstrapLock}) scoped to a fixed key, wrapping the
 * count-check and the insert inside one {@link TransactionTemplate}-demarcated transaction (the same
 * explicit-demarcation idiom {@code EmailWorker} already uses in this codebase, never
 * {@code @Transactional} on a method this class calls via self-invocation — that annotation would
 * silently no-op through Spring's proxy). A named lock is chosen over a SERIALIZABLE-isolation
 * transaction (the other option this ticket allowed): on an empty table, two concurrent SERIALIZABLE
 * transactions would both pass their {@code COUNT(*)} read holding compatible shared gap locks, then
 * both block on the INSERT's incompatible insert-intention lock — InnoDB resolves that as a
 * deadlock, aborting one side with a generic {@code CannotAcquireLockException} rather than the
 * clear, already-existing "admin_users already has N row(s)" message this class prints today. A
 * named lock serializes the two runs directly: whichever process acquires
 * {@code influora_provision_super_admin} first proceeds through count+insert and releases the lock
 * only once its row is durably committed; the second process blocks on {@code GET_LOCK} until the
 * first releases, then runs its own {@code count()} against the now-committed row and takes the
 * existing "already provisioned" abort path — no new error class, no ambiguity about which process
 * "won". A dedicated single-row lock table (this ticket's third option) is not used because it would
 * require a schema migration and a new entity/repository method, both outside this file boundary,
 * to solve a problem the named lock already solves without any schema change.
 *
 * <p><b>Correction (2026-09-04, CTO review finding — the lock must wrap the COMMIT, not just the
 * callback body):</b> the first cut of this fix acquired/released the named lock INSIDE the {@link
 * TransactionTemplate} callback (in what is now {@link #provisionAdminInTransaction}), wrapped in a
 * {@code try/finally}. That released the lock BEFORE {@link TransactionTemplate#execute} issued its
 * {@code COMMIT} — worse, {@link AdminUser} has an assigned {@code @Id} (no {@code @GeneratedValue}),
 * so {@code adminUserRepository.save(...)} defers the actual {@code INSERT} to flush time; at the
 * moment the old code released the lock, the row was not even flushed, let alone committed. A second
 * process parked on {@code GET_LOCK} would be woken into exactly that window, run its own {@code
 * count()} against the still-uncommitted (or even unflushed) first row, see {@code 0}, and insert a
 * second {@code SUPER_ADMIN} — the exact race this fix exists to close. The paragraph above
 * ("releases the lock only once its row is durably committed") described the INTENDED behavior, not
 * what the code actually did; that doc/code mismatch was flagged as a problem in its own right,
 * separate from the race itself.
 *
 * <p>Fixed by moving the acquire/release entirely OUTSIDE {@link #provisionAdmin}'s call to {@code
 * transactionTemplate.execute(...)}: {@code try { acquireBootstrapLock(); return
 * transactionTemplate.execute(...); } finally { releaseBootstrapLock(); } }. {@code
 * TransactionTemplate.execute()} is synchronous and does not return until the transaction has
 * actually committed (or rolled back), so the lock is now held across the real commit, not merely
 * across the callback body — a second process cannot be woken by {@code RELEASE_LOCK} until the
 * first process's row is durably visible. The check-then-insert critical section itself ({@link
 * #provisionAdminInTransaction}) is unchanged; only where the lock boundary sits moved.
 *
 * <p><b>Honest limit of this shape:</b> {@code GET_LOCK}/{@code RELEASE_LOCK} are scoped to the
 * physical DB connection/session that issued them, not to a transaction — and now that they run
 * OUTSIDE any Spring-managed transaction, each call goes through the container-managed {@link
 * EntityManager} proxy's non-transactional path, which opens and closes its own short-lived
 * persistence context (and thus asks the connection pool for a connection) per call, rather than
 * sharing the one connection Spring pins to an active transaction. This fix relies on this being a
 * single-threaded, one-shot ops tool run against "an otherwise-idle database" (see the operational
 * note above) with no other concurrent activity in this same process between acquire and release —
 * under those conditions the pool has at most one connection cycling in and out and will hand the
 * same physical connection back each time, so {@code GET_LOCK} and {@code RELEASE_LOCK} land on the
 * same session in practice. That is a reasonable operating assumption for this tool, not a guarantee
 * the JDBC/connection-pool spec makes — a high-concurrency caller reusing this pattern would need to
 * pin both calls to one explicitly-held {@code Connection}/{@code EntityManager} instead of relying
 * on pool reuse.
 */
@Component
@ConditionalOnProperty(prefix = "ops", name = "provision-super-admin", havingValue = "true")
public class ProvisionSuperAdminRunner implements ApplicationRunner {

    /** Floor only — this is an ops bootstrap tool for a human operator, not a public-facing signup form. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * F-0650 fix: MySQL named-lock key guarding the count-then-insert critical section in {@link
     * #provisionAdmin}. Fixed/global on purpose — this tool only ever bootstraps ONE table
     * ({@code admin_users}), so there is nothing to parameterize the key by.
     */
    private static final String BOOTSTRAP_LOCK_NAME = "influora_provision_super_admin";

    /**
     * F-0650 fix: how long a second concurrent invocation waits on {@code GET_LOCK} for the first
     * to finish before giving up. Generous on purpose — this is a one-shot ops script run by a
     * human, not a hot path; the normal case is "lock is free immediately" and this ceiling only
     * matters for the deliberately-concurrent race this fix closes.
     */
    private static final int LOCK_WAIT_TIMEOUT_SECONDS = 30;

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final AdminMfaSecretCipher mfaSecretCipher;
    private final ConfigurableApplicationContext applicationContext;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ProvisionSuperAdminRunner(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            TotpService totpService,
            AdminMfaSecretCipher mfaSecretCipher,
            ConfigurableApplicationContext applicationContext,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.mfaSecretCipher = mfaSecretCipher;
        this.applicationContext = applicationContext;
        this.entityManager = entityManager;
        // F-0650 fix: explicit TransactionTemplate demarcation (EmailWorker's existing idiom in
        // this codebase), NOT @Transactional — provision() calls provisionAdmin() via
        // self-invocation, which bypasses Spring's proxy and would make an annotation silently
        // no-op.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = provision();
        System.exit(SpringApplication.exit(applicationContext, () -> exitCode));
    }

    /** Package-private so the test can drive the same logic without the process-exit side effect. */
    int provision() {
        String email = trimToNull(System.getenv("ADMIN_BOOTSTRAP_EMAIL"));
        String password = System.getenv("ADMIN_BOOTSTRAP_PASSWORD");

        if (email == null || password == null || password.isBlank()) {
            System.err.println(
                    "[provision-super-admin] ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD must both"
                            + " be set in the environment. Aborting — no row created.");
            return 1;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            System.err.println(
                    "[provision-super-admin] ADMIN_BOOTSTRAP_PASSWORD must be at least "
                            + MIN_PASSWORD_LENGTH
                            + " characters. Aborting — no row created.");
            return 1;
        }
        return provisionAdmin(email, password);
    }

    /**
     * Does the actual row creation — split out from {@link #provision()} so tests can drive it
     * directly without mutating process environment variables (not reliably possible from within
     * the JVM). {@link #provision()} is the only caller in production; it has already validated
     * {@code email}/{@code password} by the time this runs.
     *
     * <p>F-0650 fix (corrected 2026-09-04 — see the class javadoc's "Correction" section): the
     * named-lock acquire/release now wrap the ENTIRE {@code transactionTemplate.execute(...)} call
     * below, not just its callback body. {@link TransactionTemplate#execute} is synchronous — it
     * does not return until the transaction has actually committed (or rolled back) — so releasing
     * the lock only in the {@code finally} AFTER that call returns is what makes the lock genuinely
     * cover the durable-commit window, closing the F-0650 race for real. The check-then-insert
     * critical section itself lives in {@link #provisionAdminInTransaction}, which holds no lock of
     * its own — by the time it runs, the lock is already held and will not be released until this
     * whole method returns.
     */
    int provisionAdmin(String email, String password) {
        if (!acquireBootstrapLock()) {
            System.err.println(
                    "[provision-super-admin] Could not acquire the provisioning lock within "
                            + LOCK_WAIT_TIMEOUT_SECONDS
                            + "s — another provisioning run appears to be stuck holding it."
                            + " Aborting — no row created.");
            return 1;
        }
        try {
            Integer exitCode =
                    transactionTemplate.execute(status -> provisionAdminInTransaction(email, password));
            // TransactionCallback's contract permits a null return; this callback never returns
            // null, but guard anyway rather than risk an NPE unboxing a hypothetical null into
            // `int`.
            return exitCode != null ? exitCode : 1;
        } finally {
            // Runs only after transactionTemplate.execute() has returned — which, per its
            // synchronous contract, means only after COMMIT (or ROLLBACK) has actually completed.
            // This is the crux of the F-0650 correction: the lock now covers the real commit, not
            // merely the callback body. See the class javadoc's "Correction" section.
            releaseBootstrapLock();
        }
    }

    /**
     * The check-then-insert critical section, run as the body of the {@link TransactionTemplate}
     * callback {@link #provisionAdmin} opens. Holds no lock of its own — by the time this runs,
     * {@link #provisionAdmin} has already acquired {@link #BOOTSTRAP_LOCK_NAME} and will not
     * release it until this transaction has fully committed (see {@link #provisionAdmin}'s javadoc).
     */
    private int provisionAdminInTransaction(String email, String password) {
        long existing = adminUserRepository.count();
        if (existing > 0) {
            System.err.println(
                    "[provision-super-admin] admin_users already has "
                            + existing
                            + " row(s) — this script only bootstraps the very FIRST SUPER_ADMIN."
                            + " Use the /admin/auth/mfa/reset/{targetAdminId} endpoint to recover an"
                            + " existing admin instead. Aborting — no row created.");
            return 1;
        }

        AdminUser admin =
                AdminUser.create(
                        Ulids.newUlid(), email, passwordEncoder.encode(password), AdminRole.SUPER_ADMIN);

        // Pre-enroll MFA using the SAME TOTP + encryption machinery AdminAuthService uses, so the
        // row is never persisted at mfaEnabled=false (see class javadoc — that state is exactly
        // what caused ADMIN-BOOTSTRAP-0829).
        String secret = totpService.generateSecret();
        String otpAuthUri = totpService.buildOtpAuthUri(admin.getEmail(), secret);
        admin.stageMfaSecret(mfaSecretCipher.encrypt(secret));
        admin.confirmMfa();

        adminUserRepository.save(admin);

        System.out.println("[provision-super-admin] SUPER_ADMIN provisioned:");
        System.out.println("  id:    " + admin.getId());
        System.out.println("  email: " + admin.getEmail());
        System.out.println("  role:  " + admin.getRole());
        System.out.println();
        System.out.println("MFA is already enrolled on this row. Add the secret below to an");
        System.out.println("authenticator app RIGHT NOW — it will never be shown again:");
        System.out.println("  otpauth URI: " + otpAuthUri);
        System.out.println("  raw secret:  " + secret);
        System.out.println();
        System.out.println("Log in with the resulting 6-digit code on the very first attempt.");
        return 0;
    }

    /**
     * F-0650 fix: MySQL advisory ("named") lock — {@code GET_LOCK(name, timeout)} returns {@code 1}
     * once acquired, {@code 0} on timeout, or SQL {@code NULL} on error (e.g. the session was
     * killed while waiting). Chosen over a SERIALIZABLE-isolation transaction and over a dedicated
     * lock table — see the class javadoc's "F-0650 fix" section for why. Called by {@link
     * #provisionAdmin} BEFORE it opens the transaction — see the class javadoc's "Correction"
     * section for why the lock boundary sits outside the transaction, and for the honest limits of
     * that shape.
     */
    private boolean acquireBootstrapLock() {
        Object result =
                entityManager
                        .createNativeQuery("SELECT GET_LOCK(?1, ?2)")
                        .setParameter(1, BOOTSTRAP_LOCK_NAME)
                        .setParameter(2, LOCK_WAIT_TIMEOUT_SECONDS)
                        .getSingleResult();
        return result != null && ((Number) result).intValue() == 1;
    }

    /**
     * Releases the named lock {@link #acquireBootstrapLock} took. Called by {@link #provisionAdmin}
     * in a {@code finally} AFTER {@code transactionTemplate.execute(...)} has returned — i.e. only
     * once the transaction has actually committed (or rolled back), never while it is still open.
     * See the class javadoc's "Correction" section for why this ordering — not merely "inside a
     * try/finally" — is what closes the F-0650 race, and for the connection-affinity assumption
     * this relies on now that acquire/release run outside any Spring-managed transaction.
     */
    private void releaseBootstrapLock() {
        entityManager.createNativeQuery("SELECT RELEASE_LOCK(?1)").setParameter(1, BOOTSTRAP_LOCK_NAME).getSingleResult();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
