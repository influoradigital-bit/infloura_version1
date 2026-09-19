package com.influora.service.meera.tool.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.CreatorSuggestionAiProperties;
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
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorBriefService;
import com.influora.service.brief.BriefFallbackExtractor;
import com.influora.service.brief.CreatorBriefWriter;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.GetBriefResult;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code get_brief}.
 *
 * <p><b>The executor runs over the REAL {@link CreatorBriefService} and the real
 * {@link CreatorBriefWriter}, with only the repositories, the AI client and the two pricing/risk
 * services mocked.</b> Every "another creator's X is a 404" test below would be worthless against a
 * mocked brief service: it would stub the 404 and then observe the 404 it supplied. Here the refusal
 * has to come out of the actual ownership-scoped lookups ({@code findByIdAndCreatorProfileId},
 * {@code findByIdAndCreatorId}), and each of those tests also stubs the UNSCOPED finder to hand the
 * row over — so reverting either lookup to a plain {@code findById} turns the test red rather than
 * leaving it green on an absent stub.
 */
class GetBriefExecutorTest {

    private static final String USER_ID = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String OTHER_USER_ID = "01HOTHERCREATORUSER1234";
    private static final String BRIEF_ID = "01HBRIEF0000000000000A";
    private static final String DEAL_ID = "01HDEAL00000000000000A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN0000000000A";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CreatorAgentPreferencesService preferencesService;
    private CreatorBriefRepository briefRepository;
    private MeeraBriefAiClient briefAiClient;
    private DealRiskService dealRiskService;
    private RateQuoteService rateQuoteService;
    private CollaborationRepository collaborationRepository;
    private CampaignRepository campaignRepository;
    private DealMessageRepository dealMessageRepository;
    private CreatorProfileRepository creatorProfileRepository;

    /** Every brief the repository was asked to save, as handed over. */
    private final List<CreatorBrief> saved = new ArrayList<>();

    private GetBriefExecutor executor;

