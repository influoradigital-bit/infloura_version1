package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.CreatorNudgeLog;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.NudgeMessageSource;
import com.influora.integration.ai.CreatorSuggestionAiClient;
import com.influora.integration.ai.CreatorSuggestionAiClient.SuggestionCopy;
import com.influora.repository.CreatorNudgeLogRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.TrendRepository;
import com.influora.service.creatorcopilot.CreatorNudgeService.SuggestionResult;
import com.influora.service.trendspark.ThemeMatchService;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CreatorNudgeService} — ledger F-0775. The service had zero direct unit
 * coverage; these tests pin every branch of {@code getSuggestion}/{@code markDismissed}/{@code
 * markActed} that a Mockito-level test can honestly reach.
 *
 * <p><b>What is deliberately NOT covered here, and why.</b> {@code getSuggestion}'s {@code catch
 * (DataIntegrityViolationException)} (CreatorNudgeService.java:162-170) is unreachable in
 * production: {@link CreatorNudgeLog}'s {@code @Id} is caller-assigned at line 149, the entity has
 * no {@code @Version} and does not implement {@code Persistable}, so Spring Data's {@code isNew()}
 * is false and {@code SimpleJpaRepository.save()} calls {@code em.merge()}, which schedules the
 * INSERT without flushing. The {@code uq_creator_nudge_day} violation therefore fires at
 * transaction commit — inside the {@code @Transactional} proxy, AFTER the try block has exited —
 * so the catch never runs and a genuine concurrent first-of-day race 500s today. A Mockito test
 * that stubs {@code save()} to throw would prove only that the mapping inside the catch compiles,
 * while reading as evidence that the per-day-cap race is handled. It is not, so no such test is
 * written here. Fixing the catch is a main-source change (and {@code saveAndFlush} alone is not
 * the fix — the recovery read at 166-167 would then run on a rollback-only persistence context);
 * the branch belongs to an H2 {@code @DataJpaTest}, following {@code
 * IdempotencyKeyRecordRepositoryPersistenceTest}'s precedent, not to this class.
 *
 * <p><b>Mockito hazards observed.</b> Every "the AI client was not called" assertion uses {@link
 * org.mockito.Mockito#verifyNoInteractions}, never {@code verify(aiClient, never())
 * .requestSuggestion(anyString(), ...)}: {@code trendText} comes from a mocked {@link Trend} and
 * can legitimately be null, and Mockito 2+ {@code anyString()} does not match null, so an
 * {@code anyString()}-based never()-verify would pass vacuously against code that DID call the
 * client. Likewise {@code save()} is stubbed with {@code returnsFirstArg()} in every ready-path
 * test — an unstubbed {@code @Mock} returns null from {@code save()} and {@code toDto(null)} NPEs
 * at line 254, which would make the whole ready path untestable rather than merely red.
 */
@ExtendWith(MockitoExtension.class)
class CreatorNudgeServiceTest {

    private static final String CREATOR_PROFILE_ID = ulid26("CREATORPROFILE1");
    private static final String SUGGESTION_ID = ulid26("SUGGESTION9");

    /** A suggestion id that DOES exist but belongs to {@link #OTHER_CREATOR_PROFILE_ID}. Distinct
     * from {@link #SUGGESTION_ID} so the "row is missing" and "row is someone else's" scenarios can
     * be stubbed side by side in one test without one stub overwriting the other. */
    private static final String FOREIGN_SUGGESTION_ID = ulid26("FOREIGNSUGG");

    private static final String OTHER_CREATOR_PROFILE_ID = ulid26("OTHERCREATOR");
    private static final String TREND_ID = ulid26("TREND1");
    private static final String TREND_ID_B = ulid26("TREND2");
    private static final String TREND_ID_C = ulid26("TREND3");

    /**
     * Real taxonomy members. {@code strength} and {@code action} are chosen so that the {@code
     * HashSet} {@code ThemeMatchService.parseThemeJson} returns iterates them in an order that is
     * the REVERSE of alphabetical (strength lands in bucket 4, action in bucket 9 of a 16-bucket
     * table), which is what makes {@link #getSuggestion_themeIsLowestCommonThemeAlphabetically()}
     * able to fail if {@code bestMatchedTheme}'s {@code .sorted()} is deleted. Do not swap these
     * for e.g. {@code beauty}/{@code fitness}/{@code travel} — those happen to iterate in
     * alphabetical order already, which would make that test vacuous.
     */
    private static final String THEME_STRENGTH = "strength";

