package com.influora.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0965 — runs the REAL migration file V20260919100000 against H2 (MySQL mode) on a minimal copy
 * of the three tables it touches, and checks the backfill applies the same rule as FollowerTotals:
 * verified platforms only; else the imported one (labelled); declared platforms never count;
 * creators with no platform_stats rows are left alone.
 */
class FollowerProvenanceMigrationBackfillTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V20260919100000__platform_stats_source_and_follower_provenance.sql");

    private static List<String> statements(String sql) {
        StringBuilder noComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                noComments.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : noComments.toString().split(";")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    @Test
    @DisplayName("backfill: VERIFIED sums Meta platforms, IMPORTED keeps the import, declared-only drops to 0")
    void backfillAppliesTheFollowerTotalsRule() throws Exception {
        try (Connection c =
                        DriverManager.getConnection("jdbc:h2:mem:f0965_backfill;MODE=MySQL;DB_CLOSE_DELAY=-1");
                Statement st = c.createStatement()) {
            st.execute(
                    "CREATE TABLE creator_profiles (id VARCHAR(26) PRIMARY KEY, total_followers BIGINT NOT NULL"
                            + " DEFAULT 0, engagement_rate DECIMAL(5,2))");
            st.execute(
                    "CREATE TABLE platform_stats (id VARCHAR(26) PRIMARY KEY, creator_profile_id VARCHAR(26) NOT"
                            + " NULL, platform VARCHAR(32) NOT NULL, followers BIGINT NOT NULL DEFAULT 0,"
                            + " engagement_rate DECIMAL(5,2), is_verified BOOLEAN NOT NULL DEFAULT FALSE)");
            st.execute(
                    "CREATE TABLE external_creators (id VARCHAR(26) PRIMARY KEY, linked_creator_profile_id"
                            + " VARCHAR(26) NULL)");
            st.execute(
                    "CREATE TABLE creator_metrics (id VARCHAR(26) PRIMARY KEY, creator_profile_id VARCHAR(26)"
                            + " NOT NULL, platform VARCHAR(20) NOT NULL)");

            // A: Meta Instagram + declared YouTube (old total 912000).
            st.execute("INSERT INTO creator_profiles VALUES ('A', 912000, 9.99)");
            st.execute("INSERT INTO platform_stats VALUES ('a1','A','INSTAGRAM',12000,4.10,TRUE)");
            st.execute("INSERT INTO platform_stats VALUES ('a2','A','YOUTUBE',900000,9.99,FALSE)");
            // B: linked external creator, imported Instagram only.
            st.execute("INSERT INTO creator_profiles VALUES ('B', 184000, 3.20)");
            st.execute("INSERT INTO platform_stats VALUES ('b1','B','INSTAGRAM',184000,3.20,FALSE)");
            st.execute("INSERT INTO external_creators VALUES ('x1','B')");
            // C: declared-only (old total 50000).
            st.execute("INSERT INTO creator_profiles VALUES ('C', 50000, 5.00)");
            st.execute("INSERT INTO platform_stats VALUES ('c1','C','TIKTOK',50000,5.00,FALSE)");
            // E: declared Instagram THEMSELVES (a creator_metrics row exists), linked later: still declared.
            st.execute("INSERT INTO creator_profiles VALUES ('E', 900000, 6.00)");
            st.execute("INSERT INTO platform_stats VALUES ('e1','E','INSTAGRAM',900000,6.00,FALSE)");
            st.execute("INSERT INTO creator_metrics VALUES ('m1','E','INSTAGRAM')");
            st.execute("INSERT INTO external_creators VALUES ('x2','E')");
            // F: linked creator whose unverified row is YOUTUBE (declared): never tagged IMPORTED.
            st.execute("INSERT INTO creator_profiles VALUES ('F', 40000, 3.00)");
            st.execute("INSERT INTO platform_stats VALUES ('f1','F','YOUTUBE',40000,3.00,FALSE)");
            st.execute("INSERT INTO external_creators VALUES ('x3','F')");
            // G: linked creator with an imported Instagram AND a Meta Facebook: VERIFIED, Meta only.
            st.execute("INSERT INTO creator_profiles VALUES ('G', 189000, 3.00)");
            st.execute("INSERT INTO platform_stats VALUES ('g1','G','INSTAGRAM',184000,3.20,FALSE)");
            st.execute("INSERT INTO platform_stats VALUES ('g2','G','FACEBOOK',5000,2.10,TRUE)");
            st.execute("INSERT INTO external_creators VALUES ('x4','G')");
            // H: UNLINKED creator, unverified Instagram with no metrics row: not an import, never counts.
            st.execute("INSERT INTO creator_profiles VALUES ('H', 30000, 4.00)");
            st.execute("INSERT INTO platform_stats VALUES ('h1','H','INSTAGRAM',30000,4.00,FALSE)");
            // D: no platform_stats at all (e.g. a seed row) - must be left untouched.
            st.execute("INSERT INTO creator_profiles VALUES ('D', 7000, 2.50)");

            for (String s : statements(Files.readString(MIGRATION, StandardCharsets.UTF_8))) {
                st.execute(s);
            }

            assertProfile(st, "A", 12000, new BigDecimal("4.10"), "VERIFIED");
            assertProfile(st, "B", 184000, new BigDecimal("3.20"), "IMPORTED");
            assertProfile(st, "C", 0, null, "NONE");
            assertProfile(st, "D", 7000, new BigDecimal("2.50"), "NONE");
            assertProfile(st, "E", 0, null, "NONE");
            assertProfile(st, "F", 0, null, "NONE");
            assertProfile(st, "G", 5000, new BigDecimal("2.10"), "VERIFIED");
            assertProfile(st, "H", 0, null, "NONE");

            assertSource(st, "a1", "META_API");
            assertSource(st, "a2", "CREATOR_REPORTED");
            assertSource(st, "b1", "IMPORTED");
            assertSource(st, "c1", "CREATOR_REPORTED");
            assertSource(st, "e1", "CREATOR_REPORTED");
            assertSource(st, "f1", "CREATOR_REPORTED");
            assertSource(st, "g1", "IMPORTED");
            assertSource(st, "g2", "META_API");
            assertSource(st, "h1", "CREATOR_REPORTED");
        }
    }

    private static void assertProfile(Statement st, String id, long total, BigDecimal rate, String source)
            throws Exception {
        try (ResultSet rs =
                st.executeQuery(
                        "SELECT total_followers, engagement_rate, followers_source FROM creator_profiles WHERE id='"
                                + id + "'")) {
            rs.next();
            assertEquals(total, rs.getLong(1), id + " total_followers");
            if (rate == null) {
                assertNull(rs.getBigDecimal(2), id + " engagement_rate");
            } else {
                assertEquals(0, rate.compareTo(rs.getBigDecimal(2)), id + " engagement_rate");
            }
            assertEquals(source, rs.getString(3), id + " followers_source");
        }
    }

    private static void assertSource(Statement st, String id, String source) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT source FROM platform_stats WHERE id='" + id + "'")) {
            rs.next();
            assertEquals(source, rs.getString(1), id + " source");
        }
    }
}