    @BeforeEach
    void setUp() {
        preferencesService = mock(CreatorAgentPreferencesService.class);
        briefRepository = mock(CreatorBriefRepository.class);
        briefAiClient = mock(MeeraBriefAiClient.class);
        dealRiskService = mock(DealRiskService.class);
        rateQuoteService = mock(RateQuoteService.class);
        collaborationRepository = mock(CollaborationRepository.class);
        campaignRepository = mock(CampaignRepository.class);
        dealMessageRepository = mock(DealMessageRepository.class);
        creatorProfileRepository = mock(CreatorProfileRepository.class);

        CreatorBriefService briefService =
                new CreatorBriefService(
                        briefRepository,
                        new CreatorBriefWriter(briefRepository),
                        preferencesService,
                        briefAiClient,
                        new BriefFallbackExtractor(),
                        dealRiskService,
                        rateQuoteService,
                        collaborationRepository,
                        campaignRepository,
                        dealMessageRepository,
                        creatorProfileRepository,
                        objectMapper,
                        new CreatorSuggestionAiProperties());
        executor = new GetBriefExecutor(preferencesService, briefService, collaborationRepository);

        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah");
        lenient().when(preferencesService.requireCreatorProfile(USER_ID)).thenReturn(profile);
        lenient().when(preferencesService.getByProfileId(PROFILE_ID)).thenReturn(prefs());
        lenient().when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient()
                .when(dealRiskService.evaluateExtraction(any(), any(), any(), any(), any()))
                .thenReturn(List.of(riskFlag()));
        lenient().when(rateQuoteService.quoteForExtraction(any(), any(), any())).thenReturn(quote());
        lenient()
                .when(briefRepository.save(any(CreatorBrief.class)))
                .thenAnswer(
                        invocation -> {
                            CreatorBrief brief = invocation.getArgument(0);
                            saved.add(brief);
                            return brief;
                        });
    }

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "brief_id: the stored snapshot comes back whole -- extraction, flags, and the quote with"
                    + " her floor -- and nothing is re-analysed or re-priced")
    void briefIdReadsTheStoredSnapshot() throws Exception {
        CreatorBrief stored = analysedPastedBrief(BRIEF_ID, PROFILE_ID);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));

        GetBriefResult result = executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID));

        assertThat(result.briefId()).isEqualTo(BRIEF_ID);
        assertThat(result.source()).isEqualTo("PASTED");
        assertThat(result.status()).isEqualTo("ANALYZED");
        assertThat(result.dealId()).isNull();
        assertThat(result.extractionSource()).isEqualTo(CreatorBrief.EXTRACTION_SOURCE_AI);
        assertThat(result.extraction().brandName()).isEqualTo("Glow Cosmetics");
        assertThat(result.flags()).extracting(RiskFlag::code).containsExactly("BELOW_FLOOR");
        assertThat(result.quote().floorTotalValue()).isEqualByComparingTo("9000");
        assertThat(result.quote().anchorValue()).isEqualByComparingTo("11000");

        verifyNoInteractions(briefAiClient, rateQuoteService, dealRiskService);
        verify(briefRepository, never()).save(any());
        // A pasted brief names no deal, so there is no collaboration to check.
        verifyNoInteractions(collaborationRepository);
    }

    @Test
    @DisplayName(
            "deal_id, first read: the PLATFORM brief is built from HER deal, analysed once, and read"
                    + " back from its snapshot; a second read reuses it with no second AI call")
    void dealIdCreatesThenReusesThePlatformBrief() {
        Collaboration deal = collaborationFor(USER_ID, "12000", "Here is our offer");
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID)).thenReturn(Optional.of(deal));
        when(collaborationRepository.findById(DEAL_ID)).thenReturn(Optional.of(deal));
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getTitle()).thenReturn("Serum launch");
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.unavailable());
        // The ensure lookup and the ownership-scoped read both see what the writer committed.
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        DEAL_ID, PROFILE_ID, BriefSource.PLATFORM))
                .thenAnswer(invocation -> saved.stream().reduce((first, second) -> second));
        when(briefRepository.findByIdAndCreatorProfileId(any(), eq(PROFILE_ID)))
                .thenAnswer(
                        invocation ->
                                saved.stream()
                                        .filter(b -> b.getId().equals(invocation.getArgument(0)))
                                        .reduce((first, second) -> second));

        GetBriefResult first = executor.execute(USER_ID, Map.of("deal_id", DEAL_ID));

        assertThat(first.source()).isEqualTo("PLATFORM");
        assertThat(first.status()).isEqualTo("ANALYZED");
        assertThat(first.dealId()).isEqualTo(DEAL_ID);
        assertThat(first.briefId()).isNotBlank();
        assertThat(first.extractionSource()).isEqualTo(CreatorBrief.EXTRACTION_SOURCE_FALLBACK);
        assertThat(first.extraction()).isNotNull();
        assertThat(first.flags()).extracting(RiskFlag::code).containsExactly("BELOW_FLOOR");
        assertThat(first.quote().totalValue()).isEqualByComparingTo("9000");
        assertThat(saved.get(0).getRawText()).contains("Serum launch").contains("12000");
        verify(briefAiClient, times(1)).extract(eq(PROFILE_ID), any(), any());

        GetBriefResult second = executor.execute(USER_ID, Map.of("deal_id", DEAL_ID));

        assertThat(second.briefId()).isEqualTo(first.briefId());
        // Still exactly one extraction and one priced snapshot: re-running would overwrite the frozen
        // numbers she was shown with today's rate model.
        verify(briefAiClient, times(1)).extract(any(), any(), any());
        verify(rateQuoteService, times(1)).quoteForExtraction(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // F1 HIGH (Kavya, last-call review of Wave U item U-1) -- a NEW brief is never a successful
    // read. Both paths funnel through CreatorBriefService.get (see its javadoc), so each gets its
    // own pair of tests rather than relying on the shared plumbing being exercised once.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "deal_id, second call: a NEW platform brief still inside the analysis budget is"
                    + " refused BRIEF_STILL_READING, not handed back as a clean read")
    void dealId_secondCallOnAYoungNewBriefIsRefused() {
        Collaboration deal = collaborationFor(USER_ID, "12000", "Here is our offer");
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID)).thenReturn(Optional.of(deal));
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getTitle()).thenReturn("Serum launch");
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.unavailable());
        // The first analysis attempt dies AFTER the raw text commits -- the row is left NEW.
        when(rateQuoteService.quoteForExtraction(any(), any(), any()))
                .thenThrow(new IllegalStateException("rate card unavailable"));
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        DEAL_ID, PROFILE_ID, BriefSource.PLATFORM))
                .thenAnswer(invocation -> saved.stream().reduce((first, second) -> second));
        when(briefRepository.findByIdAndCreatorProfileId(any(), eq(PROFILE_ID)))
                .thenAnswer(
                        invocation ->
                                saved.stream()
                                        .filter(b -> b.getId().equals(invocation.getArgument(0)))
                                        .reduce((first, second) -> second));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", DEAL_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(BriefStatus.NEW);

        // Second call: ensurePlatformBrief's idempotent early-return hands back the SAME NEW row,
        // still young. Before the fix this returned as a plain SUCCESS with no extraction.
        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", DEAL_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", CreatorBriefService.BRIEF_STILL_READING_CODE)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        // Priya last-call UF-2 (PRIYA-LASTCALL-U1-K4-0917.md, bar item 1): the 409 alone does not
        // prove no AI call was made on the second attempt -- an implementation that re-ran
        // analyse() and THEN threw BRIEF_STILL_READING for some unrelated reason would satisfy the
        // assertion above too. Exactly one extract() call total (the FIRST attempt's, from the
        // first executor.execute above) proves the second call's young-NEW branch refused before
        // ever reaching the AI client.
        verify(briefAiClient, times(1)).extract(any(), any(), any());
    }

    @Test
    @DisplayName(
            "deal_id, second call once the budget has passed: the stale NEW platform brief is"
                    + " RE-ANALYSED, not handed back untouched")
    void dealId_staleNewBriefIsReanalysedNotReturnedUntouched() {
        Collaboration deal = collaborationFor(USER_ID, "12000", "Here is our offer");
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID)).thenReturn(Optional.of(deal));
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getTitle()).thenReturn("Serum launch");
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.unavailable());
        when(rateQuoteService.quoteForExtraction(any(), any(), any()))
                .thenThrow(new IllegalStateException("rate card unavailable"))
                .thenReturn(quote());
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        DEAL_ID, PROFILE_ID, BriefSource.PLATFORM))
                .thenAnswer(invocation -> saved.stream().reduce((first, second) -> second));
        when(briefRepository.findByIdAndCreatorProfileId(any(), eq(PROFILE_ID)))
                .thenAnswer(
                        invocation ->
                                saved.stream()
                                        .filter(b -> b.getId().equals(invocation.getArgument(0)))
                                        .reduce((first, second) -> second));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", DEAL_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(saved).hasSize(1);
        // Past the default 5+15+10=30s budget -- the first attempt is presumed dead, not in flight.
        ReflectionTestUtils.setField(saved.get(0), "createdAt", Instant.now().minusSeconds(31));

        GetBriefResult second = executor.execute(USER_ID, Map.of("deal_id", DEAL_ID));

        assertThat(second.status()).isEqualTo("ANALYZED");
        assertThat(second.quote().totalValue()).isEqualByComparingTo("9000");
        verify(briefAiClient, times(2)).extract(any(), any(), any());
        verify(rateQuoteService, times(2)).quoteForExtraction(any(), any(), any());
    }

    @Test
    @DisplayName(
            "brief_id: a NEW brief still inside the analysis budget is refused"
                    + " BRIEF_STILL_READING, not handed back as a clean read")
    void briefId_youngNewBriefIsRefused() {
        CreatorBrief stuck = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stuck));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", CreatorBriefService.BRIEF_STILL_READING_CODE)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verifyNoInteractions(briefAiClient, rateQuoteService, dealRiskService);
    }

    @Test
    @DisplayName(
            "brief_id: a stale NEW brief -- its paste-time analysis died -- is RE-ANALYSED on"
                    + " read, not handed back untouched")
    void briefId_staleNewBriefIsReanalysedNotReturnedUntouched() {
        CreatorBrief stuck = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        ReflectionTestUtils.setField(stuck, "createdAt", Instant.now().minusSeconds(31));
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stuck));
        when(briefAiClient.extract(any(), any(), any())).thenReturn(BriefResult.of(extraction()));

        GetBriefResult result = executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID));

        assertThat(result.status()).isEqualTo("ANALYZED");
        assertThat(result.extraction().brandName()).isEqualTo("Glow Cosmetics");
        assertThat(result.extractionSource()).isEqualTo(CreatorBrief.EXTRACTION_SOURCE_AI);
        verify(briefAiClient, times(1)).extract(eq(PROFILE_ID), any(), any());
        verify(briefRepository).save(stuck);
    }

    // ------------------------------------------------------------------
    // Addition A (Priya ruling RULINGS-U-0917.md §0) -- a null extraction or flags is refused
    // whatever the status, not only on NEW.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "Addition A: a brief DISMISSED while still NEW has null extraction and null flags --"
                    + " refused BRIEF_ANALYSIS_UNAVAILABLE, never read as a clean brief")
    void dismissedWhileNewIsRefusedNotReadAsClean() {
        CreatorBrief dismissed = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        dismissed.dismiss();
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(dismissed));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", GetBriefExecutor.BRIEF_ANALYSIS_UNAVAILABLE_CODE)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName(
            "Addition A: an ANALYZED brief whose risk_flags_json is null (a serialisation or"
                    + " storage failure) is refused, not read as \"no flags\"")
    void analyzedWithNullFlagsIsRefused() throws Exception {
        CreatorBrief stored = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        stored.applyAnalysis(
                "Glow Cosmetics",
                objectMapper.writeValueAsString(extraction()),
                null,
                objectMapper.writeValueAsString(quote()),
                CreatorBrief.EXTRACTION_SOURCE_AI);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", GetBriefExecutor.BRIEF_ANALYSIS_UNAVAILABLE_CODE)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    /**
     * Kavya U-1 re-review (KAVYA-U1-RECHECK-0917.md, C1 CRITICAL): the guard at {@link
     * GetBriefExecutor#execute} is {@code extraction() == null || flags() == null}, an OR of two
     * independent conditions. {@link #analyzedWithNullFlagsIsRefused()} covers null-flags with a
     * non-null extraction, and {@link #dismissedWhileNewIsRefusedNotReadAsClean()} covers both
     * null at once — neither exercises null-extraction-WITH-non-null-flags, so a mutation that
     * dropped the extraction half of the OR (leaving only {@code flags() == null}) passed all 16
     * tests that existed at the time. This is the missing mutation: a null {@code extracted_json}
     * (a parse or serialisation failure, {@code CreatorBriefService#toResponse}/{@code #writeJson})
     * alongside a REAL, non-null {@code risk_flags_json}.
     */
    @Test
    @DisplayName(
            "Addition A: an ANALYZED brief whose extracted_json is null while flags are non-null"
                    + " (a parse or serialisation failure) is refused, not read as a clean brief")
    void analyzedWithNullExtractionButNonNullFlagsIsRefused() throws Exception {
        CreatorBrief stored = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        stored.applyAnalysis(
                "Glow Cosmetics",
                null,
                objectMapper.writeValueAsString(List.of(riskFlag())),
                objectMapper.writeValueAsString(quote()),
                CreatorBrief.EXTRACTION_SOURCE_AI);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", GetBriefExecutor.BRIEF_ANALYSIS_UNAVAILABLE_CODE)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName(
            "Addition A: an ANALYZED brief with a real EMPTY flags list still reads -- [] is a"
                    + " valid \"no flags\", not a null to refuse")
    void analyzedWithEmptyFlagsListStillReads() throws Exception {
        CreatorBrief stored = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
        stored.applyAnalysis(
                "Glow Cosmetics",
                objectMapper.writeValueAsString(extraction()),
                objectMapper.writeValueAsString(List.of()),
                objectMapper.writeValueAsString(quote()),
                CreatorBrief.EXTRACTION_SOURCE_AI);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));

        GetBriefResult result = executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID));

        assertThat(result.flags()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Ownership -- every miss is a 404, never a 403
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "another creator's brief_id is 404 BRIEF_NOT_FOUND -- the unscoped finder would return it,"
                    + " the profile-scoped one does not")
    void anotherCreatorsBriefIdIs404() throws Exception {
        CreatorBrief someoneElses = analysedPastedBrief(BRIEF_ID, "01HOTHERPROFILE000000000");
        lenient().when(briefRepository.findById(BRIEF_ID)).thenReturn(Optional.of(someoneElses));
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "BRIEF_NOT_FOUND")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "another creator's deal_id is 404 DEAL_NOT_FOUND: no brief is saved, no AI call is made,"
                    + " and her deal's text is never read")
    void anotherCreatorsDealIdIs404() {
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        DEAL_ID, PROFILE_ID, BriefSource.PLATFORM))
                .thenReturn(Optional.empty());
        Collaboration someoneElses = collaborationFor(OTHER_USER_ID, "50000", "Private offer");
        lenient().when(collaborationRepository.findById(DEAL_ID)).thenReturn(Optional.of(someoneElses));
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", DEAL_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DEAL_NOT_FOUND")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verify(briefRepository, never()).save(any());
        verifyNoInteractions(briefAiClient, campaignRepository, dealMessageRepository);
    }

    @Test
    @DisplayName(
            "her OWN brief row that names ANOTHER creator's collaboration is 404 BRIEF_NOT_FOUND, not"
                    + " 403 -- the brief row alone is not proof the deal is hers")
    void ownBriefOnAnotherCreatorsCollaborationIs404() throws Exception {
        CreatorBrief stored = analysedPlatformBrief(BRIEF_ID, PROFILE_ID, DEAL_ID);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));
        when(collaborationRepository.findById(DEAL_ID))
                .thenReturn(Optional.of(collaborationFor(OTHER_USER_ID, "50000", "Private offer")));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "BRIEF_NOT_FOUND")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "a brief whose collaboration row was cleaned up still reads -- it is her own record, and a"
                    + " deleted deal has no other creator to leak to")
    void ownBriefWhoseCollaborationIsGoneStillReads() throws Exception {
        CreatorBrief stored = analysedPlatformBrief(BRIEF_ID, PROFILE_ID, DEAL_ID);
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));
        when(collaborationRepository.findById(DEAL_ID)).thenReturn(Optional.empty());

        GetBriefResult result = executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID));

        assertThat(result.briefId()).isEqualTo(BRIEF_ID);
        assertThat(result.dealId()).isEqualTo(DEAL_ID);
        assertThat(result.quote()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Input contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both ids is 400 AMBIGUOUS_BRIEF_TARGET, before any brief or deal is read")
    void bothIdsIsRefused() {
        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        USER_ID, Map.of("brief_id", BRIEF_ID, "deal_id", DEAL_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "AMBIGUOUS_BRIEF_TARGET")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(briefRepository, collaborationRepository, briefAiClient);
    }

    @Test
    @DisplayName(
            "neither id is 400 BRIEF_TARGET_REQUIRED -- and a blank id counts as absent, as does a"
                    + " key the schema does not declare")
    void neitherIdIsRefused() {
        for (Map<String, Object> input :
                List.<Map<String, Object>>of(
                        Map.of(),
                        Map.of("brief_id", "   "),
                        Map.of("deal_id", ""),
                        Map.of("briefId", BRIEF_ID))) {
            assertThatThrownBy(() -> executor.execute(USER_ID, input))
                    .as("input %s", input)
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "BRIEF_TARGET_REQUIRED")
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }

        verifyNoInteractions(briefRepository, collaborationRepository, briefAiClient);
    }

    /**
     * See the class javadoc on {@link GetBriefExecutor}: an outer transaction would pin a pooled
     * connection across {@code ensurePlatformBrief}'s AI round trip, and its REPEATABLE READ snapshot
     * would hide the brief that call commits. The sibling executors are transactional; this one must
     * not become so by copying them.
     */
    @Test
    @DisplayName(
            "execute holds no transaction -- ensurePlatformBrief's AI round trip must not run inside a"
                    + " pooled connection")
    void executeIsNotTransactional() throws Exception {
        assertThat(GetBriefExecutor.class.getAnnotation(Transactional.class)).isNull();
        assertThat(
                        GetBriefExecutor.class
                                .getMethod("execute", String.class, Map.class)
                                .getAnnotation(Transactional.class))
                .isNull();
        // And the thing it relies on instead is still there.
        assertThat(
                        CreatorBriefService.class
                                .getMethod("ensurePlatformBrief", String.class, String.class)
                                .getAnnotation(Transactional.class))
                .isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private CreatorBrief analysedPastedBrief(String id, String profileId) throws Exception {
        CreatorBrief brief = CreatorBrief.paste(id, profileId, "Glow wants a reel for 8000");
        applySnapshot(brief);
        return brief;
    }

    private CreatorBrief analysedPlatformBrief(String id, String profileId, String collaborationId)
            throws Exception {
        CreatorBrief brief = CreatorBrief.platform(id, profileId, collaborationId, "Campaign: Serum");
        applySnapshot(brief);
        return brief;
    }

    private void applySnapshot(CreatorBrief brief) throws Exception {
        brief.applyAnalysis(
                "Glow Cosmetics",
                objectMapper.writeValueAsString(extraction()),
                objectMapper.writeValueAsString(List.of(riskFlag())),
                objectMapper.writeValueAsString(quote()),
                CreatorBrief.EXTRACTION_SOURCE_AI);
    }

    private static Collaboration collaborationFor(String creatorUserId, String amount, String message) {
        return Collaboration.propose(
                DEAL_ID, CAMPAIGN_ID, creatorUserId, new BigDecimal(amount), "INR", message);
    }

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
                "BELOW_FLOOR", "CRITICAL", "Below your floor", "detail", null, null, null, false);
    }

    private static PackageQuote quote() {
        return new PackageQuote(
                List.of(),
                List.of(),
                null,
                null,
                "9,000",
                new BigDecimal("9000"),
                "11,000",
                new BigDecimal("11000"),
                "9,000",
                new BigDecimal("9000"),
                "9,000",
                "12,000",
                "INR",
                "50/50",
                2,
                "benchmark, not market data",
                0,
                null,
                null,
                false,
                null);
    }

    private static BriefExtraction extraction() {
        return new BriefExtraction(
                "Glow Cosmetics",
                "Vitamin C serum",
                "BEAUTY",
                List.of(new DeliverableLine("REEL", 1)),
                new BigDecimal("8000"),
                true,
                false,
                null,
                "2026-10-05",
                null,
                false,
                List.of("ORGANIC"),
                null,
                null,
                List.of(),
                null,
                null,
                false,
                false,
                List.of(),
                null,
                false,
                List.of("Glow wants 1 reel", "Budget stated: 8,000"));
    }
}
