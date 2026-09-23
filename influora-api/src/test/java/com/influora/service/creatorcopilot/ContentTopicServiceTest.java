package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.ContentTopic;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.ContentTopicRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.creatorcopilot.ContentTopicService.DroppedTopic;
import com.influora.service.creatorcopilot.ContentTopicService.ScreeningResult;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-CONTENT-TOPICS -- {@link ContentTopicService}: category matching against a mocked {@link
 * ContentTopicRepository#findServable} result, safety screening via the real {@link
 * TrendHeadlineScreener} (never mocked -- a mocked screener would prove nothing about whether an
 * unsafe row is actually dropped), and the five-topic cap.
 *
 * <p>Status/date filtering ({@code DRAFT}/{@code REJECTED} excluded, future/past dates excluded,
 * boundary dates included) is NOT re-proven here: {@link #repository} is mocked to return exactly
 * the candidates each test wants, so a test at this layer cannot tell a correct WHERE clause from
 * a broken one. That is what {@code ContentTopicRepositoryTest} (a real {@code @DataJpaTest})
 * exists to prove instead.
 */
@ExtendWith(MockitoExtension.class)
class ContentTopicServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";

    /** Real headline from {@code TrendHeadlineScreenerTest} -- a DEATH-category term ("dies"). */
    private static final String UNSAFE_TEXT = "Popular actor dies in car crash on set";

    /** Benign control-set headline from {@code TrendHeadlineScreenerTest}. */
    private static final String BENIGN_TEXT = "Diwali fashion haul";

    @Mock private ContentTopicRepository contentTopicRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;

    private ContentTopicService service;

    @BeforeEach
    void setUp() {
        service = new ContentTopicService(contentTopicRepository, creatorProfileRepository);
    }

    private static ContentTopic topic(
            long id, String category, String title, String angles, String status)
            throws ReflectiveOperationException {
        Constructor<ContentTopic> ctor = ContentTopic.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ContentTopic t = ctor.newInstance();
        setField(t, "id", id);
        setField(t, "category", category);
        setField(t, "title", title);
        setField(t, "angles", angles);
        setField(t, "liveFrom", TODAY.minusDays(1));
        setField(t, "liveUntil", TODAY.plusDays(1));
        setField(t, "region", "India");
        setField(t, "sensitivity", null);
        setField(t, "status", status);
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

    // -----------------------------------------------------------------------------------------
    // Category matching
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("category match: an exact match is served")
    void categoryExactMatch() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Beauty", "Skincare routines", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of("Beauty"), TODAY);

        assertEquals(1, result.servable().size());
        assertEquals(1L, result.servable().get(0).id());
    }

    @Test
    @DisplayName("category match: case is ignored on both sides")
    void categoryMatchIsCaseInsensitive() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "BEAUTY", "Skincare routines", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of("beauty"), TODAY);

        assertEquals(1, result.servable().size());
    }

    @Test
    @DisplayName("category match: a row categorised ALL matches every creator, regardless of her categories")
    void allCategoryMatchesEveryCreator() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "ALL", "Everyone topic", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of("Fitness"), TODAY);

        assertEquals(1, result.servable().size());
    }

    @Test
    @DisplayName("category match: no overlap and not ALL -- the row is silently skipped, not dropped")
    void noCategoryOverlapIsNotServed() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Finance", "Investing basics", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of("Beauty"), TODAY);

        assertTrue(result.servable().isEmpty());
        // Not a category match at all, so it must not show up as a screening "drop" either.
        assertTrue(result.dropped().isEmpty());
    }

    @Test
    @DisplayName("category match: a creator with no stored categories is served ONLY the ALL rows")
    void creatorWithNoCategoriesGetsOnlyAllRows() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "ALL", "Everyone topic", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Beauty", "Skincare routines", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of(), TODAY);

        assertEquals(List.of(1L), result.servable().stream().map(ServableTopic::id).toList());
    }

    @Test
    @DisplayName(
            "topicsFor: a creator profile with no categories_json resolves to an empty category"
                    + " list end-to-end, which screen() then matches against ALL rows only")
    void topicsForEndToEndWithNoCategories() throws ReflectiveOperationException {
        CreatorProfile profile = org.mockito.Mockito.mock(CreatorProfile.class);
        when(profile.getCategoriesJson()).thenReturn(null);
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "ALL", "Everyone topic", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Beauty", "Skincare routines", BENIGN_TEXT, "APPROVED")));

        List<ServableTopic> served = service.topicsFor(CREATOR_USER_ID, TODAY);

        assertEquals(List.of(1L), served.stream().map(ServableTopic::id).toList());
    }

    @Test
    @DisplayName("topicsFor: no creator profile for the user id is a 404, not a silent empty list")
    void topicsForMissingProfileThrows() {
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.topicsFor(CREATOR_USER_ID, TODAY));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
    }

    // -----------------------------------------------------------------------------------------
    // Safety screening
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("an APPROVED, matched row with an unsafe TITLE is dropped, not served")
    void unsafeTitleIsDropped() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "ALL", UNSAFE_TEXT, BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of(), TODAY);

        assertTrue(result.servable().isEmpty());
        assertEquals(List.of(new DroppedTopic(1L, "DEATH")), result.dropped());
    }

    @Test
    @DisplayName("an APPROVED, matched row with an unsafe ANGLE (not the title) is dropped, not served")
    void unsafeAngleIsDropped() throws ReflectiveOperationException {
        // Title is benign; the SECOND angle line is the one that trips the filter.
        String angles = BENIGN_TEXT + "\n" + UNSAFE_TEXT;
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "ALL", "A perfectly safe title", angles, "APPROVED")));

        ScreeningResult result = service.screen(List.of(), TODAY);

        assertTrue(result.servable().isEmpty());
        assertEquals(List.of(new DroppedTopic(1L, "DEATH")), result.dropped());
    }

    @Test
    @DisplayName("a benign beauty/food topic passes through untouched -- the screener does not over-block normal creator vocabulary")
    void benignTopicPassesUntouched() throws ReflectiveOperationException {
        String angles = "Try a five-step skincare routine.\nShare your favourite Diwali recipe.";
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Beauty", "Diwali skincare and food edit", angles, "APPROVED")));

        ScreeningResult result = service.screen(List.of("Beauty"), TODAY);

        assertTrue(result.dropped().isEmpty());
        assertEquals(1, result.servable().size());
        ServableTopic served = result.servable().get(0);
        assertEquals("Diwali skincare and food edit", served.title());
        assertEquals(
                List.of("Try a five-step skincare routine.", "Share your favourite Diwali recipe."),
                served.angles());
    }

    // -----------------------------------------------------------------------------------------
    // Cap
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("more than five matched+safe rows are capped at five, newest (repository order) first")
    void cappedAtFiveTopics() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(6, "ALL", "Topic 6", BENIGN_TEXT, "APPROVED"),
                                topic(5, "ALL", "Topic 5", BENIGN_TEXT, "APPROVED"),
                                topic(4, "ALL", "Topic 4", BENIGN_TEXT, "APPROVED"),
                                topic(3, "ALL", "Topic 3", BENIGN_TEXT, "APPROVED"),
                                topic(2, "ALL", "Topic 2", BENIGN_TEXT, "APPROVED"),
                                topic(1, "ALL", "Topic 1", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of(), TODAY);

        assertEquals(
                List.of(6L, 5L, 4L, 3L, 2L),
                result.servable().stream().map(ServableTopic::id).toList());
    }

    @Test
    @DisplayName("a dropped (unsafe) row does not consume a cap slot")
    void droppedRowDoesNotConsumeCapSlot() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(6, "ALL", UNSAFE_TEXT, BENIGN_TEXT, "APPROVED"),
                                topic(5, "ALL", "Topic 5", BENIGN_TEXT, "APPROVED"),
                                topic(4, "ALL", "Topic 4", BENIGN_TEXT, "APPROVED"),
                                topic(3, "ALL", "Topic 3", BENIGN_TEXT, "APPROVED"),
                                topic(2, "ALL", "Topic 2", BENIGN_TEXT, "APPROVED"),
                                topic(1, "ALL", "Topic 1", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of(), TODAY);

        assertEquals(
                List.of(5L, 4L, 3L, 2L, 1L),
                result.servable().stream().map(ServableTopic::id).toList());
        assertEquals(List.of(new DroppedTopic(6L, "DEATH")), result.dropped());
    }

    @Test
    @DisplayName("splitAngles: split on newlines, trim, drop blank lines")
    void splitAnglesTrimsAndDropsBlanks() {
        assertEquals(
                List.of("First angle.", "Second angle."),
                ContentTopicService.splitAngles("  First angle.  \n\n Second angle.\n  \n"));
    }
}
