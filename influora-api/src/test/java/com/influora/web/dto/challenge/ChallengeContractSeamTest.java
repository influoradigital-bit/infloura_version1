package com.influora.web.dto.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.web.dto.challenge.ChallengeDtos.ActiveChallenge;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeDay;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeState;
import com.influora.web.dto.challenge.ChallengeDtos.Comparison;
import com.influora.web.dto.challenge.ChallengeDtos.LastCompleted;
import com.influora.web.dto.challenge.ChallengeDtos.WeekStat;
import com.influora.web.dto.challenge.ChallengeDtos.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * The 7-day challenge's wire contract, checked against BOTH sides' real declarations.
 *
 * <p>WHY. The backend and the app were built in parallel against a written contract. Each side's
 * own tests pass on its own assumptions — the backend's through record accessors, the app's through
 * a hand-written mock — so neither would notice if a field were renamed, dropped, or serialised in a
 * different shape (twice on 2026-09-23 a feature was green on both sides and broken at the seam).
 * This test serialises a real {@link ChallengeState} with Spring Boot's OWN configured ObjectMapper
 * and compares the keys with the TypeScript interfaces parsed out of {@code src/lib/api.ts}.
 */
@JsonTest
class ChallengeContractSeamTest {

    private static final Path API_TS = Path.of("..", "src", "lib", "api.ts");

    @Autowired private ObjectMapper objectMapper;

    private ChallengeState sample() {
        LocalDate day = LocalDate.of(2026, 9, 23);
        ChallengeDay done =
                new ChallengeDay(
                        0, day, "REEL", new Window("evening", "17:00", "22:00"), "your_posts", "DONE",
                        true, "VIDEO", "https://www.instagram.com/p/abc/");
        ChallengeDay rest =
                new ChallengeDay(6, day.plusDays(6), "REST", null, null, "REST", null, null, null);
        WeekStat week = new WeekStat(day.minusDays(7), day.minusDays(1), 3, 2, 8400L, "4.1%");
        return new ChallengeState(
                true,
                new ActiveChallenge("01JCHALLENGE", day, 3, 2, List.of(done, rest)),
                new LastCompleted("01JOLD", day.minusDays(14), 5, 6),
                new Comparison(week, week, null, null, false, "Not enough settled posts yet."));
    }

    /** Field names of one `export interface X { ... }` block in api.ts. */
    private static Set<String> tsFields(String source, String interfaceName) {
        Matcher block =
                Pattern.compile("export interface " + interfaceName + " \\{(.*?)\\n\\}", Pattern.DOTALL)
                        .matcher(source);
        assertTrue(block.find(), "interface " + interfaceName + " not found in src/lib/api.ts");
        // Strip /** */ and // comments, then take `name:` / `name?:` at the start of a line.
        String body = block.group(1).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> names = new TreeSet<>();
        Matcher field = Pattern.compile("(?m)^\\s*([a-zA-Z]+)\\??:").matcher(body);
        while (field.find()) {
            names.add(field.group(1));
        }
        return names;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void everyObjectHasExactlyTheFieldsTheAppDeclares() throws Exception {
        String ts = Files.readString(API_TS, StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sample()));

        assertEquals(tsFields(ts, "ChallengeState"), keys(json), "ChallengeState");
        assertEquals(tsFields(ts, "ActiveChallenge"), keys(json.at("/active")), "ActiveChallenge");
        assertEquals(tsFields(ts, "LastCompletedChallenge"), keys(json.at("/lastCompleted")), "LastCompletedChallenge");
        assertEquals(tsFields(ts, "ChallengeDay"), keys(json.at("/active/days/0")), "ChallengeDay");
        assertEquals(tsFields(ts, "ChallengeWindow"), keys(json.at("/active/days/0/window")), "ChallengeWindow");
        assertEquals(tsFields(ts, "ChallengeComparison"), keys(json.at("/comparison")), "ChallengeComparison");
        assertEquals(tsFields(ts, "ChallengeWeekStats"), keys(json.at("/comparison/thisWeek")), "ChallengeWeekStats");
    }

    @Test
    void datesTravelAsIsoStringsAndEmptyFieldsAsNullNotMissing() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sample()));

        // The app does string maths on these ("2026-09-23"), not [2026,9,23] arrays.
        assertEquals("2026-09-23", json.at("/active/startedOn").asText());
        assertTrue(json.at("/active/days/0/date").isTextual());
        assertTrue(json.at("/comparison/thisWeek/from").isTextual());

        // The app checks `=== null`: a REST day must carry window:null, not omit the key.
        JsonNode restDay = json.at("/active/days/1");
        assertTrue(restDay.has("window") && restDay.get("window").isNull(), "window must be present as null");
        assertTrue(json.at("/comparison").has("reachChangePercent"), "reachChangePercent must be present");
        assertTrue(json.at("/comparison/reachChangePercent").isNull());
    }
}
