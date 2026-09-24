package com.influora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md B19, A33, K-22) — source-scan architecture test, same technique
 * (and same "no ArchUnit dependency" reasoning) as {@link InfoBarrierTest}: {@link
 * com.influora.service.credits.CreatorCreditService} is the ONLY writer of the seven creator
 * credit repositories. Every other class that legitimately needs a read (the controller, the
 * reconciliation job) goes through {@code CreatorCreditService}/{@code CreatorCreditOrderService}
 * instead of injecting a repository directly — see those two classes' own read-only helper
 * methods.
 */
class CreatorCreditWriterBoundaryTest {

    private static final List<String> FORBIDDEN_IMPORTS =
            List.of(
                    "CreatorCreditAccountRepository",
                    "CreatorCreditGrantRepository",
                    "CreatorCreditLedgerRepository",
                    "CreatorCreditWelcomeClaimRepository",
                    "CreatorCreditOrderRepository",
                    "CreatorCreditPackRepository",
                    "CreatorVoiceSpeakRepository");

    /**
     * Every class allowed to import one of the seven repositories above. {@code
     * CreatorCreditService} is the sole writer (K-22). {@code CreatorCreditAccountInitializer},
     * {@code CreatorCreditWelcomeClaimWriter} and {@code CreatorCreditInvoiceApplier} are the
     * three documented {@code REQUIRES_NEW} helper beans that class (and {@code
     * CreatorCreditOrderService}) delegate writes to (self-invocation would make the annotation a
     * no-op if they lived on the same class — see each class's own javadoc). {@code
     * CreatorCreditInvoiceApplier} additionally must run strictly AFTER {@code confirmPaid}'s own
     * transaction commits (via {@code AfterCommit}), which is the same self-invocation constraint
     * for a different reason: F-14/F-22. The repository interfaces themselves trivially "import"
     * nothing forbidden but are included for robustness.
     */
    private static final Set<String> ALLOWED_IMPORTERS =
            Set.of(
                    "CreatorCreditService",
                    "CreatorCreditOrderService",
                    "CreatorCreditAccountInitializer",
                    "CreatorCreditWelcomeClaimWriter",
                    "CreatorCreditInvoiceApplier",
                    "CreatorCreditAccountRepository",
                    "CreatorCreditGrantRepository",
                    "CreatorCreditLedgerRepository",
                    "CreatorCreditWelcomeClaimRepository",
                    "CreatorCreditOrderRepository",
                    "CreatorCreditPackRepository",
                    "CreatorVoiceSpeakRepository");

    /**
     * SPEC.md §12 A33 — named exactly {@code onlyCreditServicesWriteCredits} to match the
     * acceptance table (review finding #7/#17: this method used to be real and passing but under a
     * different name, invisible to a by-name test runner).
     */
    @Test
    @DisplayName("A33/K-22: only CreatorCreditService (and its REQUIRES_NEW helper beans) import a creator-credit repository")
    void onlyCreditServicesWriteCredits() throws IOException {
        Path mainRoot = mainSourceRoot();

        List<Path> offendingFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainRoot, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED_IMPORTERS.contains(simpleClassName(p)))
                    .filter(CreatorCreditWriterBoundaryTest::importsAnyForbiddenRepository)
                    .forEach(offendingFiles::add);
        }

        assertThat(offendingFiles)
                .withFailMessage(
                        "The following files import a creator-credit repository directly, which only"
                                + " CreatorCreditService (K-22) and its two REQUIRES_NEW helper beans may do:"
                                + " %s. Add a read-only helper method on CreatorCreditService/"
                                + "CreatorCreditOrderService instead.",
                        offendingFiles)
                .isEmpty();
    }

    /**
     * SPEC.md B19 — "no creator Meera tool with credit, grant, topup or wallet in its name". Scans
     * the creator tool executor package by class name only (a tool's own class name is what
     * Claude's tool schema is built from — see {@code CreatorToolScopes}/{@code
     * ToolCallValidator}), so a future {@code GrantCreditsExecutor}-shaped tool fails this test on
     * sight rather than needing a human to notice it in review.
     */
    @Test
    @DisplayName("no creator Meera tool class name contains credit/grant/topup/wallet")
    void noCreatorToolNamedForMoney() throws IOException {
        Path mainRoot = mainSourceRoot();
        Path toolDir = mainRoot.resolve("com/influora/service/meera/tool");
        if (!Files.isDirectory(toolDir)) {
            return;
        }
        List<String> bannedWords = List.of("credit", "grant", "topup", "wallet");
        List<Path> offending = new ArrayList<>();
        try (Stream<Path> files = Files.walk(toolDir, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().toLowerCase().contains("executor"))
                    .filter(
                            p -> {
                                String lower = simpleClassName(p).toLowerCase();
                                return bannedWords.stream().anyMatch(lower::contains);
                            })
                    .forEach(offending::add);
        }
        assertThat(offending)
                .withFailMessage(
                        "A creator Meera tool executor's class name must never contain credit/grant/topup/"
                                + "wallet (SPEC.md B19) — AI never moves money (R7): %s",
                        offending)
                .isEmpty();
    }

    private static String simpleClassName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    private static boolean importsAnyForbiddenRepository(Path file) {
        try {
            String content = Files.readString(file);
            for (String forbidden : FORBIDDEN_IMPORTS) {
                Pattern pattern =
                        Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.]*\\b" + forbidden + "\\b\\s*;", Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOExceptionForTest(file, e);
        }
    }

    private static Path mainSourceRoot() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        Path candidate = here.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = here.resolve("influora-api/src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IOException("Could not locate src/main/java from working directory " + here);
    }

    private static final class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(Path file, IOException cause) {
            super("Failed to read " + file, cause);
        }
    }
}
