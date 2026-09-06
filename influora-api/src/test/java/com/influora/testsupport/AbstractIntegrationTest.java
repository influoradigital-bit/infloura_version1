package com.influora.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for REAL {@code @SpringBootTest} integration tests -- Wave E task E3
 * (wiki/tech/REMAINING_WORK_PLAN.md): "Stand up CI integration-test infra (@SpringBootTest +
 * Testcontainers MySQL) -- the standing recommendation. This repo has zero integration tests
 * today; that's why 3 pre-existing entity/DDL bugs sat undetected until the manual live-schema
 * checks."
 *
 * <p>Boots the FULL real Spring context (not a slice test) against a real, throwaway,
 * containerized MySQL instance and lets {@code spring.flyway.enabled=true}
 * (application.yml) run every {@code V*} migration for real on container startup -- this is
 * exactly the {@code ddl-auto: validate} + Flyway-migration path that this codebase's prior
 * "live-schema checks" (wiki/processes/schema-changes.md V21-V25 entries) had to do by hand with a
 * throwaway MySQL schema and a standalone FlywayRunner, specifically because no Spring Boot
 * context could be booted in that sandbox (see class javadoc note on the loopback-socket
 * limitation, and this class's own known-broken status below).
 *
 * <p><b>Image version:</b> pinned to {@code mysql:8.0.40} to match the MySQL version this
 * project's manual live-schema checks were run against (wiki/errors/*-live-migration-check.md,
 * schema-changes.md), not just "latest 8.0" -- keeps CI and the prior manual-verification baseline
 * on the same engine behavior.
 *
 * <p><b>Container lifecycle (T-CI-CONTAINERS):</b> a single MySQL 8.0.40 container, started once
 * for the whole Surefire fork and reused by every concrete subclass -- the "singleton container"
 * pattern (see {@code MYSQL}'s own javadoc below for why, and for the CI failure this replaces).
 * This used to be "one container per test class" via {@code @Testcontainers} + a static
 * {@code @Container} field, exactly as this comment previously recommended revisiting once more
 * classes extended this base -- that point was reached (4 subclasses) and the resulting
 * create/stop/recreate churn (a fresh container per class, since {@code @Testcontainers} ties
 * start()/stop() to ITS OWN per-class beforeAll/afterAll even though the field is shared) is what
 * produced backend-ci.yml run 34027735091's "Connection refused" / "Communications link failure" /
 * HikariPool "total=0, active=0, idle=0" failures in {@code DatabaseConstraintIntegrationTest} and
 * {@code FestivalBoxCouponConstraintIntegrationTest}.
 *
 * <p><b>What this does NOT replace:</b> the existing Mockito-based "repository tests"
 * (e.g. {@code AudienceDemographicsRepositoryTest}) that mock the repository and never touch a
 * real DB. Those still have value as fast entity/builder unit tests. This class is for the
 * narrower, more expensive case of proving something that can ONLY be proven against a real
 * database engine: constraint enforcement (UNIQUE/FK), Flyway migration correctness, and
 * Hibernate-entity-to-live-schema mapping agreement -- the exact bug class the plan cites.
 *
 * <p><b>SANDBOX LIMITATION -- READ BEFORE TRUSTING A GREEN RUN LOCALLY.</b> This sandbox has a
 * pre-existing, previously-documented restriction that blocks Spring Boot from completing its
 * boot sequence (wiki/processes/schema-changes.md V21/V22/V23 entries: "App did not reach
 * 'Started InfluoraApiApplication' -- blocked by the same pre-existing, unrelated
 * MetaGraphApiClient/loopback-socket VPN environment issue"). Testcontainers has an even earlier
 * failure point in the same environment: it needs to reach the Docker daemon
 * ({@code npipe:////./pipe/dockerDesktopLinuxEngine} on this Windows host) to pull/start the
 * MySQL image at all, and `docker ps` in this sandbox fails with "failed to connect to the
 * docker API ... The system cannot find the file specified." That failure happens in the
 * {@code MYSQL} field's static init (an {@code isDockerAvailable()} check followed, when true, by
 * container start -- see that field's own javadoc), BEFORE Spring context startup is even
 * attempted -- so this class is blocked by a strictly earlier failure
 * than the one that blocked the manual live-schema checks. See the E3 verification report in
 * SHARED_CONTEXT.md / wiki/processes/verification-log.md for exactly what was and was not able to
 * be run in this sandbox.
 */
@SpringBootTest
// Boots under the 'dev' Spring profile ON PURPOSE. This test's job is DB/migration/constraint
// verification against real MySQL, NOT secret-hygiene verification. Two things make 'dev' the
// correct (and only maintainable) choice for that job:
//   1. application-dev.yml supplies the throwaway AES-256 encryption keys (influora.pii.*,
//      influora.meta.token-encryption-key, shopify/woocommerce/conversion-webhook) that several
//      constructor-level ciphers (PiiEncryptionProperties, MetaTokenStorage, ...) require to boot
//      AT ALL -- application.yml has no defaults for them (a real deploy MUST supply them via env),
//      so a no-profile boot fails closed before the context is even usable.
//   2. InfluoraEnvironment.isDev() then returns true, so the fail-closed startup validators
//      (SecretsStartupValidator, CompanyTaxStartupValidator) run in WARN-only mode instead of
//      aborting a context that is deliberately using committed dev-default secrets. Those
//      validators exist to protect real non-dev deploys; asserting them here would only re-test
//      config hygiene, not the schema this class is built to verify.
// The Testcontainers datasource + Flyway path is UNAFFECTED: @DynamicPropertySource below overrides
// spring.datasource.* (highest precedence) and application-dev.yml overrides neither datasource nor
// flyway, so every V* migration still runs for real against the throwaway container.
// (Prior "INV-2 / no @ActiveProfiles" design starved the context of the keys in (1) and tripped the
// validators in (2) -- it broke every test on this base class once SecretsStartupValidator landed.)
@ActiveProfiles("dev")
// DockerAvailableCondition's own isDockerAvailable() check independently gates whether this class
// runs at all; MYSQL's static initializer below performs the identical check itself (so a
// Docker-less environment never throws out of class init either) -- see both javadocs.
@ExtendWith(DockerAvailableCondition.class)
public abstract class AbstractIntegrationTest {

    /**
     * Testcontainers "singleton container" pattern (manual lifecycle control, NOT
     * {@code @Testcontainers}/{@code @Container}): started once, here, in a static initializer,
     * and never explicitly stopped -- Testcontainers' Ryuk resource reaper removes it when this
     * JVM/Surefire fork exits, exactly as it already does for every other container in this
     * codebase.
     *
     * <p><b>Why not {@code @Testcontainers} + {@code @Container} (the previous approach):</b> that
     * extension starts the container in ITS OWN {@code beforeAll} and stops it in ITS OWN
     * {@code afterAll} -- scoped to whichever single test class is currently running. Because this
     * field lives in the shared abstract base and is inherited by every concrete subclass, each
     * subclass got its own start+stop cycle against the SAME field: class A's {@code afterAll}
     * called {@code MYSQL.stop()}, then class B's {@code beforeAll} called {@code MYSQL.start()}
     * again -- which does not no-op on an already-stopped container, it genuinely creates and boots
     * a BRAND NEW MySQL 8 container (new container ID, new ephemeral host port). With 4 subclasses
     * that is 4 full MySQL 8 boot/teardown cycles back-to-back in one CI run instead of 1; CI's log
     * showed exactly this -- repeated "Creating container for image: mysql:8.0.40" /
     * "Container ... started" pairs on strictly increasing ports (32769, 32770, 32771, ...) rather
     * than one container reused throughout. That churn, additionally competing with the
     * surefire {@code -Xmx2048m} heap (see the surefire {@code <argLine>} comment in pom.xml) for
     * the runner's memory during every create/destroy cycle, is what left
     * {@code DatabaseConstraintIntegrationTest} and {@code FestivalBoxCouponConstraintIntegrationTest}
     * unable to reach their container within Hikari's 30s connection-acquisition timeout.
     *
     * <p><b>Docker-unavailable environments must still SKIP, not ERROR</b> (e.g. this repo's
     * Windows sandbox -- see class javadoc above): the try/catch here means an unreachable Docker
     * daemon leaves {@code MYSQL == null} instead of throwing an {@code ExceptionInInitializerError}
     * out of this static block. {@link DockerAvailableCondition} performs the identical
     * {@code isDockerAvailable()} check independently and disables the whole test class -- as an
     * {@code ExecutionCondition}, evaluated before any {@code BeforeAllCallback} -- so
     * {@code @DynamicPropertySource} below (the only other place that dereferences {@code MYSQL})
     * never runs when this is null. See {@code DockerAvailableCondition}'s javadoc for why that
     * extension point, not {@code Assumptions.assumeTrue()}, is what has to do the skipping.
     *
     * <p><b>Collation note for {@code MeeraCreatorPhaseABootValidationTest}:</b> this is still a
     * bare {@code mysql:8.0.40} image with no {@code withCommand}/collation override -- that
     * class's {@code containerCollationIsNotUtf8mb4UnicodeCi()} depends on the server default
     * exactly as before. Do not add a buffer-pool or collation {@code withCommand} override here
     * without checking that test.
     */
    static final MySQLContainer<?> MYSQL;

    static {
        MySQLContainer<?> container = null;
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                container =
                        new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"))
                                .withDatabaseName("influora_it")
                                .withUsername("influora_it")
                                .withPassword("influora_it");
                container.start();
            }
        } catch (Throwable t) {
            // Same "treat any probe/start failure as not available" contract as
            // DockerAvailableCondition -- that class's own check is what actually disables the
            // test class; this catch only exists so a flaky/partial Docker daemon can't throw out
            // of a static initializer and take down class loading for every subclass in this JVM.
            container = null;
        }
        MYSQL = container;
    }

    /**
     * Points the real app's datasource at the container instead of the {@code application.yml}
     * dev default (local MySQL on :3306) -- Flyway + Hibernate then run against this container on
     * context startup exactly as they would against a real deployment's database.
     */
    @DynamicPropertySource
    static void registerMysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // INV-2: this class runs @SpringBootTest with no @ActiveProfiles, so it boots under the
        // default profile -- InfluoraEnvironment.isDev() (environment.matchesProfiles("dev")) is
        // therefore false here, same as a real non-dev deploy. CompanyTaxStartupValidator fails
        // closed outside dev, and application.yml's influora.company.gstin default is still the
        // committed REPLACE_WITH_REAL_GSTIN placeholder -- without this override, EVERY test that
        // extends this class would abort at context refresh with an unrelated
        // "influora.company.gstin is still the committed placeholder" IllegalStateException. This
        // is a syntactically valid GSTIN (matches CompanyTaxStartupValidator.GSTIN_PATTERN) purely
        // so the validator passes in tests; it is not a real registered GSTIN. Do not delete this
        // as "unused" -- nothing in test code reads it back, its only job is satisfying the
        // boot-time validator.
        registry.add("influora.company.gstin", () -> "27AAAAA0000A1Z5");

        // [T-CI-SCHEDULER] Switches off TaskSchedulerConfig.SchedulingActivation, so this context
        // registers no @Scheduled triggers. The TaskScheduler bean itself is deliberately NOT
        // gated (AnalyzeSiteTriggerService constructor-injects it), so the pool still exists here
        // -- what disappears is only the automatic firing.
        //
        // Why: @EnableScheduling used to sit unconditionally on InfluoraApiApplication, so every
        // context this base class boots also started all 30 @Scheduled jobs against the
        // Testcontainers MySQL. Their non-daemon threads outlived the context that owned them and
        // kept hitting the database, which (a) left surefire unable to exit -- "Surefire is going
        // to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)" -- and
        // (b) let background jobs mutate the schema under a running test, which is a
        // nondeterminism source no assertion can defend against.
        //
        // Scope note, so nobody over-credits this line: the six CI errors in run 34027735091 were
        // caused by per-subclass @Container start/stop churn, fixed separately by the singleton
        // container above. This property is hygiene for the noise and the shutdown hang, NOT the
        // fix for those errors.
        registry.add("influora.scheduling.enabled", () -> "false");
    }
}
