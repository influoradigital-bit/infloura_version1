package com.influora.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EV-004 architecture gate: every servlet filter in {@code src/main/java} that decides anything
 * from the request path must go through {@code com.influora.security.RequestPaths}, and no filter
 * may read a raw path accessor except to log it.
 *
 * <p>Why: raw-URI string matching was bypassed three times (AuthRateLimitFilter percent-encoding
 * and matrix params; InternalServiceTokenFilter {@code %69nternal}). Each fix was local, so the
 * next filter repeated the bug. This gate makes the shared normaliser the only door.
 *
 * <p>The scan works on source with COMMENTS removed (and, for the accessor/identifier rules,
 * string literals removed too), so prose like this javadoc — which names the banned accessors —
 * can neither trip the gate nor satisfy it. {@link #gateIgnoresCommentsAndCatchesRealCalls}
 * proves both directions on synthetic sources, and {@link #scanIsNotVacuous} proves the scan
 * actually found the filters it must judge.
 */
class RequestPathsArchitectureTest {

    private static final Pattern FILTER_DECL =
            Pattern.compile(
                    "\\bclass\\s+\\w+[^{]*\\b(extends\\s+(OncePerRequestFilter|GenericFilterBean)"
                            + "|implements\\s+[^{]*\\b(jakarta\\.servlet\\.)?Filter\\b)");

    /** Raw servlet path accessors; each must only ever feed a logger inside a filter. */
    private static final Pattern RAW_ACCESSOR =
            Pattern.compile("\\.\\s*(getRequestURI|getServletPath|getPathInfo|getRequestURL)\\s*\\(");

    private static final Pattern LOG_CALL =
            Pattern.compile("\\b(log|logger|LOG|LOGGER)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");

    /** A string literal that looks like an application path or a path regex. */
    private static final Pattern PATH_LITERAL = Pattern.compile("\"\\^?/[A-Za-z0-9_.%*{(\\[-]");

    private static final Pattern USES_UTILITY = Pattern.compile("\\bRequestPaths\\s*\\.");

    @Test
    @DisplayName("EV-004: no servlet filter reads a raw path accessor except to log it")
    void noRawPathAccessorOutsideLogging() throws IOException {
        TreeMap<String, List<String>> violations = new TreeMap<>();
        for (Path file : filterSources()) {
            List<String> v = rawAccessorViolations(Files.readString(file, StandardCharsets.UTF_8));
            if (!v.isEmpty()) {
                violations.put(file.getFileName().toString(), v);
            }
        }
        assertTrue(
                violations.isEmpty(),
                "Filters matching on a raw path accessor (use RequestPaths instead): " + violations);
    }

    @Test
    @DisplayName("EV-004: every servlet filter that matches on a path literal uses RequestPaths")
    void pathMatchingFiltersUseTheSharedNormaliser() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : filterSources()) {
            if (!usesUtilityWhenMatching(Files.readString(file, StandardCharsets.UTF_8))) {
                violations.add(file.getFileName().toString());
            }
        }
        assertTrue(
                violations.isEmpty(),
                "Filters with path literals that never call RequestPaths: " + violations);
    }

    @Test
    @DisplayName("The scan is not vacuous: it finds the filters and classifies the two path matchers")
    void scanIsNotVacuous() throws IOException {
        List<String> names = new ArrayList<>();
        List<String> pathMatchers = new ArrayList<>();
        for (Path file : filterSources()) {
            String name = file.getFileName().toString();
            names.add(name);
            if (PATH_LITERAL.matcher(stripComments(Files.readString(file, StandardCharsets.UTF_8), true)).find()) {
                pathMatchers.add(name);
            }
        }
        assertTrue(names.size() >= 5, "expected at least 5 servlet filters, found " + names);
        assertTrue(names.contains("InternalServiceTokenFilter.java"), names.toString());
        assertTrue(names.contains("AuthRateLimitFilter.java"), names.toString());
        assertTrue(names.contains("CorrelationIdFilter.java"), names.toString());
        assertTrue(pathMatchers.contains("InternalServiceTokenFilter.java"), pathMatchers.toString());
        assertTrue(pathMatchers.contains("AuthRateLimitFilter.java"), pathMatchers.toString());
    }

    @Test
    @DisplayName("Self-test: comments never trip or satisfy the gate; real calls always do")
    void gateIgnoresCommentsAndCatchesRealCalls() {
        String commentOnly =
                "class A extends OncePerRequestFilter {\n"
                        + "  // request.getRequestURI().startsWith(\"/internal\") was the bug\n"
                        + "  /* RequestPaths.isUnder(request, \"/internal\") */\n"
                        + "  void f() { chain.doFilter(request, response); }\n}\n";
        assertEquals(List.of(), rawAccessorViolations(commentOnly));
        assertTrue(usesUtilityWhenMatching(commentOnly), "a comment-only path literal is not matching");

        String rawMatch =
                "class B extends OncePerRequestFilter {\n"
                        + "  // RequestPaths.pathWithinApplication(request) would be right\n"
                        + "  void f() { if (request.getRequestURI().startsWith(\"/internal/\")) { x(); } }\n}\n";
        assertEquals(1, rawAccessorViolations(rawMatch).size());
        assertTrue(!usesUtilityWhenMatching(rawMatch), "a RequestPaths mention in a comment satisfied the gate");

        String stringMention =
                "class C extends OncePerRequestFilter {\n"
                        + "  static final String MSG = \"call RequestPaths.isUnder, not .getRequestURI()\";\n"
                        + "  boolean f() { return \"/admin\".equals(request.getServletPath()); }\n}\n";
        assertEquals(1, rawAccessorViolations(stringMention).size());
        assertTrue(!usesUtilityWhenMatching(stringMention), "a RequestPaths mention in a string satisfied the gate");

        String logged =
                "class D extends OncePerRequestFilter {\n"
                        + "  void f() { log.info(\"{} {}\", request.getMethod(), request.getRequestURI()); }\n}\n";
        assertEquals(List.of(), rawAccessorViolations(logged));

        String good =
                "class E extends OncePerRequestFilter {\n"
                        + "  boolean f() { return RequestPaths.isUnder(request, \"/internal\"); }\n}\n";
        assertEquals(List.of(), rawAccessorViolations(good));
        assertTrue(usesUtilityWhenMatching(good));
    }

    // ---- rules ------------------------------------------------------------------------------

    static List<String> rawAccessorViolations(String source) {
        String code = stripComments(source, false);
        List<String> violations = new ArrayList<>();
        Matcher m = RAW_ACCESSOR.matcher(code);
        while (m.find()) {
            String statement = enclosingStatementPrefix(code, m.start());
            if (!LOG_CALL.matcher(statement).find()) {
                violations.add(m.group(1) + "() at offset " + m.start());
            }
        }
        return violations;
    }

    static boolean usesUtilityWhenMatching(String source) {
        boolean matchesOnPath = PATH_LITERAL.matcher(stripComments(source, true)).find();
        return !matchesOnPath || USES_UTILITY.matcher(stripComments(source, false)).find();
    }

    /** Text from the start of the statement containing {@code offset} up to {@code offset}. */
    private static String enclosingStatementPrefix(String code, int offset) {
        int start = offset;
        while (start > 0) {
            char c = code.charAt(start - 1);
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            start--;
        }
        return code.substring(start, offset);
    }

    /**
     * Removes comments (and, unless {@code keepStrings}, the contents of string/char/text-block
     * literals) while preserving offsets' relative order. Good enough for this codebase's Java.
     */
    static String stripComments(String src, boolean keepStrings) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                out.append(keepStrings ? src.substring(i, stop) : "\"\"");
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(j + 1, n);
                out.append(keepStrings ? src.substring(i, stop) : "" + c + c);
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<Path> filterSources() throws IOException {
        Path root = mainSourceRoot();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8), false);
                if (FILTER_DECL.matcher(code).find()) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    private static Path mainSourceRoot() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        for (String candidate : List.of("src/main/java", "influora-api/src/main/java")) {
            Path p = here.resolve(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IOException("Could not locate src/main/java from working directory " + here);
    }
}
