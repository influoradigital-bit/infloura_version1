package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.config.TrendIngestProperties;
import com.influora.domain.entity.Trend;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.service.trendspark.ThemeMatchService;
import com.influora.service.trendspark.ingest.TrendIngestWriter;
import com.influora.service.trendspark.ingest.TrendSourceClient;
import com.influora.service.trendspark.ingest.TrendSourceClient.RawTrend;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — proves every clause of this build's done_when against
 * wiki/decisions/2026-09-18-trend-headline-screening.md and
 * .proof-os/tasks/T-COPILOT-ON-0910/job-design.md. {@link TrendSourceClient} and
 * {@link BrandSafetyAiClient} are mocked here (both are collaborator ABSTRACTIONS this job
 * consumes, not HTTP clients this lane wrote — their own wire-shape correctness is proven by
 * {@code NewsApiTopHeadlinesClientTest}/{@code TmdbUpcomingClientTest}/
 * {@code YouTubeMostPopularClientTest} and (pre-existing, another lane) {@code
 * BrandSafetyAiClientTest}). The word filter is exercised through the REAL production wiring
 * ({@link TrendPullJob#TrendPullJob(List, BrandSafetyAiClient, ThemeMatchService,
 * TrendIngestWriter, TrendIngestProperties)}) in every test below except where a test is
 * specifically about the classifier or the enabled-flag gate.
 */
class TrendPullJobTest {

    private TrendSourceClient tmdbClient;
    private TrendSourceClient newsClient;
    private TrendSourceClient youtubeClient;
    private BrandSafetyAiClient brandSafetyAiClient;
    private ThemeMatchService themeMatchService;
    private TrendIngestWriter writer;
    private TrendIngestProperties props;

    private Logger jobLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        tmdbClient = mock(TrendSourceClient.class);
        when(tmdbClient.sourceId()).thenReturn("tmdb");
        newsClient = mock(TrendSourceClient.class);
        when(newsClient.sourceId()).thenReturn("news");
        youtubeClient = mock(TrendSourceClient.class);
        when(youtubeClient.sourceId()).thenReturn("youtube_trending");

        brandSafetyAiClient = mock(BrandSafetyAiClient.class);

        themeMatchService = new ThemeMatchService();
        Method loadTaxonomy = ThemeMatchService.class.getDeclaredMethod("loadTaxonomy");
        loadTaxonomy.setAccessible(true);
        loadTaxonomy.invoke(themeMatchService);
        assertTrue(
                !themeMatchService.knownThemes().isEmpty(),
                "taxonomy failed to load — the rest of this test would pass vacuously");

