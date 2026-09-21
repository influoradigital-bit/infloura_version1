package com.influora.integration.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * EV-045 gate — no outbound client under {@code integration/} may be built without timeouts.
 *
 * <p>{@link OutboundRestClientsTimeoutTest} proves the shared builder actually times out. This test
 * proves every production call site goes through it, which is the half that rots: the defect was
 * five separate {@code RestClient.builder().build()} calls, each individually reasonable-looking,
 * added at five different times.
 *
 * <p><b>Why this gate cannot pass on its own comment.</b> A source-text gate that greps a whole
 * file matches the sentence describing the banned pattern as readily as the pattern, and then goes
 * red (or green) for the wrong reason. So {@link #stripComments} removes every {@code //} line
 * comment, block comment and javadoc block BEFORE matching, and {@link #selfCheck_theGateCanFail}
 * proves the matcher still fires on a real occurrence.
 */
class OutboundClientsHaveTimeoutsTest {

    private static final Path INTEGRATION_ROOT =
            Path.of("src", "main", "java", "com", "influora", "integration");

    /** The helper itself is the one place allowed to call the raw builder. */
    private static final String ALLOWED_FILE = "OutboundRestClients.java";

    private static final String BANNED = "RestClient.builder()";

    @Test
    void noIntegrationClientBuildsARestClientWithoutTimeouts() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(INTEGRATION_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals(ALLOWED_FILE)) {
                    continue;
                }
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains(BANNED)) {
                    offenders.add(file.toString());
                }
            }
        }

        assertThat(offenders)
                .as(
                        "these build a RestClient with no connect/read timeout — a hung provider "
                                + "holds the request thread. Use OutboundRestClients instead.")
                .isEmpty();
    }

    @Test
    void theIntegrationTreeWasActuallyScanned() throws IOException {
        // Falsification: the test above is vacuously green if the walk found nothing (wrong
        // working directory, renamed package). Pin that it really read the known clients.
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(INTEGRATION_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> scanned.add(p.getFileName().toString()));
        }

        assertThat(scanned)
                .contains(
                        "MetaGraphApiClient.java",
                        "MetaOAuthService.java",
                        "ShopifyOAuthService.java",
                        "ShopifyOrderOwnershipVerifier.java",
                        "ShopifyWebhookRegistrar.java");
    }

    @Test
    void selfCheck_theGateCanFail() {
        // Falsification of the matcher itself: prove it fires on real code and does NOT fire on a
        // comment that merely mentions the pattern. Without this, `stripComments` could be
        // over-eager (deleting the code it is meant to inspect) and the gate would pass forever.
        String realCode = "private final RestClient c = RestClient.builder().build();";
        String onlyAComment = "// never call RestClient.builder() directly; use OutboundRestClients";
        String onlyAJavadoc = "/** Do not use {@code RestClient.builder()} here. */";

        assertThat(stripComments(realCode)).contains(BANNED);
        assertThat(stripComments(onlyAComment)).doesNotContain(BANNED);
        assertThat(stripComments(onlyAJavadoc)).doesNotContain(BANNED);
    }

    /**
     * Removes {@code //} line comments and {@code /* ... *}{@code /} block comments (javadoc
     * included). Deliberately simple: it does not understand string literals, which is safe here
     * because a false NEGATIVE (a banned call hidden inside a string) is not a thing this codebase
     * does, while a false POSITIVE from a comment is exactly what this gate has to avoid.
     */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("(?m)//.*$", "");
    }
}
