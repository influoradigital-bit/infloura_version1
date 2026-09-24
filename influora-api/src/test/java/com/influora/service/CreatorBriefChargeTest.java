package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.BriefSource;
import com.influora.domain.enums.ChargeKind;
import com.influora.integration.ai.MeeraBriefAiClient;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brief.BriefFallbackExtractor;
import com.influora.service.brief.CreatorBriefWriter;
import com.influora.service.credits.ChargeResult;
import com.influora.service.credits.CreatorCreditService;
import com.influora.service.credits.ReleaseScope;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.CreatorBriefController;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.PasteBriefRequest;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md A21/A22) — {@link CreatorBriefService#paste}'s 3-credit BRIEF
 * charge: every throw after a successful charge releases it (A21), and a fallback extraction
 * (never actually read by the AI) refunds it too (A22, already covered in {@code
 * CreatorBriefServiceTest#paste_fallback*}).
 *
 * <p>F-15/F-23 regression coverage: {@code saveRawPaste} used to run OUTSIDE the release-on-throw
 * try/catch, so a throw from it (a DB blip — {@code saveRawPaste} is its own committed
 * transaction) left the charge stuck with no release. {@link #paste_saveRawPasteThrows_releases()}
 * pins that this is now covered.
 */
class CreatorBriefChargeTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String RAW_BRIEF = "Hi! Glow Cosmetics here. 1 reel, budget INR 8000.";

    private CreatorBriefRepository briefRepository;
    private CreatorAgentPreferencesService preferencesService;
    private DealRiskService dealRiskService;
    private CreatorCreditService creatorCreditService;
    private CreatorBriefService service;

    @BeforeEach
    void setUp() {
        briefRepository = mock(CreatorBriefRepository.class);
        preferencesService = mock(CreatorAgentPreferencesService.class);
        MeeraBriefAiClient briefAiClient = mock(MeeraBriefAiClient.class);
        dealRiskService = mock(DealRiskService.class);
        RateQuoteService rateQuoteService = mock(RateQuoteService.class);
        CollaborationRepository collaborationRepository = mock(CollaborationRepository.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        DealMessageRepository dealMessageRepository = mock(DealMessageRepository.class);
        CreatorProfileRepository creatorProfileRepository = mock(CreatorProfileRepository.class);
        creatorCreditService = mock(CreatorCreditService.class);

        service =
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
                        new ObjectMapper(),
                        new CreatorSuggestionAiProperties(),
                        creatorCreditService);

        CreatorProfile profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(preferencesService.requireCreatorProfile(CREATOR_USER_ID)).thenReturn(profile);
        lenient().when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID)).thenReturn(prefs());
        lenient()
                .when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));

        // A charged (not disabled/refused) outcome for every test in this class.
        lenient()
                .when(creatorCreditService.charge(any(), any(), any()))
                .thenReturn(ChargeResult.charged(ChargeKind.BRIEF, 3, 12, 3));
    }

    @Test
    @DisplayName(
            "F-15/F-23: saveRawPaste throwing (its own committed transaction failing) still"
                    + " releases the 3-credit BRIEF charge, not just a throw from the AI call")
    void paste_saveRawPasteThrows_releases() {
        RuntimeException dbBlip = new RuntimeException("lock wait timeout");
        when(briefRepository.save(any(CreatorBrief.class))).thenThrow(dbBlip);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> service.paste(CREATOR_USER_ID, RAW_BRIEF));

        org.junit.jupiter.api.Assertions.assertSame(dbBlip, thrown);
        verify(creatorCreditService)
                .release(org.mockito.ArgumentMatchers.eq(CREATOR_USER_ID), any(), org.mockito.ArgumentMatchers.eq(ReleaseScope.BRIEF));
    }

    @Test
    @DisplayName("A21: the AI/risk pipeline throwing after a successful charge releases it (regression baseline)")
    void paste_analyseThrows_releases() {
        when(briefRepository.save(any(CreatorBrief.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(dealRiskService.evaluateExtraction(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("risk rules unavailable"));

        assertThrows(IllegalStateException.class, () -> service.paste(CREATOR_USER_ID, RAW_BRIEF));

        verify(creatorCreditService)
                .release(
                        org.mockito.ArgumentMatchers.eq(CREATOR_USER_ID),
                        any(),
                        org.mockito.ArgumentMatchers.eq(ReleaseScope.BRIEF));
    }

    // ------------------------------------------------------------------
    // A21
    // ------------------------------------------------------------------

    /** A fresh, independent {@link CreatorBriefService} + its mocks, so each sub-case below has its own interaction history. */
    private record Harness(
            CreatorBriefRepository briefRepository,
            DealRiskService dealRiskService,
            CreatorCreditService creatorCreditService,
            CreatorBriefService service) {}

    private Harness freshHarness() {
        CreatorBriefRepository briefRepo = mock(CreatorBriefRepository.class);
        CreatorAgentPreferencesService prefsSvc = mock(CreatorAgentPreferencesService.class);
        MeeraBriefAiClient aiClient = mock(MeeraBriefAiClient.class);
        DealRiskService riskSvc = mock(DealRiskService.class);
        RateQuoteService rateSvc = mock(RateQuoteService.class);
        CollaborationRepository collabRepo = mock(CollaborationRepository.class);
        CampaignRepository campaignRepo = mock(CampaignRepository.class);
        DealMessageRepository dealMessageRepo = mock(DealMessageRepository.class);
        CreatorProfileRepository profileRepo = mock(CreatorProfileRepository.class);
        CreatorCreditService creditSvc = mock(CreatorCreditService.class);

        CreatorBriefService svc =
                new CreatorBriefService(
                        briefRepo,
                        new CreatorBriefWriter(briefRepo),
                        prefsSvc,
                        aiClient,
                        new BriefFallbackExtractor(),
                        riskSvc,
                        rateSvc,
                        collabRepo,
                        campaignRepo,
                        dealMessageRepo,
                        profileRepo,
                        new ObjectMapper(),
                        new CreatorSuggestionAiProperties(),
                        creditSvc);

        CreatorProfile profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(prefsSvc.requireCreatorProfile(CREATOR_USER_ID)).thenReturn(profile);
        lenient().when(prefsSvc.getOrCreatePreferences(CREATOR_USER_ID)).thenReturn(prefs());
        lenient().when(profileRepo.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(profile));

        return new Harness(briefRepo, riskSvc, creditSvc, svc);
    }

    @Test
    @DisplayName(
            "A21: charges 3 up front; a refused (402/429) charge saves NO creator_briefs row; the"
                    + " AI/risk pipeline throwing after a charge refunds it (net 0); a FALLBACK"
                    + " extraction also refunds it (net 0); readOrReanalyse and ensurePlatformBrief"
                    + " never charge or release")
    void chargesThreeRefundsOnFailureNeverOnRead() {
        // --- A refused charge (DAILY_CAP) saves NO row and never releases. ---
        Harness refusedCase = freshHarness();
        when(refusedCase.creatorCreditService().charge(eq(CREATOR_USER_ID), eq(ChargeKind.BRIEF), any()))
                .thenReturn(ChargeResult.dailyCap(ChargeKind.BRIEF, 3, 27, 30));

        ApiException refused =
                assertThrows(ApiException.class, () -> refusedCase.service().paste(CREATOR_USER_ID, RAW_BRIEF));
        assertEquals("CREATOR_DAILY_CAP_REACHED", refused.getCode());
        verify(refusedCase.briefRepository(), never()).save(any());
        verify(refusedCase.creatorCreditService(), never()).release(any(), any(), any());

        // --- A charged paste whose risk/AI pipeline throws AFTER the charge refunds it (net 0). ---
        Harness throwCase = freshHarness();
        when(throwCase.creatorCreditService().charge(eq(CREATOR_USER_ID), eq(ChargeKind.BRIEF), any()))
                .thenReturn(ChargeResult.charged(ChargeKind.BRIEF, 3, 9, 12));
        when(throwCase.briefRepository().save(any(CreatorBrief.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(throwCase.dealRiskService().evaluateExtraction(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("risk rules unavailable"));

        assertThrows(
                IllegalStateException.class, () -> throwCase.service().paste(CREATOR_USER_ID, RAW_BRIEF));
        verify(throwCase.creatorCreditService()).release(eq(CREATOR_USER_ID), any(), eq(ReleaseScope.BRIEF));

        // --- A charged paste that degrades to FALLBACK (the briefAiClient mock is left unstubbed,
        // so extract() returns null and analyse() falls back) also refunds it (net 0). ---
        Harness fallbackCase = freshHarness();
        when(fallbackCase.creatorCreditService().charge(eq(CREATOR_USER_ID), eq(ChargeKind.BRIEF), any()))
                .thenReturn(ChargeResult.charged(ChargeKind.BRIEF, 3, 9, 15));
        when(fallbackCase.briefRepository().save(any(CreatorBrief.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BriefAnalysisResponse fallbackResponse = fallbackCase.service().paste(CREATOR_USER_ID, RAW_BRIEF);
        assertEquals(CreatorBrief.EXTRACTION_SOURCE_FALLBACK, fallbackResponse.extractionSource());
        verify(fallbackCase.creatorCreditService())
                .release(eq(CREATOR_USER_ID), any(), eq(ReleaseScope.BRIEF));
        verify(fallbackCase.creatorCreditService()).charge(eq(CREATOR_USER_ID), eq(ChargeKind.BRIEF), any());

        // --- readOrReanalyse (get) and ensurePlatformBrief never charge or release, even when the
        // stored brief is already ANALYZED (the ordinary case for both). ---
        Harness readCase = freshHarness();
        CreatorBrief alreadyAnalyzed = CreatorBrief.paste("01HBRIEFALREADYREAD01", CREATOR_PROFILE_ID, RAW_BRIEF);
        alreadyAnalyzed.applyAnalysis("Glow Cosmetics", "{}", "[]", "{}", CreatorBrief.EXTRACTION_SOURCE_AI);
        when(readCase.briefRepository().findByIdAndCreatorProfileId("01HBRIEFALREADYREAD01", CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(alreadyAnalyzed));
        when(readCase.briefRepository()
                        .findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                                "01HCOLLAB0000000000001", CREATOR_PROFILE_ID, BriefSource.PLATFORM))
                .thenReturn(Optional.of(alreadyAnalyzed));

        readCase.service().get(CREATOR_USER_ID, "01HBRIEFALREADYREAD01");
        readCase.service().ensurePlatformBrief(CREATOR_PROFILE_ID, "01HCOLLAB0000000000001");

        verify(readCase.creatorCreditService(), never()).charge(any(), any(), any());
        verify(readCase.creatorCreditService(), never()).release(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // A22
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A22: the same Idempotency-Key posted twice charges the 3-credit BRIEF cost exactly once"
                    + " (the replay is rejected with 409 BEFORE CreatorBriefService.paste ever runs a"
                    + " second time); a 65-character key is rejected with 400 before any charge")
    void pasteIdempotentAndKeyBounded() {
        CreatorContextService creatorContextMock = mock(CreatorContextService.class);
        MeeraCreatorFeatureProperties featureProperties = mock(MeeraCreatorFeatureProperties.class);
        CreatorCreditProperties creditProperties = mock(CreatorCreditProperties.class);
        IdempotencyService idempotencyServiceMock = mock(IdempotencyService.class);
        CreatorBriefService mockBriefService = mock(CreatorBriefService.class);
        CreatorProfile profile = mock(CreatorProfile.class);
        AuthPrincipal principal = mock(AuthPrincipal.class);

        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
        lenient().when(creditProperties.isEnabled()).thenReturn(true);
        lenient().when(creatorContextMock.requireCreatorProfile(principal)).thenReturn(profile);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);

        CreatorBriefController controller =
                new CreatorBriefController(
                        mockBriefService,
                        creatorContextMock,
                        preferencesService,
                        featureProperties,
                        creditProperties,
                        idempotencyServiceMock);

        PasteBriefRequest body = new PasteBriefRequest(RAW_BRIEF);
        BriefAnalysisResponse chargedResponse =
                new BriefAnalysisResponse(
                        "01HBRIEFIDEMPOTENT001",
                        "PASTED",
                        "ANALYZED",
                        null,
                        null,
                        null,
                        null,
                        CreatorBrief.EXTRACTION_SOURCE_AI,
                        null,
                        java.util.List.of(),
                        null);
        when(mockBriefService.paste(CREATOR_USER_ID, RAW_BRIEF)).thenReturn(chargedResponse);

        String key = "idem-key-same-paste";
        AtomicInteger executeOnceCalls = new AtomicInteger();
        when(idempotencyServiceMock.executeOnce(eq(key), eq(CREATOR_USER_ID), any(), any()))
                .thenAnswer(
                        invocation -> {
                            // The FIRST call actually runs the guarded action (the real
                            // IdempotencyService's own contract — proven elsewhere; simulated here so
                            // this test can prove the CONTROLLER wires it correctly). Every call after
                            // that is a replay of an already-COMPLETED key, which the real
                            // IdempotencyService rejects with AlreadyCompletedException rather than
                            // silently re-running (or re-charging) the action.
                            if (executeOnceCalls.incrementAndGet() == 1) {
                                @SuppressWarnings("unchecked")
                                Supplier<BriefAnalysisResponse> action =
                                        (Supplier<BriefAnalysisResponse>) invocation.getArgument(3);
                                return action.get();
                            }
                            throw new IdempotencyService.AlreadyCompletedException(key);
                        });

        controller.paste(principal, key, body);
        ApiException replay =
                assertThrows(ApiException.class, () -> controller.paste(principal, key, body));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", replay.getCode());
        assertEquals(HttpStatus.CONFLICT, replay.getStatus());
        // A22: "3 credits once" -- CreatorBriefService.paste (which does the actual charging) must
        // have run exactly once across BOTH POSTs of the same key, not once per POST.
        verify(mockBriefService, times(1)).paste(CREATOR_USER_ID, RAW_BRIEF);

        // A65-character key is rejected before the request ever reaches the idempotency layer or
        // CreatorBriefService — no additional charge.
        String tooLongKey = "k".repeat(65);
        ApiException tooLong =
                assertThrows(ApiException.class, () -> controller.paste(principal, tooLongKey, body));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", tooLong.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatus());
        verify(mockBriefService, times(1)).paste(CREATOR_USER_ID, RAW_BRIEF);
    }

    /** Mirrors {@code CreatorBriefServiceTest#prefs()} — kept in sync deliberately, not shared,
     * so this file has no compile-time dependency on that test class's internals. */
    private static PreferencesResponse prefs() {
        return new PreferencesResponse(
                new java.math.BigDecimal("6000"),
                new java.math.BigDecimal("3000"),
                new java.math.BigDecimal("3000"),
                "INR",
                java.util.List.of(),
                java.util.List.of(),
                0,
                "en-IN",
                "FRIENDLY",
                null,
                null,
                "Asia/Kolkata",
                java.util.List.of(),
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
}
