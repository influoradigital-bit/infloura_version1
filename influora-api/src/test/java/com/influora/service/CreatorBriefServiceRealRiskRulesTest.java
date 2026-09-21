package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.CreatorSuggestionAiProperties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.integration.ai.MeeraBriefAiClient;
import com.influora.integration.ai.MeeraBriefAiClient.BriefResult;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.AuditLogService;
import com.influora.service.brief.BriefFallbackExtractor;
import com.influora.service.brief.CreatorBriefWriter;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.service.risk.rules.HideDisclosureRule;
import com.influora.service.risk.rules.OffPlatformPaymentRule;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * K-2 HIGH (Kabir, KABIR-CONSENT-0917.md Q5; Priya RULINGS-U-0917.md round 3 &sect;1) —
 * end-to-end, ONE TEST PER CALLER PATH, with a REAL (unmocked) {@link DealRiskService} wired into
 * a REAL {@link CreatorBriefService}.
 *
 * <p><b>Why this file, separate from {@link CreatorBriefServiceTest}.</b> Every test in that file
 * mocks {@code DealRiskService} entirely, so none of them could ever have caught the null-text
 * bug — they assert what argument was PASSED, not what the real rules DO with it. Priya's K-2 pass
 * bar requires both: {@code CreatorBriefServiceTest}'s mocked tests now assert the fifth argument
 * equals the stored raw text; this file proves that argument actually produces the flag, on all
 * three callers that share {@code CreatorBriefService.analyse}'s one call site — {@link
 * CreatorBriefService#paste}, {@link CreatorBriefService#ensurePlatformBrief}, and the stale-NEW
 * branch of {@link CreatorBriefService#get} (F1's {@code readOrReanalyse}).
 */
class CreatorBriefServiceRealRiskRulesTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String BRIEF_ID = "01HBRIEF12345678901234";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890123";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN12345678901";

    private static final String TRIGGER_TEXT =
            "Please pay via UPI after posting, and please don't disclose this as a paid"
                    + " partnership — just keep it looking organic.";

    private CreatorBriefRepository briefRepository;
    private CreatorAgentPreferencesService preferencesService;
    private MeeraBriefAiClient briefAiClient;
    private RateQuoteService rateQuoteService;
    private CollaborationRepository collaborationRepository;
    private CampaignRepository campaignRepository;
    private DealMessageRepository dealMessageRepository;
    private CreatorProfileRepository creatorProfileRepository;

    // The real DealRiskService's OWN extra dependencies (not shared with CreatorBriefService).
    private WorkspaceRepository workspaceRepository;
    private DeliverableRepository deliverableRepository;
    private AuditLogService auditLogService;

    private CreatorBriefService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        briefRepository = mock(CreatorBriefRepository.class);
        preferencesService = mock(CreatorAgentPreferencesService.class);
        briefAiClient = mock(MeeraBriefAiClient.class);
        rateQuoteService = mock(RateQuoteService.class);
        collaborationRepository = mock(CollaborationRepository.class);
        campaignRepository = mock(CampaignRepository.class);
        dealMessageRepository = mock(DealMessageRepository.class);
        creatorProfileRepository = mock(CreatorProfileRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        deliverableRepository = mock(DeliverableRepository.class);
        auditLogService = mock(AuditLogService.class);

        DealRiskService realDealRiskService =
                new DealRiskService(
                        creatorProfileRepository,
                        preferencesService,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        dealMessageRepository,
                        deliverableRepository,
                        briefRepository,
                        auditLogService,
                        rateQuoteService);

        service =
                new CreatorBriefService(
                        briefRepository,
                        new CreatorBriefWriter(briefRepository),
                        preferencesService,
                        briefAiClient,
                        new BriefFallbackExtractor(),
                        realDealRiskService, // the REAL service, not a mock
                        rateQuoteService,
                        collaborationRepository,
                        campaignRepository,
                        dealMessageRepository,
                        creatorProfileRepository,
                        new ObjectMapper(),
                        new CreatorSuggestionAiProperties());

        profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(preferencesService.requireCreatorProfile(CREATOR_USER_ID)).thenReturn(profile);
        lenient().when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID)).thenReturn(prefs());
        lenient().when(preferencesService.getByProfileId(CREATOR_PROFILE_ID)).thenReturn(prefs());
        lenient()
                .when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(profile));
        lenient().when(rateQuoteService.quoteForExtraction(any(), any(), any())).thenReturn(quote());
        // activeDealsFor: this creator has no OTHER collaborations, on any of the three paths.
        lenient().when(collaborationRepository.findByCreatorId(any())).thenReturn(List.of());
        lenient()
                .when(briefRepository.save(any(CreatorBrief.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName(
            "K-2 end-to-end, PASTE path: OFF_PLATFORM_PAYMENT and HIDE_DISCLOSURE fire from the"
                    + " creator's raw pasted text, through the REAL DealRiskService")
    void paste_realRiskRulesFireFromRawText() {
        when(briefAiClient.extract(any(), any(), any()))
                .thenReturn(BriefResult.of(extractionWithBothHintsFalse()));

        BriefAnalysisResponse response = service.paste(CREATOR_USER_ID, TRIGGER_TEXT);

        assertThat(response.flags())
                .extracting(com.influora.web.dto.meera.CreatorToolDtos.RiskFlag::code)
                .as("the paste path's raw text must reach the real rules, not a mock")
                .contains(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
    }

    @Test
    @DisplayName(
            "K-2 end-to-end, ensurePlatformBrief path: the flags fire from the deal's composed"
                    + " text (campaign description), through the REAL DealRiskService")
    void ensurePlatformBrief_realRiskRulesFireFromComposedText() {
        when(briefRepository.findFirstByCollaborationIdAndCreatorProfileIdAndSource(
                        COLLABORATION_ID,
                        CREATOR_PROFILE_ID,
                        com.influora.domain.enums.BriefSource.PLATFORM))
                .thenReturn(Optional.empty());
        Collaboration collaboration =
                Collaboration.propose(
                        COLLABORATION_ID,
                        CAMPAIGN_ID,
                        CREATOR_USER_ID,
                        new BigDecimal("12000"),
                        "INR",
                        "Here is our offer");
        when(collaborationRepository.findByIdAndCreatorId(COLLABORATION_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getTitle()).thenReturn("Serum launch");
        lenient().when(campaign.getDescription()).thenReturn(TRIGGER_TEXT);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(briefAiClient.extract(any(), any(), any()))
                .thenReturn(BriefResult.of(extractionWithBothHintsFalse()));

        CreatorBrief result = service.ensurePlatformBrief(CREATOR_PROFILE_ID, COLLABORATION_ID);

        List<com.influora.web.dto.meera.CreatorToolDtos.RiskFlag> flags = readFlags(result);
        assertThat(flags)
                .extracting(com.influora.web.dto.meera.CreatorToolDtos.RiskFlag::code)
                .as("the platform deal's composed text must reach the real rules")
                .contains(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
    }

    @Test
    @DisplayName(
            "K-2 end-to-end, STALE re-analyse path: the flags fire on re-analysis of a stale NEW"
                    + " brief, through the REAL DealRiskService")
    void staleReanalyse_realRiskRulesFireFromRawText() {
        CreatorBrief stuck = CreatorBrief.paste(BRIEF_ID, CREATOR_PROFILE_ID, TRIGGER_TEXT);
        org.springframework.test.util.ReflectionTestUtils.setField(
                stuck, "createdAt", java.time.Instant.now().minusSeconds(31));
        when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(stuck));
        when(briefAiClient.extract(any(), any(), any()))
                .thenReturn(BriefResult.of(extractionWithBothHintsFalse()));

        BriefAnalysisResponse response = service.get(CREATOR_USER_ID, BRIEF_ID);

        assertThat(response.flags())
                .extracting(com.influora.web.dto.meera.CreatorToolDtos.RiskFlag::code)
                .as("the stale-NEW re-analysis must feed the stored raw text to the real rules")
                .contains(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Reads the flags back off the frozen snapshot the same way a real caller would. */
    private List<com.influora.web.dto.meera.CreatorToolDtos.RiskFlag> readFlags(CreatorBrief brief) {
        try {
            if (brief.getRiskFlagsJson() == null) {
                return List.of();
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(
                    brief.getRiskFlagsJson(),
                    mapper.getTypeFactory()
                            .constructCollectionType(
                                    List.class, com.influora.web.dto.meera.CreatorToolDtos.RiskFlag.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static BriefExtraction extractionWithBothHintsFalse() {
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
                false, // offPlatformPaymentHint -- FALSE, the fix must catch this from text alone
                false, // disclosureHiddenHint -- FALSE, same
                List.of(),
                null,
                false,
                List.of("Glow wants 1 reel"));
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
                "v2",
                false,
                null,
                false,
                0,
                false);
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
}
