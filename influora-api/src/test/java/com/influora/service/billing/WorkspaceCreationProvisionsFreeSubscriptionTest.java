package com.influora.service.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-4 (wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md) — fails if a NEW workspace-creation path is
 * ever added without eagerly provisioning the Free-tier subscription row alongside it.
 *
 * <p><b>Why this shape, and not a {@code @SpringBootTest}/MockMvc conformance test.</b> As
 * {@code ConfigurationPropertiesRegistrationTest} and {@code PlanGateFilterRegistrationTest}
 * already document, every {@code @SpringBootTest} class in this module errors out on
 * Testcontainers/Docker discovery here, and a gate that cannot execute is not a gate. This test
 * mechanically derives the current set of workspace-creation call sites from
 * comment-stripped source (same technique as {@code gates/F-0141-writer-enumeration-exhaustive.py})
 * and asserts each one's enclosing method also calls the provisioning API — no Spring context, so
 * it runs green or red on a machine with no Docker at all.
 *
 * <p><b>What counts as a "workspace-creation site."</b> {@link
 * com.influora.domain.entity.Workspace}'s constructor is {@code protected}; the ONLY way any code
 * outside that entity's own package can obtain a new instance is one of its {@code public static
 * Workspace newXxx(...)} factory methods (today, only {@code newBrand} exists — there is no
 * {@code newAgency}). The regex below deliberately matches {@code Workspace.new<Anything>(}, not
 * just {@code Workspace.newBrand(}, so a hypothetical future {@code Workspace.newAgency(...)}
 * factory is picked up automatically without this test needing an update — the exact class of gap
 * this task was warned about (an {@code AGENCY} workspace must never be silently mistaken for a
 * candidate to provision, and a NEW brand-only factory must never be silently un-checked).
 *
 * <p><b>What "provisioned" means here.</b> The enclosing method (found the same way the F-0141
 * gate attributes a repository write to its enclosing method: back-scan by brace depth to the
 * nearest preceding method declaration, then forward-scan to that method's closing brace) must
 * itself contain a call to {@link SubscriptionService#getOrCreateFreeSubscription}. That is
 * deliberately a textual presence check, not a behavioral one — it cannot see whether the call is
 * correctly guarded on {@code WorkspaceType.BRAND}, only that a NEW creation path was not shipped
 * with zero awareness of provisioning at all. {@code AuthServiceTest} and {@code
 * FestivalSponsorProvisioningServiceTest} carry the behavioral proof (mock verification that the
 * call actually fires, with the actual workspace id, for the two sites this test currently finds).
 *
 * <p><b>Known limitation (stated, not hidden — see the "Dead controls invisible to static checks"
 * class of gap this codebase has hit before):</b> if a future creation path calls {@code
 * Workspace.newBrand(...)} inside a small private helper method that itself contains no
 * provisioning call, while some OTHER method that calls that helper does the provisioning, this
 * test would false-positive-fail even though the workspace ends up provisioned. That is
 * considered the right failure mode: it forces a human to either inline the provisioning call
 * into the same method (the pattern both existing sites use) or teach this test about the new
 * shape deliberately, rather than letting a genuinely new, unprovisioned path go unnoticed.
 *
 * <p><b>Falsification (run these before trusting a green result):</b>
 *
 * <ol>
 *   <li>Delete the {@code subscriptionService.getOrCreateFreeSubscription(...)} call from {@code
 *       AuthService#brandRegister} → {@link #everyWorkspaceCreationSiteProvisionsFreeSubscription()}
 *       must go RED, naming {@code AuthService#brandRegister}.
 *   <li>Delete the {@code subscriptionService.getOrCreateFreeSubscription(...)} call from {@code
 *       FestivalSponsorProvisioningService#provision} → the same test must go RED, naming {@code
 *       FestivalSponsorProvisioningService#provision}.
 *   <li>Add a brand-new method anywhere under {@code com.influora} that calls {@code
 *       Workspace.newBrand(...)} (or a hypothetical {@code Workspace.newAgency(...)}) without also
 *       calling {@code getOrCreateFreeSubscription} in that same method → the same test must go
 *       RED, naming the new method.
 * </ol>
 *
 * All three were run against this test before it was trusted; restoring the deleted call (or
 * removing the added one) turns it back GREEN.
 */
class WorkspaceCreationProvisionsFreeSubscriptionTest {

    private static final Path SRC =
            Paths.get("src", "main", "java", "com", "influora").toAbsolutePath();

    private static final Pattern CREATION_CALL =
            Pattern.compile("\\bWorkspace\\s*\\.\\s*new[A-Z]\\w*\\s*\\(");

    private static final String PROVISION_CALL = "getOrCreateFreeSubscription";

    /** A member method declaration sitting directly in a class body — same conservative shape
     * (leading modifier required, no '=' before the first '(') as the F-0141 gate's own
     * {@code METHOD_DECL}, so the two independently-written derivations agree on what counts as a
     * method. */
    private static final Pattern METHOD_DECL =
            Pattern.compile(
                    "^\\s+(?:(?:public|private|protected|static|final|synchronized|abstract|default|native)\\s+)+"
                            + "[\\w.$<>\\[\\],\\s?]+?\\s(\\w+)\\s*\\(");

    @Test
    @DisplayName(
            "every Workspace.new*(...) creation call site's enclosing method also calls"
                    + " getOrCreateFreeSubscription")
    void everyWorkspaceCreationSiteProvisionsFreeSubscription() throws IOException {
        assertThat(Files.isDirectory(SRC))
                .as(
                        "%s does not exist -- this test resolves main sources relative to the JVM's"
                            + " working directory, which must be influora-api/ (Surefire's default"
                            + " module base dir; matches how mvn surefire:test is run for this"
                            + " module per this repo's own verification workaround)",
                        SRC)
                .isTrue();

        List<Path> javaFiles = allJavaFilesUnder(SRC);
        assertThat(javaFiles)
                .as("sanity: main sources under %s must be present and enumerable", SRC)
                .isNotEmpty();

        List<String> creationSites = new ArrayList<>();
        TreeSet<String> unprovisioned = new TreeSet<>();

        for (Path file : javaFiles) {
            String stripped = stripComments(Files.readString(file));
            String[] lines = stripped.split("\n", -1);
            int[] depthBefore = depthBeforeEachLine(lines);

            for (int i = 0; i < lines.length; i++) {
                if (!CREATION_CALL.matcher(lines[i]).find()) {
                    continue;
                }
                MethodSpan method = enclosingMethod(lines, depthBefore, i);
                String label = file.getFileName() + "#" + method.name;
                creationSites.add(label + " (line " + (i + 1) + ")");

                String body = String.join("\n", java.util.Arrays.asList(lines).subList(method.startLine, method.endLine + 1));
                if (!body.contains(PROVISION_CALL)) {
                    unprovisioned.add(label);
                }
            }
        }

        // Sanity: the derivation itself must find the two known-good sites, so a green result
        // means "the harness ran and both sites still provision", not "the parser found nothing".
        assertThat(creationSites)
                .as(
                        "no Workspace.new*(...) creation call site was found at all -- the scan"
                                + " (pattern %s under %s) is broken, not proving anything",
                        CREATION_CALL, SRC)
                .isNotEmpty();

        assertThat(unprovisioned)
                .as(
                        "these workspace-creation sites call Workspace.new*(...) but their enclosing"
                            + " method never calls %s -- see this test's class javadoc for what to"
                            + " do (inline the provisioning call, or teach this test about the new"
                            + " shape deliberately): %s. All creation sites found this run: %s",
                        PROVISION_CALL, unprovisioned, creationSites)
                .isEmpty();
    }

    private record MethodSpan(String name, int startLine, int endLine) {}

    /** Back-scan by brace depth to the nearest enclosing method (mirrors the F-0141 gate's own
     * attribution algorithm), then forward-scan from there to that method's closing brace. */
    private static MethodSpan enclosingMethod(String[] lines, int[] depthBefore, int creationLine) {
        for (int back = creationLine; back >= 0; back--) {
            if (depthBefore[back] != 1) {
                continue;
            }
            Matcher m = METHOD_DECL.matcher(lines[back]);
            if (m.find() && !lines[back].substring(0, lines[back].indexOf('(')).contains("=")) {
                int depthAtStart = depthBefore[back];
                int running = depthAtStart;
                for (int fwd = back; fwd < lines.length; fwd++) {
                    running += countChar(lines[fwd], '{') - countChar(lines[fwd], '}');
                    if (fwd > back && running <= depthAtStart) {
                        return new MethodSpan(m.group(1), back, fwd);
                    }
                }
                return new MethodSpan(m.group(1), back, lines.length - 1);
            }
        }
        throw new AssertionError(
                "a Workspace.new*(...) call at line "
                        + (creationLine + 1)
                        + " could not be attributed to an enclosing method -- the depth/regex"
                        + " derivation is unsound for this file, not proving anything");
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static int[] depthBeforeEachLine(String[] lines) {
        int[] depths = new int[lines.length];
        int d = 0;
        for (int i = 0; i < lines.length; i++) {
            depths[i] = d;
            d += countChar(lines[i], '{') - countChar(lines[i], '}');
        }
        return depths;
    }

    private static List<Path> allJavaFilesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Blanks out {@code //}, {@code /* *}{@code /} and string/char literals while preserving line
     * structure and byte offsets — identical approach to the F-0141 gate's {@code strip_java},
     * ported to Java. Necessary for the same reason documented there: this very class's own
     * javadoc contains the literal token {@code Workspace.newBrand(} and {@code
     * getOrCreateFreeSubscription}, and an unstripped scan would find phantom call sites in
     * comments (including, recursively, in this file). */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                out.append("  ");
                i = Math.min(i + 2, n);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\') {
                        out.append(' ');
                        i++;
                        if (i < n) {
                            out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                            i++;
                        }
                        continue;
                    }
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                out.append(' ');
                i = Math.min(i + 1, n);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
