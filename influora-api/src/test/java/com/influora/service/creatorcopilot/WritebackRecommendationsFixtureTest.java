package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.service.creatorcopilot.CreatorRecommendationService.ValidItem;
import com.influora.service.creatorcopilot.CreatorRecommendationService.WritebackRecording;
import com.influora.web.dto.meera.MeeraToolDtos.MessageWriteback;
import com.influora.web.dto.meera.MeeraToolDtos.WritebackRecommendation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Meera intelligence v1, slice 2 (spec 8.3) -- the write-back's {@code metadata.recommendations}
 * contract, proven on the Java side against a CHECKED-IN example request that influora-ai builds
 * to: {@code influora-ai/tests/fixtures/creator_tools/writeback_recommendations.sample.json}.
 *
 * <p>The file is read with Spring Boot's OWN configured {@link ObjectMapper} into the real request
 * record ({@link MessageWriteback}), each item is converted into {@link WritebackRecommendation}
 * exactly as {@link CreatorRecommendationService} does, and the full parse must keep every item.
 * The item keys in the file must equal the record's {@code @JsonProperty} names, so a renamed
 * field on either side goes red here instead of being silently dropped at the seam.
 */
@JsonTest
class WritebackRecommendationsFixtureTest {

    static final Path FIXTURE =
            Path.of("..", "influora-ai", "tests", "fixtures", "creator_tools", "writeback_recommendations.sample.json");

    /** The day the sample plan was made: Friday 25 Sep 2026, 18:00 IST. */
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();

    @Autowired private ObjectMapper objectMapper;

    private String fixture() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName(
            "the checked-in sample write-back deserialises with the app's ObjectMapper and every"
                    + " recommendation item is accepted, field for field")
    void sampleDeserialisesAndEveryItemIsAccepted() throws IOException {
        MessageWriteback body = objectMapper.readValue(fixture(), MessageWriteback.class);
        assertNotNull(body.conversationId());
        assertNotNull(body.metadata());
        List<?> raw = assertInstanceOf(List.class, body.metadata().get("recommendations"));
        assertEquals(6, raw.size());

        WritebackRecommendation first = objectMapper.convertValue(raw.get(0), WritebackRecommendation.class);
        assertEquals("PLAN_MY_WEEK", first.source());
        assertEquals(0, first.lineIndex());
        assertEquals("2026-09-25", first.recommendedFor());
        assertEquals("REEL", first.postType());
        assertEquals("weekday evening", first.windowLabel());
        assertEquals("18:00", first.windowFrom());
        assertEquals("21:00", first.windowTo());
        assertEquals("Before/after", first.structureName());
        assertEquals("Stop scrolling if you...", first.hookTemplate());
        assertEquals("Navratri outfit ideas", first.topic());
        assertEquals("Navratri", first.festival());

        CreatorRecommendationService service =
                new CreatorRecommendationService(
                        mock(CreatorRecommendationRepository.class), mock(CreatorRecommendationWriter.class), objectMapper);
        WritebackRecording recording =
                service.parse("01HCREATORUSER1234567A", body.conversationId(), "01HMESSAGE0000000000000AB", body.metadata(), NOW);

        assertEquals("meera-2026.09.25.15", recording.promptVersion());
        assertEquals("ck-2026.09.25.1", recording.knowledgeVersion());
        assertEquals(6, recording.items().size(), "no item of the sample may be dropped");
        ValidItem plan = recording.items().get(0);
        assertEquals(CreatorRecommendationSource.PLAN_MY_WEEK, plan.source());
        assertEquals(LocalDate.of(2026, 9, 25), plan.recommendedFor());
        assertEquals(LocalDate.of(2026, 9, 26), plan.matchUntil());
        assertEquals(ChallengeDayType.REEL, plan.postType());
        assertEquals(LocalTime.of(18, 0), plan.windowFrom());
        assertEquals(ChallengeDayType.POST, recording.items().get(3).postType());
        assertEquals(LocalTime.of(22, 0), recording.items().get(4).windowFrom());
        assertEquals(LocalTime.of(1, 0), recording.items().get(4).windowTo());
        ValidItem script = recording.items().get(5);
        assertEquals(CreatorRecommendationSource.SCRIPT_CARD, script.source());
        assertEquals(7, script.lineIndex());
        assertNull(script.recommendedFor());
        assertEquals(LocalDate.of(2026, 10, 2), script.matchUntil());
    }

    @Test
    @DisplayName("every item key in the sample is exactly a WritebackRecommendation @JsonProperty name (snake_case)")
    void sampleKeysEqualTheRecordsWireNames() throws IOException {
        Set<String> recordKeys = new TreeSet<>();
        for (java.lang.reflect.RecordComponent c : WritebackRecommendation.class.getRecordComponents()) {
            recordKeys.add(c.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class).value());
        }
        assertEquals(11, recordKeys.size());
        JsonNode items = objectMapper.readTree(fixture()).path("metadata").path("recommendations");
        assertEquals(6, items.size());
        for (JsonNode item : items) {
            Set<String> keys = new TreeSet<>();
            for (Iterator<String> it = item.fieldNames(); it.hasNext(); ) {
                keys.add(it.next());
            }
            assertEquals(recordKeys, keys);
        }
    }
}
