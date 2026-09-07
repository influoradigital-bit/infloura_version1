package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.influora.common.Ulids;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.service.meera.MeeraSessionService;
import com.influora.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gate for F-0751 — a CREATOR Meera conversation must actually PERSIST against a real schema.
 *
 * <p><b>Why this class exists rather than an assertion added to an existing one.</b> {@link
 * MeeraCreatorPhaseABootValidationTest} already boots a real Spring context against real MySQL for
 * this exact feature slice, and it still did not catch F-0751: it asserts that tables and columns
 * <em>exist</em>, which they did. The defect was that {@code V12__ai_conversations_messages.sql:11}
 * constrained {@code workspace_id} to {@code workspaces(id)} while the creator path writes a
 * {@code users.id} there ({@code MeeraSessionService.java:197-198}), so every creator session died
 * with a foreign-key violation surfaced as {@code 409 DATA_INTEGRITY_VIOLATION}. Schema-shape
 * assertions cannot see that. Only performing the feature's own write can.
 *
 * <p><b>The falsification.</b> Run this class against the schema as it stood before
 * {@code V20260907120000__ai_conversations_tenant_type.sql} and {@link
 * #creatorSessionPersistsAgainstRealSchema()} fails with exactly the production error:
 *
 * <pre>
 *   Cannot add or update a child row: a foreign key constraint fails
 *   (`influora`.`ai_conversations`, CONSTRAINT `fk_conv_workspace`
 *    FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`))
 * </pre>
 *
 * A test that has only ever been run against the fixed schema proves nothing; this one is
 * documented so the next person can reproduce the red.
 *
 * <p><b>Skipped, not failed, when Docker is unavailable</b> — inherited from {@link
 * AbstractIntegrationTest}'s {@code DockerAvailableCondition}. That is a real gap rather than a
 * convenience: every local build here reports {@code Skipped: 15}, so this class proves nothing on
 * a developer machine. The merge gate is therefore a CI run observing {@code Skipped: 0} for this
 * class specifically — a green local build is not evidence that it ran.
 */
class CreatorConversationPersistIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeeraSessionService sessionService;

    private String creatorUserId;
    private String creatorProfileId;

    @BeforeEach
    void seedCreator() {
        creatorUserId = Ulids.newUlid();
        creatorProfileId = Ulids.newUlid();
        jdbcTemplate.update("INSERT INTO users (id, user_type) VALUES (?, 'CREATOR')", creatorUserId);
        jdbcTemplate.update(
                "INSERT INTO creator_profiles (id, user_id, display_name) VALUES (?, ?, ?)",
                creatorProfileId,
                creatorUserId,
                "F0751 Gate Creator");
    }

    @Test
    @DisplayName(
            "F-0751: startOrResumeForCreator persists a conversation for a creator who has no"
                    + " workspace -- the write that 409'd on production")
    void creatorSessionPersistsAgainstRealSchema() {
        AiConversation conversation =
                sessionService.startOrResumeForCreator(
                        creatorUserId, creatorUserId, "F0751 Gate Creator", "en-IN");

        assertThat(conversation).isNotNull();
        assertThat(conversation.getWorkspaceId()).isEqualTo(creatorUserId);
        assertThat(conversation.getTenantType()).isEqualTo(ConversationTenantType.CREATOR);

        // Read it back through SQL, not the returned object: the object would look correct even if
        // the row never landed, which is the failure mode being guarded.
        String persistedTenantType =
                jdbcTemplate.queryForObject(
                        "SELECT tenant_type FROM ai_conversations WHERE id = ?",
                        String.class,
                        conversation.getId());
        assertThat(persistedTenantType).isEqualTo("CREATOR");
    }

    @Test
    @DisplayName(
            "F-0751: a CREATOR row whose workspace_id is not its started_by is rejected by"
                    + " ck_conv_creator_tenant_is_starter")
    void creatorTenantKeyMustEqualStartedBy() {
        // Dropping fk_conv_workspace gave up the database's guarantee that the tenant key names a
        // real row. This CHECK is what buys it back: for a CREATOR row the key must equal
        // started_by, which fk_conv_user already constrains to users(id). If this assertion stops
        // holding, creator rows are no longer referentially checked by anything.
        String otherUserId = Ulids.newUlid();
        jdbcTemplate.update("INSERT INTO users (id, user_type) VALUES (?, 'CREATOR')", otherUserId);

        // NOT DataIntegrityViolationException. Verified against real MySQL 8: a CHECK violation is
        // error 3819 / SQL state HY000, which Spring maps to UncategorizedSQLException. Asserting
        // the constraint NAME is both accurate and more specific -- it proves THIS constraint
        // fired, not merely that some write failed. See F-0754: production code that catches
        // DataIntegrityViolationException does NOT catch a CHECK violation.
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO ai_conversations"
                                                + " (id, workspace_id, tenant_type, started_by, status, created_at)"
                                                + " VALUES (?, ?, 'CREATOR', ?, 'ACTIVE', NOW())",
                                        Ulids.newUlid(),
                                        otherUserId,
                                        creatorUserId))
                .hasMessageContaining("ck_conv_creator_tenant_is_starter");
    }

    @Test
    @DisplayName("F-0751: tenant_type has NO database default -- an INSERT that omits it fails")
    void tenantTypeHasNoDefault() {
        // The migration drops the DEFAULT after backfilling. A permanent default is the same shape
        // as the defect this gate exists for: an unset field that quietly means something wrong. A
        // creator row that forgot the discriminator would be indistinguishable from a brand row in
        // exactly the DPDP export and deletion path V74 exists to serve.
        // The schema-level guarantee, which is deterministic and independent of server settings:
        // the migration's MODIFY COLUMN must have removed the DEFAULT the ADD COLUMN needed for
        // the backfill.
        String columnDefault =
                jdbcTemplate.queryForObject(
                        "SELECT column_default FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND table_name = 'ai_conversations'"
                                + " AND column_name = 'tenant_type'",
                        String.class);
        assertThat(columnDefault).as("tenant_type must carry no DEFAULT after the migration").isNull();

        // Whether omitting the column then FAILS is a server-mode question, not a schema question.
        // Asserted explicitly so a container that silently differs from production is visible here
        // rather than making this gate weaker than it reads. Production is STRICT_TRANS_TABLES
        // (verified on 150.241.245.242, MySQL 8.4, 2026-09-07).
        String sqlMode = jdbcTemplate.queryForObject("SELECT @@SESSION.sql_mode", String.class);
        assertThat(sqlMode)
                .as("this gate only means what it says under strict mode, which production uses")
                .contains("STRICT_TRANS_TABLES");

        // F-0754 — removing the DEFAULT does NOT make this fail on its own: MySQL lands the first
        // enum value ('WORKSPACE') for an omitted NOT NULL ENUM even under STRICT_TRANS_TABLES,
        // measured here before ck_conv_workspace_tenant_is_not_starter existed. That constraint is
        // what turns the silent mislabel into a rejection, because a real brand row can never have
        // workspace_id = started_by.
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO ai_conversations"
                                                + " (id, workspace_id, started_by, status, created_at)"
                                                + " VALUES (?, ?, ?, 'ACTIVE', NOW())",
                                        Ulids.newUlid(),
                                        creatorUserId,
                                        creatorUserId))
                .as("a creator INSERT that omits tenant_type must be rejected, not silently"
                        + " persisted as a WORKSPACE row")
                .hasMessageContaining("ck_conv_workspace_tenant_is_not_starter");
    }

    @Test
    @DisplayName("F-0751: a WORKSPACE row is unaffected -- the brand path still writes normally")
    void brandConversationStillPersists() {
        // Dropping a foreign key is the kind of change that can quietly break the audience it was
        // not aimed at. This asserts the brand write still works after the migration.
        String workspaceId = Ulids.newUlid();
        String brandUserId = Ulids.newUlid();
        jdbcTemplate.update("INSERT INTO users (id, user_type) VALUES (?, 'BRAND')", brandUserId);
        jdbcTemplate.update(
                "INSERT INTO workspaces (id, name, slug, type) VALUES (?, ?, ?, 'BRAND')",
                workspaceId,
                "F0751 Gate Brand",
                "f0751-gate-brand-" + workspaceId.toLowerCase());

        assertThatCode(() -> sessionService.startOrResume(workspaceId, brandUserId))
                .doesNotThrowAnyException();

        String tenantType =
                jdbcTemplate.queryForObject(
                        "SELECT tenant_type FROM ai_conversations WHERE workspace_id = ?",
                        String.class,
                        workspaceId);
        assertThat(tenantType).isEqualTo("WORKSPACE");
    }
}
