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
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 5.1, A7a) — source-scan architecture test. No ArchUnit
 * dependency exists in this project (see {@code pom.xml}), so this follows SPEC.md's own
 * documented fallback: scan {@code .java} source files directly rather than adding a new Maven
 * dependency (out of this task's authority — new dependencies need Priya approval).
 *
 * <p><b>Fix round 1, item 5 — allow-list, not a name filter.</b> The original version of this
 * test only scanned files whose simple name CONTAINS "Brand" — the brand-facing surface that
 * would actually leak a floor (the tool executors under {@code service/meera/tool/**} such as
 * {@code ShowCreatorsExecutor}/{@code CalculateBudgetExecutor}, {@code MeeraInternalController},
 * {@code MeeraContextService}'s own callers) is mostly not Brand-named and was never checked — a
 * future {@code ShowCreatorsExecutor} importing {@link
 * com.influora.repository.CreatorAgentPreferencesRepository} would have passed the old gate. This
 * version instead scans EVERY {@code .java} file under {@code service/**}, {@code web/**} and
 * {@code job/**} and asserts the repository is imported ONLY by the small set of classes that are
 * actually allowed to touch it: {@code MeeraContextService} (the one shared class the spec
 * explicitly wires to both {@code assembleBrandContext} and {@code assembleCreatorContext}, and
 * which never lets a floor value cross from one to the other — asserted at runtime by {@code
 * InfoBarrierRuntimeTest}), {@code CreatorAgentPreferencesService} (the CREATOR-facing owner of
 * the row), and the repository interface itself (its own file trivially "imports" nothing
 * forbidden, but is included in the allow-list for completeness/robustness against a future
 * refactor that moves it under a scanned directory). Matches only a real {@code import} statement
 * line, via a word-boundary regex — not a bare substring {@code contains} — so a javadoc/comment
 * mention (e.g. {@code PublicCreatorService}, which is outside the scanned directories anyway)
 * can never false-positive this gate.
 *
 * <p><b>SPEC.md T-MEERA-CREATOR-PHASE-B, 3.6 — scan widened to {@code service/**} and {@code
 * job/**}.</b> Phase B puts seven new creator-floor-handling classes outside the old {@code
 * service/meera/**}+{@code web/**} scan ({@code RateQuoteService} under {@code service/rates},
 * {@code DealRiskService} under {@code service/risk}, {@code CreatorBriefService}, {@code
 * MediaKitService}, {@code CampaignFitService}, {@code RoutineReplyService} directly under {@code
 * service}, and {@code MeeraDelayedSendJob} under {@code job}) — without widening, the barrier was
 * decorative for exactly the classes that matter. {@code service/meera/**} is now a subset of the
 * wider {@code service/**} root, so it is folded in rather than scanned twice.
 */
class InfoBarrierTest {

    private static final String FORBIDDEN_IMPORT = "CreatorAgentPreferencesRepository";

    /** Import-statement match only — never a bare substring hit inside a comment/javadoc. */
    private static final Pattern FORBIDDEN_IMPORT_STATEMENT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.]*\\b" + FORBIDDEN_IMPORT + "\\b\\s*;", Pattern.MULTILINE);

    /**
     * The ONLY simple class names allowed to import {@link
     * com.influora.repository.CreatorAgentPreferencesRepository} within the scanned directories.
     * {@code CreatorAgentPreferencesService} lives directly under {@code service/} (not {@code
     * service/meera/}), so the pre-widening scan could never actually reach it; now that the scan
     * root is the broader {@code service/**}, this entry is genuinely enforced rather than a
     * dead placeholder. The repository interface itself still lives under {@code repository/**},
     * outside every scanned root — kept in the allow-list anyway so this test does not need to
     * change if it is ever relocated under a scanned directory.
     */
    private static final Set<String> ALLOWED_IMPORTERS =
            Set.of("MeeraContextService", "CreatorAgentPreferencesService", FORBIDDEN_IMPORT);

    /**
     * Repo-relative scan roots (SPEC.md 3.6): {@code service/**} (superset of the old {@code
     * service/meera/**}), {@code web/**} and {@code job/**}.
     */
    private static final List<String> CANDIDATE_RELATIVE_DIRS =
            List.of("com/influora/service", "com/influora/web", "com/influora/job");

    @Test
    @DisplayName(
            "A7(a) — within service/**, web/** and job/**, only the allow-listed classes may import"
                    + " CreatorAgentPreferencesRepository")
    void onlyAllowListedClassesImportCreatorAgentPreferencesRepository() throws IOException {
        Path mainRoot = mainSourceRoot();
        List<Path> candidateDirs = CANDIDATE_RELATIVE_DIRS.stream().map(mainRoot::resolve).toList();

        // Each directory's stream is opened and closed in its own try-with-resources before the
        // next is opened — Stream.flatMap does not propagate close() to substreams it flattens,
        // so a single flat-mapped expression here would leak file handles across N roots.
        List<Path> offendingFiles = new ArrayList<>();
        for (Path dir : candidateDirs) {
            try (Stream<Path> files = walkIfExists(dir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(InfoBarrierTest::importsForbiddenRepository)
                        .filter(p -> !ALLOWED_IMPORTERS.contains(simpleClassName(p)))
                        .forEach(offendingFiles::add);
            }
        }

        assertThat(offendingFiles)
                .withFailMessage(
                        "The following files import %s, which must never reach a brand-facing code path"
                                + " outside the allow-list %s (SPEC.md T-MEERA-CREATOR-PHASE-A, A7a): %s",
                        FORBIDDEN_IMPORT, ALLOWED_IMPORTERS, offendingFiles)
                .isEmpty();
    }

    private static String simpleClassName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    private static boolean importsForbiddenRepository(Path file) {
        try {
            Matcher matcher = FORBIDDEN_IMPORT_STATEMENT.matcher(Files.readString(file));
            return matcher.find();
        } catch (IOException e) {
            throw new UncheckedIOExceptionForTest(file, e);
        }
    }

    private static Stream<Path> walkIfExists(Path dir) throws IOException {
        return Files.exists(dir) ? Files.walk(dir, FileVisitOption.FOLLOW_LINKS) : Stream.empty();
    }

    /**
     * Repo root is resolved relative to this test class's own file, not the working directory —
     * Maven/IDE working directories differ (module root vs. repo root), and this must find {@code
     * src/main/java} regardless of which one invoked it.
     */
    private static Path mainSourceRoot() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        Path candidate = here.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Fallback for a working directory one level up (repo root instead of influora-api/).
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
