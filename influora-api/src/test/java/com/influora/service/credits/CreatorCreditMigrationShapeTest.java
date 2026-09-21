package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A35, design-kabir.md K-09, K-30) — asserts on the actual
 * migration SQL text (classpath {@code db/migration/*}), not a rebuilt-in-Java shadow of it, so a
 * change to the real migration file is what this test reacts to. Deliberately NOT a {@code
 * @DataJpaTest}/Flyway-against-H2 test: these migrations use MySQL-only syntax (
 * {@code ENGINE=InnoDB}, {@code DATETIME(6)}, {@code NOW(6)}) that H2 cannot run faithfully (the
 * documented H2-does-not-enforce-VARCHAR-length trap this repo's memory already flags), and the
 * REAL boot-against-MySQL8 proof is {@code CreatorCreditSchemaValidateIntegrationTest} (A39) — a Testcontainers
 * IT this class does not duplicate. This class needs no database at all, so it runs identically
 * with Docker up or down.
 */
class CreatorCreditMigrationShapeTest {

    private static String readMigration(String filename) throws IOException {
        try (InputStream in =
                Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("db/migration/" + filename)) {
            if (in == null) {
                throw new IOException("migration not found on classpath: db/migration/" + filename);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName(
            "A35: exactly one active pack, PACK_60, 60 credits, 24900 paise, gst_inclusive; ledger"
                    + " reference_id has no DEFAULT; razorpay_payment_id is UNIQUE")
    void seedAndConstraints() throws IOException {
        String packsSql = readMigration("V20260921110400__creator_credit_packs.sql");

        // -- exactly one seed row is inserted into creator_credit_packs.
        List<String> packInserts = allMatches(packsSql, "INSERT INTO creator_credit_packs[\\s\\S]*?;");
        assertEquals(1, packInserts.size(), "exactly one seed row must be inserted into creator_credit_packs");
        String insert = packInserts.get(0);

        // -- that row is PACK_60 / 60 credits / 24900 paise / gst_inclusive TRUE / active TRUE.
        // (column order per the CREATE TABLE: id, code, credits, price_paise, gst_inclusive,
        // active, sort_order, created_at, updated_at.)
        assertTrue(insert.contains("'PACK_60'"), "the seed pack's code must be PACK_60");
        Matcher valuesRow =
                Pattern.compile(
                                "VALUES\\s*\\('[^']*',\\s*'PACK_60',\\s*60,\\s*24900,\\s*TRUE,\\s*TRUE",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(insert);
        assertTrue(
                valuesRow.find(),
                "the seed row must carry credits=60, price_paise=24900, gst_inclusive=TRUE,"
                        + " active=TRUE in that column order — found: "
                        + insert);

        String ledgerSql = readMigration("V20260921110200__creator_credit_ledger.sql");

        // -- K-30: reference_id is NOT NULL with NO DEFAULT (the old-schema collision bug this
        // migration exists to avoid re-introducing — a `DEFAULT ''` lets two legitimate
        // no-reference entries collide on the unique key, or silently drop the second).
        Matcher referenceColumn =
                Pattern.compile("reference_id\\s+VARCHAR\\(64\\)\\s+NOT NULL\\s*,", Pattern.CASE_INSENSITIVE)
                        .matcher(ledgerSql);
        assertTrue(
                referenceColumn.find(),
                "creator_credit_ledger.reference_id must be declared exactly `VARCHAR(64) NOT NULL,`"
                        + " with no DEFAULT clause on the same line");
        assertFalse(
                Pattern.compile("reference_id[^,]*DEFAULT", Pattern.CASE_INSENSITIVE).matcher(ledgerSql).find(),
                "K-30: reference_id must never carry a DEFAULT (not even DEFAULT '') — the old-schema"
                        + " collision bug this migration exists to avoid");

        String ordersSql = readMigration("V20260921110500__creator_credit_orders.sql");

        // -- K-10: razorpay_payment_id is UNIQUE — the same captured payment can never credit two
        // orders, even under a webhook/verify/reconciliation race.
        assertTrue(
                Pattern.compile("UNIQUE\\s+KEY\\s+\\w+\\s*\\(razorpay_payment_id\\)", Pattern.CASE_INSENSITIVE)
                        .matcher(ordersSql)
                        .find(),
                "creator_credit_orders.razorpay_payment_id must carry a UNIQUE key (K-10)");
        // Belt-and-braces: the column itself must be declared NULL-able (a payment id is not known
        // at order-creation time) but the table must still enforce the UNIQUE constraint above.
        assertTrue(
                ordersSql.contains("razorpay_payment_id      VARCHAR(64) NULL")
                        || Pattern.compile("razorpay_payment_id\\s+VARCHAR\\(64\\)\\s+NULL").matcher(ordersSql).find(),
                "razorpay_payment_id must be VARCHAR(64) NULL (not known until payment capture)");
    }

    private static List<String> allMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }
}