        writer = mock(TrendIngestWriter.class);
        when(writer.writeAll(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        props = new TrendIngestProperties();
        props.setEnabled(true);
        props.setNewsapiApiKey("news-key");
        props.setTmdbApiKey("tmdb-key");
        props.setYoutubeApiKey("yt-key");
        props.setClassifierWorkspaceId("ws_test_platform");

        jobLogger = (Logger) LoggerFactory.getLogger(TrendPullJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);

        // Default: classifier never flags anything, unless a test overrides it. thenAnswer (not
        // thenReturn) because the job generates a fresh ULID content_id per headline — echoing the
        // id back from the actual request is required now that isFlaggedByClassifier rejects a
        // content_id mismatch (repair round 1, MEDIUM #2).
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenAnswer(inv -> List.of(floorFlags(requestedId(inv))));
    }

    /** Pulls the {@code content_id} TrendPullJob actually sent, so a stubbed classifier response
     * can echo it back — the job's ULIDs are generated fresh per call and can't be hardcoded. */
    @SuppressWarnings("unchecked")
    private static String requestedId(org.mockito.invocation.InvocationOnMock inv) {
        List<ContentItem> items = (List<ContentItem>) inv.getArgument(1);
        return items.get(0).contentId();
    }

    /** The 10 fixed GARM categories (influora-ai/app/tools/schemas.py::GARM_CATEGORIES) — a real
     * classifier response always covers all 10 (server-validated by influora-ai's
     * _validate_model_result); TrendPullJob#isFlaggedByClassifier now rejects a response that
     * doesn't (repair round 1, MEDIUM #2), so every fixture below must build all 10. */
    private static final List<String> GARM_CATEGORIES =
            List.of(
                    "adult_explicit_sexual_content",
                    "arms_ammunition",
                    "crime_harmful_acts_to_individuals",
                    "death_injury_military_conflict",
                    "hate_speech_acts_of_aggression",
                    "illegal_drugs_tobacco_alcohol",
                    "obscenity_profanity",
                    "spam_or_harmful_content",
                    "terrorism",
                    "debated_sensitive_social_issues");

    /** Builds a full 10-category GarmFlag list with every category at {@code "floor"} except
     * {@code flaggedCategory} (if non-null), which is set to {@code flaggedRisk}. */
    private static List<GarmFlag> garmFlags(String flaggedCategory, String flaggedRisk) {
        List<GarmFlag> flags = new ArrayList<>();
        for (String category : GARM_CATEGORIES) {
            boolean isFlagged = category.equals(flaggedCategory);
            flags.add(new GarmFlag(category, isFlagged ? flaggedRisk : "floor", isFlagged ? "flagged" : "n/a"));
        }
        return flags;
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logAppender);
    }

    private TrendPullJob buildJob() {
        return new TrendPullJob(
                List.of(tmdbClient, newsClient, youtubeClient),
                brandSafetyAiClient,
                themeMatchService,
                writer,
                props);
    }

    private static ClassifiedItem floorFlags(String id) {
        return new ClassifiedItem(id, garmFlags(null, null), "neutral", 0.0, 100.0, "no concern");
    }

    private static ClassifiedItem flaggedResult(String id, String risk) {
        return new ClassifiedItem(
                id,
                garmFlags("death_injury_military_conflict", risk),
                "negative",
                -0.8,
                10.0,
                risk + " risk");
    }

    // --- done_when: with trend-ingest.enabled=false nothing runs ---------------------------

    @Test
    @DisplayName("enabled=false: no source is fetched and nothing is written")
    void disabledSkipsEntireRun() {
        // Wire a source that WOULD produce a storable row if the run proceeded — otherwise this
        // test cannot distinguish "the enabled gate stopped the run" from "no source was
        // configured anyway" and would pass even with the gate removed (falsification check: it
        // did, before this fixture change — a test that cannot fail proves nothing).
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        props.setEnabled(false);

        buildJob().pullTrends();

        verify(tmdbClient, never()).fetch();
        verify(newsClient, never()).fetch();
        verify(youtubeClient, never()).fetch();
        verify(writer, never()).writeAll(any());
    }

    // --- done_when: a missing API key skips only that source, and logs it ------------------

