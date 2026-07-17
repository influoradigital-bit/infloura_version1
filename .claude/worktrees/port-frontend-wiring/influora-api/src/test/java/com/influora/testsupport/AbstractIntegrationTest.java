package com.influora.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 * <p><b>Container lifecycle:</b> one container per test class (JUnit 5 {@code @Testcontainers} +
 * static {@code @Container} field), NOT reused across classes -- correctness over speed for a
 * first pass. If integration suite runtime becomes a problem once more classes extend this,
 * revisit with Testcontainers' reusable-container / singleton-container pattern rather than
 * silently sharing mutable state across test classes.
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
 * {@code @Container} field's static init / {@code @Testcontainers} extension, BEFORE Spring
 * context startup is even attempted -- so this class is blocked by a strictly earlier failure
 * than the one that blocked the manual live-schema checks. See the E3 verification report in
 * SHARED_CONTEXT.md / wiki/processes/verification-log.md for exactly what was and was not able to
 * be run in this sandbox.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"))
                    .withDatabaseName("influora_it")
                    .withUsername("influora_it")
                    .withPassword("influora_it");

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
    }
}