    private static final String THEME_ACTION = "action";
    private static final String TREND_TEXT = "Diwali morning workout challenge";

    @Mock private TrendRepository trendRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorNudgeLogRepository creatorNudgeLogRepository;
    @Mock private CreatorSuggestionAiClient aiClient;

    /** Real, not mocked — theme scoring IS the behaviour under test on the pick-best path, and it
     * needs no Spring context: {@code score}/{@code parseThemeJson} are pure Jackson parsing and
     * never touch the {@code @PostConstruct}-loaded taxonomy set. */
    private ThemeMatchService themeMatchService;

    /** Real, not mocked — the threshold/prompt-version defaults are part of the contract. */
    private CreatorCopilotProperties props;

    private CreatorNudgeService service;

    @BeforeEach
    void setUp() {
        themeMatchService = new ThemeMatchService();
        props = new CreatorCopilotProperties();
        props.setScoreThreshold(2);
        service =
                new CreatorNudgeService(
                        trendRepository,
                        creatorProfileRepository,
                        creatorNudgeLogRepository,
                        themeMatchService,
                        aiClient,
                        props);
        // Nothing stubbed here on purpose: several tests assert that collaborators are never
        // touched at all, which a shared stub would quietly defeat.
    }

    // ---------------------------------------------------------------------------------------
    // getSuggestion — guards
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSuggestion: unknown creator profile 404s and touches nothing else")
    void getSuggestion_profileNotFound_throws404AndTouchesNothingElse() {
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.empty());

        ApiException thrown =
                assertThrows(ApiException.class, () -> service.getSuggestion(CREATOR_PROFILE_ID));

