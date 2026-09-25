package com.influora.web;

import static com.influora.service.creatorcopilot.ContentTopicService.REASON_ALL_WITH_OTHERS;
import static com.influora.service.creatorcopilot.ContentTopicService.REASON_NO_CREATOR_GROUP;
import static com.influora.service.creatorcopilot.ContentTopicService.REASON_UNKNOWN_CATEGORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.ContentTopic;
import com.influora.repository.ContentTopicRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.web.dto.admin.AdminContentTopicDtos.ContentTopicPreviewResponse;
import com.influora.web.dto.admin.AdminContentTopicDtos.TopicView;
import com.influora.web.dto.admin.AdminContentTopicDtos.UnmatchedCategoryView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * T-CONTENT-TOPICS -- {@code GET /admin/content-topics/preview}'s {@code unmatched_categories}.
 * Plain unit test (no MockMvc; admin auth is {@code SecurityConfig}'s {@code /admin/**} matcher,
 * see {@link AdminCreatorAgentControllerTest}). The controller runs over a REAL {@link
 * ContentTopicService} with only the repositories mocked, so the matching and the unmatched list
 * come from the production code, not a stubbed service.
 */
@ExtendWith(MockitoExtension.class)
class AdminContentTopicControllerTest {

    private static final String SECRET_TITLE = "Monsoon snacks secret title";
    private static final String SECRET_ANGLE = "A secret angle line";

    @Mock private ContentTopicRepository contentTopicRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;

    private AdminContentTopicController controller;

    @BeforeEach
    void setUp() {
        controller =
                new AdminContentTopicController(
                        new ContentTopicService(contentTopicRepository, creatorProfileRepository));
    }

    private static ContentTopic topic(long id, String category) throws ReflectiveOperationException {
        Constructor<ContentTopic> ctor = ContentTopic.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ContentTopic t = ctor.newInstance();
        LocalDate today = LocalDate.now();
        setField(t, "id", id);
        setField(t, "category", category);
        setField(t, "title", SECRET_TITLE + " " + id);
        setField(t, "angles", SECRET_ANGLE);
        setField(t, "liveFrom", today.minusDays(2));
        setField(t, "liveUntil", today.plusDays(2));
        setField(t, "region", "India");
        setField(t, "status", "APPROVED");
        Instant now = Instant.now();
        setField(t, "createdAt", now);
        setField(t, "updatedAt", now);
        return t;
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ContentTopic.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName(
            "preview lists a 'Snacks' part and a part next to ALL in unmatched_categories (id,"
                    + " category, part, reason only) and serves 'Snacks' only to the word 'snacks'")
    void snacksRowIsListedAsUnmatched() throws Exception {
        when(contentTopicRepository.findServable(any(LocalDate.class)))
                .thenReturn(
                        List.of(
                                topic(1, "Snacks"),
                                topic(2, "Fashion, Culture"),
                                topic(3, "Culture, ALL"),
                                topic(4, "Shopping")));

        ContentTopicPreviewResponse forFashion = controller.preview(null, "Fashion & Lifestyle", null);
        assertEquals(
                List.of(2L, 3L, 4L), forFashion.topics().stream().map(TopicView::id).toList());
        List<UnmatchedCategoryView> expectedUnmatched =
                List.of(
                        new UnmatchedCategoryView(1L, "Snacks", "Snacks", REASON_UNKNOWN_CATEGORY),
                        new UnmatchedCategoryView(3L, "Culture, ALL", "Culture", REASON_ALL_WITH_OTHERS));
        assertEquals(expectedUnmatched, forFashion.unmatchedCategories());
        assertEquals(AdminContentTopicController.UNMATCHED_CATEGORIES_NOTE, forFashion.unmatchedCategoriesNote());
        String note = forFashion.unmatchedCategoriesNote();
        assertTrue(note.contains("exact word"));
        assertTrue(note.contains(REASON_UNKNOWN_CATEGORY));
        assertTrue(note.contains(REASON_NO_CREATOR_GROUP));
        assertTrue(note.contains("ALL with other categories"));

        ContentTopicPreviewResponse forSnacks = controller.preview(null, "snacks", null);
        assertEquals(List.of(1L, 3L), forSnacks.topics().stream().map(TopicView::id).toList());
        // The unmatched list is about today's rows, not the previewed category.
        assertEquals(expectedUnmatched, forSnacks.unmatchedCategories());

        // On the wire: snake_case key, id + category + part + reason -- never a title or an angle.
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        JsonNode json = mapper.valueToTree(forFashion);
        JsonNode unmatched = json.get("unmatched_categories");
        assertEquals(2, unmatched.size());
        assertEquals(1L, unmatched.get(0).get("id").asLong());
        assertEquals("Snacks", unmatched.get(0).get("category").asText());
        assertEquals("Snacks", unmatched.get(0).get("part").asText());
        assertEquals(REASON_UNKNOWN_CATEGORY, unmatched.get(0).get("reason").asText());
        assertEquals(4, unmatched.get(0).size());
        assertTrue(json.has("unmatched_categories_note"));
        String unmatchedWire = mapper.writeValueAsString(unmatched);
        assertFalse(unmatchedWire.contains(SECRET_TITLE));
        assertFalse(unmatchedWire.contains(SECRET_ANGLE));
    }

    @Test
    @DisplayName("preview returns an empty unmatched_categories when every row maps into the calendar")
    void noUnmatchedRows() throws Exception {
        when(contentTopicRepository.findServable(any(LocalDate.class)))
                .thenReturn(List.of(topic(1, "Tech"), topic(2, "Food,,"), topic(3, "ALL")));

        ContentTopicPreviewResponse response = controller.preview(null, "Tech & Gaming", null);

        assertEquals(List.of(1L, 3L), response.topics().stream().map(TopicView::id).toList());
        assertTrue(response.unmatchedCategories().isEmpty());
    }

    @Test
    @DisplayName("preview splits ?category=Fashion, Culture on commas, like a row's category")
    void categoryParamIsSplitOnCommas() throws Exception {
        when(contentTopicRepository.findServable(any(LocalDate.class)))
                .thenReturn(
                        List.of(
                                topic(1, "Fashion"),
                                topic(2, "Culture"),
                                topic(3, "Food"),
                                topic(4, "Fashion, Culture"),
                                topic(5, "Gardening")));

        ContentTopicPreviewResponse response = controller.preview(null, " Fashion, Culture ,", null);

        assertEquals(List.of("Fashion", "Culture"), response.resolvedCategories());
        assertEquals(List.of(1L, 2L, 4L), response.topics().stream().map(TopicView::id).toList());
        assertEquals(
                List.of(new UnmatchedCategoryView(5L, "Gardening", "Gardening", REASON_NO_CREATOR_GROUP)),
                response.unmatchedCategories());
    }
}
