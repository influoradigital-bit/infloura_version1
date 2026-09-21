package com.influora.service.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.DealRiskService;
import com.influora.service.risk.rules.HideDisclosureRule;
import com.influora.service.risk.rules.OffPlatformPaymentRule;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-1773 / K-2c (Priya, {@code RULINGS-U-0917.md} round 6, "New: F-1772" and its Ruling
 * "Acceptance"). The {@code CreatorBriefServiceRealRiskRulesTest} / {@code
 * DealRiskServiceEvaluateExtractionTest} shape, applied to the FALLBACK path specifically: a REAL
 * {@link BriefFallbackExtractor} produces the {@link BriefExtraction}, and a REAL {@link
 * DealRiskService} evaluates it, so this proves the whole path end to end rather than asserting
 * only that {@link BriefFallbackExtractor#extract} returns {@code false} hints in isolation.
 *
 * <p><b>Why this matters.</b> Before this fix, {@code BriefFallbackExtractor} set both risk hints
 * from its own patterns, which predated and bypassed rounds 4, 5 and 6 of the rule fixes entirely
 * — a bare {@code upi} flagged an ordinary payout-configuration sentence, and the fallback's own
 * hide-disclosure pattern flagged an ordinary "#ad" caption. Every test here must go RED if the
 * extractor's old patterns are restored (see each test's falsification note).
 */
@ExtendWith(MockitoExtension.class)
class BriefFallbackExtractorRealRiskRulesTest {

    private static final String PROFILE_ID = "01CREATORPROFILE0000000001";
    private static final String USER_ID = "01CREATORUSER00000000000001";

    /** {@code RiskFlagCorpusTest.TRIGGER_TEXT} / {@code CreatorBriefServiceRealRiskRulesTest.TRIGGER_TEXT}, verbatim. */
    private static final String TRIGGER_TEXT =
            "Please pay via UPI after posting, and please don't disclose this as a paid"
                    + " partnership — just keep it looking organic.";

    /** {@code nisha-blind-0917.tsv} OPP-N-02: an on-platform payout instruction, must NOT flag. */
    private static final String OPP_N_02 =
            "Please add your UPI ID in your Influora payout settings so the finance team can"
                    + " release the funds correctly.";

    /** {@code nisha-blind-0917.tsv} OPP-F-01: a real off-platform ask via ROUTE_PHRASES, must flag. */
    private static final String OPP_F_01 =
            "Hi, can we just pay you directly via UPI instead of routing it through the platform?"
                    + " Saves us both the commission.";

    /** {@code RiskFlagCorpusTest.KAB-HD-N-01}: an ordinary ad caption, must NOT flag. */
    private static final String KAB_HD_N_01 = "Caption: Loving my new TECNO #ad";

    /** {@code RiskFlagCorpusTest.KAB-HD-F-01}: a real hide-the-ad ask, must flag. */
    private static final String KAB_HD_F_01 = "Please don't use #ad on this one";

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorBriefRepository creatorBriefRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private RateQuoteService rateQuoteService;

    private final BriefFallbackExtractor extractor = new BriefFallbackExtractor();
    private DealRiskService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
                new DealRiskService(
                        creatorProfileRepository,
                        preferencesService,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        dealMessageRepository,
                        deliverableRepository,
                        creatorBriefRepository,
                        auditLogService,
                        rateQuoteService);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah");
        lenient().when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        lenient().when(rateQuoteService.quoteForRisk(any(), any(), any(), any())).thenReturn(null);
    }

    /** Evaluates {@code text} exactly as the FALLBACK path does: extract, then evaluate with the same raw text. */
    private List<RiskFlag> evaluateFallback(String text) {
        BriefExtraction extraction = extractor.extract(text);
        return service.evaluateExtraction(profile, prefs(), extraction, null, text);
    }

    @Test
    @DisplayName(
            "F-1773: an on-platform payout instruction (OPP-N-02) raises no OFF_PLATFORM_PAYMENT on"
                    + " the FALLBACK path -- falsify by restoring the extractor's own bare-'upi' pattern")
    void onPlatformPayoutInstructionDoesNotFlag() {
        assertThat(evaluateFallback(OPP_N_02))
                .extracting(RiskFlag::code)
                .doesNotContain(OffPlatformPaymentRule.CODE);
    }

    @Test
    @DisplayName(
            "F-1773: an ordinary ad caption (KAB-HD-N-01) raises no HIDE_DISCLOSURE on the FALLBACK"
                    + " path -- falsify by restoring the extractor's own DISCLOSURE_HIDDEN pattern")
    void ordinaryAdCaptionDoesNotFlag() {
        assertThat(evaluateFallback(KAB_HD_N_01))
                .extracting(RiskFlag::code)
                .doesNotContain(HideDisclosureRule.CODE);
    }

    @Test
    @DisplayName(
            "F-1773: a real off-platform ask (OPP-F-01) still raises OFF_PLATFORM_PAYMENT on the"
                    + " FALLBACK path, with basis=BRIEF_TEXT -- the rule's own text check, not the hint")
    void realOffPlatformAskStillFlagsFromText() {
        List<RiskFlag> flags = evaluateFallback(OPP_F_01);
        assertThat(flags).extracting(RiskFlag::code).contains(OffPlatformPaymentRule.CODE);
        RiskFlag flag =
                flags.stream().filter(f -> OffPlatformPaymentRule.CODE.equals(f.code())).findFirst().orElseThrow();
        assertThat(flag.data())
                .as("basis must be BRIEF_TEXT: the extractor's own hint is always false now")
                .containsEntry("basis", "BRIEF_TEXT");
    }

    @Test
    @DisplayName(
            "F-1773: a real hide-the-ad ask (KAB-HD-F-01) still raises HIDE_DISCLOSURE on the"
                    + " FALLBACK path, with basis=BRIEF_TEXT")
    void realHideDisclosureAskStillFlagsFromText() {
        List<RiskFlag> flags = evaluateFallback(KAB_HD_F_01);
        assertThat(flags).extracting(RiskFlag::code).contains(HideDisclosureRule.CODE);
        RiskFlag flag =
                flags.stream().filter(f -> HideDisclosureRule.CODE.equals(f.code())).findFirst().orElseThrow();
        assertThat(flag.data())
                .as("basis must be BRIEF_TEXT: the extractor's own hint is always false now")
                .containsEntry("basis", "BRIEF_TEXT");
    }

    @Test
    @DisplayName("F-1773: TRIGGER_TEXT still raises both non-dismissible flags on the FALLBACK path")
    void triggerTextFiresBothFlags() {
        assertThat(evaluateFallback(TRIGGER_TEXT))
                .extracting(RiskFlag::code)
                .contains(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
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
}
