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

import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.CreatorNudgeLog;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.NudgeMessageSource;
import com.influora.integration.ai.CreatorSuggestionAiClient;
import com.influora.integration.ai.CreatorSuggestionAiClient.SuggestionCopy;
import com.influora.integration.ai.dto.CreatorSuggestionAiDtos.SuggestionResponse;
import com.influora.repository.CreatorNudgeLogRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.TrendRepository;
import com.influora.service.creatorcopilot.CreatorNudgeService.SuggestionResult;
import com.influora.service.creatorcopilot.CreatorNudgeService.UnsafeHeadlineTopic;
import com.influora.service.trendspark.ThemeMatchService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
                                "Your luxury content is trending",
                                "Post about luxury and heritage",
                                "AI"));

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
                .thenReturn(new SuggestionCopy("AI headline", "AI content idea", "AI"));

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
        // F-0854 (vikram, 2026-09-17): an unsafe headline is now withheld from the AI call
        // entirely (see CreatorNudgeService.getSuggestion's trend-headline gate), so this stub is
        // never exercised — lenient, and verified unreached below via verifyNoInteractions.
        lenient().when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        // Silent-but-functional: the co-pilot degrades, it does not throw and does not go dark.
        assertEquals("ready", result.status());
        assertNotNull(result.suggestion());
        verifyNoInteractions(aiClient);

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
        // F-0854 (vikram, 2026-09-17): a null headline fails isQuotableInCreatorCopy, so it is now
        // withheld from the AI call before this stub could ever be exercised — lenient.
        lenient().when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        verifyNoInteractions(aiClient);
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
        // F-0854 (vikram, 2026-09-17): a blank headline fails isQuotableInCreatorCopy, so it is
        // now withheld from the AI call before this stub could ever be exercised — lenient.
        lenient().when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        verifyNoInteractions(aiClient);
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
    // F-0826 — Kabir's 22 proven term-list bypasses
    // ---------------------------------------------------------------------------------------

    /**
     * Every bypass string from Kabir's blocking review of commit ab87c46, one row each, in the
     * order he reported them: eight inflections whose base form was already listed, then fourteen
     * terms from categories that were absent outright.
     *
     * <p>Column 3 is the ANTI-VACUITY CONTROL and is the reason this suite has teeth: it is the
     * same headline with the offending word removed, and it must be classified SAFE. Without it a
     * row would still pass if some other word in the fixture ("police", "died") were doing the
     * blocking, which is exactly how a filter test goes green while the gap it names stays open.
     * Every row therefore proves two things at once — this headline blocks, and it blocks BECAUSE
     * OF the term under test.
     */
    static Stream<Arguments> kabirTermListBypasses() {
        return Stream.of(
                // --- inflections of terms that were already present in some other form ---
                arguments("murderer", "Serial murderer still at large", "Serial still at large"),
                arguments("rapes", "Man rapes minor, say reports", "Man minor, say reports"),
                arguments("rapists", "Rapists identified in the case file", "identified in the case file"),
                arguments("killings", "Wave of killings grips the district", "Wave of grips the district"),
                arguments("stabs", "Teen stabs classmate at school", "Teen classmate at school"),
                arguments("molestation", "Molestation complaint filed in Thane", "complaint filed in Thane"),
                arguments("arrests", "Six arrests in the Pune racket", "Six in the Pune racket"),
                arguments("abducted", "Two children abducted from a fair", "Two children from a fair"),
                // --- categories that were absent outright ---
                arguments("massacre", "Village massacre leaves the state stunned", "Village leaves the state stunned"),
                arguments("homicide", "Homicide unit takes over the file", "unit takes over the file"),
                arguments("manslaughter", "Driver charged with manslaughter", "Driver charged with it"),
                arguments("beheaded", "Statue beheaded in an overnight raid", "Statue in an overnight raid"),
                arguments("hostage", "Hostage standoff ends at the mall", "standoff ends at the mall"),
                arguments("bomb", "Bomb found near the metro gate", "found near the metro gate"),
                arguments("blast", "Blast rips through a market lane", "rips through a market lane"),
                arguments("explosion", "Explosion reported at the plant", "reported at the plant"),
                arguments("ied", "IED recovered ahead of the rally", "recovered ahead of the rally"),
                arguments("self harm (spaced)", "Self harm helpline sees record calls", "helpline sees record calls"),
                arguments("self-harm (hyphenated)", "Self-harm reports rise among teens", "reports rise among teens"),
                arguments("grooming", "Grooming case against a tuition teacher", "case against a tuition teacher"),
                arguments("child exploitation", "Child exploitation racket traced online", "racket traced online"),
                arguments("csam", "CSAM network traced to three states", "network traced to three states"));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("kabirTermListBypasses")
    @DisplayName("F-0826: each of Kabir's 22 term-list bypasses is now blocked")
    void firstUnsafeTopic_blocksEveryKabirTermListBypass(
            String term, String bypassHeadline, String sameHeadlineWithoutTheTerm) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(sameHeadlineWithoutTheTerm),
                "anti-vacuity control failed: '"
                        + sameHeadlineWithoutTheTerm
                        + "' is blocked by something OTHER than '"
                        + term
                        + "', so this row proves nothing about that term");

        assertNotNull(
                CreatorNudgeService.firstUnsafeTopic(bypassHeadline),
                "F-0826 bypass still open for '" + term + "': " + bypassHeadline);
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(bypassHeadline),
                "bypass headline must not be quotable in creator copy: " + bypassHeadline);
    }

    /**
     * The phrase-rescue technique F-0786 invented for {@code mob}/{@code court}, applied to the
     * three words it had skipped. Both halves are asserted in ONE test on purpose: a future
     * "hardening" that adds bare {@code shooting}/{@code attack}/{@code clash} would make the
     * blocked half pass for the wrong reason, and only the excluded half can catch that.
     */
    @Test
    @DisplayName("F-0826: shooting/attack/clash block as PHRASES while the bare words stay excluded")
    void firstUnsafeTopic_phraseRescueBlocksPhrasesButNotBareWords() {
        for (String blocked :
                List.of(
                        "School shooting coverage dominates the feed",
                        "Mass shooting reported in the suburb",
                        "Acid attack survivor speaks out",
                        "Terror attack foiled near the border",
                        "Communal clash erupts in the old city")) {
            assertNotNull(
                    CreatorNudgeService.firstUnsafeTopic(blocked),
                    "phrase must be blocked: " + blocked);
        }

        // The bare words remain deliberately excluded — "shooting a reel", "attack the day" and
        // "clash of styles" are ordinary copy in this product's niche, and that judgement call is
        // what the phrase technique exists to preserve. If someone adds them, these go red.
        for (String stillAllowed :
                List.of(
                        "Shooting a reel at golden hour",
                        "Attack the day with this morning routine",
                        "Clash of styles in this season's lookbook")) {
            assertNull(
                    CreatorNudgeService.firstUnsafeTopic(stillAllowed),
                    "bare word must stay excluded (too common in-niche): " + stillAllowed);
        }
    }

    // ---------------------------------------------------------------------------------------
    // F-0827 — Unicode evasion
    // ---------------------------------------------------------------------------------------

    /**
     * Every evasion technique Kabir's compiled harness landed against the pre-fix
     * {@code normalizeForMatching}. All of them carry the SAME fixture sentence, "Town … case
     * reopened", so the two controls in {@link
     * #firstUnsafeTopic_blocksEveryUnicodeEvasion(String, String)} are shared and exact: the plain
     * sentence blocks, and the sentence with the word removed does not.
     *
     * <p>The realistic channel is not hypothetical — YouTube video titles are one of the three
     * trend sources, they are user-authored, and fullwidth/stylized Unicode is ordinary creator
     * styling there, so this arrives as ambient traffic rather than only as a deliberate attack.
     *
     * <p>Kabir's write-up names thirteen distinct techniques plus a count of fourteen; both
     * candidates for the fourteenth are covered here (hyphen-separation alongside dot-separation)
     * and a STACKED case — fullwidth + zero-width + homoglyph in one word — is added because it is
     * strictly harder than any single technique and is what an attacker who reads this fix will
     * actually send.
     */
    static Stream<Arguments> unicodeEvasions() {
        return Stream.of(
                arguments("ZWSP U+200B", "Town mur​der case reopened"),
                arguments("ZWNJ U+200C", "Town mur‌der case reopened"),
                arguments("ZWJ U+200D", "Town mur‍der case reopened"),
                arguments("soft hyphen U+00AD", "Town mur­der case reopened"),
                arguments("BOM U+FEFF", "Town mur﻿der case reopened"),
                arguments("word joiner U+2060", "Town mur⁠der case reopened"),
                arguments("RTL override U+202E", "Town mur‮der case reopened"),
                // VISUAL lookalikes only — Cyrillic м/ԁ/е for Latin m/d/e. Note there is no Cyrillic
                // or Greek lookalike for Latin 'r' or, in Cyrillic, for 'u': substituting Cyrillic
                // р (which looks like 'p') into the 'r' slot renders as "mupder", which is not the
                // word murder to any reader and so is not a homoglyph attack at all. The fold is
                // SHAPE-based, and these fixtures are built the same way.
                arguments("Cyrillic homoglyphs (U+043C, U+0501, U+0435)", "Town мurԁеr case reopened"),
                arguments("Cyrillic e only (U+0435 — Kabir's named sample)", "Town murdеr case reopened"),
                arguments("Greek homoglyphs (U+03BC, U+03C5, U+03B5)", "Town μυrdεr case reopened"),
                arguments("fullwidth forms", "Town ｍｕｒｄｅｒ case reopened"),
                arguments("combining mark U+0308", "Town mürder case reopened"),
                arguments("dot separation", "Town m.u.r.d.e.r case reopened"),
                arguments("hyphen separation", "Town m-u-r-d-e-r case reopened"),
                arguments(
                        "stacked: fullwidth + ZWSP + Cyrillic",
                        "Town ｍｕ​ｒｄеｒ case reopened"));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("unicodeEvasions")
    @DisplayName("F-0827: each Unicode evasion of 'murder' is now normalized back into a match")
    void firstUnsafeTopic_blocksEveryUnicodeEvasion(String technique, String evadedHeadline) {
        // Positive control: the fixture sentence with the plain word IS blocked, and as CRIME.
        assertEquals(
                UnsafeHeadlineTopic.CRIME,
                CreatorNudgeService.firstUnsafeTopic("Town murder case reopened"),
                "test premise broken: the un-evaded fixture sentence is not classified CRIME");
        // Negative control: nothing ELSE in the fixture sentence blocks, so a pass below can only
        // come from the evaded word being normalized back to "murder".
        assertNull(
                CreatorNudgeService.firstUnsafeTopic("Town case reopened"),
                "anti-vacuity control failed: the carrier sentence is itself unsafe");

        assertEquals(
                UnsafeHeadlineTopic.CRIME,
                CreatorNudgeService.firstUnsafeTopic(evadedHeadline),
                "F-0827 evasion still succeeds via " + technique);
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(evadedHeadline),
                "evaded headline must not be quotable in creator copy (" + technique + ")");
    }

    @Test
    @DisplayName("F-0827: math-bold 'rape' is normalized back into a match (Kabir's own sample)")
    void firstUnsafeTopic_blocksMathBoldRape() {
        // U+1D42B U+1D41A U+1D429 U+1D41E — MATHEMATICAL BOLD SMALL R/A/P/E.
        String mathBold = "𝐫𝐚𝐩𝐞";
        assertNull(
                CreatorNudgeService.firstUnsafeTopic("Town case reopened"),
                "anti-vacuity control failed: the carrier sentence is itself unsafe");

        assertEquals(
                UnsafeHeadlineTopic.CRIME,
                CreatorNudgeService.firstUnsafeTopic("Town " + mathBold + " case reopened"));
    }

    /**
     * The other half of F-0827, and the half a "does it block?" suite cannot see: normalization
     * that folds aggressively enough to catch homoglyphs must not start folding ORDINARY copy into
     * accidental matches. These are the words the term list would hit if the token-boundary anchors
     * were dropped while separators were being ignored — which is precisely the trade the new
     * matcher makes.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                // "bomb" must not match inside a larger token, in either position.
                "Bombay street food tour goes viral",
                "Bombshell reveal in the finale episode",
                "Photobomb compilation hits a million views",
                // "kill"/"sue"/"die" — the original near-misses, re-checked under the new matcher.
                "Killer ab workout routine goes viral",
                "Big issue with the new fitness app launch",
                "Audience numbers climb for regional creators",
                // Non-Latin script must survive normalization without becoming an English term.
                "दिवाली मॉर्निंग वर्कआउट चैलेंज"
            })
    @DisplayName("F-0827: aggressive normalization does not create new false positives")
    void firstUnsafeTopic_normalizationDoesNotOverBlock(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "normalization over-blocked a benign headline: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "normalization over-blocked a benign headline: " + benign);
    }

    // ---------------------------------------------------------------------------------------
    // LOW-6 — fail-closed on an all-invisible headline
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("LOW-6: an all-zero-width headline is NOT quotable, even though isBlank() says false")
    void isQuotableInCreatorCopy_allInvisibleHeadline_failsClosed() {
        String invisible = "​‌‍﻿⁠­";

        // The premise of the whole finding: String.isBlank() answers FALSE here, because a format
        // character is not whitespace. That is why the old blank-guard let this through.
        assertFalse(
                invisible.isBlank(),
                "test premise broken: if isBlank() were true the old guard already covered this and"
                        + " this test proves nothing");

        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(invisible),
                "an all-invisible headline must fail closed — it produces an empty-looking quotation");
        // And it fails closed via the EMPTY-NORMALIZED-FORM arm, not via a term match.
        assertNull(CreatorNudgeService.firstUnsafeTopic(invisible));
    }

    @Test
    @DisplayName("LOW-6: an all-invisible trend headline degrades to generic copy, not an empty quote")
    void getSuggestion_allInvisibleHeadline_producesNoEmptyQuotation() {
        String invisible = "​‌‍﻿";
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), invisible));
        // F-0854 (vikram, 2026-09-17): an all-invisible headline fails isQuotableInCreatorCopy, so
        // it is now withheld from the AI call before this stub could ever be exercised — lenient.
        lenient().when(aiClient.requestSuggestion(any(), any(), any())).thenReturn(null);

        service.getSuggestion(CREATOR_PROFILE_ID);

        verifyNoInteractions(aiClient);
        CreatorNudgeLog saved = captureSaved();
        assertEquals(NudgeMessageSource.FALLBACK, saved.getMessageSource());
        assertFalse(
                saved.getContentIdea().contains("There's a trend around"),
                "an all-invisible headline must not be quoted at all, got: " + saved.getContentIdea());
        assertFalse(
                saved.getHeadline().contains("your " + THEME_ACTION + " content"),
                "an all-invisible headline must degrade to generic copy, got: " + saved.getHeadline());
    }

    // ---------------------------------------------------------------------------------------
    // F-0825 — the gate is on the persisted copy, and message_source is READ not derived
    // ---------------------------------------------------------------------------------------

    /**
     * THE F-0825 test. Everything the previous commit's suite covered stubbed {@code
     * requestSuggestion} to return {@code null} — i.e. only Java's own transport-failure fallback.
     * This case is the one that actually ships in production: influora-ai answers HTTP 200 with
     * {@code success: true} and {@code message_source: "FALLBACK"}, because its spend gate tripped
     * or its provider errored, and its {@code fallback_message} has interpolated the raw trend text
     * into the copy with no model and no prompt-safety layer anywhere in the path.
     *
     * <p>Against the pre-fix code this returns a non-null {@link SuggestionCopy}, so the service
     * took the AI branch: the headline was persisted verbatim and stamped {@code AI}. Both halves
     * are asserted here.
     *
     * <p><b>F-0854 update (vikram, 2026-09-17):</b> this fixture's headline is itself unsafe, so
     * the new trend-headline gate now withholds it from the AI call before this scenario can even
     * arise — the stub below is unreachable and is marked lenient, with {@code
     * verifyNoInteractions} added to prove it. The assertions on the persisted/served copy are
     * left as-is: they still hold (via {@code templatedFallback}'s own duplicate check) and remain
     * the right regression guard for the case a SAFE headline comes back from a model paraphrase
     * that happens to be unsafe, which {@link #getSuggestion_aiLabelledUnsafeCopy_isStillFilteredAndDowngraded()}
     * covers directly.
     */
    @Test
    @DisplayName("F-0825: a python-side FALLBACK 200 carrying an unsafe headline is filtered AND logged FALLBACK")
    void getSuggestion_pythonFallbackWithUnsafeCopy_isFilteredAndRecordedAsFallback() {
        String unsafeHeadline = "Actor's son found dead in Mumbai flat, police suspect suicide";
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), unsafeHeadline));
        // Exactly what influora-ai's fallback_message branch returns: a SUCCESSFUL call. F-0854:
        // now unreachable (the headline is withheld before the AI call), hence lenient.
        lenient()
                .when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Asha, your action content is trending right now",
                                "There's a trend around \"" + unsafeHeadline + "\" that fits your niche.",
                                "FALLBACK"));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        verifyNoInteractions(aiClient);
        CreatorNudgeLog saved = captureSaved();

        // Half 1 — the content gate now runs on this path.
        for (String leaked : List.of("dead", "police", "suicide", unsafeHeadline)) {
            assertFalse(
                    (saved.getHeadline() + " || " + saved.getContentIdea())
                            .toLowerCase(Locale.ROOT)
                            .contains(leaked.toLowerCase(Locale.ROOT)),
                    "'" + leaked + "' reached the persisted row: " + saved.getContentIdea());
            assertFalse(
                    (result.suggestion().headline() + " || " + result.suggestion().contentIdea())
                            .toLowerCase(Locale.ROOT)
                            .contains(leaked.toLowerCase(Locale.ROOT)),
                    "'" + leaked + "' reached the served DTO: " + result.suggestion().contentIdea());
        }

        // Half 2 — the audit trail. Pre-fix this row said AI.
        assertEquals(
                NudgeMessageSource.FALLBACK,
                saved.getMessageSource(),
                "a python-side fallback must never be recorded as AI");
    }

    /**
     * The anti-vacuity counterweight to the test above, and the only one that can tell "the label
     * is READ from the wire" apart from "the label is FALLBACK because the copy got suppressed".
     * Safe copy, so no suppression happens at all — and the row must still say FALLBACK purely
     * because influora-ai said so.
     */
    @Test
    @DisplayName("F-0825: a python-side FALLBACK 200 with SAFE copy keeps the copy and still records FALLBACK")
    void getSuggestion_pythonFallbackWithSafeCopy_keepsCopyAndStillRecordsFallback() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Asha, your action content is trending",
                                "A quick post today could land well.",
                                "FALLBACK"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertEquals(
                NudgeMessageSource.FALLBACK,
                saved.getMessageSource(),
                "message_source must be read from the response, not derived from 'the call worked'");
        // Unsuppressed: the copy is untouched, which is what proves the FALLBACK label above did
        // not come from the suppression path.
        assertEquals("Asha, your action content is trending", saved.getHeadline());
        assertEquals("A quick post today could land well.", saved.getContentIdea());
    }

    @Test
    @DisplayName("F-0825: copy labelled AI is filtered too — the gate does not trust the label")
    void getSuggestion_aiLabelledUnsafeCopy_isStillFilteredAndDowngraded() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Asha, the murder probe is trending in your niche",
                                "Post about the murder probe while it's hot.",
                                "AI"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        assertFalse(
                (saved.getHeadline() + saved.getContentIdea()).toLowerCase(Locale.ROOT).contains("murder"),
                "model-INVENTED unsafe copy must be filtered too, got: " + saved.getContentIdea());
        assertEquals(
                NudgeMessageSource.FALLBACK,
                saved.getMessageSource(),
                "a suppressed row holds OUR copy, not the model's — labelling it AI would be the same"
                        + " class of audit-trail lie F-0825 is about");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "AI_FALLBACK", "MODEL", "fallback_message"})
    @DisplayName("F-0825: an absent or unrecognised message_source fails closed to FALLBACK")
    void getSuggestion_unrecognisedMessageSource_failsClosedToFallback(String wireValue) {
        assertMessageSourceRecordedAs(wireValue, NudgeMessageSource.FALLBACK);
    }

    @Test
    @DisplayName("F-0825: a null message_source fails closed to FALLBACK, and 'AI'/'ai' still map to AI")
    void getSuggestion_messageSourceMapping_nullFailsClosedAndAiStillMapsToAi() {
        assertMessageSourceRecordedAs(null, NudgeMessageSource.FALLBACK);
        // Positive control: if this were also FALLBACK the assertions above would be vacuous —
        // every row would be FALLBACK regardless of what the AI service said.
        assertMessageSourceRecordedAs("AI", NudgeMessageSource.AI);
        assertMessageSourceRecordedAs("ai", NudgeMessageSource.AI);
        // Surrounding whitespace is trimmed, not treated as an unrecognised value — pinned here so
        // the trim is a decision rather than an accident. (An earlier draft of the suite listed
        // "ai " among the UNRECOGNISED values and went red against this line; the fixture was
        // wrong, the code was right.)
        assertMessageSourceRecordedAs(" AI ", NudgeMessageSource.AI);
        assertMessageSourceRecordedAs("\tFALLBACK\n", NudgeMessageSource.FALLBACK);
    }

    /**
     * The wire-binding guard. Everything above stubs {@link CreatorSuggestionAiClient}, so it would
     * ALL stay green if {@code @JsonProperty("message_source")} were misspelled — {@code
     * @JsonIgnoreProperties(ignoreUnknown = true)} means a wrong name binds {@code null} silently,
     * every row would fail closed to FALLBACK, and no service-level test could tell that apart from
     * a correctly-read FALLBACK. This deserializes the response body influora-ai actually sends
     * ({@code app/routes/creator_suggestion.py} lines 255-257 and 350-352, copied verbatim).
     */
    @Test
    @DisplayName("F-0825: message_source actually binds off the wire — not just off a mocked client")
    void suggestionResponse_bindsMessageSourceFromThePythonResponseBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        SuggestionResponse fallback =
                mapper.readValue(
                        "{\"success\": true, \"data\": {\"headline\": \"h\","
                                + " \"content_idea\": \"c\", \"message_source\": \"FALLBACK\"}}",
                        SuggestionResponse.class);
        assertEquals("FALLBACK", fallback.data().messageSource());
        assertEquals("c", fallback.data().contentIdea(), "content_idea must still bind");

        SuggestionResponse ai =
                mapper.readValue(
                        "{\"success\": true, \"data\": {\"headline\": \"h\","
                                + " \"content_idea\": \"c\", \"message_source\": \"AI\"}}",
                        SuggestionResponse.class);
        assertEquals("AI", ai.data().messageSource());
    }

    /**
     * The theme arm of the F-0825 gate. Degrading the COPY cannot launder the theme, because the
     * theme is persisted in its own column and rendered on its own in {@code SuggestionDto.theme} —
     * so an unsafe theme must stop the suggestion entirely rather than ship alongside generic copy.
     *
     * <p>The fixture is not contrived: {@code ThemeMatchService.parseThemeJson} returns whatever
     * strings sit in {@code trends.themes_json} without validating them against {@code
     * knownThemes}, which is the documented reason {@code theme} is not provably closed-vocab.
     * "abduction" is chosen because it sorts BEFORE "action", so {@code bestMatchedTheme}'s
     * {@code .sorted().findFirst()} actually selects it.
     */
    @Test
    @DisplayName("F-0825: an unsafe server-derived theme stops the suggestion — no row, no generic-copy cover")
    void getSuggestion_unsafeTheme_returnsNoSuggestionAndWritesNoRow() {
        String poisoned = themesJson("abduction", THEME_ACTION);
        givenProfile("Asha", poisoned);
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, poisoned, TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("no_suggestion_today", result.status());
        assertNull(result.suggestion());
        verifyNoNudgeRowWritten();
    }

    @Test
    @DisplayName("F-0825: the theme arm is not a blanket reject — a clean theme still produces a row")
    void getSuggestion_safeTheme_stillProducesARow() {
        // Anti-vacuity counterweight to the test above: same shape, same code path, clean theme.
        String clean = themesJson("abseiling", THEME_ACTION);
        givenProfile("Asha", clean);
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, clean, TREND_TEXT));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        assertEquals("abseiling", captureSaved().getTheme());
    }

    // ---------------------------------------------------------------------------------------
    // F-0838 — the theme gate runs before influora-ai is paid
    // ---------------------------------------------------------------------------------------

    /**
     * The theme verdict cannot depend on the AI's answer, so an unsafe theme must be refused
     * before the call. The client is stubbed LENIENTLY to return perfectly safe copy: that is the
     * worst case for this assertion, because against the pre-fix order the call is made, succeeds,
     * and its result is then discarded — nothing observable except the spend. Only {@code
     * verifyNoInteractions} can see that (see the class javadoc on why not an {@code anyString()}
     * never-verify).
     */
    @Test
    @DisplayName("F-0838: an unsafe theme never reaches influora-ai — no AI spend, no row")
    void getSuggestion_unsafeTheme_neverCallsTheAiClient() {
        String poisoned = themesJson("abduction", THEME_ACTION);
        givenProfile("Asha", poisoned);
        givenNoRowYetToday();
        givenActiveTrends(trend(TREND_ID, poisoned, TREND_TEXT));
        lenient()
                .when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(new SuggestionCopy("Asha, a safe headline", "A safe idea.", "AI"));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("no_suggestion_today", result.status());
        verifyNoInteractions(aiClient);
        verifyNoNudgeRowWritten();
    }

    // ---------------------------------------------------------------------------------------
    // F-0854 — the trend headline gate runs before influora-ai is paid
    // ---------------------------------------------------------------------------------------

    /**
     * F-0854: a safe theme says nothing about the headline. Before this fix, only {@code theme}
     * was checked ahead of {@code callAiSafely}, so a safe-themed trend carrying an unsafe
     * headline sent the RAW headline (F-0786's "third-party news headline") to influora-ai as a
     * prompt input — Kabir's finding was a murder headline reaching the AI and its paraphrase
     * being persisted {@code status=ready}, {@code message_source=AI}. (vikram, 2026-09-17,
     * F-0854)
     *
     * <p>Unlike the theme arm ({@link #getSuggestion_unsafeTheme_neverCallsTheAiClient()}), an
     * unsafe headline does not blank out the whole suggestion: it withholds the headline from the
     * AI call and degrades straight to the same generic fallback copy {@link
     * #templatedFallback} already produces for this headline offline — so the co-pilot still
     * returns {@code status=ready}, just never with the AI ever having seen the headline and never
     * labelled {@code message_source=AI}. {@code verifyNoInteractions(aiClient)} is used, not an
     * {@code anyString()} never()-verify, per this class's javadoc on why the latter passes
     * vacuously against a null {@code trendText}. The AI client is stubbed (leniently, since it
     * must not actually be invoked) to return copy labelled AI — the worst case for this
     * assertion, since against the pre-fix code that is exactly what would have been persisted.
     */
    @Test
    @DisplayName("F-0854: an unsafe trend headline with a SAFE theme never reaches the AI, and no AI-labelled row is saved")
    void getSuggestion_unsafeTrendHeadlineWithSafeTheme_neverCallsAiAndSavesNoAiRow() {
        String unsafeHeadline = "MURDER probe widens in Pune";
        assertEquals(
                UnsafeHeadlineTopic.CRIME,
                CreatorNudgeService.firstUnsafeTopic(unsafeHeadline),
                "test premise broken: this sample headline is not classified unsafe");
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(THEME_ACTION),
                "test premise broken: the matched theme itself must be safe, or this test cannot"
                        + " isolate the headline arm from the theme arm");

        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(
                trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), unsafeHeadline));
        // Stubbed leniently to return copy labelled AI: against the pre-fix code this is exactly
        // what would have been persisted with message_source=AI, which is the bug this test pins.
        // Lenient because the fix must never actually invoke this stub at all.
        lenient()
                .when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(new SuggestionCopy("Asha, a safe headline", "A safe idea.", "AI"));

        SuggestionResult result = service.getSuggestion(CREATOR_PROFILE_ID);

        assertEquals("ready", result.status());
        verifyNoInteractions(aiClient);
        CreatorNudgeLog saved = captureSaved();
        assertEquals(
                NudgeMessageSource.FALLBACK,
                saved.getMessageSource(),
                "an unsafe headline must never be sent to the AI, and the saved row must never be"
                        + " labelled message_source=AI");
        assertFalse(
                (saved.getHeadline() + " || " + saved.getContentIdea())
                        .toLowerCase(Locale.ROOT)
                        .contains("murder"),
                "the unsafe headline must not be quoted in the persisted copy either: "
                        + saved.getContentIdea());
    }

    // ---------------------------------------------------------------------------------------
    // F-0834 — every real taxonomy theme is quotable
    // ---------------------------------------------------------------------------------------

    /**
     * The theme arm of the gate refuses the whole suggestion, so a taxonomy theme that happened to
     * contain a term (say "power" gaining a phrase, or a future "true crime" theme) would silently
     * zero out every creator tagged with it. Loaded from the REAL classpath file, not a copy, so a
     * taxonomy edit is what trips this.
     */
    @Test
    @DisplayName("F-0834: every theme in trendspark/theme-taxonomy.json passes the content gate")
    void everyTaxonomyTheme_isQuotableInCreatorCopy() throws Exception {
        JsonNode root;
        try (var in = new ClassPathResource("trendspark/theme-taxonomy.json").getInputStream()) {
            root = new ObjectMapper().readTree(in);
        }
        JsonNode themes = root.get("themes");
        assertNotNull(themes, "test premise broken: taxonomy file has no 'themes' array");
        assertTrue(themes.isArray() && themes.size() > 0, "anti-vacuity: taxonomy has no themes");

        List<String> unquotable = new ArrayList<>();
        for (JsonNode theme : themes) {
            assertTrue(theme.isTextual(), "non-string theme entry: " + theme);
            if (!CreatorNudgeService.isQuotableInCreatorCopy(theme.asText())) {
                unquotable.add(
                        theme.asText() + "=" + CreatorNudgeService.firstUnsafeTopic(theme.asText()));
            }
        }
        assertTrue(
                unquotable.isEmpty(),
                "taxonomy themes the gate would refuse (every creator tagged with one gets no"
                        + " suggestion, ever): "
                        + unquotable);
    }

    // ---------------------------------------------------------------------------------------
    // F-0832 / F-0837 — plurals of multi-word phrases
    // ---------------------------------------------------------------------------------------

    /**
     * GENERATED from the live term sets, not hand-listed: F-0837 was a hand-written list of plurals
     * that missed entries, and any phrase added later is covered here without anyone remembering.
     * The plural is produced by this test's OWN regular-English rule ({@link #regularPlural}), not
     * by the service's suffix logic, so the two can disagree — e.g. a future phrase ending in
     * consonant-y fails here unless the service handles {@code ies}.
     *
     * <p>Carrier sentence "Town … case reopened" is asserted safe first, so a block can only come
     * from the phrase. Some phrases are redundantly covered by their leading word ("terror
     * attacks" via {@code terror}); those rows pass either way, which is fine — every row that is
     * NOT redundant is what the falsification run showed going red.
     */
    @Test
    @DisplayName("F-0832/F-0837: the plural of EVERY multi-word phrase term is blocked")
    void firstUnsafeTopic_blocksThePluralOfEveryMultiWordPhrase() {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic("Town case reopened"),
                "anti-vacuity control failed: the carrier sentence is itself unsafe");

        List<String> phrases = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            for (String term : topic.terms()) {
                if (term.indexOf(' ') < 0) {
                    continue;
                }
                phrases.add(term);
                String plural = regularPlural(term);
                String headline = "Town " + plural + " case reopened";
                if (CreatorNudgeService.firstUnsafeTopic(headline) == null
                        || CreatorNudgeService.isQuotableInCreatorCopy(headline)) {
                    leaked.add(plural);
                }
            }
        }
        // Anti-vacuity: the term sets really do contain phrases (currently 19). If this drops to 0
        // the loop above proves nothing.
        assertTrue(phrases.size() >= 10, "expected the term sets to carry phrases, got: " + phrases);
        assertTrue(leaked.isEmpty(), "plural phrase forms still evade the filter: " + leaked);
    }

    /** The existing single-word boundaries must survive the phrase-plural rule untouched. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Killer ab workout routine goes viral",
                "Big issue with the new fitness app launch",
                "Tennis courts resurfaced across Mumbai clubs",
                "Flash mobs take over the mall this weekend"
            })
    @DisplayName("F-0832: the plural rule does not widen single-word matching")
    void firstUnsafeTopic_pluralRuleDoesNotWidenSingleWords(String benign) {
        assertNull(CreatorNudgeService.firstUnsafeTopic(benign), "over-blocked: " + benign);
    }

    /** Regular English plural of a phrase's final word: s/x/z/ch/sh → es, consonant+y → ies,
     * else s. Deliberately independent of the service's implementation. */
    private static String regularPlural(String phrase) {
        if (phrase.matches(".*(s|x|z|ch|sh)")) {
            return phrase + "es";
        }
        if (phrase.matches(".*[^aeiou]y")) {
            return phrase.substring(0, phrase.length() - 1) + "ies";
        }
        return phrase + "s";
    }

    // ---------------------------------------------------------------------------------------
    // F-0833 — the suppression log names the field that actually failed
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("F-0833: suppression log names only the failing field, not MISSING_OR_BLANK for a clean one")
    void getSuggestion_suppressionLog_namesOnlyTheFieldThatMatched() {
        givenProfile("Asha", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Asha, the murder probe is trending",
                                "A quick post today could land well.",
                                "AI"));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(CreatorNudgeService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            service.getSuggestion(CREATOR_PROFILE_ID);
        } finally {
            serviceLogger.detachAppender(appender);
        }

        List<String> suppression =
                appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("suppressed"))
                        .toList();
        assertEquals(1, suppression.size(), "expected one suppression warning, got: " + appender.list);
        String line = suppression.get(0);
        assertTrue(line.contains("headline=CRIME"), "must name the failing field and category: " + line);
        assertFalse(
                line.contains("MISSING_OR_BLANK"),
                "contentIdea matched nothing and is not blank — naming it MISSING_OR_BLANK misleads: "
                        + line);
        assertFalse(line.contains("contentIdea"), "a passing field must not be named: " + line);
    }

    /**
     * The degrade's own degrade. A creator picks their own display name, so "first-party" is not
     * "safe": interpolating it into the generic fallback could persist copy the gate had just
     * rejected. {@code safeGenericFallback} therefore drops to the name-free variant.
     */
    @Test
    @DisplayName("F-0825: suppression falls back to 'You' when the creator's own display name is unsafe")
    void getSuggestion_suppressionWithUnsafeDisplayName_dropsToTheNameFreeCopy() {
        givenProfile("Murder Mavens", themesJson(THEME_ACTION, THEME_STRENGTH));
        givenNoRowYetToday();
        givenSaveEchoesArgument();
        givenActiveTrends(trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT));
        when(aiClient.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "A riot of colour — and a real riot downtown",
                                "Post about the riot while it's hot.",
                                "AI"));

        service.getSuggestion(CREATOR_PROFILE_ID);

        CreatorNudgeLog saved = captureSaved();
        String copy = (saved.getHeadline() + " || " + saved.getContentIdea()).toLowerCase(Locale.ROOT);
        assertFalse(copy.contains("riot"), "suppressed copy leaked: " + copy);
        assertFalse(
                copy.contains("murder"),
                "the creator's own display name is not automatically safe, got: " + copy);
        assertTrue(
                saved.getHeadline().startsWith("You, "),
                "expected the name-free last-resort copy, got: " + saved.getHeadline());
    }

    private void assertMessageSourceRecordedAs(String wireValue, NudgeMessageSource expected) {
        // A fresh service+mock per assertion: these are invoked several times from one test method,
        // and captureSaved() verifies exactly ONE saveAndFlush.
        CreatorNudgeLogRepository logRepository = mock(CreatorNudgeLogRepository.class);
        CreatorProfileRepository profileRepository = mock(CreatorProfileRepository.class);
        TrendRepository trends = mock(TrendRepository.class);
        CreatorSuggestionAiClient ai = mock(CreatorSuggestionAiClient.class);

        CreatorProfile profile =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, ulid26("USER1"), "Asha");
        profile.setThemeTagsJson(themesJson(THEME_ACTION, THEME_STRENGTH));
        when(profileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(profile));
        when(logRepository.findByCreatorProfileIdAndShownAtAfter(eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(Optional.empty());
        // Hoisted out of thenReturn(...) deliberately: trend() stubs a mock of its own, and doing
        // that inside an in-progress when() is the UnfinishedStubbingException hazard — Mockito
        // sees the inner stubbing before the outer one is completed.
        Trend activeTrend = trend(TREND_ID, themesJson(THEME_STRENGTH, THEME_ACTION), TREND_TEXT);
        when(trends.findActive(any())).thenReturn(List.of(activeTrend));
        when(logRepository.saveAndFlush(any(CreatorNudgeLog.class))).thenAnswer(returnsFirstArg());
        when(ai.requestSuggestion(any(), any(), any()))
                .thenReturn(
                        new SuggestionCopy(
                                "Asha, your action content is trending",
                                "A quick post today could land well.",
                                wireValue));

        new CreatorNudgeService(
                        trends, profileRepository, logRepository, themeMatchService, ai, props)
                .getSuggestion(CREATOR_PROFILE_ID);

        ArgumentCaptor<CreatorNudgeLog> captor = ArgumentCaptor.forClass(CreatorNudgeLog.class);
        verify(logRepository).saveAndFlush(captor.capture());
        assertEquals(
                expected,
                captor.getValue().getMessageSource(),
                "message_source '" + wireValue + "' must be recorded as " + expected);
    }

    // ---------------------------------------------------------------------------------------
    // F-0853 / F-0855 / F-0856 (decision 2026-09-18) — the deterministic filter, widened
    // ---------------------------------------------------------------------------------------

    /**
     * Every probe string from Kabir's F-0853/F-0855/F-0856 ledger entries (opened 2026-09-17),
     * one row each, asserting the CATEGORY it must block as — table-driven so a regression fails
     * by name, per the decision's own done_when.
     *
     * <p>F-0856 names four look-alike-character techniques (Hangul filler, small capitals,
     * dotless i, Armenian look-alikes) without a concrete headline for any of them — the ledger
     * entry states the technique, not a sample string. Those four rows are constructed here, one
     * technique per row, spelling an otherwise-ordinary already-listed term so the row proves
     * exactly the new fold/skip and nothing else; every other row is Kabir's own text, unedited.
     */
    static Stream<Arguments> decisionProbes() {
        return Stream.of(
                // F-0853 — generated -ing inflection of a MULTI-WORD phrase ("self harm").
                arguments(
                        "F-0853: -ing on a phrase (self harm -> self-harming)",
                        "self-harming teens",
                        UnsafeHeadlineTopic.DEATH),
                // F-0855 — generated SINGLE-WORD inflections (previously no suffix rule at all).
                arguments("F-0855: terrorist+s", "terrorists", UnsafeHeadlineTopic.COMMUNAL),
                arguments("F-0855: riot+ers", "rioters", UnsafeHeadlineTopic.COMMUNAL),
                arguments("F-0855: bomb+ed", "bombed", UnsafeHeadlineTopic.COMMUNAL),
                arguments("F-0855: crime+s", "crimes", UnsafeHeadlineTopic.CRIME),
                arguments("F-0855: funeral+s", "funerals", UnsafeHeadlineTopic.DEATH),
                arguments("F-0855: verdict+s", "verdicts", UnsafeHeadlineTopic.LEGAL),
                // F-0855 — hashtag / camelCase / joined-word splitting.
                arguments(
                        "F-0855: hashtag camelCase split (#DelhiRiots -> ...Riots)",
                        "#DelhiRiots",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "F-0855: hashtag camelCase split (#GangRape -> ...Rape)",
                        "#GangRape",
                        UnsafeHeadlineTopic.CRIME),
                // F-0855 — Indian-English news term set, named outright in the ledger.
                arguments("F-0855: stone pelting", "stone pelting", UnsafeHeadlineTopic.COMMUNAL),
                arguments("F-0855: FIR lodged", "FIR lodged", UnsafeHeadlineTopic.CRIME),
                arguments("F-0855: Delhi HC", "Delhi HC", UnsafeHeadlineTopic.LEGAL),
                arguments("F-0855: apex court", "apex court", UnsafeHeadlineTopic.LEGAL),
                arguments("F-0855: succumb+s", "succumbs", UnsafeHeadlineTopic.DEATH),
                arguments("F-0855: stampede", "stampede", UnsafeHeadlineTopic.DEATH),
                arguments("F-0855: slain (irregular, listed literally)", "slain", UnsafeHeadlineTopic.DEATH),
                // F-0856 — Hindi/Hinglish (Latin-script) term set, named outright in the ledger.
                arguments("F-0856: hatya", "hatya", UnsafeHeadlineTopic.CRIME),
                arguments("F-0856: balatkar", "balatkar", UnsafeHeadlineTopic.CRIME),
                arguments("F-0856: atmahatya", "atmahatya", UnsafeHeadlineTopic.DEATH),
                // F-0856 — digit/symbol confusables, Kabir's own named samples verbatim.
                arguments("F-0856: murd3r (3->e, Kabir's own sample)", "murd3r", UnsafeHeadlineTopic.CRIME),
                arguments("F-0856: r@pe (@->a, Kabir's own sample)", "r@pe", UnsafeHeadlineTopic.CRIME),
                // F-0856 — look-alike-character techniques, constructed (see javadoc above).
                arguments(
                        "F-0856: Hangul filler U+3164 inside 'murder'",
                        "muㅤrder",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "F-0856: small capitals spelling 'rape' (ʀᴀᴘᴇ)",
                        "ʀᴀᴘᴇ",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "F-0856: dotless i (U+0131) in 'indicted'",
                        "ındıcted",
                        UnsafeHeadlineTopic.LEGAL),
                arguments(
                        "F-0856: Armenian OH (U+0585) in 'bomb'", "bօmb", UnsafeHeadlineTopic.COMMUNAL));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("decisionProbes")
    @DisplayName("2026-09-18 decision: every F-0853/F-0855/F-0856 probe blocks as its named category")
    void firstUnsafeTopic_blocksEveryDecisionProbeAsItsCategory(
            String technique, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + technique + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + technique + "]: \"" + probe + "\"");
    }

    /**
     * The decision doc's own required benign control set (done_when), verbatim. Every generated-
     * inflection, hashtag-splitting, Hindi-term and confusable-folding mechanism added above must
     * co-exist with these staying quotable — this is the anti-vacuity counterweight to {@link
     * #firstUnsafeTopic_blocksEveryDecisionProbeAsItsCategory}: a filter that blocked everything
     * would pass that suite too.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Tennis courts open",
                "Flash mobs dance",
                "Killer ab workout",
                "issues with skincare",
                "Diwali fashion haul",
                "Budget travel in Goa",
                "Sale ends tonight",
                "Court shoes styling",
                "Shooting stars photography",
                "Bombay Duck recipe"
            })
    @DisplayName("2026-09-18 decision: the required benign control set still passes as quotable")
    void firstUnsafeTopic_decisionBenignControlSetStaysQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    // ---------------------------------------------------------------------------------------
    // F-0857 repair round 1 (vikram, 2026-09-18) — an independent reviewer FAILED commit 927002a
    // against Kabir's own F-0853/F-0855/F-0856 ledger entries and the LOCKED decision text. Every
    // row below reproduces one named defect from that review, table-driven, same discipline as
    // {@link #decisionProbes()} above.
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> repairRound1Probes() {
        return Stream.of(
                // HIGH-1 — the '@'->'a' fold 927002a added turned '@' from a separator into a
                // letter, gluing "murder@midnight" into one token and breaking the token-end
                // anchor. Fixed by trying both readings (CONFUSABLE_FOLD_VARIANTS).
                arguments("HIGH-1: murder@midnight", "murder@midnight", UnsafeHeadlineTopic.CRIME),
                arguments("HIGH-1: Riots@Delhi", "Riots@Delhi", UnsafeHeadlineTopic.COMMUNAL),
                arguments("HIGH-1: suicide@IIT", "suicide@IIT", UnsafeHeadlineTopic.DEATH),
                // HIGH-2 — '1' is ambiguous between 'i' and 'l' (the LOCKED decision says
                // "1->i/l"); 927002a only tried 'l'. Fixed the same way as HIGH-1.
                arguments("HIGH-2: k1lled (1->i)", "k1lled", UnsafeHeadlineTopic.DEATH),
                arguments("HIGH-2: su1c1de (1->i)", "su1c1de", UnsafeHeadlineTopic.DEATH),
                // LOW-8 — '|' is the same i/l ambiguity as '1', folded via the same mechanism.
                arguments("LOW-8: k|lled (|->i)", "k|lled", UnsafeHeadlineTopic.DEATH),
                arguments("LOW-8: ki||ed (|->l)", "ki||ed", UnsafeHeadlineTopic.DEATH),
                // MEDIUM-4 — e-dropping inflections for terms ending in a bare "e". The 927002a
                // code comment claimed "stampedes"/"stampeded"/"stampeding" were already generated;
                // they were not (plain "+ed"/"+ing" gives "stampedeed"/"stampedeing").
                arguments("MEDIUM-4: stampede+d", "stampeded", UnsafeHeadlineTopic.DEATH),
                arguments("MEDIUM-4: stampede, drop-e+ing", "stampeding", UnsafeHeadlineTopic.DEATH),
                arguments("MEDIUM-4: rape, drop-e+ing", "raping", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-4: abuse, drop-e+ing", "abusing", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "MEDIUM-4: police, drop-e+ing (phrase)",
                        "policing crackdown",
                        UnsafeHeadlineTopic.CRIME),
                // HIGH-3 — Devanagari coverage, required outright by the LOCKED decision text ("a
                // Hindi and Hinglish term set, in Latin script and Devanagari"). Correctly-spelled
                // Devanagari input (with matras/virama) reduces to the same consonant skeleton the
                // listed terms are written in, via the SAME normalizeForMatching combining-mark
                // stripping the Latin-script evasions rely on.
                arguments("HIGH-3: हत्या (hatya, correctly spelled)",
                        "हत्या", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "HIGH-3: बलात्कार (balatkar, correctly spelled)",
                        "बलात्कार",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "HIGH-3: आत्महत्या (atmahatya, correctly spelled)",
                        "आत्महत्या",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "HIGH-3: दंगे (dange/riots, correctly spelled)",
                        "दंगे",
                        UnsafeHeadlineTopic.COMMUNAL),
                // MEDIUM-6 — Indian-English/Hinglish vocabulary an independent reviewer's probe
                // found bypassing.
                arguments("MEDIUM-6: aatmahatya", "aatmahatya", UnsafeHeadlineTopic.DEATH),
                arguments("MEDIUM-6: maut", "maut", UnsafeHeadlineTopic.DEATH),
                arguments("MEDIUM-6: hatyakand", "hatyakand", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: balatkari", "balatkari", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: qatl", "qatl", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: danga", "danga", UnsafeHeadlineTopic.COMMUNAL),
                arguments("MEDIUM-6: stone pelters", "stone pelters", UnsafeHeadlineTopic.COMMUNAL),
                arguments("MEDIUM-6: kidnappers (double consonant)", "Kidnappers", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: scammers (double consonant)", "Scammers", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: fraudster (irregular)", "Fraudster held", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: chargesheet filed", "chargesheet filed", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: gunned down", "Man gunned down in Delhi", UnsafeHeadlineTopic.CRIME),
                arguments("MEDIUM-6: found hanging", "Woman found hanging", UnsafeHeadlineTopic.DEATH),
                arguments("MEDIUM-6: Bombay HC", "Bombay HC quashes FIR", UnsafeHeadlineTopic.LEGAL),
                arguments("MEDIUM-6: Allahabad HC", "Allahabad HC", UnsafeHeadlineTopic.LEGAL),
                arguments("MEDIUM-6: top court", "top court", UnsafeHeadlineTopic.LEGAL),
                // LOW-8 — additional confusable folds an independent reviewer's probe found
                // bypassing, same small-curated-table discipline as the existing entries.
                arguments("LOW-8: r4pe (4->a)", "r4pe", UnsafeHeadlineTopic.CRIME),
                arguments("LOW-8: 5uicide (5->s)", "5uicide", UnsafeHeadlineTopic.DEATH),
                arguments("LOW-8: a$$ault ($->s)", "a$$ault", UnsafeHeadlineTopic.CRIME),
                arguments("LOW-8: murd€r (euro sign->e)", "murd€r", UnsafeHeadlineTopic.CRIME),
                arguments("LOW-8: rɑpe (Latin alpha U+0251->a)", "rɑpe", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "LOW-8: ԁеатһ (Cyrillic shha U+04BB->h)",
                        "ԁеатһ",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "LOW-8: murdЗr (Cyrillic Ze U+0417->e)", "murdЗr", UnsafeHeadlineTopic.CRIME),
                arguments("LOW-8: bomƄ (LATIN TONE SIX U+0184->b)", "bomƄ", UnsafeHeadlineTopic.COMMUNAL));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("repairRound1Probes")
    @DisplayName("F-0857 repair round 1: every independent-reviewer probe blocks as its named category")
    void firstUnsafeTopic_blocksEveryRepairRound1Probe(
            String defect, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + defect + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + defect + "]: \"" + probe + "\"");
    }

    /**
     * LOW-6 — the hashtag/camelCase splitting F-0855 added generates "bomber"/"blaster"/
     * "blasting"/"arresting" from terms ("bomb", "blast", "arrest") this filter must keep; these
     * are the same class of accepted false positive as "Killer ab workout" already pinned safe
     * above, restored via {@code GENERATED_FORM_BLOCKLIST}.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Bomber jacket styling",
                "Nerf blaster unboxing",
                "Blasting music workout",
                "Arresting sunset shots"
            })
    @DisplayName("F-0857 repair round 1: generated-suffix false positives stay quotable")
    void firstUnsafeTopic_repairRound1GeneratedFormExclusionsStayQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    // ---------------------------------------------------------------------------------------
    // T-GOLIVE-0918-R2 (vikram, 2026-09-18) — repair round 2. Kabir's own round-1 review of
    // 49a0415 named 5 defects (2 MEDIUM vocabulary/hashtag, 1 MEDIUM Devanagari over-block, 1
    // MEDIUM Devanagari/Hindi vocabulary gap, 1 MEDIUM confusables-table deviation). Every row
    // below reproduces one named bypass or over-block from that review, table-driven, same
    // discipline as decisionProbes()/repairRound1Probes() above.
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> repairRound2Probes() {
        return Stream.of(
                // (b) hashtags with a digit suffix — the camelCase split reaches a token END only
                // if nothing but a separator follows; a glued-on year kept the run going.
                arguments(
                        "R2-b: #DelhiRiots2020 (digit-suffixed hashtag)",
                        "#DelhiRiots2020",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-b: #Riots2024 (digit-suffixed hashtag)",
                        "#Riots2024",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-b: #murder2023 (digit-suffixed hashtag)",
                        "#murder2023",
                        UnsafeHeadlineTopic.CRIME),
                // (b) all-lowercase/all-caps joined hashtags — closed for the two named compounds
                // by listing them, not by a general segmenter (see the CRIME/COMMUNAL javadoc).
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — repinned to the full attested probe
                // string (was the shortened "#gangrape" alone); behaviour is unchanged, this only
                // makes the table literally pin what an independent reviewer's probe used.
                arguments(
                        "R2-b: #gangrape trending (all-lowercase hashtag)",
                        "#gangrape trending",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-b: #delhiriots (all-lowercase hashtag)",
                        "#delhiriots",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-b: #DELHIRIOTS (all-caps hashtag)", "#DELHIRIOTS", UnsafeHeadlineTopic.COMMUNAL),
                // (c) Indian-English news vocabulary Kabir's round-1 review found bypassing.
                arguments(
                        "R2-c: Gangrape accused held in UP",
                        "Gangrape accused held in UP",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Minor gangraped, two held",
                        "Minor gangraped, two held",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Woman strangled by husband",
                        "Woman strangled by husband",
                        UnsafeHeadlineTopic.CRIME),
                // T-GOLIVE-0918-R2 repair round 2 (vikram, 2026-09-18): bare "shot at" (this
                // entry's original probe) over-blocked ordinary creator filming language and was
                // replaced with the narrower "shot at by"/"opened fire" — see
                // repairRound2FixProbes() for the corrected probe and the new benign controls.
                arguments(
                        "R2-c: Hindu-Muslim clashes in town",
                        "Hindu-Muslim clashes in town",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-c: Mob torches bus in Nuh", "Mob torches bus in Nuh", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-c: Man hanged himself in hostel",
                        "Man hanged himself in hostel",
                        UnsafeHeadlineTopic.DEATH),
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — "R2-c: Body found in suitcase" is REMOVED,
                // not merely re-pointed: round 4 found bare "body found" over-blocking fitness-idiom
                // content ("My body found its rhythm with Pilates") that uses the identical "body
                // found <noun phrase>" construction. See CreatorNudgeService's DEATH-set javadoc for
                // why no phrase-only fix keeps blocking a crime-discovery headline without also
                // matching the idiom, and firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable for
                // the now-documented, tested accepted gap this leaves.
                arguments(
                        "R2-c: Actor jailed for 2 years",
                        "Actor jailed for 2 years",
                        UnsafeHeadlineTopic.LEGAL),
                arguments(
                        "R2-c: Bail denied to accused", "Bail denied to accused", UnsafeHeadlineTopic.LEGAL),
                arguments(
                        "R2-c: Custodial torture case",
                        "Custodial torture case",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Woman set ablaze by stalker",
                        "Woman set ablaze by stalker",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Murderous attack on journalist",
                        "Murderous attack on journalist",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Molester thrashed by crowd",
                        "Molester thrashed by crowd",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: Terrorised villagers flee",
                        "Terrorised villagers flee",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-c: Sentenced to life imprisonment",
                        "Sentenced to life imprisonment",
                        UnsafeHeadlineTopic.LEGAL),
                // (c) Devanagari vocabulary Kabir's round-1 review found bypassing.
                arguments(
                        "R2-c: हादसे में 5 की मौत (accident death)",
                        "हादसे में 5 की मौत",
                        UnsafeHeadlineTopic.DEATH),
                arguments("R2-c: रेप केस (rape case)", "रेप केस", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: नाबालिग से गैंगरेप (minor gangraped)",
                        "नाबालिग से गैंगरेप",
                        UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: हत्याकांड (murder case)", "हत्याकांड", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: हत्यारा गिरफ्तार (murderer arrested)",
                        "हत्यारा गिरफ्तार",
                        UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: हत्याओं (murders, plural)", "हत्याओं", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2-c: आतंकी हमला (terrorist attack)",
                        "आतंकी हमला",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-c: बम धमाका (bomb blast)", "बम धमाका", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-c: दंगाई गिरफ्तार (rioter arrested)",
                        "दंगाई गिरफ्तार",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-c: खुदकुशी (suicide)", "खुदकुशी", UnsafeHeadlineTopic.DEATH),
                arguments("R2-c: क़त्ल (qatl, with nukta)", "क़त्ल", UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: कत्ल (qatl, without nukta)", "कत्ल", UnsafeHeadlineTopic.CRIME),
                // (c) Latin/Hinglish alternate spellings Kabir's round-1 review found bypassing.
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — repinned to the full attested probe
                // string (was the shortened "balatkaar" alone); behaviour is unchanged.
                arguments(
                        "R2-c: balatkaar ka aaropi giraftar",
                        "balatkaar ka aaropi giraftar",
                        UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: hatyaa", "hatyaa", UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: aatmhatya", "aatmhatya", UnsafeHeadlineTopic.DEATH),
                arguments("R2-c: atmhatya", "atmhatya", UnsafeHeadlineTopic.DEATH),
                arguments("R2-c: khudkushi", "khudkushi", UnsafeHeadlineTopic.DEATH),
                arguments("R2-c: dangai", "dangai", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-c: aatankwadi", "aatankwadi", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-c: bam dhamaka", "bam dhamaka", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-c: qatal", "qatal", UnsafeHeadlineTopic.CRIME),
                arguments("R2-c: katl", "katl", UnsafeHeadlineTopic.CRIME),
                // (d) confusables outside the old curated table, named outright by Kabir's review.
                arguments(
                        "R2-d: ɗeath (U+0257 LATIN SMALL LETTER D WITH HOOK -> d)",
                        "ɗeath toll rises",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2-d: Ꮇurder (U+13B7 CHEROKEE LETTER LU -> m)",
                        "Ꮇurder suspect held",
                        UnsafeHeadlineTopic.CRIME),
                // (a) the दंगा-family term must still block correctly-spelled riot forms — the
                // over-block fix must not have thrown out the term it was protecting.
                arguments("R2-a: दंगा (danga/riot, singular)", "दंगा", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2-a: दंगों (dangon, riots oblique plural)",
                        "दंगों",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2-a: दंगाई (dangai, rioter)", "दंगाई गिरफ्तार", UnsafeHeadlineTopic.COMMUNAL));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("repairRound2Probes")
    @DisplayName("T-GOLIVE-0918-R2: every Kabir round-1-review probe blocks as its named category")
    void firstUnsafeTopic_blocksEveryRepairRound2Probe(
            String defect, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + defect + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + defect + "]: \"" + probe + "\"");
    }

    /**
     * (a) THE over-block fix itself: the दंगा-family term must NOT catch the everyday Hindi
     * future-tense forms of "to give" (देगा/देगी/देंगे/दूंगा) or दाग ("spot/stain" — a core
     * skincare word), including in a skincare-context sentence. Plus at least 10 new everyday
     * creator headlines in Hindi and Hinglish, per this round's done_when.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                // The exact over-block regression, named word by word.
                "यह क्रीम देगी ग्लो",
                "दिवाली पर ऑफर देंगे ब्रांड",
                "यह टिप मदद करेगा और रिजल्ट देगा",
                "चेहरे के दाग हटाएं",
                "देगा", "देगी", "देंगे", "दूंगा", "दाग",
                "दाग-धब्बे हटाने का आसान तरीका",
                // 10+ new everyday creator headlines in Hindi and Hinglish (done_when).
                "आज का मेकअप लुक बहुत सुंदर है",
                "यह साड़ी लुक त्योहार के लिए परफेक्ट है",
                "स्किनकेयर रूटीन जो ग्लो देगा",
                "बजट में गोवा ट्रिप कैसे प्लान करें",
                "फेस्टिवल के लिए बेस्ट हेयरस्टाइल आइडिया",
                "Aaj ka vlog bahut mazedaar tha",
                "Yeh outfit ideas Diwali ke liye perfect hain",
                "Skincare routine jo glow dega",
                "Budget mein Goa trip kaise plan karein",
                "Mera naya reel bahut viral ho gaya",
                "Ghar ka khana banane ka aasan tarika"
            })
    @DisplayName(
            "T-GOLIVE-0918-R2 (a): the दंगा-family term does not over-block देगा/देगी/देंगे/दूंगा/दाग"
                    + " or everyday Hindi/Hinglish creator headlines")
    void firstUnsafeTopic_repairRound2DevanagariOverblockFixedAndBenignSetStaysQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    // ---------------------------------------------------------------------------------------
    // T-GOLIVE-0918-R2 repair round 2 (vikram, 2026-09-18) — an independent reviewer FAILED
    // commit 72cabea (repairRound2Probes()'s own commit) with a HIGH regression, four MEDIUMs and
    // two LOWs. Every row below reproduces one named bypass/over-block from that review,
    // table-driven, same discipline as repairRound1Probes()/repairRound2Probes() above.
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> repairRound2FixProbes() {
        return Stream.of(
                // HIGH — Devanagari inflection regression. बलात्कारी was BLOCKED at 49a0415 (via the
                // since-replaced consonant-skeleton technique) and silently BYPASSED once the
                // skeleton was rewritten to correctly-spelled words in 72cabea, because Devanagari
                // has no suffix generator and no inflected form was listed.
                arguments("R2fix-HIGH: बलात्कारी (rapist)", "बलात्कारी", UnsafeHeadlineTopic.CRIME),
                // Category is DEATH, not CRIME: this headline also contains DEATH's फांसी
                // (hanging), and firstUnsafeTopic returns the FIRST matching category in
                // UnsafeHeadlineTopic.values() order (DEATH, CRIME, COMMUNAL, LEGAL) — see that
                // method's javadoc ("which one wins is a logging detail only; any match at all
                // suppresses the text"). What this probe actually proves is बलात्कारी को फांसी
                // (containing the regressed बलात्कारी term) is BLOCKED at all, same as the bare
                // बलात्कारी probe above.
                arguments(
                        "R2fix-HIGH: बलात्कारी को फांसी", "बलात्कारी को फांसी", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-HIGH: बलात्कारियों को सजा",
                        "बलात्कारियों को सजा",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-HIGH: हत्यारे पकड़े गए", "हत्यारे पकड़े गए", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-HIGH: हत्यारों को सजा", "हत्यारों को सजा", UnsafeHeadlineTopic.CRIME),
                arguments("R2fix-HIGH: हत्याएं बढ़ीं", "हत्याएं बढ़ीं", UnsafeHeadlineTopic.CRIME),
                arguments("R2fix-HIGH: दंगाइयों", "दंगाइयों", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2fix-HIGH: आत्महत्याओं", "आत्महत्याओं", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-HIGH: हादसे में 5 लोगों की मौतें",
                        "हादसे में 5 लोगों की मौतें",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-HIGH: कोविड से मौतों का आंकड़ा",
                        "कोविड से मौतों का आंकड़ा",
                        UnsafeHeadlineTopic.DEATH),
                // MEDIUM (digitBoundary) — rule 5's digit boundary was checked AFTER the confusable
                // fold, so a digit that folds to a letter (0/3/4/5) never registered as a digit and
                // no boundary was ever created for it.
                arguments("R2fix-MED-digit: #Riots1984", "#Riots1984", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-digit: #BombayBlasts1993",
                        "#BombayBlasts1993",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-digit: #Murder4Justice",
                        "#Murder4Justice",
                        UnsafeHeadlineTopic.CRIME),
                arguments("R2fix-MED-digit: #Riots05", "#Riots05", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2fix-MED-digit: #murder1", "#murder1", UnsafeHeadlineTopic.CRIME),
                arguments("R2fix-MED-digit: #rape0", "#rape0", UnsafeHeadlineTopic.CRIME),
                arguments("R2fix-MED-digit: #Blast4", "#Blast4", UnsafeHeadlineTopic.COMMUNAL),
                arguments("R2fix-MED-digit: #Stampede05", "#Stampede05", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-MED-digit: #दिल्लीदंगा (all-Devanagari joined hashtag)",
                        "#दिल्लीदंगा",
                        UnsafeHeadlineTopic.COMMUNAL),
                // MEDIUM (shot at) — the narrower replacement terms must still block.
                arguments(
                        "R2fix-MED-shot: Man shot at by gunman outside mall",
                        "Man shot at by gunman outside mall",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-shot: Gunman opened fire outside mall",
                        "Gunman opened fire outside mall",
                        UnsafeHeadlineTopic.CRIME),
                // MEDIUM (बम/धमाका) — the narrower replacement phrase must still block.
                arguments("R2fix-MED-bam: बम ब्लास्ट (bomb blast)", "बम ब्लास्ट", UnsafeHeadlineTopic.COMMUNAL),
                // MEDIUM (Hindi/Hinglish vocabulary gaps) — an independent reviewer's probe named
                // these outright as still bypassing 72cabea.
                arguments("R2fix-MED-vocab: युवती से दुष्कर्म", "युवती से दुष्कर्म", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: dushkarm ka aaropi giraftar",
                        "dushkarm ka aaropi giraftar",
                        UnsafeHeadlineTopic.CRIME),
                // T-GOLIVE-0918-R3 (vikram, 2026-09-18) — repair round 3: the bare "छात्रा से
                // छेड़छाड़" row above is REMOVED, not merely re-pointed. Round 3 found bare
                // "छेड़छाड़"/"chhedchhad" over-blocking benign tamper-sense sentences that use the
                // IDENTICAL "<subject> से छेड़छाड़" construction ("प्रकृति से छेड़छाड़ मत करो") —
                // see CreatorNudgeService's CRIME-set javadoc for why no phrase-only fix can keep
                // blocking one and not the other. This is now a documented, TESTED accepted gap
                // (see firstUnsafeTopic_repairRound3AcceptedGapsAndBenignSetStaysQuotable below),
                // not a silently dropped assertion. The qualified forms below still block.
                arguments(
                        "R2fix-MED-vocab: chhedchhad ka aaropi",
                        "chhedchhad ka aaropi",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: गोलीबारी में 2 घायल",
                        "गोलीबारी में 2 घायल",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: फांसी लगाकर जान दी",
                        "फांसी लगाकर जान दी",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-MED-vocab: fansi laga ke jaan de di",
                        "fansi laga ke jaan de di",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-MED-vocab: मर्डर केस में बड़ा खुलासा",
                        "मर्डर केस में बड़ा खुलासा",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: आतंकवादी हमला", "आतंकवादी हमला", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: आतंकवाद पर बड़ा फैसला",
                        "आतंकवाद पर बड़ा फैसला",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: सांप्रदायिक हिंसा भड़की",
                        "सांप्रदायिक हिंसा भड़की",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: sampradayik hinsa",
                        "sampradayik hinsa",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: मॉब लिंचिंग का मामला",
                        "मॉब लिंचिंग का मामला",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: पथराव के बाद तनाव",
                        "पथराव के बाद तनाव",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: बच्चे का अपहरण", "बच्चे का अपहरण", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: bachche ka apharan",
                        "bachche ka apharan",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: महिला को जिंदा जलाया",
                        "महिला को जिंदा जलाया",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-MED-vocab: maar daala gaya", "maar daala gaya", UnsafeHeadlineTopic.CRIME),
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — "R2fix-MED-vocab: goli maar di" (the bare
                // phrase, no subject) is REMOVED: round 4 found it over-blocking the Hinglish idiom
                // "X ko goli maar di" ("forget about X") — see CRIME's own javadoc and
                // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable.
                arguments(
                        "R2fix-MED-vocab: aatanki hamla", "aatanki hamla", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R2fix-MED-vocab: atankwadi hamla",
                        "atankwadi hamla",
                        UnsafeHeadlineTopic.COMMUNAL),
                // LOW — round-1's own LOW defect listed these as BYPASS and they were never closed.
                arguments(
                        "R2fix-LOW: m*rder in Delhi (asterisk->u)",
                        "m*rder in Delhi",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-LOW: r*pe case (asterisk->a)", "r*pe case", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R2fix-LOW: s*icide note (asterisk->u)",
                        "s*icide note",
                        UnsafeHeadlineTopic.DEATH),
                arguments("R2fix-LOW: k!lled (!->i)", "k!lled", UnsafeHeadlineTopic.DEATH),
                arguments("R2fix-LOW: su!c!de (!->i)", "su!c!de", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R2fix-LOW: unalived himself (evasion slang)",
                        "unalived himself",
                        UnsafeHeadlineTopic.DEATH),
                arguments("R2fix-LOW: dea7h toll (7->t)", "dea7h toll", UnsafeHeadlineTopic.DEATH),
                arguments("R2fix-LOW: dea+h toll (+->t)", "dea+h toll", UnsafeHeadlineTopic.DEATH),
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — "R2fix-LOW: maar diya gaya" (the bare
                // phrase, no subject) is REMOVED: round 4 found it over-blocking routine cricket
                // commentary ("Last ball pe chhakka maar diya gaya") — see CRIME's own javadoc and
                // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable. "maar daala gaya"/
                // "maar dala gaya" above are unaffected (no attested commentary collision).
                arguments(
                        "R2fix-LOW: ѕуісіде (all-Cyrillic homoglyph, missing д->d fold)",
                        "ѕуісіде",
                        UnsafeHeadlineTopic.DEATH));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("repairRound2FixProbes")
    @DisplayName(
            "T-GOLIVE-0918-R2 repair round 2: every independent-reviewer probe against 72cabea blocks"
                    + " as its named category")
    void firstUnsafeTopic_blocksEveryRepairRound2FixProbe(
            String defect, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + defect + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + defect + "]: \"" + probe + "\"");
    }

    /**
     * MEDIUM over-block fixes: the narrower "shot at by"/"opened fire" terms must not catch
     * ordinary creator filming language, and बम/धमाका must not catch festival/sale content, now
     * that both are phrase-only. All six were QUOTABLE at 49a0415 and OVER-BLOCKED once 72cabea
     * added the too-broad bare/phrase terms this round replaces.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Reel shot at Marine Drive",
                "Shot at golden hour on iPhone 15",
                "This whole vlog was shot at home",
                "बम बम भोले महाशिवरात्रि स्पेशल",
                "दिवाली धमाका सेल शुरू",
                "धमाका ऑफर सिर्फ आज"
            })
    @DisplayName(
            "T-GOLIVE-0918-R2 repair round 2: the narrowed shot-at/बम-धमाका terms do not over-block"
                    + " camera and festival/sale content")
    void firstUnsafeTopic_repairRound2NarrowedTermsStayQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    // ---------------------------------------------------------------------------------------
    // T-GOLIVE-0918-R3 repair round 3 (vikram, 2026-09-18) — an independent reviewer FAILED
    // commit 857e954 (repairRound2FixProbes()'s own commit) with two HIGHs, four MEDIUMs and two
    // LOWs. Every row below reproduces one named bypass/over-block from that review, table-driven,
    // same discipline as repairRound1Probes()/repairRound2Probes()/repairRound2FixProbes() above.
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> repairRound3Probes() {
        return Stream.of(
                // HIGH-1 — '!'/'*' folded to a letter in EVERY variant 857e954 shipped, so nothing
                // ever ended the token on "Murder!"-style punctuation. Fixed by making the fold
                // context-sensitive (isWordFlank) instead of adding a separator variant — see
                // PUNCTUATION_LETTER_LOOKALIKES's javadoc for why a variant-only fix could not also
                // have fixed the LOW-1 "Rio+ Carnival" over-block below.
                arguments("R3-HIGH1: Murder! (bang at word end)", "Murder!", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-HIGH1: Actor found dead! (bang at word end)",
                        "Actor found dead!",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-HIGH1: Stampede! (bang at word end)", "Stampede!", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-HIGH1: BREAKING: Suicide! (bang at word end)",
                        "BREAKING: Suicide!",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-HIGH1: 10 killed! (bang at word end)", "10 killed!", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-HIGH1: Riots!! Delhi on edge (doubled bang)",
                        "Riots!! Delhi on edge",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R3-HIGH1: Riots* in Delhi (asterisk at word end)",
                        "Riots* in Delhi",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R3-HIGH1: **Murder** in Pune (markdown bold)",
                        "**Murder** in Pune",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-HIGH1: *Rape* case shocks city (markdown italic)",
                        "*Rape* case shocks city",
                        UnsafeHeadlineTopic.CRIME),
                // HIGH-2 — the passive Hinglish/Devanagari crime-reporting register, closing the
                // round-1 LOW that was never closed AND covering the spelling variants an
                // independent reviewer's probe found. See CRIME's own javadoc for why the bare
                // active/casual forms are excluded instead (benign controls below).
                arguments(
                        "R3-HIGH2: maar dala gaya (single-a spelling)",
                        "maar dala gaya",
                        UnsafeHeadlineTopic.CRIME),
                arguments("R3-HIGH2: goli maari gayi", "goli maari gayi", UnsafeHeadlineTopic.CRIME),
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — "R3-HIGH2: युवक को मार डाला" is REMOVED:
                // round 4 found bare "को मार डाला" over-blocking the identical hyperbolic-song-lyric
                // construction ("इस गाने ने दिल को मार डाला") — see CRIME's own javadoc and
                // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable. "गोली मार दी" below is
                // unaffected (no attested idiom collision for that exact spelling).
                arguments(
                        "R3-HIGH2: गोली मार दी (Devanagari passive)",
                        "गोली मार दी",
                        UnsafeHeadlineTopic.CRIME),
                // MEDIUM — nukta/chandrabindu spelling-insensitivity (DEVANAGARI_NUKTA/
                // DEVANAGARI_CHANDRABINDU) closes the "same word, different mark" half; missing
                // inflections (Devanagari has no suffix generator) are their own literals below.
                arguments(
                        "R3-MED-spelling: फाँसी लगाकर जान दी (chandrabindu)",
                        "फाँसी लगाकर जान दी",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-MED-spelling: ख़ुदकुशी कर ली (nukta)",
                        "ख़ुदकुशी कर ली",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-MED-spelling: महिला को ज़िंदा जलाया (nukta)",
                        "महिला को ज़िंदा जलाया",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-spelling: हत्याएँ बढ़ीं (chandrabindu plural)",
                        "हत्याएँ बढ़ीं",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-spelling: छेडछाड का आरोपी (no nukta at all)",
                        "छेडछाड का आरोपी",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-spelling: हादसे में 3 की मौते (informal plural)",
                        "हादसे में 3 की मौते",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-MED-inflect: दुष्कर्मी गिरफ्तार",
                        "दुष्कर्मी गिरफ्तार",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-inflect: दुष्कर्मियों को सजा",
                        "दुष्कर्मियों को सजा",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-inflect: आत्महत्याएं बढ़ीं", "आत्महत्याएं बढ़ीं", UnsafeHeadlineTopic.DEATH),
                arguments("R3-MED-latin: phansi laga li", "phansi laga li", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R3-MED-latin: golibari mein 2 ghayal",
                        "golibari mein 2 ghayal",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-latin: hatyara pakda gaya",
                        "hatyara pakda gaya",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-latin: hatyare giraftar", "hatyare giraftar", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-latin: dushkarmi giraftar",
                        "dushkarmi giraftar",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R3-MED-latin: khudkhushi kar li", "khudkhushi kar li", UnsafeHeadlineTopic.DEATH),
                // T-GOLIVE-0918-R4 (vikram, 2026-09-19) — "R3-MED-shot: Man/Woman shot at outside
                // mall/market" (both rows) are REMOVED: round 4 found bare "man/woman shot at"
                // over-blocking photography captions using the identical "<person> shot at
                // <location/time>" grammar ("Woman shot at golden hour on 85mm") — see CRIME's own
                // javadoc and firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable. "shot at by"
                // (unambiguous — names the attacker) still blocks.
                //
                // "R3-MED-qatil: क़ातिल (with nukta)" and "कातिल गिरफ्तार (no nukta)" (both rows) are
                // REMOVED: round 4 found कातिल over-blocking Bollywood-song/romantic vocabulary
                // ("कातिल अदाएं डांस कवर") — the exact Hindi-Urdu translation of the already-excluded
                // English "killer", now added to that same exclusion list. See
                // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable and
                // firstUnsafeTopic_repairRound4BenignSetStaysQuotable.
                // LOW (still open per the round-3 review's failed clause 1) — '0' is genuinely
                // ambiguous between 'o' and 'u'; the old single '0'->'o' fold gave "succombs",
                // which matches nothing.
                arguments("R3-LOW-zero: 5ucc0mbs (0->u)", "5ucc0mbs", UnsafeHeadlineTopic.DEATH));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("repairRound3Probes")
    @DisplayName(
            "T-GOLIVE-0918-R3: every independent-reviewer probe against 857e954 blocks as its named"
                    + " category")
    void firstUnsafeTopic_blocksEveryRepairRound3Probe(
            String defect, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + defect + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + defect + "]: \"" + probe + "\"");
    }

    /**
     * Benign controls for every round-3 over-block fix, plus at least 10 new everyday creator
     * headlines in Hindi/Hinglish (done_when) — cricket commentary, a Bollywood song title, meme/
     * compliment slang, tamper-sense छेड़छाड़, gym "reps", and the punctuation-adjacent case the
     * HIGH-1 fix itself must not regress.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                // HIGH-2 — the bare active/casual maar-diya/daala/goli-maar forms this round
                // deliberately excludes (see CRIME's own class javadoc).
                "Chhakka maar diya Kohli ne!",
                "Sixer maar diya last ball pe!",
                "Maar Daala song dance cover",
                "Goli maar bheje mein dance cover",
                "Tune toh maar daala yaar, kya look hai",
                "Maar diya jaaye ya chhod diya jaaye meme",
                // MEDIUM (छेड़छाड़) — the tamper sense, lexically identical to the crime sense.
                "स्किन के साथ छेड़छाड़ मत करो",
                "प्रकृति से छेड़छाड़ मत करो",
                // MEDIUM (रेप) — the gym "reps" sense, identically spelled to the crime sense.
                "आखिरी रेप तक पुश करो",
                "बस एक रेप और, हार मत मानो",
                // LOW-1 — '+' unconditionally folding to 't' turned "rio+" into "riot" regardless
                // of what followed the '+'; fixed by the same context-sensitive fold as HIGH-1.
                "Rio+ Carnival looks",
                // HIGH-1 regression guard — the context-sensitive fold must not reintroduce any
                // ALREADY-accepted false positive merely because punctuation now sits next to it.
                "Killer ab workout!",
                "Bomber jacket styling!",
                "This whole vlog was shot at home!",
                // 10+ new everyday creator headlines in Hindi/Hinglish (done_when), covering
                // cricket, dance/song covers and vlog content the round-3 review found no coverage
                // for at all.
                "Kohli ne aaj zabardast shatak banaya",
                "Aaj ka dance cover bahut viral ho gaya",
                "Weekend vlog mein Goa ki trip dikhayi",
                "Mera skincare routine dekho, glow guaranteed hai",
                "Is Diwali outfit ideas try karo",
                "आज मैच में छक्कों की बारिश हुई",
                "यह गाना रील के लिए परफेक्ट है",
                "सुबह की चाय के साथ छोटी सी बातचीत",
                "नई रेसिपी ट्राई की आज किचन में",
                "फेस्टिव सीजन के लिए मेकअप टिप्स"
            })
    @DisplayName(
            "T-GOLIVE-0918-R3: benign Hinglish/Devanagari creator content stays quotable after the"
                    + " punctuation, maar-diya, छेड़छाड़ and रेप fixes")
    void firstUnsafeTopic_repairRound3BenignSetStaysQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    /**
     * T-GOLIVE-0918-R3 (vikram, 2026-09-18) — DOCUMENTED, TESTED accepted GAP (a headline that
     * stays quotable although it names a real crime): a bare crime headline with no case/accused/
     * complaint qualifier no longer blocks, because Hindi uses the IDENTICAL "&lt;subject&gt; से
     * छेड़छाड़" construction for both the crime sense and the harmless "tamper with" sense (see
     * the benign set above — "प्रकृति से छेड़छाड़ मत करो" uses the same words). This is the SAME
     * class of call as the accepted-false-positive list in {@code UnsafeHeadlineTopic}'s own class
     * javadoc ("killing", "sue", "plea") but in the opposite direction (a false NEGATIVE accepted
     * for a false POSITIVE's sake) — flagged for a Priya ruling rather than decided unilaterally
     * (see the CRIME enum's own comment at the छेड़छाड़ removal).
     */
    @Test
    @DisplayName(
            "T-GOLIVE-0918-R3: documented accepted gap — bare छेड़छाड़ without a case/accused"
                    + " qualifier stays quotable (a stated product trade-off, not a bug)")
    void firstUnsafeTopic_repairRound3AcceptedGapStaysQuotable() {
        String acceptedGap = "छात्रा से छेड़छाड़";
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(acceptedGap),
                "expected accepted-gap string to be quotable (if this now fails, the gap has been"
                        + " independently closed — update this test's comment, do not just delete the"
                        + " row): "
                        + acceptedGap);
    }

    /**
     * T-GOLIVE-0918-R3 (vikram, 2026-09-18) — DOCUMENTED, TESTED accepted OVER-BLOCK (the opposite
     * of the gap above: a benign headline that stays blocked). The round-2 digit-boundary fix that
     * correctly isolates "#Riots2024" also isolates "Sue" from "4Style" in "#Sue4Style" — landing
     * on the SAME already-accepted "sue" (given name) false positive round 1's own report already
     * documented for "Sue's makeup tutorial" ({@code UnsafeHeadlineTopic}'s own class javadoc lists
     * bare "sue" as an accepted false positive), not a new class of bug. Fixing it would mean not
     * splitting "#Riots2024" into "riots"+"2024" either, which would reopen the round-2 MEDIUM this
     * digit boundary exists to close — so this is accepted, not attempted here.
     */
    @Test
    @DisplayName(
            "T-GOLIVE-0918-R3: documented accepted over-block — #Sue4Style stays blocked (same"
                    + " accepted \"sue\" false positive as round 1, not a new bug)")
    void firstUnsafeTopic_repairRound3AcceptedOverblockStaysBlocked() {
        String acceptedOverblock = "#Sue4Style";
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(acceptedOverblock),
                "expected accepted-over-block string to stay blocked (if this now fails, either the"
                        + " digit-boundary rule or the bare \"sue\" accepted false positive has changed"
                        + " — update this test's comment, do not just delete the row): "
                        + acceptedOverblock);
    }

    // ---------------------------------------------------------------------------------------
    // T-GOLIVE-0918-R4 repair round 4 (vikram, 2026-09-19) — an independent reviewer FAILED commit
    // cf2f33c1d37ed943b6939334ba5a82ddeab8528d with one HIGH, five MEDIUMs and two LOWs. Every row
    // below reproduces one named bypass/over-block from that review, table-driven, same discipline
    // as repairRound1Probes()/repairRound2Probes()/repairRound3Probes() above.
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> repairRound4Probes() {
        return Stream.of(
                // HIGH — '!'/'+'/'*' folded to a letter whenever BOTH raw neighbours were merely
                // letter-or-digit, which let a decorative title-joiner/emphasis mark between two
                // DIFFERENT capitalised (or digit-suffixed) words glue them into one token exactly
                // like an unconditionally-folded '@' would. Fixed by restricting isWordFlank's
                // trailing side to a plain LOWERCASE letter (see its own javadoc) instead of adding
                // more fold-variant readings — a variant-only fix could only ADD matches, never
                // remove the over-fold causing this.
                arguments(
                        "R4-HIGH: Murder!Pune shocked", "Murder!Pune shocked", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-HIGH: Riots!Delhi on edge",
                        "Riots!Delhi on edge",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-HIGH: Murder!Delhi shocked",
                        "Murder!Delhi shocked",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-HIGH: Suicide!2024 report", "Suicide!2024 report", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R4-HIGH: Suicide+Note found", "Suicide+Note found", UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R4-HIGH: Stampede+Chaos at station",
                        "Stampede+Chaos at station",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R4-HIGH: Death+Destruction", "Death+Destruction", UnsafeHeadlineTopic.DEATH),
                arguments("R4-HIGH: Murder*Delhi", "Murder*Delhi", UnsafeHeadlineTopic.CRIME),
                // LOW — the round-3 "Rio+ Carnival" (with space) fix never covered the no-space
                // hashtag form; the SAME isWordFlank fix above also closes this, since '+' followed
                // by the capital 'C' of "Carnival" is no longer treated as mid-word either. Verified
                // as a genuine bypass fix, not a benign control, by also asserting it below in the
                // benign set (a moot assertion would mean this row belongs there instead).
                //
                // MEDIUM — mixed-script hashtag boundary. A Latin<->Devanagari script change carries
                // no case-transition signal for rule 5's camelBoundary and, for a Devanagari suffix,
                // no digit either — so "Delhi" + "दंगे"/"हत्याकांड" glued into one token with no
                // boundary at all. "#दंगेDelhi" is a regression (BLOCKED at 49a0415 via the
                // since-replaced skeleton technique).
                arguments(
                        "R4-MED-script: #Delhiदंगे (Latin->Devanagari)",
                        "#Delhiदंगे",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-script: #Delhiहत्याकांड (Latin->Devanagari)",
                        "#Delhiहत्याकांड",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-script: #दंगेDelhi (Devanagari->Latin, regression)",
                        "#दंगेDelhi",
                        UnsafeHeadlineTopic.COMMUNAL),
                // MEDIUM — Devanagari/Hinglish literal-inflection gaps an independent reviewer's
                // probe named outright.
                arguments(
                        "R4-MED-vocab: आतंकवादियों ने हमला किया",
                        "आतंकवादियों ने हमला किया",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: आतंकियों का सफाया",
                        "आतंकियों का सफाया",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: बम धमाके से दहला शहर",
                        "बम धमाके से दहला शहर",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: बम धमाकों से दहला शहर",
                        "बम धमाकों से दहला शहर",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: बम धमाके में 10 घायल",
                        "बम धमाके में 10 घायल",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: aatankwadiyon ne hamla kiya",
                        "aatankwadiyon ne hamla kiya",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: dangon mein 5 ghayal",
                        "dangon mein 5 ghayal",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: hatyaon ka silsila",
                        "hatyaon ka silsila",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: balatkariyon ko saza",
                        "balatkariyon ko saza",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: दंगो में 5 घायल (informal, regression)",
                        "दंगो में 5 घायल",
                        UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: हत्याओ का सिलसिला (informal)",
                        "हत्याओ का सिलसिला",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: बलात्कारियो को सजा (informal)",
                        "बलात्कारियो को सजा",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: अपहरणकर्ता गिरफ्तार",
                        "अपहरणकर्ता गिरफ्तार",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: आत्मघाती हमला", "आत्मघाती हमला", UnsafeHeadlineTopic.COMMUNAL),
                arguments(
                        "R4-MED-vocab: सुसाइड नोट में बड़ा खुलासा",
                        "सुसाइड नोट में बड़ा खुलासा",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R4-MED-vocab: हादसे में 5 लोग मारे गए",
                        "हादसे में 5 लोग मारे गए",
                        UnsafeHeadlineTopic.DEATH),
                arguments(
                        "R4-MED-vocab: चाकू से हमला", "चाकू से हमला", UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: महिला को ज़िंदा जला दिया",
                        "महिला को ज़िंदा जला दिया",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: हत्यारिन पत्नी गिरफ्तार",
                        "हत्यारिन पत्नी गिरफ्तार",
                        UnsafeHeadlineTopic.CRIME),
                arguments(
                        "R4-MED-vocab: दिग्गज अभिनेता का निधन",
                        "दिग्गज अभिनेता का निधन",
                        UnsafeHeadlineTopic.DEATH),
                // MEDIUM (phansi/fansi vocabulary) — the qualified hanging-specific collocations
                // that replace the now-removed bare Latin forms must still block.
                arguments(
                        "R4-MED-latin: fansi laga ke jaan de di (still blocks via qualified phrase)",
                        "fansi laga ke jaan de di",
                        UnsafeHeadlineTopic.DEATH),
                // LOW — the mutation-testing gap: removing the vowel-fold from ONLY the 40 main
                // CONFUSABLE_FOLD_VARIANTS entries (as opposed to the 5 digit-literal variants,
                // which also map '*' to a vowel) used to survive undetected because no probe forced
                // BOTH an unconditionally-letter-folded digit (here '3'->'e', which the 5
                // digit-literal variants deliberately leave AS a digit so hashtag/digit-suffix
                // boundaries stay visible) and an asterisk-vowel fold in the SAME matched word: the
                // digit-literal variants can never match "m*rd3r" as "murder" (their '3' stays a
                // literal digit, not 'e'), so only a main-loop variant with vowel='u' can.
                arguments("R4-LOW: m*rd3r (asterisk->u AND digit-fold 3->e together)",
                        "m*rd3r", UnsafeHeadlineTopic.CRIME));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" -> {2}")
    @MethodSource("repairRound4Probes")
    @DisplayName(
            "T-GOLIVE-0918-R4: every independent-reviewer probe against cf2f33c blocks as its named"
                    + " category")
    void firstUnsafeTopic_blocksEveryRepairRound4Probe(
            String defect, String probe, UnsafeHeadlineTopic expectedCategory) {
        assertEquals(
                expectedCategory,
                CreatorNudgeService.firstUnsafeTopic(probe),
                "probe did not block as " + expectedCategory + " [" + defect + "]: \"" + probe + "\"");
        assertFalse(
                CreatorNudgeService.isQuotableInCreatorCopy(probe),
                "probe must not be quotable in creator copy [" + defect + "]: \"" + probe + "\"");
    }

    /**
     * Benign controls for every round-4 over-block fix, plus at least 10 new everyday creator
     * headlines in Hindi/Hinglish (done_when) not covered by any earlier round's benign set.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                // phansi/fansi — the everyday "stuck" sense, lexically identical in Latin script to
                // the now-qualified hanging sense.
                "Traffic mein phansi hui thi 2 ghante",
                "Exam ke chakkar mein fansi rahi",
                // कातिल — Bollywood-song/romantic register, the same false-positive class as the
                // already-excluded English "killer".
                "कातिल अदाएं डांस कवर",
                "तेरी कातिल निगाहें रील",
                // man/woman shot at — photography usage, lexically identical to the crime register.
                "Woman shot at golden hour on 85mm",
                "Street portrait: man shot at Chandni Chowk",
                "Old man shot at sunset on 35mm film",
                // opened fire — brand-name collision.
                "Just opened Fire-Boltt Ninja 3 unboxing",
                // goli maar di / maar diya gaya — Hinglish idiom and cricket commentary.
                "Cheat day pe diet ko goli maar di",
                "Tension ko goli maar di, weekend vibes",
                "Last ball pe chhakka maar diya gaya",
                // को मार डाला — hyperbolic song-lyric idiom, identical construction to the genuine
                // crime headline.
                "इस गाने ने दिल को मार डाला",
                // hanged / body found — ordinary transitive "hang" and fitness idiom.
                "Hanged fairy lights for Diwali decor",
                "My body found its rhythm with Pilates",
                "Your dream body found in 30 days",
                // LOW — the no-space "Rio+Carnival" hashtag form, closed by the same isWordFlank
                // fix as the HIGH bypasses above.
                "#Rio+Carnival 2025",
                "Rio+Carnival looks",
                // HIGH regression guard — the narrower isWordFlank must not reintroduce any
                // ALREADY-accepted false positive, nor stop matching a genuine mid-word fold that
                // happens to be followed by more of the SAME lowercase word.
                "Killer ab workout!",
                "This vlog was shot at home!",
                // 10+ new everyday creator headlines in Hindi/Hinglish (done_when), distinct from
                // every earlier round's benign set.
                "Aaj subah gym mein naya PR banaya",
                "Yeh recipe har kisi ko pasand aayegi",
                "Mera naya vlog Goa trip pe based hai",
                "Is outfit ke saath konsa makeup try karu",
                "Weekend pe naya trek explore kiya humne",
                "आज की रील बहुत सारे लोगों को पसंद आई",
                "यह प्रोडक्ट स्किन को ग्लो देगा",
                "कल का मैच बहुत रोमांचक रहा",
                "नए साल का प्लान बना लिया हमने",
                "यह गाना हर पार्टी में बजता है"
            })
    @DisplayName(
            "T-GOLIVE-0918-R4: benign Hinglish/Devanagari creator content stays quotable after the"
                    + " punctuation-flank, phansi/fansi, कातिल, shot-at, maar-diya/goli-maar, को मार"
                    + " डाला, hanged and body-found fixes")
    void firstUnsafeTopic_repairRound4BenignSetStaysQuotable(String benign) {
        assertNull(
                CreatorNudgeService.firstUnsafeTopic(benign),
                "benign control was classified unsafe: " + benign);
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(benign),
                "benign control was classified unsafe: " + benign);
    }

    /**
     * T-GOLIVE-0918-R4 (vikram, 2026-09-19) — DOCUMENTED, TESTED accepted GAPS: headlines that stay
     * quotable although each names something the corresponding removed term used to catch. Every
     * one of these shares its exact surface grammar with a benign headline in the set above (see
     * each removed term's own comment in {@code UnsafeHeadlineTopic} for the specific collision),
     * so no phrase-only fix can keep blocking one without also re-blocking the other. Same class of
     * call as round 3's छेड़छाड़ accepted gap, and flagged for a Priya ruling the same way.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "कातिल गिरफ्तार", // कातिल — collides with romantic/song usage.
                "Man shot at outside mall", // collides with photography "shot at <place>".
                "Woman shot at outside market",
                "goli maar di", // collides with the Hinglish "forget about X" idiom.
                "maar diya gaya", // collides with cricket commentary.
                "युवक को मार डाला", // को मार डाला — collides with hyperbolic song-lyric idiom.
                "Body found in suitcase" // body found — collides with the fitness idiom.
            })
    @DisplayName(
            "T-GOLIVE-0918-R4: documented accepted gaps — headlines that stay quotable despite"
                    + " naming a real crime/death, because the term that used to catch them collided"
                    + " with a benign headline of identical surface grammar (a stated product"
                    + " trade-off, not a bug)")
    void firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable(String acceptedGap) {
        assertTrue(
                CreatorNudgeService.isQuotableInCreatorCopy(acceptedGap),
                "expected accepted-gap string to be quotable (if this now fails, either the gap has"
                        + " been independently closed or a term was wrongly re-added — update this"
                        + " test's comment, do not just delete the row): "
                        + acceptedGap);
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
