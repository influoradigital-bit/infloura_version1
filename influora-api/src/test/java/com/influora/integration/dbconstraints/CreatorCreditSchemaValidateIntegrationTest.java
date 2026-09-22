package com.influora.integration.dbconstraints;

import static org.assertj.core.api.Assertions.assertThat;

import com.influora.domain.entity.CreatorVoiceSpeak;
import com.influora.repository.CreatorCreditPackRepository;
import com.influora.repository.CreatorVoiceSpeakRepository;
import com.influora.testsupport.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md §12, A39) — "one {@code ddl-auto=validate} boot against
 * real MySQL 8 with all 7 migrations", guarding against the documented H2 trap: H2 (used by every
 * {@code @DataJpaTest} in this feature, e.g. {@code CreatorCreditServiceTest}) does not enforce
 * {@code VARCHAR} lengths, silently accepts a column shape H2's own dialect maps loosely, and does
 * not always agree with MySQL/InnoDB on collation, {@code CHECK} constraints or unique-key
 * enforcement. A schema that passes every H2 unit test can still fail to boot, or boot with a
 * subtly different shape, against the real engine. This class follows the exact pattern {@code
 * MeeraCreatorPhaseABootValidationTest} established: a real {@code @SpringBootTest}, real Flyway
 * migration run, real {@code spring.jpa.hibernate.ddl-auto=validate} (application.yml's committed
 * default — never overridden here), against a real, throwaway, containerized MySQL 8.
 *
 * <p><b>Why reaching the body of the test at all is already most of the proof.</b> Spring Boot
 * makes the {@code entityManagerFactory} bean depend on Flyway, and {@code ddl-auto=validate} runs
 * as part of that same bean's initialization — so if any of the 7 {@code creator_credit_*}/{@code
 * creator_voice_speaks} migrations failed to apply, OR if any of the 7 new entities
 * ({@code CreatorCreditAccount}, {@code CreatorCreditGrant}, {@code CreatorCreditLedgerEntry},
 * {@code CreatorCreditWelcomeClaim}, {@code CreatorCreditPack}, {@code CreatorCreditOrder}, {@code
 * CreatorVoiceSpeak}) disagreed with the live schema Hibernate validates against (a missing column,
 * a length mismatch, a type Hibernate cannot map), context refresh would abort before {@code
 * @Autowired JdbcTemplate} could ever be injected — this test would ERROR, not FAIL, and never
 * reach its first assertion. The assertions below are the positive, falsifiable half: they prove
 * specifically that these 7 tables (not just "some 7 migrations") exist and are queryable through
 * both raw JDBC and the real JPA repositories/entities, which a mere "context loaded" assertion
 * would not pin down on its own (a context can boot successfully while these particular migrations
 * are, e.g., accidentally omitted from the migration path in a future refactor).
 *
 * <p><b>Falsification check (do this before trusting a green run):</b> temporarily rename or delete
 * one of the {@code V20260921110000}-{@code V20260921110600} migration files, or add an extra
 * mapped field to one of the 7 entities with no matching column — {@link
 * #bootsAgainstMysql()} must fail (most likely by erroring at context refresh, before its own
 * assertions run at all).
 *
 * <p><b>[READ BEFORE TRUSTING A GREEN OR SKIPPED RUN LOCALLY]</b> Inherited from {@link
 * AbstractIntegrationTest}: SKIPPED (not failed) wherever the Docker daemon is unreachable, which is
 * this repo's Windows sandbox. Written and compiled, NOT run, in that environment.
 */
class CreatorCreditSchemaValidateIntegrationTest extends AbstractIntegrationTest {

    private static final String[] CREATOR_CREDIT_MIGRATIONS = {
        "20260921110000",
        "20260921110100",
        "20260921110200",
        "20260921110300",
        "20260921110400",
        "20260921110500",
        "20260921110600"
    };

    private static final String[] CREATOR_CREDIT_TABLES = {
        "creator_credit_accounts",
        "creator_credit_grants",
        "creator_credit_ledger",
        "creator_credit_welcome_claims",
        "creator_credit_packs",
        "creator_credit_orders",
        "creator_voice_speaks"
    };

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CreatorCreditPackRepository packRepository;
    @Autowired private CreatorVoiceSpeakRepository voiceSpeakRepository;