    @Test
    @DisplayName("a missing key skips only that source (its fetch() is never called) and logs it")
    void missingKeySkipsOnlyThatSource() {
        props.setTmdbApiKey(""); // TMDb unconfigured; news + youtube still configured
        when(newsClient.isConfigured()).thenReturn(true);
        when(youtubeClient.isConfigured()).thenReturn(true);
        when(tmdbClient.isConfigured()).thenReturn(false);
        when(newsClient.fetch()).thenReturn(List.of());
        when(youtubeClient.fetch()).thenReturn(List.of());

        buildJob().pullTrends();

        verify(tmdbClient, never()).fetch();
        verify(newsClient, times(1)).fetch();
        verify(youtubeClient, times(1)).fetch();

        boolean loggedSkip =
                logAppender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.INFO
                                                && e.getFormattedMessage().contains("tmdb")
                                                && e.getFormattedMessage().toLowerCase().contains("skipped"));
        assertTrue(loggedSkip, "expected an INFO log naming the skipped source (F-0781)");
    }

    // --- done_when: a headline the word filter blocks is never stored ----------------------

    @Test
    @DisplayName("a headline the deterministic word filter blocks is never stored")
    void wordFilterBlockedHeadlineNeverStored() {
        // T-GOLIVE-0918 repair round 1 [vikram · 2026-09-18] — LOW fix: the old fixture ("Popular
        // actor dies in car crash on set") matches no taxonomy theme either, so the "never stored"
        // assertion was vacuous — with the word filter removed the headline would still be
        // dropped at the theme-match stage, and the test would stay green through the wrong gate.
        // This headline matches the "diwali" keyword (-> festive/light/celebration/family/
        // tradition themes) AND the DEATH word-filter category ("kills"), so removing the word
        // filter would make this headline reach a themed, classifier-approved, storable row —
        // the storage assertion below is now load-bearing.
        wireSingleSource(newsClient, "Diwali fireworks factory blast kills several workers");

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(captor.getValue().isEmpty(), "an unsafe headline must never reach the writer");
        // The classifier must not even be called — the word filter is the FIRST gate.
        verify(brandSafetyAiClient, never()).classify(anyString(), anyList());
    }

    // --- done_when: passes word filter but classifier flags it -> never stored -------------

    @ParameterizedTest(name = "classifier risk={0} is rejected, never stored")
    @ValueSource(strings = {"low", "medium", "high"})
    @DisplayName("a headline the classifier flags at low/medium/high risk is never stored")
    void classifierFlaggedHeadlineNeverStored(String risk) {
        // T-GOLIVE-0918 repair round 1 [vikram · 2026-09-18] — MEDIUM #3 fix: the pre-repair test
        // only exercised risk="high". Mutation M7 (reject only risk.equals("high")) survived 8/8
        // green against that single case — a regression that stored "low"/"medium" headlines would
        // have shipped undetected. Parameterizing over all 3 non-floor levels closes that gap.
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenAnswer(inv -> List.of(flaggedResult(requestedId(inv), risk)));

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(
                captor.getValue().isEmpty(),
                "a classifier-flagged headline (risk=" + risk + ") must never be stored");
    }

    // --- repair round 1, MEDIUM #2: a malformed/lenient-bound classifier result fails closed ---

    @Test
    @DisplayName("classifier result with a null risk on a flag fails closed, stores nothing")
    void classifierNullRiskFailsClosed() {
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenAnswer(
                        inv -> {
                            String id = requestedId(inv);
                            List<GarmFlag> flags = new ArrayList<>(garmFlags(null, null));
                            // Replace one category's flag with a null-risk flag — simulates a DTO
                            // field rename under lenient @JsonIgnoreProperties(ignoreUnknown=true)
                            // binding nulling every risk.
                            flags.set(0, new GarmFlag(GARM_CATEGORIES.get(0), null, "n/a"));
                            return List.of(new ClassifiedItem(id, flags, "neutral", 0.0, 100.0, "n/a"));
                        });

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(captor.getValue().isEmpty(), "a null-risk flag must fail closed, not be read as safe");
    }

    @Test
    @DisplayName("classifier result with an empty garm_flags list fails closed, stores nothing")
    void classifierEmptyFlagsFailsClosed() {
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenAnswer(
                        inv ->
                                List.of(
                                        new ClassifiedItem(
                                                requestedId(inv), List.of(), "neutral", 0.0, 100.0, "n/a")));

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(
                captor.getValue().isEmpty(), "an empty garm_flags list must fail closed, not be read as safe");
    }

    @Test
    @DisplayName("classifier result with a content_id mismatch fails closed, stores nothing")
    void classifierContentIdMismatchFailsClosed() {
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenReturn(List.of(floorFlags("some-other-content-id")));

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(
                captor.getValue().isEmpty(), "a content_id mismatch must fail closed, not be read as safe");
    }

    // --- done_when: classifier failure/timeout stores nothing -------------------------------

    @Test
    @DisplayName("a classifier failure (exception/timeout) stores nothing — fails closed")
    void classifierFailureStoresNothing() {
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");
        when(brandSafetyAiClient.classify(anyString(), anyList()))
                .thenThrow(new BrandSafetyAiException("influora-ai brand-safety call failed"));

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(captor.getValue().isEmpty(), "a classifier failure must fail CLOSED — nothing stored");
    }

    @Test
    @DisplayName("classifier workspace not configured: fails closed, stores nothing, never calls classify")
    void classifierUnconfiguredFailsClosed() {
        props.setClassifierWorkspaceId("");
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");

        buildJob().pullTrends();

        verify(brandSafetyAiClient, never()).classify(anyString(), anyList());
        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    // --- done_when: a passing headline is stored with only known-taxonomy themes + expiry ---

    @Test
    @DisplayName("a headline passing both gates is stored with non-empty known-taxonomy themes and"
            + " a future expiry")
    void passingHeadlineIsStoredWithThemesAndExpiry() {
        wireSingleSource(newsClient, "Diwali fashion haul trending this week");

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        Trend stored = captor.getValue().get(0);
        assertTrue(stored.getThemesJson().contains("festive") || stored.getThemesJson().length() > 2);
        for (String theme : themeMatchService.parseThemeJson(stored.getThemesJson())) {
            assertTrue(
                    themeMatchService.knownThemes().contains(theme),
                    "stored theme '" + theme + "' is not in the known taxonomy (F-0823)");
        }
        assertTrue(stored.getExpiresAt().isAfter(java.time.Instant.now()));
        assertEquals("IN", stored.getRegion());
    }

    @Test
    @DisplayName("a headline that matches no taxonomy theme is dropped, never stored")
    void headlineWithNoKnownThemeIsDropped() {
        wireSingleSource(newsClient, "Zxq Wvbn Qplm reported today");

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    // --- repair round 1, LOW #6: one misbehaving source must never sink another's rows ---------

    @Test
    @DisplayName("a source throwing does not stop another source's row from being stored")
    void sourceFailureIsolatedFromOtherSources() {
        // T-GOLIVE-0918 repair round 1 [vikram · 2026-09-18] — job-level test for safeFetch's
        // per-source isolation. Mutation M8 (narrow the catch to IllegalStateException) survived
        // 8/8 with no test exercising a source throwing at the job level; this proves the RIGHT
        // behavior directly: one source throwing RuntimeException must not stop a healthy
        // source's row from being written.
        when(tmdbClient.isConfigured()).thenReturn(true);
        when(newsClient.isConfigured()).thenReturn(true);
        when(youtubeClient.isConfigured()).thenReturn(false);
        when(tmdbClient.fetch()).thenThrow(new RuntimeException("upstream 500"));
        when(newsClient.fetch())
                .thenReturn(List.of(new RawTrend("Diwali fashion haul trending this week", "news", "")));

        buildJob().pullTrends();

        ArgumentCaptor<List<Trend>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeAll(captor.capture());
        assertEquals(
                1,
                captor.getValue().size(),
                "the healthy source's row must still be written despite the other source throwing");
    }

    private void wireSingleSource(TrendSourceClient configuredClient, String headline) {
        when(tmdbClient.isConfigured()).thenReturn(configuredClient == tmdbClient);
        when(newsClient.isConfigured()).thenReturn(configuredClient == newsClient);
        when(youtubeClient.isConfigured()).thenReturn(configuredClient == youtubeClient);
        // sourceId() resolved BEFORE entering the when(...) chain below — calling a second mock
        // method as an argument expression inside when(...).thenReturn(...) confuses Mockito's
        // ongoing-stubbing recorder (it reads as an attempt to stub that second call instead),
        // and throws UnfinishedStubbingException. Verified directly: this was red before the fix.
        String sourceId = configuredClient.sourceId();
        when(configuredClient.fetch()).thenReturn(List.of(new RawTrend(headline, sourceId, "")));
    }
}
