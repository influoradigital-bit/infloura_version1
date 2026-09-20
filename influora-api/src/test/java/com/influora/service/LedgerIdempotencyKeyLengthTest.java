package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.influora.domain.entity.Payout;
import com.influora.domain.entity.WalletTransaction;
import jakarta.persistence.Column;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [EV-014 regression gate] Every idempotency key that reaches {@link WalletLedgerService#post} has
 * to fit {@code wallet_transactions.idempotency_key} — and some of them additionally have to fit
 * {@code payouts.idempotency_key} and the RazorpayX {@code reference_id}. Nothing in the type
 * system says so, and nothing in the unit suite noticed when the creator-withdrawal key grew to 80
 * characters, because the H2 database these tests run against is created by {@code ddl-auto} and
 * H2 does not enforce {@code VARCHAR} length the way MySQL in strict mode does. That combination is
 * exactly how a defect that breaks EVERY creator withdrawal against the real database sat in a
 * green test suite.
 *
 * <p>So this test does not exercise the database at all. It asserts three separate things, and each
 * one fails independently:
 *
 * <ol>
 *   <li>{@link #columnWidthsAreWhatTheConstantsClaim()} — the widths in {@link
 *       LedgerIdempotencyKeys} are read back out of the Flyway migration text AND out of the JPA
 *       {@code @Column(length)}, so the budget this test enforces is the real schema's, not a
 *       number someone typed. If the migration is widened and the entity is not (or vice versa),
 *       this fails before anything else has a chance to.
 *   <li>{@link #everyLedgerPostCallerIsAccountedFor()} — the set of classes that call {@code
 *       WalletLedgerService.post} is pinned. A NEW caller fails this test with an instruction to
 *       add its key shape to the table below. This is the part that stops a future caller
 *       reintroducing EV-014: you cannot post to the ledger from a new place without stating how
 *       long your key can get.
 *   <li>{@link #everyLedgerKeyFitsEveryColumnItIsWrittenTo()} — the worst case of each key shape,
 *       measured against those widths, including {@link WalletLedgerService}'s {@code ":D"}/{@code
 *       ":C"} leg suffix.
 * </ol>
 *
 * <p>Writing (3) is what surfaced two further instances of the same defect beyond the reported one:
 * {@code AdminFinanceService#recordManualPayout} put the raw {@code Idempotency-Key} header into
 * both columns unbounded, and {@code PayoutReconciliationService}'s reversal key concatenated a
 * gateway-supplied payout id. Both are fixed; both are in the table.
 */
class LedgerIdempotencyKeyLengthTest {

    /** ULIDs are 26 characters ({@code com.influora.common.Ulids}) and every id in this table is one. */
    private static final int ULID = 26;

    /**
     * The longest {@code Idempotency-Key} header value a caller could plausibly send. Deliberately
     * absurd rather than realistic: the point of hashing is that the number below cannot matter, so
     * the test asserts against a value no client would ever send. Raise it and nothing should move.
     */
    private static final String ADVERSARIAL_CLIENT_KEY = "x".repeat(4096);

    private static final String SAMPLE_ULID = "0".repeat(ULID);

    /**
     * Every class in {@code src/main/java} that calls {@code WalletLedgerService.post}. Pinned on
     * purpose — see the class javadoc. Adding a caller without adding its key shape to {@link
     * #keyShapes()} is the mistake this list exists to catch.
     */
    private static final Set<String> KNOWN_LEDGER_CALLERS =
            new TreeSet<>(
                    Set.of(
                            "AdminFinanceService",
                            "AffiliateSettlementWriter",
                            "BrandCampaignFeeService",
                            "CreatorWithdrawalOps",
                            "LedgerEscrowBackend",
                            "PayoutReconciliationService",
                            "PayoutService",
                            "PlatformFeeService",
                            "WalletService",
                            "WalletTopUpService"));

    /**
     * One key shape: where it is built, its worst-case value, and which length-bounded destinations
     * it actually reaches. {@code payoutsColumn} and {@code razorpayReferenceId} are true only for
     * the keys that really are written to {@code payouts.idempotency_key} / sent to RazorpayX as
     * {@code reference_id} — over-claiming them would make this test pass for the wrong reason.
     */
    private record KeyShape(
            String where, String worstCase, boolean payoutsColumn, boolean razorpayReferenceId) {}

    private static List<KeyShape> keyShapes() {
        List<KeyShape> shapes = new ArrayList<>();

        // --- CreatorWithdrawalOps#reserveWithdrawal (the reported EV-014 defect) --------------
        // Was "creator-withdraw:" + userId + ":" + <client header> = 80 chars for a UUID header.
        shapes.add(
                new KeyShape(
                        "CreatorWithdrawalOps (WalletService#requestCreatorWithdrawal)",
                        LedgerIdempotencyKeys.creatorWithdrawal(SAMPLE_ULID, ADVERSARIAL_CLIENT_KEY),
                        true,
                        true));

        // --- AdminFinanceService#recordManualPayout (second instance) -------------------------
        // Was the Idempotency-Key header verbatim, into both columns, with no bound at all.
        shapes.add(
                new KeyShape(
                        "AdminFinanceService#recordManualPayout",
                        LedgerIdempotencyKeys.manualPayout(SAMPLE_ULID, ADVERSARIAL_CLIENT_KEY),
                        true,
                        false));

        // --- PayoutService#doQueuePayout ------------------------------------------------------
        shapes.add(new KeyShape("PayoutService#doQueuePayout debit", "payout-debit:" + SAMPLE_ULID, false, false));
        // The queue key itself is both the payouts row key and the RazorpayX reference_id.
        shapes.add(new KeyShape("PayoutService#queuePayout gateway key", "payout:" + SAMPLE_ULID, true, true));

        // --- PayoutReconciliationService ------------------------------------------------------
        String attemptKey = PayoutReconciliationService.buildRetryAttemptKey(longestPossiblePayout());
        shapes.add(
                new KeyShape(
                        "PayoutReconciliationService#retryDebitKey",
                        PayoutReconciliationService.retryDebitKey(attemptKey),
                        false,
                        false));
        shapes.add(
                new KeyShape(
                        "PayoutReconciliationService#retryReversalKey",
                        PayoutReconciliationService.retryReversalKey(attemptKey),
                        false,
                        false));
        shapes.add(
                new KeyShape(
                        "PayoutReconciliationService#retryGatewayKey",
                        PayoutReconciliationService.retryGatewayKey(attemptKey),
                        false,
                        true));
        // Third instance: keyed on a gateway-supplied id, which is only bounded by its own
        // VARCHAR(64) column — 16 + 64 + 2 = 82 before this was digested.
        shapes.add(
                new KeyShape(
                        "PayoutReconciliationService#reversalKey",
                        PayoutReconciliationService.reversalKey("z".repeat(64)),
                        false,
                        false));

        // --- EscrowService -> LedgerEscrowBackend ---------------------------------------------
        // Every escrow key is "<prefix>:<escrowHoldId>"; the longest prefix wins.
        for (String prefix :
                List.of(
                        "escrow-fund:",
                        "release:",
                        "refund:",
                        "dispute-release:",
                        "dispute-refund:",
                        "dispute-split-release:",
                        "dispute-split-refund:")) {
            shapes.add(new KeyShape("EscrowService " + prefix, prefix + SAMPLE_ULID, false, false));
        }

        // --- the remaining single-shape callers ------------------------------------------------
        shapes.add(new KeyShape("PlatformFeeService", "release-fee:" + SAMPLE_ULID, false, false));
        shapes.add(new KeyShape("BrandCampaignFeeService", "brand-fee-publish:" + SAMPLE_ULID, false, false));
        shapes.add(new KeyShape("WalletTopUpService", WalletTopUpService.RECEIPT_PREFIX + SAMPLE_ULID, false, false));
        // AffiliateSettlementWriter posts AffiliateEarning#getIdempotencyKey, whose only producer
        // is AffiliateEarningsService#deriveIdempotencyKey = DERIVED_KEY_PREFIX + redemptionId.
        // NOTE: affiliate_earnings.idempotency_key is VARCHAR(100), i.e. WIDER than the ledger
        // column it is copied into — so the producer, not the column, is what can be trusted here.
        shapes.add(new KeyShape("AffiliateSettlementWriter", "affearn:" + SAMPLE_ULID, false, false));

        return shapes;
    }

    /**
     * A {@link Payout} whose every field is at its column maximum, so {@code buildRetryAttemptKey}
     * (id + status + updatedAt) is measured at its genuine worst case rather than at a convenient
     * one.
     */
    private static Payout longestPossiblePayout() {
        return Payout.createPending(
                "9".repeat(ULID),
                null,
                SAMPLE_ULID,
                "f".repeat(64),
                new java.math.BigDecimal("1.00"),
                "INR",
                "k".repeat(64),
                java.time.Instant.parse("2026-09-20T12:34:56.789123456Z"));
    }

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the enforced widths are read out of the migration and the entity, not asserted from memory")
    void columnWidthsAreWhatTheConstantsClaim() {
        assertEquals(
                LedgerIdempotencyKeys.WALLET_TRANSACTION_KEY_MAX_LENGTH,
                varcharWidthInMigration("V8__wallet_transactions.sql", "idempotency_key"),
                "V8__wallet_transactions.sql no longer declares idempotency_key at the width"
                        + " LedgerIdempotencyKeys enforces — one of the two has to move");
        assertEquals(
                LedgerIdempotencyKeys.WALLET_TRANSACTION_KEY_MAX_LENGTH,
                columnLength(WalletTransaction.class, "idempotencyKey"),
                "WalletTransaction's @Column(length) disagrees with the migration; the H2 schema"
                        + " these tests run on comes from the entity, so a drift here means the"
                        + " tests stop modelling production");
        assertEquals(
                LedgerIdempotencyKeys.PAYOUT_KEY_MAX_LENGTH,
                varcharWidthInMigration("V48__payouts.sql", "idempotency_key"),
                "V48__payouts.sql no longer declares idempotency_key at the enforced width");
        assertEquals(
                LedgerIdempotencyKeys.PAYOUT_KEY_MAX_LENGTH,
                columnLength(Payout.class, "idempotencyKey"),
                "Payout's @Column(length) disagrees with the migration");
    }

    @Test
    @DisplayName("a new WalletLedgerService.post caller must declare its key shape here")
    void everyLedgerPostCallerIsAccountedFor() {
        Set<String> found = new TreeSet<>();
        Pattern call = Pattern.compile("\\b\\w*[lL]edgerService\\s*\\.\\s*post\\s*\\(");

        try (Stream<Path> files = Files.walk(mainJavaRoot())) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            p -> {
                                String source = stripComments(read(p));
                                if (call.matcher(source).find()) {
                                    String name = p.getFileName().toString();
                                    found.add(name.substring(0, name.length() - ".java".length()));
                                }
                            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Comments are stripped before matching on purpose: a prose mention of
        // "ledgerService.post(...)" inside a javadoc is not a caller, and a gate that counts one is
        // a gate that fails for the wrong reason (and, worse, can be silenced by editing prose).
        assertTrue(found.contains("WalletLedgerService") || !found.isEmpty(), "the scan found nothing at all —"
                + " mainJavaRoot() is probably resolving to the wrong directory, which would make this"
                + " gate vacuous");
        found.remove("WalletLedgerService");

        assertEquals(
                KNOWN_LEDGER_CALLERS,
                found,
                "The set of classes that post to the wallet ledger changed. Every ledger key has to"
                        + " fit wallet_transactions.idempotency_key VARCHAR(64) minus the \":D\"/\":C\""
                        + " leg suffix WalletLedgerService#post appends — that is EV-014, which broke"
                        + " every creator withdrawal. Add the new caller's worst-case key to"
                        + " LedgerIdempotencyKeyLengthTest#keyShapes() and to this set.");
    }

    @Test
    @DisplayName("every ledger key's worst case fits every length-bounded destination it reaches")
    void everyLedgerKeyFitsEveryColumnItIsWrittenTo() {
        int ledgerBudget =
                LedgerIdempotencyKeys.WALLET_TRANSACTION_KEY_MAX_LENGTH
                        - LedgerIdempotencyKeys.LEDGER_LEG_SUFFIX_LENGTH;

        Map<String, String> failures = new LinkedHashMap<>();
        for (KeyShape shape : keyShapes()) {
            int len = shape.worstCase().length();
            if (len > ledgerBudget) {
                failures.put(
                        shape.where(),
                        "key is "
                                + len
                                + " chars; wallet_transactions.idempotency_key allows "
                                + LedgerIdempotencyKeys.WALLET_TRANSACTION_KEY_MAX_LENGTH
                                + " and WalletLedgerService#post appends 2 more, so the budget is "
                                + ledgerBudget);
            }
            if (shape.payoutsColumn() && len > LedgerIdempotencyKeys.PAYOUT_KEY_MAX_LENGTH) {
                failures.merge(
                        shape.where(),
                        "; also exceeds payouts.idempotency_key ("
                                + LedgerIdempotencyKeys.PAYOUT_KEY_MAX_LENGTH
                                + ")",
                        String::concat);
            }
            if (shape.razorpayReferenceId()
                    && len > LedgerIdempotencyKeys.RAZORPAYX_REFERENCE_ID_MAX_LENGTH) {
                failures.merge(
                        shape.where(),
                        "; also exceeds the RazorpayX reference_id limit ("
                                + LedgerIdempotencyKeys.RAZORPAYX_REFERENCE_ID_MAX_LENGTH
                                + ")",
                        String::concat);
            }
        }

        if (!failures.isEmpty()) {
            fail(
                    "Ledger idempotency key(s) too long for the column(s) they are written to:\n"
                            + failures.entrySet().stream()
                                    .map(e -> "  - " + e.getKey() + ": " + e.getValue())
                                    .reduce("", (a, b) -> a + b + "\n"));
        }
    }

    @Test
    @DisplayName("the withdrawal key is scoped per creator, so two creators' identical client keys cannot collide")
    void withdrawalKeyIsScopedPerCreator() {
        String shared = "b3b1c0de-0000-4000-8000-000000000000";
        String a = LedgerIdempotencyKeys.creatorWithdrawal("01HCREATORAAAAAAAAAAAAAAAA", shared);
        String b = LedgerIdempotencyKeys.creatorWithdrawal("01HCREATORBBBBBBBBBBBBBBBB", shared);

        org.junit.jupiter.api.Assertions.assertNotEquals(
                a,
                b,
                "hashing away the user id would let one creator's withdrawal replay another's"
                        + " ledger posting");
        assertEquals(
                a,
                LedgerIdempotencyKeys.creatorWithdrawal("01HCREATORAAAAAAAAAAAAAAAA", shared),
                "the key must be deterministic — replay-by-key and the orphan reaper both recompute"
                        + " it from persisted state");
    }

    // ------------------------------------------------------------------------------------------

    private static Path mainJavaRoot() {
        return moduleRoot().resolve("src/main/java/com/influora");
    }

    private static Path moduleRoot() {
        // Surefire runs with the module directory as the working directory; basedir is set when it
        // does not. Both are checked so this cannot silently scan an empty tree.
        Path base = Paths.get(System.getProperty("basedir", "."));
        return base.toAbsolutePath().normalize();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes block and line comments so a prose mention of a call is not mistaken for one. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static int varcharWidthInMigration(String fileName, String columnName) {
        Path migration = moduleRoot().resolve("src/main/resources/db/migration").resolve(fileName);
        assertTrue(Files.exists(migration), "migration not found: " + migration);
        Matcher m =
                Pattern.compile(
                                "^\\s*" + Pattern.quote(columnName) + "\\s+VARCHAR\\((\\d+)\\)",
                                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
                        .matcher(read(migration));
        assertTrue(m.find(), "no VARCHAR declaration for " + columnName + " in " + fileName);
        return Integer.parseInt(m.group(1));
    }

    private static int columnLength(Class<?> entity, String fieldName) {
        try {
            Field f = entity.getDeclaredField(fieldName);
            Column column = f.getAnnotation(Column.class);
            assertTrue(column != null, fieldName + " on " + entity.getSimpleName() + " has no @Column");
            return column.length();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "field " + fieldName + " no longer exists on " + entity.getSimpleName(), e);
        }
    }
}
