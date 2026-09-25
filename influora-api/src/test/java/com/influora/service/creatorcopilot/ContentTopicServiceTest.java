package com.influora.service.creatorcopilot;

import static com.influora.service.creatorcopilot.ContentTopicService.REASON_ALL_WITH_OTHERS;
import static com.influora.service.creatorcopilot.ContentTopicService.REASON_NO_CREATOR_GROUP;
import static com.influora.service.creatorcopilot.ContentTopicService.REASON_UNKNOWN_CATEGORY;
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
import com.influora.service.creatorcopilot.ContentTopicService.UnmatchedCategoryTopic;
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
    @DisplayName(
            "category map (lane B1): an onboarding vertical is served the admin's topic in the"
                    + " calendar category it maps to")
    void onboardingVerticalMatchesMappedCalendarCategory() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Food", "Filter coffee week", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Local business", "Shop local", BENIGN_TEXT, "APPROVED"),
                                topic(3, "Finance", "Investing basics", BENIGN_TEXT, "APPROVED"),
                                topic(4, "Technology", "Phone launch", BENIGN_TEXT, "APPROVED")));

        assertEquals(
                List.of(1L),
                service.screen(List.of("Food & Cooking"), TODAY).servable().stream()
                        .map(ServableTopic::id)
                        .toList());
        assertEquals(
                List.of(2L, 3L),
                service.screen(List.of("Finance & Business"), TODAY).servable().stream()
                        .map(ServableTopic::id)
                        .toList());
    }

    @Test
    @DisplayName("category map (lane B1): free-text aliases like 'tech' and 'gadgets' reach Technology")
    void freeTextAliasMatchesMappedCalendarCategory() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(4, "Technology", "Phone launch", BENIGN_TEXT, "APPROVED")));

        assertEquals(1, service.screen(List.of("tech"), TODAY).servable().size());
        assertEquals(1, service.screen(List.of("  GADGETS "), TODAY).servable().size());
        assertTrue(service.screen(List.of("Beauty & Skincare"), TODAY).servable().isEmpty());
    }

    @Test
    @DisplayName(
            "category map (lane B1): the creator's raw category still matches, and inner whitespace"
                    + " is collapsed on both sides")
    void rawCategoryAndCollapsedWhitespaceStillMatch() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Tech  &  Gaming", "Raw vertical topic", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Local business", "Shop local", BENIGN_TEXT, "APPROVED")));

        assertEquals(
                List.of(1L),
                service.screen(List.of("tech & gaming"), TODAY).servable().stream()
                        .map(ServableTopic::id)
                        .toList());
        assertEquals(
                List.of(2L),
                service.screen(List.of("Local   business"), TODAY).servable().stream()
                        .map(ServableTopic::id)
                        .toList());
    }

    @Test
    @DisplayName("category map (lane B1): every onboarding vertical maps to at least one calendar category")
    void everyTableEntryMapsIntoTheCalendar() {
        CreatorCategoryMap.TABLE.forEach(
                (key, mapped) -> {
                    assertTrue(!mapped.isEmpty(), key);
                    for (String calendar : mapped) {
                        assertTrue(CreatorCategoryMap.CALENDAR_CATEGORIES.contains(calendar), key);
                        assertEquals(mapped, CreatorCategoryMap.calendarCategoriesFor(key), key);
                    }
                });
        assertEquals(List.of("Culture"), CreatorCategoryMap.calendarCategoriesFor(" culture "));
        assertTrue(CreatorCategoryMap.calendarCategoriesFor("knitting").isEmpty());
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
    // Topic-side mapping: several comma-separated categories, each resolved through the table
    // -----------------------------------------------------------------------------------------

    private List<Long> servedIds(List<String> creatorCategories) {
        return service.screen(creatorCategories, TODAY).servable().stream()
                .map(ServableTopic::id)
                .toList();
    }

    @Test
    @DisplayName("topic-side map: a 'Tech' topic reaches a 'Tech & Gaming' creator (both reach Technology)")
    void techTopicReachesTechAndGamingCreator() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Tech", "Phone launch", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Tech & Gaming")));
        assertEquals(List.of(), servedIds(List.of("Beauty & Skincare")));
    }

    @Test
    @DisplayName("topic-side map: a 'Fashion' topic reaches a 'Fashion & Lifestyle' creator")
    void fashionTopicReachesFashionAndLifestyleCreator() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Fashion", "Festive looks", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Fashion & Lifestyle")));
        assertEquals(List.of(), servedIds(List.of("Food & Cooking")));
    }

    @Test
    @DisplayName("topic-side map: 'Fashion, Culture' reaches a 'Music & Dance' creator via Culture")
    void multiCategoryTopicReachesViaAnyPart() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(topic(1, "Fashion, Culture", "Festive looks", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Music & Dance")));
        assertEquals(List.of(1L), servedIds(List.of("Fashion & Lifestyle")));
        assertEquals(List.of(), servedIds(List.of("Food & Cooking")));
    }

    @Test
    @DisplayName("topic-side map: 'Culture, ALL' reaches every creator, even one with no categories")
    void anAllPartMatchesEveryone() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Culture, all", "Everyone topic", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Finance & Business")));
        assertEquals(List.of(1L), servedIds(List.of()));
        // The Culture part changes nothing next to ALL, so the preview flags it.
        assertEquals(
                List.of(new UnmatchedCategoryTopic(1L, "Culture, all", "Culture", REASON_ALL_WITH_OTHERS)),
                service.unmatchedCategories(TODAY));
    }

    @Test
    @DisplayName("topic-side map: a 'Shopping' topic reaches a 'Fashion & Lifestyle' creator via Lifestyle")
    void shoppingTopicReachesViaLifestyle() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Shopping", "Sale week hauls", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Fashion & Lifestyle")));
        assertEquals(List.of(1L), servedIds(List.of("lifestyle")));
        assertEquals(List.of(), servedIds(List.of("Tech & Gaming")));
    }

    @Test
    @DisplayName(
            "topic-side map: a 'Snacks' topic is listed as unmatched and reaches only a creator who"
                    + " typed 'snacks'")
    void offTableTopicIsUnmatchedAndReachesOnlyThatWord() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Snacks", "Monsoon snacks", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Snacks, Food", "Chai and snacks", BENIGN_TEXT, "APPROVED"),
                                topic(3, "ALL", "Everyone topic", BENIGN_TEXT, "APPROVED"),
                                topic(4, "Food", "Filter coffee week", BENIGN_TEXT, "APPROVED"),
                                // Off-table AND unsafe: still listed (category question only).
                                topic(5, "Snakcs", UNSAFE_TEXT, BENIGN_TEXT, "APPROVED")));

        assertEquals(
                List.of(
                        new UnmatchedCategoryTopic(1L, "Snacks", "Snacks", REASON_UNKNOWN_CATEGORY),
                        new UnmatchedCategoryTopic(2L, "Snacks, Food", "Snacks", REASON_UNKNOWN_CATEGORY),
                        new UnmatchedCategoryTopic(5L, "Snakcs", "Snakcs", REASON_UNKNOWN_CATEGORY)),
                service.unmatchedCategories(TODAY));
        assertEquals(List.of(1L, 2L, 3L), servedIds(List.of("  SNACKS ")));
        assertEquals(List.of(2L, 3L, 4L), servedIds(List.of("Food & Cooking")));
    }

    @Test
    @DisplayName("topic-side map: blank parts ('Food,,') are ignored; an all-blank category matches no one")
    void blankPartsAreIgnored() throws ReflectiveOperationException {
        assertEquals(List.of("Food"), CreatorCategoryMap.splitTopicCategories("Food,,"));
        assertEquals(
                List.of("Fashion", "Culture"),
                CreatorCategoryMap.splitTopicCategories(" Fashion ,  , Culture "));
        assertTrue(CreatorCategoryMap.splitTopicCategories(" , ,").isEmpty());
        assertTrue(CreatorCategoryMap.splitTopicCategories(null).isEmpty());

        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Food,,", "Filter coffee week", BENIGN_TEXT, "APPROVED"),
                                topic(2, " , ", "Nobody topic", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Food & Cooking")));
        assertEquals(List.of(), servedIds(List.of()));
        assertEquals(
                List.of(new UnmatchedCategoryTopic(2L, " , ", "", REASON_UNKNOWN_CATEGORY)),
                service.unmatchedCategories(TODAY));
    }

    @Test
    @DisplayName("topic-side map: the five-topic cap and safety screening still hold for multi-category rows")
    void capAndScreeningHoldForMultiCategoryRows() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(7, "Fashion, Culture", UNSAFE_TEXT, BENIGN_TEXT, "APPROVED"),
                                topic(6, "Music", "Topic 6", BENIGN_TEXT, "APPROVED"),
                                topic(5, "Culture", "Topic 5", BENIGN_TEXT, "APPROVED"),
                                topic(4, "Dance, Snacks", "Topic 4", BENIGN_TEXT, "APPROVED"),
                                topic(3, "Entertainment", "Topic 3", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Food, Culture", "Topic 2", BENIGN_TEXT, "APPROVED"),
                                topic(1, "Culture, ALL", "Topic 1", BENIGN_TEXT, "APPROVED")));

        ScreeningResult result = service.screen(List.of("Music & Dance"), TODAY);

        assertEquals(
                List.of(6L, 5L, 4L, 3L, 2L),
                result.servable().stream().map(ServableTopic::id).toList());
        assertEquals(List.of(new DroppedTopic(7L, "DEATH")), result.dropped());
    }

    // -----------------------------------------------------------------------------------------
    // Topic side does not fan out: a multi-target onboarding option reaches only that option
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "no fan-out: a 'Parenting & Family' topic reaches a 'Parenting & Family' creator, not a"
                    + " 'Food & Cooking' one")
    void parentingTopicDoesNotFanOutToFoodCreators() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(topic(1, "Parenting & Family", "School lunchbox week", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Parenting & Family")));
        assertEquals(List.of(), servedIds(List.of("Food & Cooking")));
        assertEquals(List.of(), servedIds(List.of("Education & Learning")));
        assertEquals(List.of(), servedIds(List.of("parenting")));
        // A deliberate onboarding-option name is not a warning.
        assertTrue(service.unmatchedCategories(TODAY).isEmpty());
    }

    @Test
    @DisplayName("no fan-out: a 'Music & Dance' topic does not reach a 'Fashion & Lifestyle' creator")
    void musicAndDanceTopicDoesNotReachFashionCreators() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(List.of(topic(1, "Music & Dance", "Garba practice", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(), servedIds(List.of("Fashion & Lifestyle")));
        assertEquals(List.of(), servedIds(List.of("Entertainment & Comedy")));
        assertEquals(List.of(1L), servedIds(List.of("music & dance")));
    }

    @Test
    @DisplayName("no fan-out: single-target words still resolve ('Tech' -> Tech & Gaming, 'Shopping' -> Lifestyle)")
    void singleTargetTopicWordsStillResolve() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Tech", "Phone launch", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Shopping", "Sale week hauls", BENIGN_TEXT, "APPROVED"),
                                topic(3, "Food", "Filter coffee week", BENIGN_TEXT, "APPROVED")));

        assertEquals(List.of(1L), servedIds(List.of("Tech & Gaming")));
        assertEquals(List.of(2L), servedIds(List.of("Fashion & Lifestyle")));
        // The creator side still fans out: a Parenting & Family creator gets the Food topic.
        assertEquals(List.of(3L), servedIds(List.of("Parenting & Family")));
    }

    @Test
    @DisplayName("topicMatchKeys: raw part always, calendar category only for exactly one target")
    void topicMatchKeysAddOnlySingleTargets() {
        assertEquals(
                java.util.Set.of("parenting & family"),
                CreatorCategoryMap.topicMatchKeys(List.of("Parenting & Family")));
        assertEquals(
                java.util.Set.of("tech", "technology", "food", "shopping", "lifestyle", "snacks"),
                CreatorCategoryMap.topicMatchKeys(List.of("Tech", " FOOD ", "Shopping", "Snacks", " ")));
        for (String vertical : CreatorCategoryMap.ONBOARDING_VERTICALS) {
            List<String> targets = CreatorCategoryMap.calendarCategoriesFor(vertical);
            int expected = targets.size() == 1 ? 2 : 1;
            assertEquals(expected, CreatorCategoryMap.topicMatchKeys(List.of(vertical)).size(), vertical);
        }
        assertTrue(CreatorCategoryMap.topicMatchKeys(null).isEmpty());
    }

    // -----------------------------------------------------------------------------------------
    // The admin preview's per-part checks
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("per-part check: Gardening is the one calendar category no onboarding option reaches")
    void gardeningIsTheOnlyUnreachedCalendarCategory() {
        assertEquals(List.of("Gardening"), CreatorCategoryMap.calendarCategoriesNoVerticalReaches());
        assertEquals(12, CreatorCategoryMap.ONBOARDING_VERTICALS.size());
        for (String vertical : CreatorCategoryMap.ONBOARDING_VERTICALS) {
            assertTrue(CreatorCategoryMap.TABLE.containsKey(vertical), vertical);
        }
    }

    @Test
    @DisplayName(
            "per-part check: a typo inside a comma list, a Gardening part and a part next to ALL are"
                    + " each flagged; good parts and onboarding-option names are not")
    void unmatchedCategoriesChecksEveryPart() throws ReflectiveOperationException {
        when(contentTopicRepository.findServable(TODAY))
                .thenReturn(
                        List.of(
                                topic(1, "Food, Snakcs", "Chai week", BENIGN_TEXT, "APPROVED"),
                                topic(2, "Gardening", "Monsoon balcony garden", BENIGN_TEXT, "APPROVED"),
                                topic(3, "Culture, gardening", "Tulsi puja", BENIGN_TEXT, "APPROVED"),
                                topic(4, "ALL, Food, Snacks", "Everyone topic", BENIGN_TEXT, "APPROVED"),
                                topic(5, "Tech, Parenting & Family, Shopping", "Good row", BENIGN_TEXT, "APPROVED"),
                                topic(6, "ALL", "Everyone topic", BENIGN_TEXT, "APPROVED")));

        assertEquals(
                List.of(
                        new UnmatchedCategoryTopic(1L, "Food, Snakcs", "Snakcs", REASON_UNKNOWN_CATEGORY),
                        new UnmatchedCategoryTopic(2L, "Gardening", "Gardening", REASON_NO_CREATOR_GROUP),
                        new UnmatchedCategoryTopic(
                                3L, "Culture, gardening", "gardening", REASON_NO_CREATOR_GROUP),
                        new UnmatchedCategoryTopic(4L, "ALL, Food, Snacks", "Food", REASON_ALL_WITH_OTHERS),
                        new UnmatchedCategoryTopic(4L, "ALL, Food, Snacks", "Snacks", REASON_ALL_WITH_OTHERS)),
                service.unmatchedCategories(TODAY));
    }

    @Test
    @DisplayName("category map: the widened verticals and the new aliases reach the new calendar categories")
    void widenedVerticalsAndNewAliases() {
        assertEquals(
                List.of("Beauty", "Culture", "Fashion", "Lifestyle"),
                CreatorCategoryMap.calendarCategoriesFor("Fashion & Lifestyle"));
        assertEquals(
                List.of("Technology", "Gaming"), CreatorCategoryMap.calendarCategoriesFor("Tech & Gaming"));
        assertEquals(
                List.of("Culture", "Entertainment"),
                CreatorCategoryMap.calendarCategoriesFor("Entertainment & Comedy"));
        assertEquals(
                List.of("Education", "Food", "Parenting"),
                CreatorCategoryMap.calendarCategoriesFor("Parenting & Family"));
        assertEquals(
                List.of("Culture", "Music", "Entertainment"),
                CreatorCategoryMap.calendarCategoriesFor("Music & Dance"));
        assertEquals(List.of("Lifestyle"), CreatorCategoryMap.calendarCategoriesFor("Shopping"));
        assertEquals(List.of("Entertainment"), CreatorCategoryMap.calendarCategoriesFor("comedy"));
        assertEquals(List.of("Gaming"), CreatorCategoryMap.calendarCategoriesFor("games"));
        assertEquals(List.of("Music"), CreatorCategoryMap.calendarCategoriesFor("dance"));
        assertEquals(List.of("Parenting"), CreatorCategoryMap.calendarCategoriesFor("family"));
        for (String calendar :
                List.of("Fashion", "Lifestyle", "Entertainment", "Gaming", "Music", "Parenting")) {
            assertEquals(List.of(calendar), CreatorCategoryMap.calendarCategoriesFor(calendar.toLowerCase()));
        }
        assertEquals(17, CreatorCategoryMap.CALENDAR_CATEGORIES.size());
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