    @Test
    @DisplayName(
            "A39: a real Spring context boots with spring.jpa.hibernate.ddl-auto=validate against a"
                    + " real MySQL 8 with all 7 creator-credit / creator-voice-speaks migrations applied"
                    + " -- SPEC.md §3's exact 7-migration list, not the 6 an earlier review found")
    void bootsAgainstMysql() {
        // Reaching this line already proves Flyway ran every migration cleanly AND
        // ddl-auto=validate passed for every entity in the codebase, including the 7 new ones --
        // either failure aborts context refresh before @Autowired fields are populated at all.

        // 1) All 7 creator-credit / creator-voice-speaks migrations are present in
        // flyway_schema_history and succeeded -- named individually so a future contributor who
        // deletes/renames ONE of the 7 (leaving the other 6, per the round-1 review finding) fails
        // this exact assertion instead of silently passing on "some migrations ran".
        for (String version : CREATOR_CREDIT_MIGRATIONS) {
            Integer applied =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = true",
                            Integer.class,
                            version);
            assertThat(applied)
                    .as("migration V%s (creator credits) must be present and successful in"
                            + " flyway_schema_history", version)
                    .isEqualTo(1);
        }

        // 2) All 7 tables actually exist in the live MySQL schema -- the table-level counterpart to
        // (1), independent of whether Flyway's own bookkeeping row could somehow be right while the
        // DDL itself did not land (defense in depth, same shape as
        // MeeraCreatorPhaseABootValidationTest's own table-existence checks).
        for (String table : CREATOR_CREDIT_TABLES) {
            Integer tableExists =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                                    + " AND table_name = ?",
                            Integer.class,
                            table);
            assertThat(tableExists).as("table %s should exist", table).isEqualTo(1);
        }

        // 3) Round-trip through the REAL JPA repositories/entities against the REAL MySQL schema --
        // not just "ddl-auto=validate didn't throw", but an actual read/write succeeding, which is
        // the thing H2-backed @DataJpaTest classes (e.g. CreatorCreditServiceTest) cannot prove
        // about the live engine on their own.
        assertThat(packRepository.findByCodeAndActiveTrue("PACK_60"))
                .as("the seeded PACK_60 row (V20260921110400) must be readable through"
                        + " CreatorCreditPackRepository against the real schema")
                .hasValueSatisfying(
                        pack -> {
                            assertThat(pack.getCredits()).isEqualTo(60);
                            assertThat(pack.getPricePaise()).isEqualTo(24900);
                            assertThat(pack.isGstInclusive()).isTrue();
                            assertThat(pack.isActive()).isTrue();
                        });

        // 4) CreatorVoiceSpeak specifically -- the 7th migration/entity an earlier review found
        // missing (only 6 of 7 landed). A composite-@EmbeddedId entity is exactly the shape most
        // likely to validate fine against H2's looser dialect while disagreeing with MySQL, so this
        // performs a REAL insert + read against the live table rather than trusting ddl-auto=validate
        // alone.
        String probeCreatorUserId = "01SCHEMAPROBEUSER0000A";
        String probeTurnId = "01SCHEMAPROBETURN00001";
        jdbcTemplate.update("DELETE FROM creator_voice_speaks WHERE creator_user_id = ?", probeCreatorUserId);
        try {
            CreatorVoiceSpeak row = CreatorVoiceSpeak.newRow(probeCreatorUserId, probeTurnId);
            row.tryIncrement(3);
            voiceSpeakRepository.saveAndFlush(row);

            List<CreatorVoiceSpeak> reread =
                    jdbcTemplate.query(
                            "SELECT speak_count FROM creator_voice_speaks WHERE creator_user_id = ? AND"
                                    + " turn_id = ?",
                            (rs, rowNum) -> {
                                CreatorVoiceSpeak s = CreatorVoiceSpeak.newRow(probeCreatorUserId, probeTurnId);
                                for (int i = 0; i < rs.getInt("speak_count"); i++) {
                                    s.tryIncrement(3);
                                }
                                return s;
                            },
                            probeCreatorUserId,
                            probeTurnId);
            assertThat(reread).hasSize(1);
            assertThat(reread.get(0).getSpeakCount()).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM creator_voice_speaks WHERE creator_user_id = ?", probeCreatorUserId);
        }
    }
}