        assertEquals("CREATOR_PROFILE_NOT_FOUND", thrown.getCode());
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatus());
        assertEquals("Creator profile not found", thrown.getMessage());
        verifyNoInteractions(creatorNudgeLogRepository, trendRepository, aiClient);
    }

    @Test
    @DisplayName("getSuggestion: today's row is returned as-is — no re-scoring, no AI spend, no write")
    void getSuggestion_todaysRowExists_returnsSameRowWithNoRescoringAndNoAiSpend() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        CreatorNudgeLog existing = existingRow(SUGGESTION_ID, THEME_ACTION);
        when(creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(Optional.of(existing));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        assertEquals(SUGGESTION_ID, result.suggestion().id());
        // THE PER-DAY CAP READ PATH: a same-day repeat must not re-score or re-phrase.
        verifyNoInteractions(trendRepository, aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSuggestion: idempotent read uses the caller's profile id and the start-of-UTC-day boundary")
    void getSuggestion_idempotentRead_usesCallerProfileIdAndStartOfUtcDayBoundary() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        when(creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(any(), any()))
                .thenReturn(Optional.of(existingRow(SUGGESTION_ID, THEME_ACTION)));

        Instant startOfDayBefore = startOfUtcDay();
        service.getSuggestion(CREATOR_PROFILE_ID);
        Instant startOfDayAfter = startOfUtcDay();

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> afterCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(creatorNudgeLogRepository)
                .findByCreatorProfileIdAndShownAtAfter(idCaptor.capture(), afterCaptor.capture());

        assertEquals(CREATOR_PROFILE_ID, idCaptor.getValue());
        Instant captured = afterCaptor.getValue();
        // Accepts either side of a midnight rollover between the call and this assertion, but
        // still fails for any other boundary (e.g. now(), now()-24h, local-zone midnight).
        assertTrue(
                captured.equals(startOfDayBefore) || captured.equals(startOfDayAfter),
                "expected start of the current UTC day, got " + captured);
    }

    @Test
    @DisplayName("getSuggestion: null theme_tags -> pending_tagging, no scoring and no write")
    void getSuggestion_nullThemeTags_returnsPendingTagging() {
        givenProfile("Asha", null);
        givenNoRowYetToday();

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("pending_tagging", result.status());
        assertNull(result.suggestion());
        verifyNoInteractions(trendRepository, aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "theme_tags = [{0}]")
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("getSuggestion: blank theme_tags -> pending_tagging (isBlank half of the guard)")
    void getSuggestion_blankThemeTags_returnsPendingTagging(String blank) {
        givenProfile("Asha", blank);
        givenNoRowYetToday();

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("pending_tagging", result.status());
        assertNull(result.suggestion());
        verifyNoInteractions(trendRepository, aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSuggestion: theme_tags '[]' is NOT blank — falls through to scoring, so no_suggestion_today")
    void getSuggestion_emptyThemeTagsJsonArray_returnsNoSuggestionTodayNotPendingTagging() {
        givenProfile("Asha", "[]");
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("no_suggestion_today", result.status());
        verifyNoInteractions(aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // getSuggestion — trend selection
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSuggestion: no active trends -> no_suggestion_today, and no row is written")
    void getSuggestion_noActiveTrends_returnsNoSuggestionTodayAndWritesNoRow() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        givenNoRowYetToday();
        when(trendRepository.findActive(any())).thenReturn(List.of());

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        // bestTrend == null half of the line-123 OR (bestScore never leaves its -1 seed).
        assertEquals("no_suggestion_today", result.status());
        assertNull(result.suggestion());
        verifyNoInteractions(aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSuggestion: an active trend with zero overlap -> no_suggestion_today, and no row is written")
    void getSuggestion_onlyZeroScoringTrends_returnsNoSuggestionTodayAndWritesNoRow() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        givenNoRowYetToday();
        // Disjoint themes: bestTrend is non-null with score 0 — the bestScore < threshold half of
        // the OR, which the empty-list test above cannot reach.
        givenActiveTrends(trend(TREND_ID, themesJson("luxury", "heritage"), TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("no_suggestion_today", result.status());
        verifyNoInteractions(aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSuggestion: score one below the threshold stays silent")
    void getSuggestion_scoreOneBelowThreshold_staysSilent() {
        props.setScoreThreshold(3);
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("no_suggestion_today", result.status());
        verifyNoInteractions(aiClient);
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSuggestion: score exactly AT the threshold produces a suggestion (comparison is '<', not '<=')")
    void getSuggestion_scoreExactlyAtThreshold_producesSuggestion() {
        props.setScoreThreshold(2);
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        assertEquals(2, captureSaved().getMatchScore().intValue());
    }

    @Test
    @DisplayName("getSuggestion: picks the highest-scoring trend, not the first and not the last")
    void getSuggestion_picksHighestScoringTrend_notFirstAndNotMostRecent() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION, "energy", "family"));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(
                trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT), // 2
                trend(
                        TREND_ID_B,
                        themesJson(THEME_STRENGTH, THEME_ACTION, "energy", "family"),
                        TREND_TEXT), // 4
                trend(TREND_ID_C, themesJson(THEME_STRENGTH, THEME_ACTION, "energy"), TREND_TEXT)); // 3

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(TREND_ID_B, saved.getTrendId());
        assertEquals(4, saved.getMatchScore().intValue());
    }

    @Test
    @DisplayName("getSuggestion: tied scores keep the FIRST trend in repository order (strict '>')")
    void getSuggestion_tiedScores_keepFirstTrendInRepositoryOrder() {
        givenProfile("Asha", themesJson(THEME_STRENGTH, THEME_ACTION));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(
                trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT),
                trend(TREND_ID_B, themesJson(THEME_STRENGTH, THEME_ACTION), "a different trend"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals(TREND_ID, captureSaved().getTrendId());
    }

    // ---------------------------------------------------------------------------------------
    // getSuggestion — theme derivation
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSuggestion: theme is the alphabetically-lowest common theme, not the set's iteration order")
    void getSuggestion_themeIsLowestCommonThemeAlphabetically() {
        String trendThemes = themesJson(THEME_STRENGTH, THEME_ACTION);
        // Premise of this test: the parsed HashSet iterates strength BEFORE action, i.e. the
        // reverse of alphabetical order. Without that, deleting `.sorted()` from bestMatchedTheme
        // would still yield "action" and the assertion below would prove nothing. Asserted rather
        // than assumed so a future JDK hashing change fails loudly instead of going vacuous.
        Iterator<String> it = themeMatchService.parseThemeJson(trendThemes).iterator();
        assertEquals(
                THEME_STRENGTH,
                it.next(),
                "test premise broken: pick two taxonomy themes whose HashSet order is the reverse"
                        + " of alphabetical, otherwise this test cannot detect a missing .sorted()");

        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, trendThemes, TREND_TEXT));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(THEME_ACTION, saved.getTheme());
        assertEquals(2, saved.getMatchScore().intValue());
    }

    @Test
    @DisplayName("getSuggestion: theme is server-derived and never taken from AI copy")
    void getSuggestion_themeIsServerDerived_neverTakenFromAiCopy() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Your luxury content is trending", "Post about luxury and heritage"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals(THEME_ACTION, captureSaved().getTheme());
    }

    // ---------------------------------------------------------------------------------------
    // getSuggestion — AI phrasing and fallback
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSuggestion: AI call receives (creatorProfileId, theme, trendText) in that order")
    void getSuggestion_aiCallReceivesCreatorIdThemeAndTrendTextInThatOrder() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        service.getSuggestion(CREATOR_PROFILE_ID);

        verify(aiClient).requestSuggestion(eq(CREATOR_PROFILE_ID), eq(THEME_ACTION), eq(TREND_TEXT));
    }

    @Test
    @DisplayName("getSuggestion: AI copy is persisted with messageSource=AI")
    void getSuggestion_aiReturnsCopy_persistsAiCopyWithMessageSourceAi() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(new SuggestionCopy("AI headline", "AI content idea"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals("AI headline", saved.getHeadline());
        assertEquals("AI content idea", saved.getContentIdea());
        assertEquals(NudgeMessageSource.AI, saved.getMessageSource());
    }

    @Test
    @DisplayName("getSuggestion: AI returns null -> templated fallback with messageSource=FALLBACK")
    void getSuggestion_aiReturnsNull_usesTemplatedFallbackWithMessageSourceFallback() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());
        assertEquals(
                String.format("%s, your %s content is trending right now", "Asha", THEME_ACTION),
                saved.getHeadline());
        assertTrue(
                saved.getContentIdea().contains(TREND_TEXT),
                "fallback content idea must quote the trend text, got: " + saved.getContentIdea());
        assertTrue(
                saved.getContentIdea().contains(THEME_ACTION),
                "fallback content idea must name the theme, got: " + saved.getContentIdea());
    }

    @Test
    @DisplayName("getSuggestion: an AI client exception is swallowed into the fallback, never propagated")
    void getSuggestion_aiClientThrows_swallowedIntoFallbackAndNeverPropagates() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenThrow(new RuntimeException("influora-ai unreachable"));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        assertEquals(NudgeMessageSource.FALLBACK, captureSaved().getMessageSource());
    }

    @Test
    @DisplayName("getSuggestion: fallback with a null display name addresses the creator as 'You'")
    void getSuggestion_fallbackWithNullDisplayName_usesYou() {
        assertFallbackHeadlineStartsWithYou(null);
    }

    @Test
    @DisplayName("getSuggestion: fallback with a blank display name addresses the creator as 'You'")
    void getSuggestion_fallbackWithBlankDisplayName_usesYou() {
        assertFallbackHeadlineStartsWithYou("   ");
    }

    // ---------------------------------------------------------------------------------------
    // getSuggestion — persistence and DTO
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSuggestion: persisted row carries the canonical field set")
    void getSuggestion_persistsCanonicalRowFields() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertNotNull(saved.getId());
        assertEquals(26, saved.getId().length(), "id must be a ULID");
        assertEquals(CREATOR_PROFILE_ID, saved.getCreatorProfileId());
        assertEquals(TREND_ID, saved.getTrendId());
        assertEquals(2, saved.getMatchScore().intValue());
        assertEquals(THEME_ACTION, saved.getTheme());
        assertEquals("creator-copilot-v1", props.getPromptVersion());
        assertEquals(props.getPromptVersion(), saved.getPromptVersion());
        assertNotNull(saved.getShownAt());
        assertNull(saved.getDismissedAt());
        assertNull(saved.getActedAt());
    }

    @Test
    @DisplayName("getSuggestion: the returned DTO is built from the SAVED row, not the pre-save local")
    void getSuggestion_returnedDtoMirrorsPersistedRow() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        // A DIFFERENT instance than the argument: with returnsFirstArg the two would be the same
        // object reference and this assertion could not distinguish line 172 from line 161.
        CreatorNudgeLog persisted = existingRow(SUGGESTION_ID, "wellness");
        when(creatorNudgeLogRepository.save(any(CreatorNudgeLog.class))).thenReturn(persisted);

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertNotSame(persisted, captureSaved());
        assertEquals(persisted.getId(), result.suggestion().id());
        assertEquals(persisted.getTheme(), result.suggestion().theme());
        assertEquals(persisted.getHeadline(), result.suggestion().headline());
        assertEquals(persisted.getContentIdea(), result.suggestion().contentIdea());
    }

    @Test
    @DisplayName("getSuggestion: expiresAt is the start of the NEXT UTC day, as an ISO-8601 string")
    void getSuggestion_expiresAtIsStartOfNextUtcDay() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        String expected =
                saved.getShownAt()
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toString();
        assertEquals(expected, result.suggestion().expiresAt());
        assertTrue(
                result.suggestion().expiresAt().endsWith("T00:00:00Z"),
                "expiresAt must be a midnight-UTC instant, got " + result.suggestion().expiresAt());
    }

    // ---------------------------------------------------------------------------------------
    // markDismissed / markActed
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("markDismissed: an owned row is stamped and saved")
    void markDismissed_ownedRow_stampsDismissedAtAndSaves() {
        CreatorNudgeLog row = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenOwnedRow(row);

        service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        assertNotNull(row.getDismissedAt());
        verify(creatorNudgeLogRepository).save(same(row));
    }

    @Test
    @DisplayName("markDismissed: a second call keeps the original timestamp and still saves")
    void markDismissed_alreadyDismissed_keepsOriginalTimestampAndStillSaves() {
        CreatorNudgeLog row = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenOwnedRow(row);

        // The builder exposes no dismissedAt setter, so the only way to reach the already-stamped
        // state is a first call. Identity (not equality) is asserted below because Instant.now()
        // on a coarse clock can return an equal-but-distinct value, which would let a broken
        // set-once guard pass a value-equality assertion.
        service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID);
        Instant first = row.getDismissedAt();
        assertNotNull(first);

        service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        assertSame(first, row.getDismissedAt());
        verify(creatorNudgeLogRepository, times(2)).save(same(row));
    }

    @Test
    @DisplayName("markDismissed: unknown or foreign suggestion 404s and never saves")
    void markDismissed_unknownOrForeignSuggestion_throws404AndNeverSaves() {
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () -> service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID));

        assertEquals("SUGGESTION_NOT_FOUND", thrown.getCode());
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatus());
        assertEquals("Suggestion not found", thrown.getMessage());
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("markDismissed: looks up (suggestionId, creatorProfileId) — arguments are not swapped")
    void markDismissed_looksUpBySuggestionIdThenCreatorId_argumentsNotSwapped() {
        CreatorNudgeLog row = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenOwnedRow(row);

        service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        verify(creatorNudgeLogRepository)
                .findByIdAndCreatorProfileId(eq(SUGGESTION_ID), eq(CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("markActed: an owned row is stamped and saved")
    void markActed_ownedRow_stampsActedAtAndSaves() {
        CreatorNudgeLog row = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenOwnedRow(row);

        service.markActed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        assertNotNull(row.getActedAt());
        verify(creatorNudgeLogRepository).save(same(row));
    }

    @Test
    @DisplayName("markActed: a second call keeps the original timestamp and still saves")
    void markActed_alreadyActed_keepsOriginalTimestampAndStillSaves() {
        CreatorNudgeLog row = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenOwnedRow(row);

        service.markActed(CREATOR_PROFILE_ID, SUGGESTION_ID);
        Instant first = row.getActedAt();
        assertNotNull(first);

        service.markActed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        assertSame(first, row.getActedAt());
        verify(creatorNudgeLogRepository, times(2)).save(same(row));
    }

    @Test
    @DisplayName("markActed: unknown or foreign suggestion 404s and never saves")
    void markActed_unknownOrForeignSuggestion_throws404AndNeverSaves() {
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        ApiException thrown =
                assertThrows(
                        ApiException.class, () -> service.markActed(CREATOR_PROFILE_ID, SUGGESTION_ID));

        assertEquals("SUGGESTION_NOT_FOUND", thrown.getCode());
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatus());
        assertEquals("Suggestion not found", thrown.getMessage());
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("markActed and markDismissed stamp different columns and never each other's")
    void markActed_doesNotStampDismissedAt_andMarkDismissed_doesNotStampActedAt() {
        CreatorNudgeLog actedRow = existingRow(SUGGESTION_ID, THEME_ACTION);
        CreatorNudgeLog dismissedRow = existingRow(SUGGESTION_ID, THEME_ACTION);
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(actedRow))
                .thenReturn(Optional.of(dismissedRow));

        service.markActed(CREATOR_PROFILE_ID, SUGGESTION_ID);
        service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID);

        assertNotNull(actedRow.getActedAt());
        assertNull(actedRow.getDismissedAt());
        assertNotNull(dismissedRow.getDismissedAt());
        assertNull(dismissedRow.getActedAt());
    }

    /**
     * Pins the no-IDOR-oracle rule at CreatorNudgeService.java:192-199 (API-CONTRACT.md §1.2): a
     * suggestion id that does not exist and a suggestion id that belongs to ANOTHER creator must be
     * indistinguishable to the caller, so a creator cannot enumerate other creators' suggestion ids
     * by diffing the two responses.
     *
     * <p>The two scenarios are deliberately given different repository STATE, not just identical
     * stubs. The foreign row is stubbed into the unscoped {@code findById} — which the service must
     * never consult — so that a future "resolve by id, then compare the owner" refactor would have
     * the data available to leak a distinguishable error. That is precisely what the assertions
     * below forbid. Both {@code lenient()} stubs are unused today by design; strict stubbing would
     * otherwise fail the test for the very property it is asserting.
     *
     * <p>Identity between the two errors is asserted AND their absolute values are pinned — pairwise
     * equality alone would stay green if both scenarios started returning a 403, which would itself
     * be the leak.
     */
    @Test
    @DisplayName("requireOwnedSuggestion: a missing row and another creator's row give the identical 404")
    void requireOwnedSuggestion_missingAndForeignRowsProduceIdenticalError() {
        // Scenario A — the id does not exist anywhere.
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        lenient().when(creatorNudgeLogRepository.findById(SUGGESTION_ID)).thenReturn(Optional.empty());

        // Scenario B — the id exists, but it is another creator's row, so the ownership-scoped read
        // misses for exactly the same reason an unknown id does.
        CreatorNudgeLog foreignRow =
                CreatorNudgeLog.builder()
                        .id(FOREIGN_SUGGESTION_ID)
                        .creatorProfileId(OTHER_CREATOR_PROFILE_ID)
                        .trendId(TREND_ID)
                        .matchScore(2)
                        .theme(THEME_ACTION)
                        .headline("Another creator's headline")
                        .contentIdea("Another creator's content idea")
                        .messageSource(NudgeMessageSource.AI)
                        .promptVersion("creator-copilot-v1")
                        .build();
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(
                        FOREIGN_SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
        lenient()
                .when(creatorNudgeLogRepository.findById(FOREIGN_SUGGESTION_ID))
                .thenReturn(Optional.of(foreignRow));

        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () -> service.markDismissed(CREATOR_PROFILE_ID, SUGGESTION_ID));
        ApiException foreign =
                assertThrows(
                        ApiException.class,
                        () -> service.markDismissed(CREATOR_PROFILE_ID, FOREIGN_SUGGESTION_ID));

        assertEquals(missing.getCode(), foreign.getCode(), "error code must not distinguish the two");
        assertEquals(
                missing.getStatus(), foreign.getStatus(), "HTTP status must not distinguish the two");
        assertEquals(
                missing.getMessage(), foreign.getMessage(), "message must not distinguish the two");

        // Pin the absolute values so "both became a 403" cannot pass the equality checks above.
        assertEquals("SUGGESTION_NOT_FOUND", foreign.getCode());
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());
        assertEquals("Suggestion not found", foreign.getMessage());

        // The other verb must agree with the first — a divergence between markDismissed and
        // markActed would itself be an oracle.
        ApiException foreignViaActed =
                assertThrows(
                        ApiException.class,
                        () -> service.markActed(CREATOR_PROFILE_ID, FOREIGN_SUGGESTION_ID));
        assertEquals(foreign.getCode(), foreignViaActed.getCode());
        assertEquals(foreign.getStatus(), foreignViaActed.getStatus());
        assertEquals(foreign.getMessage(), foreignViaActed.getMessage());

        // And no scenario may mutate or persist anything.
        assertNull(foreignRow.getDismissedAt());
        assertNull(foreignRow.getActedAt());
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void assertFallbackHeadlineStartsWithYou(String displayName) {
        givenProfile(displayName, themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());
        assertTrue(
                saved.getHeadline().startsWith("You, your "),
                "expected the 'You' fallback name, got: " + saved.getHeadline());
    }

    private void givenProfile(String displayName, String themeTagsJson) {
        CreatorProfile profile =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, ulid26("USER1"), displayName);
        profile.setThemeTagsJson(themeTagsJson);
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(profile));
    }

    private void givenNoRowYetToday() {
        when(creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(Optional.empty());
    }

    private void givenActiveTrends(Trend... trends) {
        when(trendRepository.findActive(any())).thenReturn(List.of(trends));
    }

    private void givenSaveEchoesArgument() {
        when(creatorNudgeLogRepository.save(any(CreatorNudgeLog.class))).thenAnswer(returnsFirstArg());
    }

    private void givenOwnedRow(CreatorNudgeLog row) {
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(row));
    }

    private CreatorNudgeLog captureSaved() {
        ArgumentCaptor<CreatorNudgeLog> captor = ArgumentCaptor.forClass(CreatorNudgeLog.class);
        verify(creatorNudgeLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private static CreatorNudgeLog existingRow(String id, String theme) {
        return CreatorNudgeLog.builder()
                .id(id)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .trendId(TREND_ID)
                .matchScore(2)
                .theme(theme)
                .headline("Existing headline")
                .contentIdea("Existing content idea")
                .messageSource(NudgeMessageSource.AI)
                .promptVersion("creator-copilot-v1")
                .build();
    }

    /** {@link Trend} has no public constructor or builder, so selection tests use mocks. Stubs are
     * lenient because a losing trend is only ever asked for its themes JSON. */
    private static Trend trend(String id, String themesJson, String trendText) {
        Trend t = mock(Trend.class);
        lenient().when(t.getId()).thenReturn(id);
        lenient().when(t.getThemesJson()).thenReturn(themesJson);
        lenient().when(t.getTrendText()).thenReturn(trendText);
        return t;
    }

    private static String themesJson(String... themes) {
        return "[\"" + String.join("\",\"", themes) + "\"]";
    }

    private static Instant startOfUtcDay() {
        return Instant.now()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /** 26-character ULID-shaped identifier. The real service stamps {@code Ulids.newUlid()}, which
     * is genuinely 26 characters, and the persisted-fields test asserts that length. */
    private static String ulid26(String label) {
        return ("01H" + label.toUpperCase(Locale.ROOT) + "0".repeat(26)).substring(0, 26);
    }

    static {
        // Guards the constants above against a typo that would silently make them non-ULID-shaped.
        for (String id :
                List.of(
                        CREATOR_PROFILE_ID,
                        OTHER_CREATOR_PROFILE_ID,
                        SUGGESTION_ID,
                        FOREIGN_SUGGESTION_ID,
                        TREND_ID,
                        TREND_ID_B,
                        TREND_ID_C)) {
            if (id.length() != 26) {
                throw new IllegalStateException("test id must be 26 chars: " + id);
            }
        }
    }
}
