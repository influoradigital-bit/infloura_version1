package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.testsupport.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-MEERA-CREATOR-PHASE-B / B0-17 — the boot gate for Phase B0 Wave 1. Boots a real Spring context
 * against a real, stock MySQL 8 container, lets Flyway run every {@code V*} migration for real
 * (including this wave's four {@code V20260910*} files), and proves {@code
 * spring.jpa.hibernate.ddl-auto=validate} — application.yml's committed default, never overridden
 * here — accepts the four Wave 1 tables against the entities that map them.
 *
 * <p>This is the exact counterpart of {@link MeeraCreatorPhaseABootValidationTest} for Phase B, and
 * it exists for the same reason: nothing else in this repo can prove an entity and its migration
 * agree. A Mockito-backed service test constructs entities in memory and never asks MySQL whether
 * the column exists, so a mis-named, mis-typed or entirely missing column compiles, passes every
 * unit test and every review, and then aborts context refresh at boot with "Schema-validation:
 * missing column". That failure mode is recorded against this codebase already.
 *
 * <p><b>WHAT A PASS HERE MEANS AND WHAT A SKIP DOES NOT.</b> When Docker is unavailable this class
 * is DISABLED, not failed — inherited from {@link AbstractIntegrationTest}'s
 * {@code @ExtendWith(DockerAvailableCondition.class)}; see that class's javadoc for why an
 * {@code Assumptions.assumeTrue} in a {@code @BeforeAll} cannot do the job (the container's own
 * {@code start()} runs in an earlier JUnit 5 phase and throws first). A skipped run proves
 * NOTHING about the schema. On a machine with no Docker daemon, {@code ddl-auto=validate} is
 * backed only by a static entity-vs-migration column diff, which reads the {@code @Column}
 * annotations and the DDL as text and can be defeated by anything MySQL decides at runtime —
 * a type coercion, a collation, a reserved word, a default. Do not record this wave as
 * boot-verified until the SKIPPED count for this class is 0.
 *
 * <p><b>Assertion set (why these and not a bare contextLoads).</b> Reaching any test method at all
 * already proves Flyway migrated cleanly and Hibernate's live-schema validation passed — Spring
 * Boot makes {@code entityManagerFactory} depend on Flyway, so a context that failed either would
 * never have injected {@link JdbcTemplate}. Each method below then pins one specific thing this
 * wave could get wrong, so a future regression names itself instead of arriving as an opaque
 * context-refresh failure.
 */
class MeeraPhaseB0BootValidationTest extends AbstractIntegrationTest {

    /** The four migrations this wave adds, by Flyway version (the digits between {@code V} and {@code __}). */
    private static final List<String> WAVE_1_VERSIONS =
            List.of("20260910100000", "20260910100100", "20260910100300", "20260910100500");

    /** The three tables this wave creates, and the column count each must have. */
    private static final Map<String, Integer> NEW_TABLES =
            Map.of("creator_briefs", 13, "meera_drafts", 16, "deal_offer_history", 9);

