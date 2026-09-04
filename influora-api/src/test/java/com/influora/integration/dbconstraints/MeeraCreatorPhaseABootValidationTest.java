package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Priya gate review defect 6 — the exact regression test that would have caught defect 1 (V73/
 * V74's missing {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci} clause)
 * BEFORE it shipped: a real Spring context, real Flyway migration run, real {@code
 * spring.jpa.hibernate.ddl-auto=validate} (application.yml's committed default — never overridden
 * here), against a real MySQL 8 server running its OWN default collation.
 *
 * <p><b>Why this specifically would have caught it:</b> {@code
 * DatabaseConstraintIntegrationTest#contextLoadsAndFlywayMigrationsRunAgainstRealMysql} already
 * proves this same infra boots a real context against real MySQL, but nothing about that test's
 * name or intent calls out the collation hazard — a future contributor has no signal that THIS is
 * the test standing guard over the "MySQL 8 default is `utf8mb4_0900_ai_ci`, not the codebase's
 * `utf8mb4_unicode_ci`" collation-mismatch class of bug. This class exists to make that guarantee
 * explicit and self-documenting, independent of {@code DatabaseConstraintIntegrationTest}'s own
 * (differently scoped) purpose: {@link AbstractIntegrationTest}'s {@code MYSQL} container is a
 * bare {@code mysql:8.0.40} image with NO collation override on the command line, so its server
 * default is exactly the "wrong" (relative to this codebase's migrations) collation the V73/V74
 * bug needed to surface — {@link #containerCollationIsNotUtf8mb4UnicodeCi()} below asserts that
 * fixture property directly, so a future change to {@link AbstractIntegrationTest} that
 * accidentally started pinning the container to {@code utf8mb4_unicode_ci} (silently defeating
 * this whole class's purpose) fails loudly here instead of everyone just trusting the container
 * setup forever.
 *
 * <p>Before the V73/V74 fix, {@link #contextBootsFlywayAppliesAndDdlAutoValidatePasses()} would
 * have failed at Spring context refresh: Flyway aborts the migration with a collation-mismatch FK
 * error on {@code creator_agent_preferences.creator_id -> creator_profiles.id}, which fails the
 * auto-configured {@code entityManagerFactory} bean (Spring Boot makes it depend on Flyway) before
 * {@code ddl-auto=validate} even gets a chance to run — so this single {@code contextLoads}-shaped
 * assertion is suffient to prove both "Flyway migrated cleanly" and "Hibernate's live-schema
 * validation passed" in one shot; a context that failed either would never have booted enough for
 * {@code jdbcTemplate} to be injected at all.
 *
 * <p><b>Skipped, not failed, when Docker is unavailable:</b> inherited from {@link
 * AbstractIntegrationTest}'s {@code @ExtendWith(DockerAvailableCondition.class)} — see that
 * class's javadoc for why an {@code Assumptions.assumeTrue(DockerClientFactory.isDockerAvailable())}
 * in a {@code @BeforeAll} does not work with a static {@code @Container} field (the container's
 * own {@code start()} call happens in an earlier JUnit 5 phase and throws first); {@code
 * DockerAvailableCondition} achieves the identical "skip cleanly, never ERROR" contract via an
 * {@code ExecutionCondition} instead, which is why this class extends {@link
 * AbstractIntegrationTest} rather than hand-rolling its own {@code Assumptions} check.
 */
class MeeraCreatorPhaseABootValidationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "boot-validate: Spring context boots against real MySQL 8, every V* Flyway migration"
                    + " (through V74 / T-MEERA-CREATOR-PHASE-A) applies cleanly, and"
                    + " spring.jpa.hibernate.ddl-auto=validate passes -- this is exactly the failure"
                    + " mode V73/V74's missing ENGINE=InnoDB/COLLATE clause caused before it was fixed")
    void contextBootsFlywayAppliesAndDdlAutoValidatePasses() {
        // Reaching this line at all already proves the context booted: Flyway ran for real
        // (auto-configured to run before the entityManagerFactory bean) and
        // spring.jpa.hibernate.ddl-auto=validate (application.yml's committed default, never
        // overridden by this test) passed Hibernate's live-schema check. Either failing would have
        // aborted context refresh before @Autowired JdbcTemplate could ever be injected.
        Integer appliedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(appliedCount).isNotNull();
        // V1..V74 plus the three T-MEERA-CREATOR-PHASE-A gate-fix timestamped migrations at time of
        // writing -- a floor, not an exact count, so this doesn't need editing every time a new V*
        // file lands.
        assertThat(appliedCount).isGreaterThanOrEqualTo(74);

        // The two tables V73/V74 created exist and are queryable -- the concrete, table-level proof
        // that the collation-mismatch FK failure this class guards against did not occur.
        for (String table : new String[] {"creator_agent_preferences", "meera_creator_conversations"}) {
            Integer tableExists =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                                    + " AND table_name = ?",
                            Integer.class,
                            table);
            assertThat(tableExists).as("table %s should exist", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "fixture sanity: the AbstractIntegrationTest MySQL container's own default collation is"
                    + " NOT utf8mb4_unicode_ci -- so a clean pass above is a real proof against the"
                    + " V73/V74 hazard, not an accident of the container matching the codebase's"
                    + " collation by coincidence")
    void containerCollationIsNotUtf8mb4UnicodeCi() {
        String serverCollation = jdbcTemplate.queryForObject("SELECT @@collation_server", String.class);
        assertThat(serverCollation).isNotEqualTo("utf8mb4_unicode_ci");
    }
}
