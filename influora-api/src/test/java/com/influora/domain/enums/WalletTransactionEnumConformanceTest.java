package com.influora.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [EV-011] Fails when a Java enum persisted into a MySQL {@code ENUM} column has a constant the
 * column does not list.
 *
 * <h2>The bug this exists to make impossible</h2>
 *
 * {@code V8__wallet_transactions.sql:6-12} declared {@code wallet_transactions.type} and {@code
 * .reference_type} as MySQL {@code ENUM}s. [F-0402] later added {@code
 * WalletTransactionType.AFFILIATE_COMMISSION} and {@code TxnReferenceType.AFFILIATE_EARNING} on the
 * Java side and shipped {@code AffiliateSettlementWriter} posting with them — and no migration ever
 * widened the columns. On MySQL every such INSERT failed, the settlement transaction rolled back,
 * and affiliate commission was never credited to anyone. Nothing in the suite caught it: the unit
 * tests that exercise that path mock {@code WalletLedgerService}, and the ones that touch a real
 * database run on H2 with {@code ddl-auto}, where an {@code @Enumerated(EnumType.STRING)} field
 * becomes a plain {@code VARCHAR} that accepts any string. <b>A migration alone is therefore not
 * covered by this module's tests at all</b> — which is exactly why this gate reads the migration
 * FILES rather than a live schema.
 *
 * <h2>Why this is not a vacuous gate</h2>
 *
 * Both sides are derived, never hand-listed:
 *
 * <ul>
 *   <li>The DB side is the real migration chain under {@code src/main/resources/db/migration},
 *       replayed in Flyway version order — {@code CREATE TABLE} column definitions plus every
 *       later {@code ALTER TABLE ... ADD/MODIFY/CHANGE COLUMN}, so a column widened in a later
 *       migration is seen at its final shape and a column retyped AWAY from {@code ENUM} stops
 *       being checked.
 *   <li>The Java side is reflection over the compiled {@code @Entity} classes: every field
 *       annotated {@code @Enumerated(EnumType.STRING)}, resolved to its table via {@code @Table}
 *       and to its column via {@code @Column(name=...)} or, when unnamed, Hibernate's default
 *       camelCase-to-underscores conversion (the case that let an unnamed {@code @Column} slip
 *       through before).
 * </ul>
 *
 * <p>{@link #gateItselfIsNotVacuous()} then asserts the two derivations actually met — a parser
 * that silently matched nothing would make every conformance assertion below trivially true, which
 * is the failure mode that let two "blocking" schema invariants compare {@code ""} to {@code ""}
 * for months. It pins a floor on tables parsed, enum columns found and matched (table, column)
 * pairs, and names {@code wallet_transactions.type}/{@code .reference_type} explicitly.
 *
 * <p>No Spring context and no database — same discipline as {@code EntitlementConformanceTest}
 * (every {@code @SpringBootTest} in this module needs Docker/Testcontainers).
 */
class WalletTransactionEnumConformanceTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path COMPILED_ENTITY_DIR =
            Path.of("target/classes/com/influora/domain/entity");
    private static final String ENTITY_PACKAGE = "com.influora.domain.entity";

    /** table -> column -> the ENUM members that column allows after the whole chain is replayed. */
    private static Map<String, Map<String, Set<String>>> dbEnumColumns;

    /** Every table the chain creates, whether or not it has an ENUM column. */
    private static Set<String> dbTables;

    /** One {@code @Enumerated(EnumType.STRING)} field, already resolved to its table and column. */
    private record PersistedEnum(
            String entityClass, String fieldName, String table, String column, Class<?> enumType) {
        Set<String> javaConstants() {
            Set<String> out = new LinkedHashSet<>();
            for (Object c : enumType.getEnumConstants()) {
                out.add(((Enum<?>) c).name());
            }
            return out;
        }
    }

    private static List<PersistedEnum> persistedEnums;

    @BeforeAll
    static void parseOnce() throws Exception {
        MigrationSchema schema = MigrationSchema.replay(MIGRATION_DIR);
        dbEnumColumns = schema.enumColumns();
        dbTables = schema.tables();
        persistedEnums = scanPersistedEnums();
    }

    // ------------------------------------------------------------------
    // THE GATE
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "EV-011: every @Enumerated(EnumType.STRING) constant mapped to a MySQL ENUM column is"
                    + " listed in that column's ENUM after the whole migration chain is replayed")
    void everyJavaEnumConstantExistsInItsDbEnumColumn() {
        List<String> drift = new ArrayList<>();

        for (PersistedEnum pe : persistedEnums) {
            Set<String> allowed = allowedValues(pe);
            if (allowed == null) {
                // Not a DB ENUM column (VARCHAR, or a table this chain does not create) — this
                // gate has nothing to say about it.
                continue;
            }
            Set<String> missing = new TreeSet<>(pe.javaConstants());
            missing.removeAll(allowed);
            if (!missing.isEmpty()) {
                drift.add(
                        pe.entityClass()
                                + "."
                                + pe.fieldName()
                                + " ("
                                + pe.enumType().getSimpleName()
                                + ") -> "
                                + pe.table()
                                + "."
                                + pe.column()
                                + " is missing "
                                + missing
                                + "; the column allows "
                                + new TreeSet<>(allowed));
            }
        }

        assertThat(drift)
                .as(
                        "A Java enum constant is persisted into a MySQL ENUM column that does not"
                            + " list it. On MySQL in strict mode the INSERT fails with \"Data"
                            + " truncated for column\" and the surrounding transaction rolls back;"
                            + " H2 with ddl-auto (this module's DB-backed tests) cannot reproduce"
                            + " it, because it emits VARCHAR. Add an append-only ALTER TABLE ..."
                            + " MODIFY COLUMN migration — append new members at the END of the"
                            + " ENUM, never reorder, because MySQL stores the ordinal. If a"
                            + " narrowing is DELIBERATE, add it to KNOWN_EXCEPTIONS with its"
                            + " justification; this assertion is exact-match, so a fixed entry left"
                            + " in that list also turns this red.")
                .containsExactlyInAnyOrderElementsOf(KNOWN_EXCEPTIONS.keySet());
    }

    /**
     * Exact-match, not a suppression list. Every entry is a (table, column) whose Java enum is
     * WIDER than the DB column TODAY, with the reason it is allowed to be. Because {@link
     * #everyJavaEnumConstantExistsInItsDbEnumColumn()} asserts the live drift equals this set
     * EXACTLY, the list cannot rot in either direction: a new drift is red, and an entry that has
     * since been fixed is ALSO red until it is deleted from here. That is the property an
     * {@code isEmpty()}-plus-{@code @Disabled} or a "contains at least" allow-list does not have.
     *
     * <p>Both entries below were found by this gate on the run that introduced it (2026-09-20,
     * EV-011); neither is in the affiliate settlement chain, so neither is fixed here.
     */
    private static final Map<String, String> KNOWN_EXCEPTIONS =
            Map.of(
                    "SupportTicket.userType (UserType) -> support_tickets.user_type is missing"
                            + " [ADMIN]; the column allows [BRAND, CREATOR]",
                    "DELIBERATE, not a defect. SupportTicket.java:19 states userType is"
                        + " BRAND/CREATOR only, and V34__admin_tables.sql:6-10 explains why: admin"
                        + " operators live in their own admin_users table, not users, so an ADMIN"
                        + " can never be the subject of a support ticket. UserType is a shared"
                        + " three-member enum reused on a column that deliberately accepts a"
                        + " subset. Widening the column would make an impossible state"
                        + " representable.",
                    "MeeraToolCall.toolName (MeeraToolName) -> meera_tool_calls.tool_name is"
                            + " missing [get_campaign_performance]; the column allows"
                            + " [calculate_budget, confirm_launch, create_campaign, request_payment,"
                            + " show_creators]",
                    "A REAL DEFECT of exactly the EV-011 shape, in the Meera subsystem, NOT in this"
                        + " lane. MeeraToolName.get_campaign_performance (Phase 2 item 2.2) was"
                        + " added to the Java enum and registered in ToolCallValidator, but"
                        + " V14__ai_credits_tool_calls.sql:19-20 was never widened. It is LATENT"
                        + " rather than live only because GetCampaignPerformanceExecutor does not"
                        + " currently persist a MeeraToolCall row (unlike"
                        + " CreateCampaignExecutor/RequestPaymentExecutor/ConfirmLaunchExecutor,"
                        + " which do) — the first code that audits an R-tier tool call will hit"
                        + " it. Needs its own append-only migration; delete this entry when that"
                        + " lands.");

    @Test
    @DisplayName(
            "EV-011 regression pin: wallet_transactions.type lists AFFILIATE_COMMISSION and"
                    + " .reference_type lists AFFILIATE_EARNING")
    void walletTransactionsCarriesTheAffiliateMembers() {
        Set<String> types = dbEnumColumns.get("wallet_transactions").get("type");
        Set<String> referenceTypes = dbEnumColumns.get("wallet_transactions").get("reference_type");

        assertThat(types)
                .as(
                        "AffiliateSettlementWriter#creditCreatorWallet posts"
                            + " WalletTransactionType.AFFILIATE_COMMISSION; without it in the"
                            + " column, affiliate commission can never be credited on MySQL")
                .contains("AFFILIATE_COMMISSION");
        assertThat(referenceTypes)
                .as("the same posting sets TxnReferenceType.AFFILIATE_EARNING as reference_type")
                .contains("AFFILIATE_EARNING");
    }

    @Test
    @DisplayName(
            "EV-011: the V8 members keep their original ordinal positions — MySQL stores an ENUM"
                    + " value as its index, so appending is safe and reordering silently re-labels"
                    + " every existing row")
    void v8MembersKeepTheirOriginalOrder() {
        List<String> typeOrder = new ArrayList<>(dbEnumColumns.get("wallet_transactions").get("type"));
        List<String> referenceOrder =
                new ArrayList<>(dbEnumColumns.get("wallet_transactions").get("reference_type"));

        assertThat(typeOrder)
                .as("V8__wallet_transactions.sql:6-7 order, with the new member appended LAST")
                .containsExactly(
                        "DEPOSIT",
                        "WITHDRAWAL",
                        "ESCROW_HOLD",
                        "ESCROW_RELEASE",
                        "ESCROW_REFUND",
                        "PLATFORM_FEE",
                        "PAYOUT",
                        "ADJUSTMENT",
                        "AFFILIATE_COMMISSION");
        assertThat(referenceOrder)
                .as("V8__wallet_transactions.sql:12 order, with the new member appended LAST")
                .containsExactly(
                        "COLLABORATION",
                        "ESCROW_HOLD",
                        "MILESTONE",
                        "CAMPAIGN",
                        "DEPOSIT_ORDER",
                        "MANUAL",
                        "AFFILIATE_EARNING");
    }

    // ------------------------------------------------------------------
    // ANTI-VACUITY
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "the gate is not vacuous: the migration chain really parsed, entities really loaded,"
                    + " and the two sides really matched up on a known-good set of columns")
    void gateItselfIsNotVacuous() {
        assertThat(dbTables)
                .as("CREATE TABLE statements parsed out of the migration chain")
                .hasSizeGreaterThan(50);
        assertThat(dbEnumColumns.keySet())
                .as("tables with at least one ENUM column")
                .hasSizeGreaterThan(15);
        assertThat(persistedEnums)
                .as("@Enumerated(EnumType.STRING) fields found on compiled @Entity classes")
                .hasSizeGreaterThan(40);

        assertThat(KNOWN_EXCEPTIONS)
                .as(
                        "each known exception must carry a written justification, so the list can"
                            + " never grow by silent append")
                .allSatisfy((drift, why) -> assertThat(why).hasSizeGreaterThan(80));

        long matched = persistedEnums.stream().filter(pe -> allowedValues(pe) != null).count();
        assertThat(matched)
                .as(
                        "persisted-enum fields whose (table, column) actually resolved to a parsed"
                            + " DB ENUM — if this were 0 the conformance assertion above would pass"
                            + " for free")
                .isGreaterThan(15);

        // The specific columns EV-011 is about must be among them, by name, in both directions.
        assertThat(dbEnumColumns).containsKey("wallet_transactions");
        assertThat(dbEnumColumns.get("wallet_transactions").keySet())
                .contains("type", "reference_type", "direction", "status");
        assertThat(persistedEnums)
                .anySatisfy(
                        pe -> {
                            assertThat(pe.table()).isEqualTo("wallet_transactions");
                            assertThat(pe.column()).isEqualTo("type");
                            assertThat(pe.enumType()).isEqualTo(WalletTransactionType.class);
                        });
        assertThat(persistedEnums)
                .anySatisfy(
                        pe -> {
                            assertThat(pe.table()).isEqualTo("wallet_transactions");
                            // reference_type comes from an @Column(name = "reference_type"); the
                            // sibling `type`/`direction`/`status` fields have NO explicit name, so
                            // this pair also proves the camelCase fallback and the explicit-name
                            // path are both live.
                            assertThat(pe.column()).isEqualTo("reference_type");
                            assertThat(pe.enumType()).isEqualTo(TxnReferenceType.class);
                        });
    }

    // ------------------------------------------------------------------
    // Java side
    // ------------------------------------------------------------------

    private static Set<String> allowedValues(PersistedEnum pe) {
        Map<String, Set<String>> byColumn = dbEnumColumns.get(pe.table());
        return byColumn == null ? null : byColumn.get(pe.column());
    }

    private static List<PersistedEnum> scanPersistedEnums() throws IOException {
        List<PersistedEnum> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(COMPILED_ENTITY_DIR)) {
            List<Path> classFiles =
                    files.filter(p -> p.getFileName().toString().endsWith(".class"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
            for (Path classFile : classFiles) {
                String simpleName =
                        classFile.getFileName().toString().replaceFirst("\\.class$", "");
                if (simpleName.contains("$")) {
                    continue; // nested/anonymous — entities are top-level in this package
                }
                Class<?> clazz;
                try {
                    clazz = Class.forName(ENTITY_PACKAGE + "." + simpleName);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    continue;
                }
                if (!clazz.isAnnotationPresent(Entity.class)) {
                    continue;
                }
                String table = tableNameOf(clazz);
                for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                            continue;
                        }
                        Enumerated enumerated = f.getAnnotation(Enumerated.class);
                        if (enumerated == null || enumerated.value() != EnumType.STRING) {
                            continue;
                        }
                        if (!f.getType().isEnum()) {
                            continue;
                        }
                        out.add(
                                new PersistedEnum(
                                        clazz.getSimpleName(),
                                        f.getName(),
                                        table,
                                        columnNameOf(f),
                                        f.getType()));
                    }
                }
            }
        }
        return out;
    }

    private static String tableNameOf(Class<?> clazz) {
        Table t = clazz.getAnnotation(Table.class);
        if (t != null && !t.name().isBlank()) {
            return t.name().toLowerCase();
        }
        return camelToSnake(clazz.getSimpleName());
    }

    private static String columnNameOf(Field f) {
        Column c = f.getAnnotation(Column.class);
        if (c != null && !c.name().isBlank()) {
            return c.name().toLowerCase();
        }
        // Hibernate/Spring Boot's default CamelCaseToUnderscoresNamingStrategy. This branch is
        // load-bearing: WalletTransaction#type/#direction/#status all carry a NAMELESS @Column.
        return camelToSnake(f.getName());
    }

    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SQL side — a deliberately small MySQL DDL reader, only enough for ENUM columns
    // ------------------------------------------------------------------

    /**
     * Replays the migration chain in Flyway version order and reports, per table and column, the
     * ENUM members allowed at the END of the chain. Column ORDER is preserved (a {@link
     * LinkedHashSet}) because MySQL stores an ENUM value as its ordinal — {@link
     * #v8MembersKeepTheirOriginalOrder()} depends on that.
     */
    record MigrationSchema(Set<String> tables, Map<String, Map<String, Set<String>>> enumColumns) {

        private static final Pattern CREATE_TABLE =
                Pattern.compile(
                        "^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?\\s*\\((.*)\\)[^)]*$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern ALTER_TABLE =
                Pattern.compile(
                        "^ALTER\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?\\s+(.*)$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern ALTER_CLAUSE =
                Pattern.compile(
                        "^(ADD|MODIFY|CHANGE)\\s+(?:COLUMN\\s+)?`?([A-Za-z0-9_]+)`?\\s+(?:`?([A-Za-z0-9_]+)`?\\s+)?(.*)$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern ENUM_DEF =
                Pattern.compile("\\bENUM\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        private static final Set<String> CONSTRAINT_KEYWORDS =
                Set.of(
                        "primary", "unique", "key", "index", "constraint", "foreign", "fulltext",
                        "spatial", "check");

        static MigrationSchema replay(Path migrationDir) throws IOException {
            List<Path> files;
            try (Stream<Path> s = Files.list(migrationDir)) {
                files =
                        s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                                .sorted(Comparator.comparing(MigrationSchema::flywayVersionOf))
                                .toList();
            }
            if (files.isEmpty()) {
                throw new IllegalStateException(
                        "No migrations found under " + migrationDir.toAbsolutePath());
            }

            Set<String> tables = new LinkedHashSet<>();
            Map<String, Map<String, Set<String>>> enums = new LinkedHashMap<>();

            for (Path file : files) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                for (String statement : splitStatements(stripComments(sql))) {
                    String normalized = statement.trim().replaceAll("\\s+", " ");
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    Matcher create = CREATE_TABLE.matcher(normalized);
                    if (create.matches()) {
                        String table = create.group(1).toLowerCase();
                        tables.add(table);
                        for (String item : splitTopLevel(create.group(2))) {
                            String trimmed = item.trim();
                            if (trimmed.isEmpty()) {
                                continue;
                            }
                            String firstWord =
                                    trimmed.split("\\s+")[0].replace("`", "").toLowerCase();
                            if (CONSTRAINT_KEYWORDS.contains(firstWord)) {
                                continue;
                            }
                            Set<String> members = enumMembers(trimmed);
                            if (members != null) {
                                enums.computeIfAbsent(table, t -> new LinkedHashMap<>())
                                        .put(firstWord, members);
                            }
                        }
                        continue;
                    }
                    Matcher alter = ALTER_TABLE.matcher(normalized);
                    if (alter.matches()) {
                        String table = alter.group(1).toLowerCase();
                        for (String clause : splitTopLevel(alter.group(2))) {
                            Matcher m = ALTER_CLAUSE.matcher(clause.trim());
                            if (!m.matches()) {
                                continue;
                            }
                            String verb = m.group(1).toUpperCase();
                            // CHANGE old new <definition>; ADD/MODIFY col <definition>
                            String column =
                                    "CHANGE".equals(verb) && m.group(3) != null
                                            ? m.group(3).toLowerCase()
                                            : m.group(2).toLowerCase();
                            String definition =
                                    "CHANGE".equals(verb) && m.group(3) != null
                                            ? m.group(4)
                                            : (m.group(3) == null ? "" : m.group(3) + " ") + m.group(4);
                            Set<String> members = enumMembers(definition);
                            if (members != null) {
                                enums.computeIfAbsent(table, t -> new LinkedHashMap<>())
                                        .put(column, members);
                            } else if (!"ADD".equals(verb)) {
                                // Retyped away from ENUM (e.g. MODIFY ... VARCHAR): stop claiming
                                // it is one, rather than keeping a stale allow-list alive.
                                Map<String, Set<String>> byColumn = enums.get(table);
                                if (byColumn != null) {
                                    byColumn.remove(column);
                                }
                            }
                        }
                    }
                }
            }
            return new MigrationSchema(tables, enums);
        }

        /** {@code V20260919100000__x.sql} / {@code V8__x.sql} -> a sortable fixed-width key. */
        private static String flywayVersionOf(Path p) {
            String name = p.getFileName().toString();
            Matcher m = Pattern.compile("^V([0-9]+)__").matcher(name);
            if (!m.find()) {
                return name;
            }
            return String.format("%020d", Long.parseLong(m.group(1)));
        }

        /** Returns the ENUM members of a column definition, or {@code null} if it is not an ENUM. */
        private static Set<String> enumMembers(String definition) {
            Matcher m = ENUM_DEF.matcher(definition);
            if (!m.find()) {
                return null;
            }
            Set<String> members = new LinkedHashSet<>();
            Matcher lit = Pattern.compile("'([^']*)'").matcher(m.group(1));
            while (lit.find()) {
                members.add(lit.group(1));
            }
            return members;
        }

        /** Strips {@code --} line comments without eating a {@code --} inside a quoted literal. */
        private static String stripComments(String sql) {
            StringBuilder out = new StringBuilder(sql.length());
            boolean inSingle = false;
            for (int i = 0; i < sql.length(); i++) {
                char ch = sql.charAt(i);
                if (!inSingle && ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                    continue;
                }
                if (ch == '\'') {
                    inSingle = !inSingle;
                }
                out.append(ch);
            }
            return out.toString();
        }

        private static List<String> splitStatements(String sql) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inSingle = false;
            for (int i = 0; i < sql.length(); i++) {
                char ch = sql.charAt(i);
                if (ch == '\'') {
                    inSingle = !inSingle;
                }
                if (ch == ';' && !inSingle) {
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
            }
            out.add(current.toString());
            return out;
        }

        /** Splits on commas that are not inside parentheses or a quoted literal. */
        private static List<String> splitTopLevel(String body) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            boolean inSingle = false;
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '\'') {
                    inSingle = !inSingle;
                }
                if (!inSingle) {
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                    } else if (ch == ',' && depth == 0) {
                        out.add(current.toString());
                        current.setLength(0);
                        continue;
                    }
                }
                current.append(ch);
            }
            out.add(current.toString());
            return out;
        }
    }
}
