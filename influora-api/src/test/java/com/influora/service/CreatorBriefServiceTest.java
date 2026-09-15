package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.BriefSource;
import com.influora.domain.enums.BriefStatus;
import com.influora.integration.ai.MeeraBriefAiClient;
import com.influora.integration.ai.MeeraBriefAiClient.BriefResult;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.service.brief.BriefFallbackExtractor;
import com.influora.service.brief.CreatorBriefWriter;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.BriefListItem;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8), B0-42 — proof for the capability the feature is named
 * for.
 *
 * <p><b>The load-bearing test in this file is
 * {@link #paste_persistsTheRawTextBeforeTheAiCall_soAnOutageDegradesRatherThanLoses()}.</b> Everything
 * else here checks that the right values come back; that one checks the creator does not lose what she
 * typed. A creator pastes a brief once, often from a DM she will not keep. If the extraction is
 * attempted before the text is stored and the provider is down, the paste is gone — and nothing in a
 * response-shape assertion would notice, because the response on that path looks the same either way:
 * a fallback extraction with {@code degraded_reason} set. So that test pins the ORDER, not the output,
 * and it was falsified by inverting the two statements in {@code paste} and watching it go red.
 *
 * <p>Uses the REAL {@link BriefFallbackExtractor} rather than a mock. A mocked fallback would let the
 * degraded path pass while the deterministic extractor returned nothing at all, which is precisely the
 * failure the fallback exists to prevent.
 */
class CreatorBriefServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String BRIEF_ID = "01HBRIEF12345678901234";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890123";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN12345678901";

    private static final String RAW_BRIEF =
            "Hi! Glow Cosmetics here. We want 1 reel and 1 story set for our Vitamin C serum. "
                    + "Budget INR 8000, live by 2026-10-05. 60 days exclusivity, 50% advance.";

    private CreatorBriefRepository briefRepository;
    private CreatorAgentPreferencesService preferencesService;
    private MeeraBriefAiClient briefAiClient;
    private BriefFallbackExtractor fallbackExtractor;
    private DealRiskService dealRiskService;
    private RateQuoteService rateQuoteService;
    private CollaborationRepository collaborationRepository;
    private CampaignRepository campaignRepository;
    private DealMessageRepository dealMessageRepository;
    private CreatorProfileRepository creatorProfileRepository;

    private CreatorBriefService service;

    /** Every brief the repository was asked to save, in order, as the service handed it over. */
    private final List<CreatorBrief> saved = new ArrayList<>();

    /**
     * The FIELDS of each save, frozen at the moment of the call. The entity is a single instance the
     * service mutates in place, so {@link #saved} alone can only ever answer questions about the final
     * state — which is how {@code paste_firstSaveIsNewWithNoExtraction} came to assert nothing its name
     * promised.
     */
    private final List<SavedState> savedStates = new ArrayList<>();

    private record SavedState(
            BriefStatus status,
            String rawText,
            String extractedJson,
            String riskFlagsJson,
            String quoteJson,
            String extractionSource) {

        static SavedState of(CreatorBrief brief) {
            return new SavedState(
                    brief.getStatus(),
                    brief.getRawText(),
                    brief.getExtractedJson(),
                    brief.getRiskFlagsJson(),
                    brief.getQuoteJson(),
                    brief.getExtractionSource());
        }
    }

    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        briefRepository = mock(CreatorBriefRepository.class);
        preferencesService = mock(CreatorAgentPreferencesService.class);
        briefAiClient = mock(MeeraBriefAiClient.class);
        fallbackExtractor = new BriefFallbackExtractor();
        dealRiskService = mock(DealRiskService.class);
        rateQuoteService = mock(RateQuoteService.class);
        collaborationRepository = mock(CollaborationRepository.class);
        campaignRepository = mock(CampaignRepository.class);
        dealMessageRepository = mock(DealMessageRepository.class);
        creatorProfileRepository = mock(CreatorProfileRepository.class);

        service =
                new CreatorBriefService(
                        briefRepository,
                        // The REAL writer over the mocked repository, not a mock of it. The writer is a
                        // separate bean purely so its two @Transactional boundaries exist in
                        // production (self-invocation would not create them); mocking it here would
                        // hide every save from this harness and delete the order proof below.
                        new CreatorBriefWriter(briefRepository),
                        preferencesService,
                        briefAiClient,
                        fallbackExtractor,
                        dealRiskService,
                        rateQuoteService,
                        collaborationRepository,
                        campaignRepository,
                        dealMessageRepository,
                        creatorProfileRepository,
                        new ObjectMapper());

        profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(preferencesService.requireCreatorProfile(CREATOR_USER_ID)).thenReturn(profile);
        lenient()
                .when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(prefs());
        lenient().when(preferencesService.getByProfileId(CREATOR_PROFILE_ID)).thenReturn(prefs());
        lenient()
                .when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));
        lenient()
                .when(dealRiskService.evaluateExtraction(any(), any(), any(), any()))
                .thenReturn(List.of(riskFlag()));
        lenient().when(rateQuoteService.quoteForExtraction(any(), any(), any())).thenReturn(quote());

        lenient()
                .when(briefRepository.save(any(CreatorBrief.class)))
                .thenAnswer(
                        invocation -> {
                            CreatorBrief brief = invocation.getArgument(0);
                            saved.add(brief);
                            savedStates.add(SavedState.of(brief));
                            return brief;
                        });
    }

    // ------------------------------------------------------------------
    // Paste, AI path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("paste with a working AI extraction stores it, labels the source AI and sets no degraded reason")
    void paste_withAi() {
        when(briefAiClient.extract(eq(CREATOR_PROFILE_ID), any(), any()))
                .thenReturn(BriefResult.of(aiExtraction()));

        BriefAnalysisResponse response = service.paste(CREATOR_USER_ID, RAW_BRIEF);

        assertEquals(CreatorBrief.EXTRACTION_SOURCE_AI, response.extractionSource());
        assertNull(response.degradedReason(), "a successful AI read is not degraded");
        assertEquals(BriefStatus.ANALYZED.name(), response.status());
        assertEquals(BriefSource.PASTED.name(), response.source());
        assertEquals("Glow Cosmetics", response.extraction().brandName());
        assertEquals(new BigDecimal("8000"), response.extraction().budgetInr());
        assertEquals(1, response.flags().size(), "the risk flags are carried through");
        assertNotNull(response.quote(), "the priced package is carried through");
        assertEquals(3, response.summaryLines().size());

        // The profile id, never the user id — the Python route equality-checks it against the token.
        verify(briefAiClient).extract(eq(CREATOR_PROFILE_ID), any(), any());
        verify(briefAiClient, never()).extract(eq(CREATOR_USER_ID), any(), any());
    }

    @Test
    @DisplayName("paste snapshots the extraction, flags and quote onto the row as one write")
    void paste_writesAllThreeSnapshotsTogether() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.of(aiExtraction()));

        service.paste(CREATOR_USER_ID, RAW_BRIEF);

        CreatorBrief analysed = saved.get(saved.size() - 1);
        assertNotNull(analysed.getExtractedJson());
        assertNotNull(analysed.getRiskFlagsJson());
        assertNotNull(analysed.getQuoteJson());
        assertEquals(CreatorBrief.EXTRACTION_SOURCE_AI, analysed.getExtractionSource());
        assertEquals("Glow Cosmetics", analysed.getBrandNameGuess());
    }

    // ------------------------------------------------------------------
    // Paste, fallback path — the one that matters
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "THE GUARANTEE: a paste whose AI call fails still has its raw text persisted BEFORE the call,"
                    + " and returns a labelled fallback")
    void paste_persistsTheRawTextBeforeTheAiCall_soAnOutageDegradesRatherThanLoses() {
        // Recorded from INSIDE the AI call: was the creator's text already in the repository by the
        // time the provider was asked? If the two statements in `paste` are swapped, this is false and
        // the assertion below fails — which is how this test was falsified.
        AtomicBoolean rawTextAlreadyPersisted = new AtomicBoolean(false);
        when(briefAiClient.extract(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            rawTextAlreadyPersisted.set(
                                    saved.stream()
                                            .anyMatch(brief -> RAW_BRIEF.equals(brief.getRawText())));
                            return BriefResult.unavailable();
                        });

        BriefAnalysisResponse response = service.paste(CREATOR_USER_ID, RAW_BRIEF);

        assertTrue(
                rawTextAlreadyPersisted.get(),
                "the raw text must be saved BEFORE the AI call — otherwise a provider outage loses a"
                        + " brief the creator pasted once and may no longer have");

        // And the save/call/save order, stated directly rather than only implied by the flag above.
        InOrder order = inOrder(briefRepository, briefAiClient);
        order.verify(briefRepository).save(any(CreatorBrief.class));
        order.verify(briefAiClient).extract(any(), any(), any());
        order.verify(briefRepository).save(any(CreatorBrief.class));

        // Labelled, not disguised.
        assertEquals(CreatorBrief.EXTRACTION_SOURCE_FALLBACK, response.extractionSource());
        assertEquals(BriefAnalysisResponse.DEGRADED_AI_UNAVAILABLE, response.degradedReason());
        assertEquals(BriefStatus.ANALYZED.name(), response.status());
        assertNotNull(response.extraction(), "the deterministic extractor produced a reading");
        assertTrue(
                response.summaryLines().get(0).startsWith(BriefFallbackExtractor.SUMMARY_PREFIX),
                "the creator is told in words that this was read without AI");
        // The deterministic reading is a real reading, not an empty shell.
        assertEquals(new BigDecimal("8000"), response.extraction().budgetInr());
        assertTrue(response.extraction().budgetStated());
        assertEquals(60, response.extraction().exclusivityDays());
    }

    @Test
    @DisplayName("a cap-exhausted creator gets degraded_reason=cap, not ai_unavailable")
    void paste_capIsDistinguishedFromAnOutage() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.cap());

        BriefAnalysisResponse response = service.paste(CREATOR_USER_ID, RAW_BRIEF);

        assertEquals(BriefAnalysisResponse.DEGRADED_CAP, response.degradedReason());
        assertEquals(CreatorBrief.EXTRACTION_SOURCE_FALLBACK, response.extractionSource());
    }

    @Test
    @DisplayName("a null client result is treated as an outage, never as a success")
    void paste_nullClientResultDegrades() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(null);

        BriefAnalysisResponse response = service.paste(CREATOR_USER_ID, RAW_BRIEF);

        assertEquals(CreatorBrief.EXTRACTION_SOURCE_FALLBACK, response.extractionSource());
        assertEquals(BriefAnalysisResponse.DEGRADED_AI_UNAVAILABLE, response.degradedReason());
    }

    /**
     * This test used to assert its own name away. It captured both saves, asserted they were the SAME
     * instance — trivially true, since the service mutates one entity — and then read {@code rawText}
     * off it, which is identical on both saves. Neither "status NEW" nor "no extraction" was checked,
     * and the tester's deliberate inversion of the two statements in {@code paste} left it green.
     *
     * <p>The state at each save is therefore SNAPSHOTTED as the repository sees it (see
     * {@link #savedStates}), because the entity is mutated in place afterwards and a post-hoc read of
     * it can only ever show the final row.
     */
    @Test
    @DisplayName("the first persisted row is status NEW with no extraction — the text is stored before it is read")
    void paste_firstSaveIsNewWithNoExtraction() {
        when(briefAiClient.extract(any(), any(), any()))
                .thenReturn(BriefResult.of(aiExtraction()));

        service.paste(CREATOR_USER_ID, RAW_BRIEF);

        assertEquals(2, savedStates.size(), "one raw-text write, then one snapshot write");

        SavedState first = savedStates.get(0);
        assertEquals(BriefStatus.NEW, first.status(), "the FIRST write is the untouched paste");
        assertNull(first.extractedJson(), "nothing has read the brief yet at the first write");
        assertNull(first.riskFlagsJson());
        assertNull(first.quoteJson());
        assertNull(first.extractionSource());
        assertEquals(RAW_BRIEF, first.rawText());

        SavedState last = savedStates.get(savedStates.size() - 1);
        assertEquals(BriefStatus.ANALYZED, last.status(), "and only the LAST write is analysed");
        assertNotNull(last.extractedJson());
    }

    /**
     * The durability half of the restructure, as far as a unit test can reach.
     *
     * <p>{@code paste} used to be one {@code @Transactional} method, so a throw from the quote step
     * rolled the already-"saved" raw text back with it: the creator lost a brief the class javadoc
     * promises survives an outage. A mocked repository cannot see that — it records the {@code save}
     * a real rollback would erase — which is exactly why the guarantee went unnoticed. What IS
     * assertable here is that the raw-text write happens before the throwing step and is not retried
     * or undone in code; the boundary itself is asserted structurally by
     * {@link #paste_rawTextCommitsOnItsOwnBoundaryOutsideTheAiCall()}.
     */
    @Test
    @DisplayName("a throw from the quote step leaves the raw-text write standing, unanalysed")
    void paste_quoteFailureLeavesTheRawTextSaved() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.of(aiExtraction()));
        when(rateQuoteService.quoteForExtraction(any(), any(), any()))
                .thenThrow(new IllegalStateException("rate card unavailable"));

        assertThrows(IllegalStateException.class, () -> service.paste(CREATOR_USER_ID, RAW_BRIEF));

        assertEquals(1, savedStates.size(), "the raw text was written, the snapshot never was");
        assertEquals(RAW_BRIEF, savedStates.get(0).rawText());
        assertEquals(BriefStatus.NEW, savedStates.get(0).status());
        assertNull(savedStates.get(0).extractedJson());
    }

    /**
     * The structural gate on the two things a Mockito test cannot observe: that the raw-text write has
     * its OWN transaction, and that the AI call is not inside one.
     *
     * <p>Both rest on {@code CreatorBriefWriter} being a DIFFERENT bean. Spring's
     * {@code @Transactional} is proxy-based, so a self-called or non-public method silently runs in
     * the caller's transaction whatever its annotation says — moving these saves back onto
     * {@code CreatorBriefService} would restore the original defect while looking annotated. Re-adding
     * {@code @Transactional} to {@code paste} would put the AI round trip back inside a pooled JDBC
     * connection for up to 20 seconds. Either regression turns this red.
     */
    @Test
    @DisplayName("paste holds no transaction, and the raw-text write commits on its own REQUIRES_NEW boundary")
    void paste_rawTextCommitsOnItsOwnBoundaryOutsideTheAiCall() throws Exception {
        assertNull(
                CreatorBriefService.class
                        .getMethod("paste", String.class, String.class)
                        .getAnnotation(Transactional.class),
                "paste must not be transactional — the AI call inside it would hold a pooled"
                        + " connection for the whole round trip");

        Transactional rawWrite =
                CreatorBriefWriter.class
                        .getMethod("saveRawPaste", String.class, String.class)
                        .getAnnotation(Transactional.class);
        assertNotNull(rawWrite, "the raw-text write is the durability promise and needs a boundary");
        assertEquals(
                Propagation.REQUIRES_NEW,
                rawWrite.propagation(),
                "REQUIRED would let a transactional caller absorb the commit the promise depends on");

        assertNotSame(
                CreatorBriefService.class,
                CreatorBriefWriter.class,
                "a separate bean is the only place the proxy actually applies");
    }

    // ------------------------------------------------------------------
    // ensurePlatformBrief
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ensurePlatformBrief is idempotent: an existing PLATFORM brief is returned without re-analysing")
    void ensurePlatformBrief_idempotent() {
        CreatorBrief existing =
                CreatorBrief.platform(BRIEF_ID, CREATOR_PROFILE_ID, COLLABORATION_ID, "already read");
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        COLLABORATION_ID, CREATOR_PROFILE_ID, BriefSource.PLATFORM))
                .thenReturn(Optional.of(existing));

        CreatorBrief result = service.ensurePlatformBrief(CREATOR_PROFILE_ID, COLLABORATION_ID);

        assertSame(existing, result);
        // Nothing recomputed: re-running would overwrite the frozen snapshot with today's rate model,
        // so a creator reopening the deal would see numbers she was never shown.
        verify(briefAiClient, never()).extract(any(), any(), any());
        verify(briefRepository, never()).save(any());
        verify(rateQuoteService, never()).quoteForExtraction(any(), any(), any());
    }

    @Test
    @DisplayName("ensurePlatformBrief builds the brief from the collaboration and campaign on first call")
    void ensurePlatformBrief_firstCallCreatesAndAnalyses() {
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        COLLABORATION_ID, CREATOR_PROFILE_ID, BriefSource.PLATFORM))
                .thenReturn(Optional.empty());
        Collaboration collaboration = platformCollaboration();
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        // Built BEFORE the when(...) call: platformCampaign() stubs a mock of its own, and doing
        // that inside the argument list of thenReturn() is what Mockito reports as
        // UnfinishedStubbing.
        Campaign campaign = platformCampaign();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.unavailable());

        CreatorBrief result = service.ensurePlatformBrief(CREATOR_PROFILE_ID, COLLABORATION_ID);

        assertEquals(BriefSource.PLATFORM, result.getSource());
        assertEquals(COLLABORATION_ID, result.getCollaborationId());
        assertEquals(BriefStatus.ANALYZED, result.getStatus());
        assertTrue(result.getRawText().contains("Serum launch"), "the campaign title is in the text");
        assertTrue(result.getRawText().contains("12000"), "the offer amount is in the text");
        assertTrue(
                result.getRawText().contains("45 days exclusivity"),
                "the structured deal terms are in the text");
    }

    @Test
    @DisplayName("ensurePlatformBrief 404s on an unknown collaboration rather than storing an empty brief")
    void ensurePlatformBrief_unknownCollaboration() {
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        COLLABORATION_ID, CREATOR_PROFILE_ID, BriefSource.PLATFORM))
                .thenReturn(Optional.empty());
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());

        ApiException e =
                assertThrows(
                        ApiException.class,
                        () -> service.ensurePlatformBrief(CREATOR_PROFILE_ID, COLLABORATION_ID));
        assertEquals("DEAL_NOT_FOUND", e.getCode());
        verify(briefRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // get / list / dismiss
    // ------------------------------------------------------------------

    @Test
    @DisplayName("get rebuilds from the frozen snapshot and does NOT recompute the quote or the flags")
    void get_readsTheSnapshot() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.of(aiExtraction()));
        service.paste(CREATOR_USER_ID, RAW_BRIEF);
        CreatorBrief stored = saved.get(saved.size() - 1);
        when(briefRepository.findByIdAndCreatorProfileId(stored.getId(), CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(stored));

        org.mockito.Mockito.clearInvocations(rateQuoteService, dealRiskService);
        BriefAnalysisResponse response = service.get(CREATOR_USER_ID, stored.getId());

        assertEquals("Glow Cosmetics", response.extraction().brandName());
        assertEquals(1, response.flags().size());
        assertNotNull(response.quote());
        verify(rateQuoteService, never()).quoteForExtraction(any(), any(), any());
        verify(dealRiskService, never()).evaluateExtraction(any(), any(), any(), any());
    }

    @Test
    @DisplayName("get on another creator's brief is a 404, not a 403 — an id must not be probeable")
    void get_notOwned() {
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        ApiException e =
                assertThrows(ApiException.class, () -> service.get(CREATOR_USER_ID, BRIEF_ID));
        assertEquals("BRIEF_NOT_FOUND", e.getCode());
    }

    @Test
    @DisplayName("list caps the limit and defaults it, so a caller cannot ask for the whole table")
    void list_capsLimit() {
        when(briefRepository.findByCreatorProfileIdOrderByCreatedAtDesc(eq(CREATOR_PROFILE_ID), any()))
                .thenReturn(List.of(CreatorBrief.paste(BRIEF_ID, CREATOR_PROFILE_ID, RAW_BRIEF)));

        List<BriefListItem> zero = service.list(CREATOR_USER_ID, 0);
        assertEquals(1, zero.size());
        assertEquals(BriefStatus.NEW.name(), zero.get(0).status());
        assertEquals(BriefSource.PASTED.name(), zero.get(0).source());

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        service.list(CREATOR_USER_ID, 10_000);
        verify(briefRepository, org.mockito.Mockito.atLeastOnce())
                .findByCreatorProfileIdOrderByCreatedAtDesc(eq(CREATOR_PROFILE_ID), pageable.capture());
        assertEquals(
                CreatorBriefService.MAX_LIST_LIMIT,
                pageable.getAllValues().get(pageable.getAllValues().size() - 1).getPageSize());
        assertEquals(
                CreatorBriefService.DEFAULT_LIST_LIMIT,
                pageable.getAllValues().get(0).getPageSize());
    }

    @Test
    @DisplayName("dismiss is idempotent — a second click writes nothing rather than 409ing")
    void dismiss_idempotent() {
        CreatorBrief brief = CreatorBrief.paste(BRIEF_ID, CREATOR_PROFILE_ID, RAW_BRIEF);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(brief));

        service.dismiss(CREATOR_USER_ID, BRIEF_ID);
        assertEquals(BriefStatus.DISMISSED, brief.getStatus());
        assertEquals(1, saved.size());

        service.dismiss(CREATOR_USER_ID, BRIEF_ID);
        assertEquals(1, saved.size(), "already dismissed — nothing more to write");
    }

    @Test
    @DisplayName("the pasted text is sanitised and capped before it is stored")
    void paste_sanitisesAndCaps() {
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.unavailable());
        String huge = "<b>Budget INR 9000</b> " + "x".repeat(20_000);

        service.paste(CREATOR_USER_ID, huge);

        String stored = saved.get(0).getRawText();
        assertEquals(CreatorBrief.MAX_RAW_TEXT_LENGTH, stored.length());
        assertFalse(stored.contains("<b>"), "a pasted email body routinely carries markup");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static PreferencesResponse prefs() {
        return new PreferencesResponse(
                new BigDecimal("6000"),
                new BigDecimal("3000"),
                new BigDecimal("3000"),
                "INR",
                List.of(),
                List.of(),
                0,
                "en-IN",
                "FRIENDLY",
                null,
                null,
                "Asia/Kolkata",
                List.of(),
                null,
                false,
                null,
                true,
                "v1",
                false,
                null,
                false,
                0,
                false);
    }

    private static RiskFlag riskFlag() {
        return new RiskFlag(
                "BELOW_FLOOR", "HIGH", "Below your floor", "detail", null, null, null, false);
    }

    private static PackageQuote quote() {
        return new PackageQuote(
                List.of(),
                List.of(),
                null,
                null,
                "INR 9,000",
                new BigDecimal("9000"),
                "INR 11,000",
                new BigDecimal("11000"),
                "INR 9,000",
                new BigDecimal("9000"),
                "INR 9,000",
                "INR 12,000",
                "INR",
                "50/50",
                2,
                "FORMULA",
                0,
                "HOLD",
                null,
                false,
                null);
    }

    private static BriefExtraction aiExtraction() {
        return new BriefExtraction(
                "Glow Cosmetics",
                "Vitamin C serum",
                "BEAUTY",
                List.of(new DeliverableLine("REEL", 1), new DeliverableLine("STORY_SET", 1)),
                new BigDecimal("8000"),
                true,
                false,
                null,
                "2026-10-05",
                null,
                false,
                List.of("ORGANIC"),
                60,
                "CATEGORY",
                List.of(),
                null,
                "50% advance",
                false,
                false,
                List.of(),
                null,
                false,
                List.of("Glow wants 1 reel + 1 story set", "Budget stated: 8,000", "Deadline 5 Oct"));
    }

    private static Collaboration platformCollaboration() {
        Collaboration collaboration =
                Collaboration.propose(
                        COLLABORATION_ID,
                        CAMPAIGN_ID,
                        CREATOR_USER_ID,
                        new BigDecimal("12000"),
                        "INR",
                        "Here is our offer");
        collaboration.setUsageRights("Organic only");
        collaboration.applyDealTerms(
                6, false, "ORGANIC", 45, com.influora.domain.enums.ExclusivityScope.CATEGORY, null, 2);
        return collaboration;
    }

    private static Campaign platformCampaign() {
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getTitle()).thenReturn("Serum launch");
        lenient().when(campaign.getDescription()).thenReturn("Vitamin C serum launch campaign");
        lenient().when(campaign.getRequirementsJson()).thenReturn(null);
        lenient().when(campaign.getBrandGuidelines()).thenReturn(null);
        return campaign;
    }
}
