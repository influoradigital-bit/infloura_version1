package com.influora.config;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [SEC: Kabir C-18] Dev-only re-seed of the 5 demo discovery creators.
 *
 * <p>These accounts used to be inserted by the Flyway migration {@code
 * V7__seed_discoverable_creators.sql}, which runs in <b>every</b> environment — so production shipped
 * with 5 ACTIVE, email-verified "creator" accounts that all share the bcrypt of {@code Password@123},
 * a password written in plaintext in the committed SQL. Anyone could log into prod as a verified
 * creator, and brands saw fabricated inventory.
 *
 * <p>The applied V7 migration is deliberately left untouched (rewriting an applied migration would
 * change its Flyway checksum and break {@code validate} on every existing deployment). Instead:
 * <ul>
 *   <li>a new forward migration {@code V72__remove_seed_creators.sql} deletes those 5 accounts in
 *       every environment (including prod) — that is the security fix, and
 *   <li>this runner re-inserts them <b>only when the {@code dev} profile is active AND the explicit
 *       opt-in flag {@code influora.dev.seed-creators=true} is set</b>, so local development can still
 *       have creators to browse in discovery after V72 has removed them — while an internet-reachable
 *       box left on the {@code dev} profile (e.g. the IP-only test deploy) does <b>not</b> silently
 *       re-create the {@code Password@123} accounts the migration just removed. [SEC: Kabir residual —
 *       the migration alone did not close C-18 on a dev-profiled exposed box because this runner ran
 *       unconditionally under {@code dev}; the flag makes re-seeding a deliberate local choice.]
 * </ul>
 *
 * <p>Runs as an {@link ApplicationRunner}, i.e. after Flyway has finished (Flyway runs during
 * context initialization, well before {@code ApplicationRunner}s fire), so ordering is: V7 inserts →
 * V72 deletes → this runner re-inserts in dev. Every statement is {@code INSERT IGNORE} keyed on the
 * fixed {@code 01SEED*} primary keys, so the runner is idempotent across restarts and races
 * harmlessly with a fresh V7 (the rows simply already exist). MySQL-only ({@code INSERT IGNORE} /
 * {@code NOW()}); this profile's datasource is MySQL, matching V7's own dialect.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "influora.dev.seed-creators", havingValue = "true")
