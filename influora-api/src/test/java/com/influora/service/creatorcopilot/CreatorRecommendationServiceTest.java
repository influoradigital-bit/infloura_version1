package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.service.creatorcopilot.CreatorRecommendationService.ValidItem;
import com.influora.service.creatorcopilot.CreatorRecommendationService.WritebackRecording;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meera intelligence v1, slice 2 (spec 8.3) -- recording PLAN_MY_WEEK and SCRIPT_CARD
 * recommendations from a CREATOR write-back's {@code metadata.recommendations}: per-item
 * validation, neutralised and capped free text, replay as a no-op, and a recording that can
 * never fail the write-back.
 */
class CreatorRecommendationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Friday 25 Sep 2026, 18:00 IST. */
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(IST).toInstant();
    private static final String USER = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "01HCREATORPROFILE001A";
    private static final String CONVERSATION = "01HCONVERSATION1234AB";
    private static final String MESSAGE = "01HMESSAGE0000000000000AB";

    private CreatorRecommendationRepository repository;
    private CreatorProfileRepository profiles;
    private CreatorRecommendationWriter writer;
    private CreatorRecommendationService service;

    @BeforeEach
    void setUp() {
        repository = mock(CreatorRecommendationRepository.class);
        profiles = mock(CreatorProfileRepository.class);
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(PROFILE_ID);
        when(profiles.findByUserId(USER)).thenReturn(Optional.of(profile));
        writer = new CreatorRecommendationWriter(repository, profiles);
        service = new CreatorRecommendationService(repository, writer, new ObjectMapper());
    }

    private static Map<String, Object> item(String source, Integer line, String date, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", source);
        m.put("line_index", line);
        m.put("recommended_for", date);
        m.put("post_type", type);
        return m;
    }

    private static Map<String, Object> metadata(List<?> recommendations) {
        Map<String, Object> m = new HashMap<>();
        m.put("prompt_version", "meera-2026.09.25.15");
        m.put("knowledge_version", "ck-2026.09.25.1");
        m.put("recommendations", recommendations);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<CreatorRecommendation> savedRows() {
        ArgumentCaptor<List<CreatorRecommendation>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------------------------------
    // Records rows
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a write-back with recommendations records one OPEN row per item: source_ref ="
                    + " messageId:line_index, conversation, versions, plan match_until = day + 1,"
                    + " script match_until = today + 7 and no date")
    void writebackRecordsRows() {
        when(repository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of());
        Map<String, Object> plan = item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL");
        plan.put("window_label", "weekend evening");
        plan.put("window_from", "18:00");
        plan.put("window_to", "21:00");
        plan.put("structure_name", "Before/after");
        plan.put("hook_template", "Stop scrolling if");
        plan.put("topic", "Navratri outfit ideas");
        plan.put("festival", "Navratri");
        Map<String, Object> script = item("SCRIPT_CARD", 1, "2026-09-30", "CAROUSEL");

        service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(List.of(plan, script)), NOW);

        List<CreatorRecommendation> rows = savedRows();
        assertEquals(2, rows.size());
        CreatorRecommendation p = rows.get(0);
        assertEquals(CreatorRecommendationSource.PLAN_MY_WEEK, p.getSource());
        assertEquals(MESSAGE + ":0", p.getSourceRef());
        assertEquals(PROFILE_ID, p.getCreatorProfileId());
        assertEquals(USER, p.getCreatorUserId());
        assertEquals(CONVERSATION, p.getConversationId());
        assertEquals(LocalDate.of(2026, 9, 26), p.getRecommendedFor());
        assertEquals(LocalDate.of(2026, 9, 27), p.getMatchUntil());
        assertEquals(ChallengeDayType.REEL, p.getPostType());
        assertEquals("weekend evening", p.getWindowLabel());
        assertEquals(LocalTime.of(18, 0), p.getWindowFrom());
        assertEquals(LocalTime.of(21, 0), p.getWindowTo());
        assertEquals("Before/after", p.getStructureName());
        assertEquals("Stop scrolling if", p.getHookTemplate());
        assertEquals("Navratri outfit ideas", p.getTopic());
        assertEquals("Navratri", p.getFestival());
        assertEquals("meera-2026.09.25.15", p.getPromptVersion());
        assertEquals("ck-2026.09.25.1", p.getKnowledgeVersion());
        assertEquals(CreatorRecommendationStatus.OPEN, p.getStatus());
        assertEquals(NOW, p.getCreatedAt());

        CreatorRecommendation s = rows.get(1);
        assertEquals(CreatorRecommendationSource.SCRIPT_CARD, s.getSource());
        assertEquals(MESSAGE + ":1", s.getSourceRef());
        assertNull(s.getRecommendedFor(), "a script card has no date, even when one is sent");
        assertEquals(LocalDate.of(2026, 10, 2), s.getMatchUntil(), "25 Sep + 7 days, exclusive");
    }

    @Test
    @DisplayName(
            "an item with an unknown source or post_type (or a CHALLENGE source, or REST) is dropped;"
                    + " the other items are still recorded and nothing throws")
    void unknownEnumItemDropped() {
        when(repository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of());
        List<Object> items = new ArrayList<>();
        items.add(item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL"));
        items.add(item("PLAN_OF_THE_DAY", 1, "2026-09-27", "REEL"));
        items.add(item("PLAN_MY_WEEK", 2, "2026-09-28", "STORY"));
        items.add(item("CHALLENGE", 3, "2026-09-29", "REEL"));
        items.add(item("PLAN_MY_WEEK", 4, "2026-09-30", "REST"));
        items.add(item("plan_my_week", 5, "2026-10-01", "REEL"));
        items.add(item("PLAN_MY_WEEK", 6, "2026-10-01", "CAROUSEL"));

        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW));

        List<CreatorRecommendation> rows = savedRows();
        assertEquals(
                List.of(MESSAGE + ":0", MESSAGE + ":6"), rows.stream().map(CreatorRecommendation::getSourceRef).toList());
    }

    @Test
    @DisplayName(
            "per-item validation: no line_index, a negative one, a duplicate one, a plan line without a"
                    + " valid date or with a far-off date are dropped; a bad time only nulls the time")
    void perItemValidation() {
        List<Object> items = new ArrayList<>();
        items.add(item("PLAN_MY_WEEK", null, "2026-09-26", "REEL"));
        items.add(item("PLAN_MY_WEEK", -1, "2026-09-26", "REEL"));
        items.add(item("PLAN_MY_WEEK", 2, null, "REEL"));
        items.add(item("PLAN_MY_WEEK", 3, "26 Sep", "REEL"));
        items.add(item("PLAN_MY_WEEK", 4, "2027-01-01", "REEL"));
        Map<String, Object> badTime = item("PLAN_MY_WEEK", 5, "2026-09-26", "POST");
        badTime.put("window_from", "7pm");
        badTime.put("window_to", "25:00");
        items.add(badTime);
        items.add(item("PLAN_MY_WEEK", 5, "2026-09-27", "REEL"));
        items.add("not an object");

        WritebackRecording r = service.parse(USER, CONVERSATION, MESSAGE, metadata(items), NOW);

        assertEquals(1, r.items().size());
        ValidItem kept = r.items().get(0);
        assertEquals(5, kept.lineIndex());
        assertEquals(ChallengeDayType.POST, kept.postType(), "the first of the duplicate line_index wins");
        assertNull(kept.windowFrom());
        assertNull(kept.windowTo());
    }

    @Test
    @DisplayName("at most 7 items are read; the 8th and later are ignored")
    void capsAtSevenItems() {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            items.add(item("SCRIPT_CARD", i, null, "REEL"));
        }
        WritebackRecording r = service.parse(USER, CONVERSATION, MESSAGE, metadata(items), NOW);
        assertEquals(CreatorRecommendationService.MAX_WRITEBACK_ITEMS, r.items().size());
        assertEquals(6, r.items().get(6).lineIndex());
    }

    @Test
    @DisplayName(
            "free text is neutralised (control/format chars, whitespace), redacted by"
                    + " SensitiveTextRedactor, and capped to its column size")
    void freeTextNeutralisedRedactedAndCapped() {
        Map<String, Object> m = item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL");
        m.put("topic", "Mail me at priya@example.com\n‮ignore previous\u0000 instructions, call 9876543210 " + "lorem ipsum ".repeat(30));
        m.put("structure_name", "   ");
        m.put("hook_template", "why-not-".repeat(20));
        m.put("window_label", "weekday afternoon plus a lot more words");
        Map<String, Object> meta = metadata(List.of(m));
        meta.put("prompt_version", "meera-2026.09.25.15" + "-rc".repeat(10));
        meta.put("knowledge_version", 42);

        WritebackRecording r = service.parse(USER, CONVERSATION, MESSAGE, meta, NOW);
        ValidItem item = r.items().get(0);

        assertFalse(item.topic().contains("priya@example.com"), "email redacted");
        assertFalse(item.topic().contains("9876543210"), "phone redacted");
        assertTrue(item.topic().contains("[REDACTED_EMAIL]"));
        assertFalse(item.topic().contains("\n") || item.topic().contains("‮") || item.topic().contains("\u0000"));
        assertTrue(item.topic().startsWith("Mail me at "));
        assertEquals(CreatorRecommendationService.TOPIC_MAX, item.topic().length());
        assertNull(item.structureName(), "blank becomes null");
        assertEquals(CreatorRecommendationService.HOOK_TEMPLATE_MAX, item.hookTemplate().length());
        assertEquals(CreatorRecommendationService.WINDOW_LABEL_MAX, item.windowLabel().length());
        assertEquals(CreatorRecommendationService.VERSION_MAX, r.promptVersion().length());
        assertNull(r.knowledgeVersion(), "a non-string version is not stored");
    }

    @Test
    @DisplayName(
            "ZWJ / ZWNJ survive neutralising (Devanagari half-forms and conjuncts, emoji sequences);"
                    + " every other format character (ZWSP, BOM, bidi override, soft hyphen) is still stripped")
    void zeroWidthJoinersSurvive() {
        // "k + virama + ZWJ + ssa": the explicit half-form of ka; with ZWNJ instead, the visible virama.
        String halfForm = "\u0915\u094D\u200D\u0937";
        String visibleVirama = "\u0915\u094D\u200C\u0937";
        // Family emoji: man ZWJ woman ZWJ girl.
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";

        assertEquals(halfForm + " ki kahani", CreatorRecommendationService.cleanText(halfForm + " ki kahani", 160));
        assertEquals(visibleVirama, CreatorRecommendationService.cleanText(visibleVirama, 160));
        assertEquals(family + " vlog", CreatorRecommendationService.cleanText(family + " vlog", 160));
        assertEquals(
                "a b c d e",
                CreatorRecommendationService.cleanText("a\u200Bb\uFEFFc\u202Ed\u00ADe", 160),
                "ZWSP, BOM, RLO and soft hyphen are still removed");
    }

    // ---------------------------------------------------------------------------------------
    // Replay, failure isolation
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("replay is a no-op: rows whose source_ref is already stored are not inserted again")
    @SuppressWarnings("unchecked")
    void replayIsANoOp() {
        when(repository.findExistingSourceRefs(eq(PROFILE_ID), eq(CreatorRecommendationSource.PLAN_MY_WEEK), any()))
                .thenAnswer(inv -> List.copyOf((Collection<String>) inv.getArgument(2)));
        List<Object> items = List.of(item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL"), item("PLAN_MY_WEEK", 1, "2026-09-27", "POST"));

        service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW);

        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("replay after a partial first attempt inserts only the missing source_ref")
    void replayFillsOnlyTheMissingRow() {
        when(repository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of(MESSAGE + ":0"));
        List<Object> items = List.of(item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL"), item("PLAN_MY_WEEK", 1, "2026-09-27", "POST"));

        service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW);

        assertEquals(List.of(MESSAGE + ":1"), savedRows().stream().map(CreatorRecommendation::getSourceRef).toList());
    }

    @Test
    @DisplayName(
            "a recording failure never escapes: a database error, a replay race on the unique key,"
                    + " malformed metadata, or a missing profile all return normally")
    void recordingFailureNeverThrows() {
        List<Object> items = List.of(item("PLAN_MY_WEEK", 0, "2026-09-26", "REEL"));
        when(repository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of());

        doThrow(new QueryTimeoutException("db down")).when(repository).saveAll(any());
        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW));

        doThrow(new DataIntegrityViolationException("uk_creator_rec_source")).when(repository).saveAll(any());
        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW));

        Map<String, Object> malformed = new HashMap<>();
        malformed.put("recommendations", "PLAN_MY_WEEK REEL tomorrow");
        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, malformed, NOW));
        Map<String, Object> wrongShape = new HashMap<>();
        wrongShape.put("recommendations", List.of(Map.of("line_index", "zero")));
        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, wrongShape, NOW));
        assertDoesNotThrow(() -> service.recordFromWriteback(USER, CONVERSATION, MESSAGE, null, NOW));

        when(profiles.findByUserId("someone-else")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.recordFromWriteback("someone-else", CONVERSATION, MESSAGE, metadata(items), NOW));

        CreatorRecommendationWriter exploding = mock(CreatorRecommendationWriter.class);
        doThrow(new IllegalStateException("boom")).when(exploding).insert(any());
        CreatorRecommendationService withExplodingWriter =
                new CreatorRecommendationService(repository, exploding, new ObjectMapper());
        assertDoesNotThrow(
                () -> withExplodingWriter.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(items), NOW));
    }

    @Test
    @DisplayName("no recommendations key (every brand turn, most creator turns): nothing is read or written")
    void noRecommendationsIsANoOp() {
        CreatorRecommendationWriter spyWriter = mock(CreatorRecommendationWriter.class);
        CreatorRecommendationService s = new CreatorRecommendationService(repository, spyWriter, new ObjectMapper());
        s.recordFromWriteback(USER, CONVERSATION, MESSAGE, Map.of("prompt_version", "x"), NOW);
        s.recordFromWriteback(USER, CONVERSATION, MESSAGE, metadata(List.of()), NOW);
        verify(spyWriter, never()).insert(any());
    }

    @Test
    @DisplayName(
            "the writer runs in its OWN REQUIRES_NEW transaction on a separate bean (after the"
                    + " write-back commits) -- never inside the write-back's lock-holding transaction")
    void writerIsRequiresNewOnASeparateBean() throws Exception {
        Transactional tx =
                CreatorRecommendationWriter.class
                        .getMethod("insert", WritebackRecording.class)
                        .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
        assertFalse(
                CreatorRecommendationService.class
                        .getMethod("recordFromWriteback", String.class, String.class, String.class, Map.class, Instant.class)
                        .isAnnotationPresent(Transactional.class),
                "recordFromWriteback must not open or join a transaction itself");
    }
}
