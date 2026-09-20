package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- pins the four migration files' text against the
 * invariants CREDITS-SPEC.md §2 calls out as build-time risks that neither {@code
 * ddl-auto=validate} nor a Mockito unit test would ever see:
 *
 * <ul>
 *   <li>the pack seed's {@code credits} values are TENTHS (500/1000/3000), not the creator-facing
 *       50/100/300 -- amendment A1's own warning: "getting this one row wrong ships a pack that
 *       grants a tenth of what it sold, and no compile or unit test would see it";
 *   <li>the ledger's signed amount column is named {@code delta}, not {@code amount};
 *   <li>every table carries {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci}
 *       and no {@code CHAR(n)} column (rule 4).
 * </ul>
 *
 * <p>This is a text-pinning test, not a DB-execution test -- there is no Docker on this machine
 * to boot these migrations against real MySQL (see {@code CreatorAiCreditRepositoryH2Test} for
 * the H2-executed proof of the repository queries against the equivalent Hibernate-generated
 * schema). A future edit that silently reverts the amendment fails THIS test instead of shipping
 * unnoticed, exactly the failure mode amendment A1 itself warns about.
 */
class CreatorCreditMigrationShapeTest {

    private static String readMigration(String filename) {
        String path = "db/migration/" + filename;
        try (InputStream in =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                fail("migration file not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("failed reading " + path + ": " + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("creator_ai_credits: FK to users, both weekly-search columns, no CHAR(n)")
    void creatorAiCreditsShape() {
        String sql = readMigration("V20260912100000__creator_ai_credits.sql");

        assertTrue(
                sql.matches("(?s).*creator_user_id\\s+VARCHAR\\(26\\) NOT NULL PRIMARY KEY.*"),
                "creator_user_id must be the VARCHAR(26) primary key: " + sql);
        assertTrue(sql.contains("REFERENCES users(id)"), "must FK to users(id)");
        assertTrue(sql.contains("free_searches_used"), "amendment A4 weekly counter column missing");
        assertTrue(sql.contains("free_search_week_start"), "amendment A4 weekly counter column missing");
        assertTrue(
                sql.contains("free_search_week_start DATE NULL")
                        || sql.contains("free_search_week_start  DATE NULL"),
                "free_search_week_start must be nullable -- NULL is load-bearing for tryClaimFreeSearch");
        assertTrue(sql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
        assertFalse(containsPlainChar(sql), "no plain CHAR(n) column allowed (rule 4)");
    }

    /** True if the SQL declares a plain {@code CHAR(n)} column, as opposed to {@code VARCHAR(n)}. */
    private static boolean containsPlainChar(String sql) {
        for (String line : sql.split("\n")) {
            String withoutVarchar = line.replace("VARCHAR(", "");
            if (withoutVarchar.contains(" CHAR(")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("creator_credit_ledger: the signed column is `delta`, not `amount`; unique key covers bucket")
    void creatorCreditLedgerShape() {
        String sql = readMigration("V20260912100100__creator_credit_ledger.sql");

        assertTrue(sql.contains("delta              INT NOT NULL"), "signed amount column must be named delta");
        assertFalse(
                sql.replaceAll("(?i)--.*", "").matches("(?s).*\\bamount\\s+INT.*"),
                "ledger must never have an `amount` column -- PLAN.md's own step 1 note flags this exact mistake");
        assertTrue(
                sql.contains(
                        "UNIQUE KEY uk_creator_credit_ledger_ref (creator_user_id, reason, bucket, reference_id)"),
                "unique key must include bucket -- one turn can debit both buckets (two rows)");
        assertTrue(sql.contains("reference_id       VARCHAR(64) NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
    }

    @Test
    @DisplayName("creator_credit_packs: seeded credits are TENTHS (500/1000/3000), not 50/100/300")
    void creatorCreditPacksSeedIsTenths() {
        String sql = readMigration("V20260912100200__creator_credit_packs.sql");

        assertTrue(
                sql.contains("'STARTER',  'Starter',   500, 14900"),
                "Starter pack must seed 500 tenths (50.0 credits), not 50 -- amendment A1's exact failure mode");
        assertTrue(
                sql.contains("'STANDARD', 'Standard', 1000, 24900"),
                "Standard pack must seed 1000 tenths (100.0 credits), not 100");
        assertTrue(
                sql.contains("'POWER',    'Power',    3000, 64900"),
                "Power pack must seed 3000 tenths (300.0 credits), not 300");
        assertFalse(sql.contains("'01K4CRPACK"), "spec's illustrative placeholder ids must be replaced with real ULIDs");
        assertTrue(sql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
    }

    @Test
    @DisplayName("creator_credit_orders: FKs to users and packs, razorpay_order_id is nullable (no `nullable=false` trap)")
    void creatorCreditOrdersShape() {
        String sql = readMigration("V20260912100300__creator_credit_orders.sql");

        assertTrue(sql.contains("REFERENCES users(id)"));
        assertTrue(sql.contains("REFERENCES creator_credit_packs(id)"));
        assertTrue(
                sql.contains("razorpay_order_id    VARCHAR(64) NULL"),
                "razorpay_order_id must be NULL-able -- MySQL allows many NULLs under a UNIQUE key,"
                        + " and the entity must not declare nullable=false here (Priya's correction §2.5(c))");
        assertTrue(sql.contains("UNIQUE KEY uk_creator_credit_orders_rzp (razorpay_order_id)"));
        assertTrue(sql.contains("credits              INT NOT NULL"), "credits snapshot column must exist, TENTHS");
        assertTrue(sql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
    }
}
