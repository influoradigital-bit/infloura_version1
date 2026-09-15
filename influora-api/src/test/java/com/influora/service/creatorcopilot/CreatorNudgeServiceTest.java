package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.influora.service.creatorcopilot.CreatorNudgeService.UnsafeHeadlineTopic;
import com.influora.service.trendspark.ThemeMatchService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.lang.reflect.Method;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link CreatorNudgeService} — ledger F-0775. The service had zero direct unit
 * coverage; these tests pin every branch of {@code getSuggestion}/{@code markDismissed}/{@code
 * markActed} that a Mockito-level test can honestly reach.
 *
 * <p><b>F-0785 changed what this class can honestly claim — read this before trusting the
 * race-recovery tests below.</b> The above USED to say the {@code catch
 * (DataIntegrityViolationException)} was unreachable dead code, and it was: {@link CreatorNudgeLog}
 * has a caller-assigned {@code @Id}, no {@code @Version} and no {@code Persistable}, so {@code
 * SimpleJpaRepository.save()} routed through {@code em.merge()} and deferred the INSERT to the
 * {@code @Transactional} proxy's commit — outside the try block. F-0785 removed
 * {@code @Transactional} from {@code getSuggestion} and switched the write to {@code
 * saveAndFlush}, so the violation now surfaces from the repository call itself, inside the try.
 *
 * <p>That makes a Mockito stub of {@code saveAndFlush} throwing {@code
 * DataIntegrityViolationException} a FAITHFUL model of the runtime shape for the first time — but
 * only of the shape. <b>What the three {@code lostDailyCapRace} tests below prove: the recovery
 * LOGIC (re-read, return the winner, rethrow when there is no winner, rethrow when the re-read
 * itself fails). What they CANNOT prove: that a real MySQL {@code uq_creator_nudge_day} violation
 * actually arrives at that call site rather than at some later commit.</b> Only a real-MySQL test
 * can falsify that, and it lives in {@code
 * com.influora.integration.dbconstraints.CreatorNudgeDailyCapConcurrencyIntegrationTest}
 * (Testcontainers, skipped where Docker is unreachable). Do not read a green run of this class as
 * proof that the concurrent-500 is fixed.
 *
 * <p>{@link #getSuggestion_isNotTransactional_theAnnotationIsWhatMadeTheCatchDead()} exists because
 * re-adding {@code @Transactional} to {@code getSuggestion} would silently reintroduce F-0785 and
 * NO behavioural test in this class could notice: Mockito calls the bean directly and never goes
 * through the Spring proxy where the annotation has its effect.
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        when(creatorNudgeLogRepository.saveAndFlush(any(CreatorNudgeLog.class))).thenReturn(persisted);

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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
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
        verifyNoNudgeRowWritten();
    }

    // ---------------------------------------------------------------------------------------
    // F-0786 — content filter on the fallback path
    // ---------------------------------------------------------------------------------------

    /**
     * One test per rejected category, driven off the enum itself.
     *
     * <p>{@link #sampleFor} is an EXHAUSTIVE switch expression over {@link UnsafeHeadlineTopic}, so
     * adding a fifth category without also adding a sample headline for it is a COMPILE error here
     * — not a silently-uncovered category that this suite would keep reporting green.
     *
     * <p><b>Anti-vacuity.</b> These four would all pass just as happily if the filter rejected
     * EVERY headline, which would be its own (product-destroying) bug. The counterweight already
     * exists and is untouched: {@link
     * #getSuggestion_aiReturnsNull_usesTemplatedFallbackWithMessageSourceFallback()} asserts a
     * benign headline IS still quoted verbatim, and {@link
     * #getSuggestion_benignHeadlineIsStillQuoted(String)} pins the specific near-miss words the
     * filter documents as deliberately excluded.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(UnsafeHeadlineTopic.class)
    @DisplayName("F-0786: a headline in an unsafe category never reaches creator copy")
    void getSuggestion_unsafeHeadline_neverReachesCreatorCopy(UnsafeHeadlineTopic category) {
        UnsafeHeadlineSample sample = sampleFor(category);

        assertEquals(
                category,
                CreatorNudgeService.firstUnsafeTopic(sample.headline()),
                "test premise broken: this sample headline is not classified as " + category);

        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), sample.headline()));
        // Force the fallback path — the one F-0786 is about, which runs when influora-ai is
        // unreachable and no AI-side safety layer is in play.
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        // Silent-but-functional: the co-pilot degrades, it does not throw and does not go dark.
        assertEquals("ready", result.status());
        assertNotNull(result.suggestion());

        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());

        // The persisted row...
        assertHeadlineAbsent(sample, saved.getHeadline(), saved.getContentIdea(), "persisted row");
        // ...and the DTO the creator is actually served.
        assertHeadlineAbsent(
                sample,
                result.suggestion().headline(),
                result.suggestion().contentIdea(),
                "returned DTO");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // "issue"/"pursue" must not trip LEGAL's "sue" — whole-token matching, not
                // String.contains.
                "Big issue with the new fitness app launch",
                "Brands pursue creator-led campaigns this season",
                // "audience" must not trip DEATH's "die".
                "Audience numbers climb for regional creators",
                // Each of the next three pins a term the filter DOCUMENTS as deliberately excluded
                // for false-positive reasons. If someone "hardens" the filter by adding killer /
                // court / mob back, these go red and force the trade-off to be re-argued.
                "Killer ab workout routine goes viral",
                "Tennis court resurfacing trend in Mumbai clubs",
                "Flash mob proposal video breaks records"
            })
    @DisplayName("F-0786: a benign headline is still quoted verbatim — the filter is not a blanket reject")
    void getSuggestion_benignHeadlineIsStillQuoted(String benignHeadline) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benignHeadline),
                "benign headline was classified unsafe: " + benignHeadline);

        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), benignHeadline));
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());
        assertTrue(
                saved.getContentIdea().contains(benignHeadline),
                "a safe headline must still be quoted, got: " + saved.getContentIdea());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MURDER probe widens in Pune",
                "Murder, probe widens in Pune",
                "murder-probe widens in Pune",
                "Probe widens in Pune (murder)"
            })
    @DisplayName("F-0786: matching survives case and punctuation around the term")
    void firstUnsafeTopic_isCaseAndPunctuationInsensitive(String variant) {
        assertEquals(UnsafeHeadlineTopic.CRIME, CreatorNudgeService.firstUnsafeTopic(variant));
    }

    @Test
    @DisplayName("F-0786: a null headline fails closed — generic copy, no 'null' in the copy, no throw")
    void getSuggestion_nullHeadline_failsClosedToGenericCopy() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), null));
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());
        // The pre-F-0786 template would have rendered: There's a trend around "null" that fits...
        assertFalse(
                saved.getContentIdea().toLowerCase(Locale.ROOT).contains("null"),
                "a missing headline must not be rendered as the literal string 'null', got: "
                        + saved.getContentIdea());
        assertFalse(
                saved.getHeadline().contains("your " + THEME_ACTION + " content"),
                "a missing headline must degrade to generic copy, got: " + saved.getHeadline());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("F-0786: a blank headline fails closed to generic copy too")
    void getSuggestion_blankHeadline_failsClosedToGenericCopy(String blankHeadline) {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), blankHeadline));
        when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertFalse(
                saved.getHeadline().contains("your " + THEME_ACTION + " content"),
                "a blank headline must degrade to generic copy, got: " + saved.getHeadline());
    }

    // ---------------------------------------------------------------------------------------
    // F-0785 — daily-cap race recovery and the dead-catch regression guards
    // ---------------------------------------------------------------------------------------

    /**
     * The single most important regression guard for F-0785, and the only one that can catch a
     * re-added {@code @Transactional}: Mockito invokes the bean directly, never through the Spring
     * proxy, so no behavioural test in this class would notice the annotation coming back — while
     * in production it would once again defer the INSERT past the try block and restore the
     * concurrent 500.
     *
     * <p>The {@code markDismissed} assertion is a POSITIVE CONTROL, not decoration: without it, a
     * change that broke annotation retention or renamed the annotation would make every {@code
     * assertNull} below pass vacuously.
     */
    @Test
    @DisplayName("F-0785: getSuggestion is NOT @Transactional — that annotation is what made the catch dead")
    void getSuggestion_isNotTransactional_theAnnotationIsWhatMadeTheCatchDead() throws Exception {
        Method getSuggestion = CreatorNudgeService.class.getMethod("getSuggestion", String.class);

        assertNull(
                getSuggestion.getAnnotation(Transactional.class),
                "getSuggestion must NOT be @Transactional: an outer transaction defers the"
                        + " merge()-scheduled INSERT to commit, outside the try block, which is exactly"
                        + " what made the DataIntegrityViolationException catch dead code (F-0785)");
        assertNull(
                CreatorNudgeService.class.getAnnotation(Transactional.class),
                "a class-level @Transactional would apply to getSuggestion just the same");

        // POSITIVE CONTROL — proves the reflection above can actually see this annotation.
        assertNotNull(
                CreatorNudgeService.class
                        .getMethod("markDismissed", String.class, String.class)
                        .getAnnotation(Transactional.class),
                "markDismissed should still be @Transactional; if this is null the assertions above"
                        + " are vacuous");
    }

    @Test
    @DisplayName("F-0785: the suggestion row is written with saveAndFlush, never plain save")
    void getSuggestion_writesViaSaveAndFlushNeverPlainSave() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));

        service.getSuggestion(CREATOR_PROFILE_ID);

        verify(creatorNudgeLogRepository).saveAndFlush(any(CreatorNudgeLog.class));
        // save() would route through merge() and only SCHEDULE the INSERT, putting the
        // uq_creator_nudge_day violation somewhere other than inside the try block.
        verify(creatorNudgeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("F-0785: losing the per-day-cap race returns the winner's row, not a 500")
    void getSuggestion_lostDailyCapRace_returnsWinnersRowInsteadOfThrowing() {
        CreatorNudgeLog winner = existingRow(SUGGESTION_ID, THEME_ACTION);
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        // First read: nothing yet (we are the first-of-day, as far as we can see). Second read:
        // the concurrent winner's row, which committed between our read and our write.
        when(creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(creatorNudgeLogRepository.saveAndFlush(any(CreatorNudgeLog.class)))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "Duplicate entry for key 'uq_creator_nudge_day'"));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        assertEquals(
                SUGGESTION_ID,
                result.suggestion().id(),
                "both racers must end up with the SAME row — the winner's");
        assertEquals(winner.getHeadline(), result.suggestion().headline());
    }

    @Test
    @DisplayName("F-0785: an integrity violation that is NOT the cap race still propagates")
    void getSuggestion_integrityViolationWithNoWinnerRow_propagatesInsteadOfFakingSuccess() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        // Both reads miss: nothing was concurrently written, so the violation was something else
        // (creator_nudge_log also carries FKs to creator_profiles and trends, which raise the very
        // same exception type). Swallowing it would hide a real integrity bug forever.
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        DataIntegrityViolationException fkViolation =
                new DataIntegrityViolationException("fk_creator_nudge_log_trend violated");
        when(creatorNudgeLogRepository.saveAndFlush(any(CreatorNudgeLog.class))).thenThrow(fkViolation);

        DataIntegrityViolationException thrown =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> service.getSuggestion(CREATOR_PROFILE_ID));

        assertSame(fkViolation, thrown, "the ORIGINAL violation must propagate, unwrapped");
    }

    @Test
    @DisplayName("F-0785: a failing recovery read rethrows the original violation, with the read failure suppressed")
    void getSuggestion_recoveryReadFails_rethrowsOriginalWithReadFailureSuppressed() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        IllegalStateException readFailure = new IllegalStateException("connection already closed");
        when(creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(Optional.empty())
                .thenThrow(readFailure);
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        DataIntegrityViolationException race =
                new DataIntegrityViolationException("Duplicate entry for key 'uq_creator_nudge_day'");
        when(creatorNudgeLogRepository.saveAndFlush(any(CreatorNudgeLog.class))).thenThrow(race);

        DataIntegrityViolationException thrown =
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> service.getSuggestion(CREATOR_PROFILE_ID));

        assertSame(race, thrown, "a recovery-path failure must not replace the diagnostic exception");
        assertTrue(
                List.of(thrown.getSuppressed()).contains(readFailure),
                "the read failure must be attached as suppressed, not discarded");
    }

    @Test
    @DisplayName("F-0785: dailyCapAdvisory is silent at 1 and explains the DB constraint otherwise")
    void dailyCapAdvisory_silentWhenEnforceableAndExplicitWhenNot() {
        assertNull(
                CreatorNudgeService.dailyCapAdvisory(1),
                "1 is exactly what uq_creator_nudge_day permits — nothing to warn about");

        String advisory = CreatorNudgeService.dailyCapAdvisory(3);
        assertNotNull(advisory);
        assertTrue(advisory.contains("uq_creator_nudge_day"), "must name the real enforcement point");
        assertTrue(advisory.contains("3"), "must name the configured value");
    }

    @Test
    @DisplayName("F-0785: constructing the service reads the cap knob and warns when it cannot take effect")
    void construction_warnsWhenConfiguredCapCannotTakeEffect() {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(CreatorNudgeService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            CreatorCopilotProperties capped = new CreatorCopilotProperties();
            capped.setMaxSuggestionsPerCreatorPerDay(3);
            newService(capped);

            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage()
                                                            .contains("max-suggestions-per-creator-per-day")),
                    "CREATOR_COPILOT_DAILY_CAP=3 cannot be enforced by uq_creator_nudge_day and must"
                            + " not be silently ignored; logged events were: "
                            + appender.list);

            // And the enforceable value stays quiet — otherwise this would warn on every boot.
            appender.list.clear();
            newService(new CreatorCopilotProperties());
            assertTrue(
                    appender.list.isEmpty(),
                    "a cap of 1 is enforceable and must produce no warning, got: " + appender.list);
        } finally {
            serviceLogger.detachAppender(appender);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private CreatorNudgeService newService(CreatorCopilotProperties properties) {
        return new CreatorNudgeService(
                trendRepository,
                creatorProfileRepository,
                creatorNudgeLogRepository,
                themeMatchService,
                aiClient,
                properties);
    }

    /** A realistic third-party news headline for one unsafe category, plus the distinctive words
     * from it that must not survive into creator copy. */
    private record UnsafeHeadlineSample(String headline, List<String> mustBeAbsent) {}

    /**
     * EXHAUSTIVE switch over {@link UnsafeHeadlineTopic} — deliberately a switch EXPRESSION with no
     * {@code default} branch, so a newly added category fails to COMPILE here until it gets a
     * sample headline. That is the coverage guard: "one unit test per rejected category" cannot
     * quietly become "one per category we remembered".
     */
    private static UnsafeHeadlineSample sampleFor(UnsafeHeadlineTopic category) {
        return switch (category) {
            case DEATH ->
                    new UnsafeHeadlineSample(
                            "Veteran actor dies at 78 after long illness",
                            List.of("dies", "actor", "veteran", "illness"));
            case CRIME ->
                    new UnsafeHeadlineSample(
                            "Two arrested in Bengaluru jewellery robbery case",
                            List.of("arrested", "robbery", "jewellery", "Bengaluru"));
            case COMMUNAL ->
                    new UnsafeHeadlineSample(
                            "Curfew imposed after communal violence in Nagpur",
                            List.of("curfew", "communal", "violence", "Nagpur"));
            case LEGAL ->
                    new UnsafeHeadlineSample(
                            "Startup founder sued over alleged trademark infringement",
                            List.of("sued", "trademark", "infringement", "founder"));
        };
    }

    private static void assertHeadlineAbsent(
            UnsafeHeadlineSample sample, String headline, String contentIdea, String where) {
        String copy = headline + " || " + contentIdea;
        String copyLower = copy.toLowerCase(Locale.ROOT);
        String headlineLower = sample.headline().toLowerCase(Locale.ROOT);

        assertFalse(
                copyLower.contains(headlineLower),
                "the raw headline leaked verbatim into the " + where + ": " + copy);

        assertFalse(sample.mustBeAbsent().isEmpty(), "test premise: name at least one word");
        for (String word : sample.mustBeAbsent()) {
            String wordLower = word.toLowerCase(Locale.ROOT);
            // Anti-vacuity: a typo'd or reworded entry would be trivially absent from the copy and
            // would prove nothing at all.
            assertTrue(
                    headlineLower.contains(wordLower),
                    "test premise broken: '" + word + "' does not occur in the sample headline");
            // Word-level, not just verbatim: a future "quote the first 60 characters" teaser would
            // slip past a verbatim-only check.
            assertFalse(
                    copyLower.contains(wordLower),
                    "headline word '" + word + "' leaked into the " + where + ": " + copy);
        }
    }

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

    /** F-0785: the suggestion write is {@code saveAndFlush}, not {@code save} — see {@link
     * #getSuggestion_writesViaSaveAndFlushNeverPlainSave()} for why that distinction is the whole
     * fix and must not be "simplified" back. */
    private void givenSaveEchoesArgument() {
        when(creatorNudgeLogRepository.saveAndFlush(any(CreatorNudgeLog.class)))
                .thenAnswer(returnsFirstArg());
    }

    private void givenOwnedRow(CreatorNudgeLog row) {
        when(creatorNudgeLogRepository.findByIdAndCreatorProfileId(SUGGESTION_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(row));
    }

    private CreatorNudgeLog captureSaved() {
        ArgumentCaptor<CreatorNudgeLog> captor = ArgumentCaptor.forClass(CreatorNudgeLog.class);
        verify(creatorNudgeLogRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    /** No suggestion row reached persistence by EITHER write method. Checking both (rather than
     * just the one the service currently calls) means a future swap between {@code save} and
     * {@code saveAndFlush} cannot turn these "stayed silent" assertions vacuous. */
    private void verifyNoNudgeRowWritten() {
        verify(creatorNudgeLogRepository, never()).save(any());
        verify(creatorNudgeLogRepository, never()).saveAndFlush(any());
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
