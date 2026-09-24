package com.influora.web.dto.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorAccountInsightsResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * GET /creator/analytics/me/account-insights, checked against BOTH sides' real declarations
 * (2026-09-24): Spring Boot's own ObjectMapper serialises a real response, and its keys must equal
 * the {@code CreatorAccountInsights} interface parsed out of {@code src/lib/api.ts}. A renamed or
 * dropped field fails here instead of rendering as a blank tile.
 */
@JsonTest
class AccountInsightsContractSeamTest {

    private static final Path API_TS = Path.of("..", "src", "lib", "api.ts");

    @Autowired private ObjectMapper objectMapper;

    private static Set<String> tsFields(String source, String interfaceName) {
        Matcher block =
                Pattern.compile("export interface " + interfaceName + " \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        assertTrue(block.find(), "interface " + interfaceName + " not found in src/lib/api.ts");
        String body = block.group(1).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> names = new TreeSet<>();
        Matcher field = Pattern.compile("(?m)^\\s*([a-zA-Z]+)\\??:").matcher(body);
        while (field.find()) {
            names.add(field.group(1));
        }
        return names;
    }

    @Test
    void keysMatchTheAppInterfaceAndTravelInTheShapeTheAppReads() throws Exception {
        CreatorAccountInsightsResponse sample =
                new CreatorAccountInsightsResponse(
                        true, LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 23),
                        12400L, 48210L, 1930L, 822L, null, Instant.parse("2026-09-24T00:00:00Z"));
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sample));

        Set<String> keys = new TreeSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        String ts = Files.readString(API_TS, StandardCharsets.UTF_8);
        assertEquals(tsFields(ts, "CreatorAccountInsights"), keys);

        // The app formats "2026-08-27" as a calendar date: an ISO string, never [2026,8,27].
        assertEquals("2026-08-27", json.get("periodStart").asText());
        // `=== null` means "Not reported": the key must be present as null, not omitted.
        assertTrue(json.has("profileLinksTaps") && json.get("profileLinksTaps").isNull());
        // An empty state still carries every key.
        JsonNode empty = objectMapper.readTree(objectMapper.writeValueAsString(CreatorAccountInsightsResponse.empty()));
        assertEquals(keys, toSet(empty));
    }

    private static Set<String> toSet(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
