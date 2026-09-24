package com.influora.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Two branches merged on 2026-09-24 each added a migration numbered V20260924100000 (account
 * insights; existing creators to English). Flyway refuses to start with a repeated version, so
 * the API would not have booted — and nothing local said so: H2 tests run with Flyway off, and
 * only CI's real-MySQL boot would have failed. This test fails the moment two files share one.
 */
class FlywayVersionsAreUniqueTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9_.]+)__.+\\.sql$");

    @Test
    void everyMigrationVersionIsUsedOnce() throws IOException {
        Map<String, List<String>> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.map(p -> p.getFileName().toString()).forEach(name -> {
                Matcher m = VERSIONED.matcher(name);
                if (m.matches()) {
                    // Flyway treats 1_1 and 1.1 as the same version.
                    String version = m.group(1).replace('_', '.');
                    byVersion.computeIfAbsent(version, k -> new java.util.ArrayList<>()).add(name);
                }
            });
        }
        assertTrue(byVersion.size() > 50, "migration folder not found or nearly empty: " + MIGRATIONS.toAbsolutePath());
        Map<String, List<String>> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertTrue(duplicates.isEmpty(), "Flyway versions used more than once: " + duplicates);
    }
}