public class DevSeedCreatorsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedCreatorsRunner.class);

    // bcrypt of "Password@123" — identical to the value V7 used. Acceptable ONLY because this runs
    // exclusively under the dev profile; V72 removes it from every real environment.
    private static final String DEMO_PASSWORD_HASH =
            "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.G2oXeiKPaPaS0q8a";

    private final JdbcTemplate jdbcTemplate;

    public DevSeedCreatorsRunner(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        int users = seedUsers();
        seedCreatorProfiles();
        seedPlatformStats();
        log.info(
                "[dev-seed] ensured {} demo discovery creators are present (idempotent re-seed of the"
                        + " accounts V72 removes from real environments)",
                users);
    }

    private int seedUsers() {
        String sql =
                "INSERT IGNORE INTO users (id, email, password_hash, user_type, status, email_verified,"
                        + " phone_verified, onboarding_completed, display_name, first_name, last_name,"
                        + " timezone, created_at, updated_at) VALUES (?, ?, ?, 'CREATOR', 'ACTIVE', TRUE,"
                        + " FALSE, TRUE, ?, ?, ?, 'Asia/Kolkata', NOW(), NOW())";
        int count = 0;
        count +=
                jdbcTemplate.update(
                        sql, "01SEED00000000000000000001", "priya.creates@demo.influora.com",
                        DEMO_PASSWORD_HASH, "Priya Sharma", "Priya", "Sharma");
        count +=
                jdbcTemplate.update(
                        sql, "01SEED00000000000000000002", "arjun.fitness@demo.influora.com",
                        DEMO_PASSWORD_HASH, "Arjun Mehta", "Arjun", "Mehta");
        count +=
                jdbcTemplate.update(
                        sql, "01SEED00000000000000000003", "maya.beauty@demo.influora.com",
                        DEMO_PASSWORD_HASH, "Maya Kapoor", "Maya", "Kapoor");
        count +=
                jdbcTemplate.update(
                        sql, "01SEED00000000000000000004", "rohit.tech@demo.influora.com",
                        DEMO_PASSWORD_HASH, "Rohit Verma", "Rohit", "Verma");
        count +=
                jdbcTemplate.update(
                        sql, "01SEED00000000000000000005", "neha.food@demo.influora.com",
                        DEMO_PASSWORD_HASH, "Neha Gupta", "Neha", "Gupta");
        return count;
    }

    private void seedCreatorProfiles() {
        String sql =
                "INSERT IGNORE INTO creator_profiles (id, user_id, display_name, bio, avatar_url, city,"
                        + " categories, languages, rate_min, rate_max, currency, is_verified,"
                        + " is_discoverable, engagement_rate, total_followers, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 'INR', ?, TRUE, ?, ?, NOW(), NOW())";
        jdbcTemplate.update(
                sql, "01SEEDCR00000000000000001", "01SEED00000000000000000001", "Priya Sharma",
                "Fashion & lifestyle creator from Mumbai.", "Mumbai",
                "[\"fashion\",\"beauty\",\"lifestyle\"]", "[\"hindi\",\"english\"]", 35000, 85000, true,
                4.80, 185000);
        jdbcTemplate.update(
                sql, "01SEEDCR00000000000000002", "01SEED00000000000000000002", "Arjun Mehta",
                "Fitness & wellness content.", "Delhi",
                "[\"fitness\",\"health\"]", "[\"hindi\",\"english\"]", 25000, 60000, true, 5.20, 220000);
        jdbcTemplate.update(
                sql, "01SEEDCR00000000000000003", "01SEED00000000000000000003", "Maya Kapoor",
                "Beauty tutorials & skincare reviews.", "Bangalore",
                "[\"beauty\",\"skincare\"]", "[\"english\",\"kannada\"]", 40000, 95000, false, 4.10,
                142000);
        jdbcTemplate.update(
                sql, "01SEEDCR00000000000000004", "01SEED00000000000000000004", "Rohit Verma",
                "Tech reviews & unboxing.", "Pune",
                "[\"tech\",\"gadgets\"]", "[\"hindi\",\"english\"]", 30000, 75000, true, 3.90, 98000);
        jdbcTemplate.update(
                sql, "01SEEDCR00000000000000005", "01SEED00000000000000000005", "Neha Gupta",
                "Food & travel reels across India.", "Hyderabad",
                "[\"food\",\"travel\"]", "[\"hindi\",\"telugu\"]", 20000, 50000, false, 6.10, 310000);
    }

    private void seedPlatformStats() {
        String sql =
                "INSERT IGNORE INTO platform_stats (id, creator_profile_id, platform, handle, followers,"
                        + " engagement_rate, is_verified, profile_url, created_at, updated_at) VALUES (?, ?,"
                        + " ?, ?, ?, ?, ?, NULL, NOW(), NOW())";
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000001", "01SEEDCR00000000000000001", "INSTAGRAM",
                "@priya.fashion", 150000, 5.20, true);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000002", "01SEEDCR00000000000000001", "YOUTUBE",
                "PriyaStyleDiaries", 35000, 3.80, false);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000003", "01SEEDCR00000000000000002", "INSTAGRAM",
                "@arjun.fit", 180000, 5.50, true);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000004", "01SEEDCR00000000000000002", "YOUTUBE",
                "ArjunFitness", 40000, 4.90, true);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000005", "01SEEDCR00000000000000003", "INSTAGRAM",
                "@maya.glow", 120000, 4.30, false);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000006", "01SEEDCR00000000000000004", "INSTAGRAM",
                "@rohit.tech", 75000, 4.00, true);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000007", "01SEEDCR00000000000000004", "YOUTUBE",
                "RohitReviews", 23000, 3.50, false);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000008", "01SEEDCR00000000000000005", "INSTAGRAM",
                "@neha.eats", 280000, 6.20, false);
        jdbcTemplate.update(
                sql, "01SEEDPL00000000000000009", "01SEEDCR00000000000000005", "YOUTUBE",
                "NehaFoodTrail", 30000, 5.80, false);
    }
}