    /** The six columns V20260910100000 appends to the existing Phase-A preferences table. */
    private static final List<String> NEW_PREFS_COLUMNS =
            List.of(
                    "rate_card_shareable",
                    "rate_card_json",
                    "negotiation_holdout",
                    "holdout_until",
                    "approved_draft_count",
                    "level_up_prompted_at");

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "boot-validate: the context boots against real MySQL 8, every V* migration through"
                    + " V20260910100500 applies, and spring.jpa.hibernate.ddl-auto=validate accepts the"
                    + " four Phase-B0 Wave 1 tables")
    void contextBootsFlywayAppliesAndDdlAutoValidatePasses() {
        Integer appliedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(appliedCount).isNotNull();
        // 132 V* files on disk at the time of writing (70 numbered V1..V74 plus 62 timestamped).
        // A floor, not an exact count, so a later migration landing does not edit this test.
        assertThat(appliedCount).isGreaterThanOrEqualTo(132);

        // The four Wave 1 migrations specifically -- a floor above would still pass if all four
        // were absent and four unrelated files had landed instead.
        for (String version : WAVE_1_VERSIONS) {
            Integer applied =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = true",
                            Integer.class,
                            version);
            assertThat(applied).as("migration V%s should have applied successfully", version).isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "the three tables this wave creates exist with exactly the column count the migrations"
                    + " declare -- the runtime half of the static entity-vs-migration column diff")
    void newTablesExistWithTheDeclaredColumnCount() {
        NEW_TABLES.forEach(
                (table, expectedColumns) -> {
                    Integer actual =
                            jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM information_schema.columns"
                                            + " WHERE table_schema = DATABASE() AND table_name = ?",
                                    Integer.class,
                                    table);
                    assertThat(actual)
                            .as("table %s should exist with %d columns", table, expectedColumns)
                            .isEqualTo(expectedColumns);
                });
    }

    @Test
    @DisplayName(
            "V20260910100000's six columns are really on creator_agent_preferences -- an ALTER TABLE"
                    + " that silently no-ops is invisible to a table-existence check")
    void phaseBColumnsWereAppendedToCreatorAgentPreferences() {
        for (String column : NEW_PREFS_COLUMNS) {
            Integer present =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.columns"
                                    + " WHERE table_schema = DATABASE()"
                                    + " AND table_name = 'creator_agent_preferences' AND column_name = ?",
                            Integer.class,
                            column);
            assertThat(present)
                    .as("creator_agent_preferences.%s should exist after V20260910100000", column)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "the three new tables are InnoDB/utf8mb4_unicode_ci -- the missing-ENGINE/COLLATE clause"
                    + " that broke V73/V74 and would break the FKs to creator_profiles and collaborations")
    void newTablesUseInnoDbAndTheCodebaseCollation() {
        NEW_TABLES
                .keySet()
                .forEach(
                        table -> {
                            Map<String, Object> row =
                                    jdbcTemplate.queryForMap(
                                            "SELECT engine, table_collation FROM information_schema.tables"
                                                    + " WHERE table_schema = DATABASE() AND table_name = ?",
                                            table);
                            assertThat(row.get("ENGINE")).as("%s engine", table).isEqualTo("InnoDB");
                            assertThat(row.get("TABLE_COLLATION"))
                                    .as(
                                            "%s collation -- must be the codebase collation, not the MySQL 8"
                                                    + " server default utf8mb4_0900_ai_ci",
                                            table)
                                    .isEqualTo("utf8mb4_unicode_ci");
                        });
    }

    @Test
    @DisplayName(
            "deal_offer_history has UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no) --"
                    + " PRIYA-COMPAT-0904 section 7 condition 3, the enforcement half of the"
                    + " count-under-row-lock sequence derivation")
    void dealOfferHistorySequenceUniquenessIsEnforced() {
        // NON_UNIQUE = 0 is the whole assertion: a plain INDEX would serve the same ordered read
        // and enforce nothing, letting a writer outside the collaboration row lock silently write a
        // duplicate sequence_no and corrupt the negotiation ordering forever.
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList(
                        "SELECT column_name, seq_in_index, non_unique FROM information_schema.statistics"
                                + " WHERE table_schema = DATABASE() AND table_name = 'deal_offer_history'"
                                + " AND index_name = 'uk_doh_collab_seq' ORDER BY seq_in_index");

        assertThat(columns).as("uk_doh_collab_seq should exist with two columns").hasSize(2);
        assertThat(columns.get(0).get("COLUMN_NAME")).isEqualTo("collaboration_id");
        assertThat(columns.get(1).get("COLUMN_NAME")).isEqualTo("sequence_no");
        assertThat(columns)
                .as("uk_doh_collab_seq must be UNIQUE, not a plain index")
                .allSatisfy(column -> assertThat(((Number) column.get("NON_UNIQUE")).intValue()).isZero());
    }

    @Test
    @DisplayName(
            "no CHAR(n) on any Wave 1 table (SPEC.md 0.5) -- ddl-auto=validate rejects CHAR against a"
                    + " @Column(length=N) String with 'wrong column type ... found [char]'")
    void waveOneTablesUseNoFixedWidthCharColumns() {
        List<Map<String, Object>> charColumns =
                jdbcTemplate.queryForList(
                        "SELECT table_name, column_name FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND data_type = 'char'"
                                + " AND table_name IN ('creator_briefs', 'meera_drafts', 'deal_offer_history',"
                                + " 'creator_agent_preferences')");
        assertThat(charColumns)
                .as("CHAR columns found -- this is the boot failure V20260718150000 was written to repair")
                .isEmpty();
    }

    /**
     * Goal memory (Meera intelligence v1, spec T28) -- V20260925150000 adds four columns to
     * {@code creator_agent_preferences}. Reaching this method already proves ddl-auto=validate
     * accepted the entity's four new {@code @Column}s against the live table; this pins the exact
     * MySQL types too, because a VARCHAR declared as the wrong width or a TEXT declared as
     * VARCHAR would validate against a differently-written entity and drift silently.
     */
    @Test
    @DisplayName(
            "V20260925150000 applied: creator_agent_preferences has content_goal VARCHAR(20),"
                    + " weekly_time_band VARCHAR(12), equipment TEXT and content_dislikes TEXT, all nullable")
    void goalMemoryColumnsMatchTheEntity() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = true",
                        Integer.class,
                        "20260925150000");
        assertThat(applied).as("migration V20260925150000 should have applied successfully").isEqualTo(1);

        Map<String, String> expectedType =
                Map.of(
                        "content_goal", "varchar(20)",
                        "weekly_time_band", "varchar(12)",
                        "equipment", "text",
                        "content_dislikes", "text");
        expectedType.forEach(
                (column, type) -> {
                    Map<String, Object> row =
                            jdbcTemplate.queryForMap(
                                    "SELECT column_type, is_nullable FROM information_schema.columns"
                                            + " WHERE table_schema = DATABASE()"
                                            + " AND table_name = 'creator_agent_preferences' AND column_name = ?",
                                    column);
                    assertThat(String.valueOf(row.get("COLUMN_TYPE")).toLowerCase(java.util.Locale.ROOT))
                            .as("creator_agent_preferences.%s type", column)
                            .isEqualTo(type);
                    assertThat(row.get("IS_NULLABLE")).as("creator_agent_preferences.%s nullable", column).isEqualTo("YES");
                });
    }
}
