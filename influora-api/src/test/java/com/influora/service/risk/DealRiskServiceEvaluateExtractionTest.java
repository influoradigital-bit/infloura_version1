package com.influora.service.risk;

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
import com.influora.service.risk.rules.HideDisclosureRule;
import com.influora.service.risk.rules.OffPlatformPaymentRule;
import com.influora.service.risk.rules.UsagePerpetualRule;
import com.influora.service.risk.rules.VagueDeliverablesRule;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
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
 * K-2 HIGH (Kabir, KABIR-CONSENT-0917.md Q5; Priya RULINGS-U-0917.md round 3 &sect;1) — the loader
 * {@link DealRiskServiceTest} deliberately does not touch: {@code evaluateExtraction}, the
 * paste-and-read flow's entry point into the real rule engine.
 *
 * <p><b>Why this file exists.</b> Before this fix, {@code evaluateExtraction} built its {@link
 * RiskContext} with {@code text = null} (the rules' free-text input), so the regex halves of
 * {@code OFF_PLATFORM_PAYMENT}, {@code HIDE_DISCLOSURE}, {@code USAGE_PERPETUAL} and {@code
 * VAGUE_DELIVERABLES} — which all read {@link RiskContext#text()} via {@code RiskText.matches} —
 * were structurally dead on this path. Only the extractor's OWN boolean hint could fire them.
 * {@code CreatorBriefServiceTest} and {@code GetBriefExecutorTest} both mock {@code
 * DealRiskService} entirely, so neither ever exercised the real rules here — this file runs the
 * REAL service, unmocked, exactly as Priya required ("not only a mocked service with an argument
 * assertion").
 *
 * <p>Kabir's exact case: an AI extraction whose own hints are both {@code false} (a targeted
 * prompt, or simply a miss), with raw brief text that says the thing anyway. Every test below must
 * go RED if {@code text} is reverted to {@code null} — see the falsification note on each.
 */
@ExtendWith(MockitoExtension.class)
class DealRiskServiceEvaluateExtractionTest {

    private static final String PROFILE_ID = "01CREATORPROFILE0000000000";
    private static final String USER_ID = "01CREATORUSER00000000000000";

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
        // activeDealsFor: this creator has no OTHER collaborations. Every test below calls
        // evaluateExtraction with a null Collaboration, so nothing else in DealRiskService's
        // dependency graph is touched (no campaign/workspace/deliverable lookups).
        lenient().when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        lenient().when(rateQuoteService.quoteForRisk(any(), any(), any(), any())).thenReturn(null);
    }

    // ------------------------------------------------------------------
    // Kabir's exact case, and its silent-today control
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "K-2: OFF_PLATFORM_PAYMENT and HIDE_DISCLOSURE fire from raw text when the extractor's"
                    + " own hints are both false")
    void bothNonDismissibleFlagsFireFromRawTextWhenHintsAreFalse() {
        String rawText =
                "Hi! Loved your last reel. Please pay via UPI after posting, and please don't"
                        + " disclose this as a paid partnership — just keep it looking organic.";

        List<RiskFlag> flags =
                service.evaluateExtraction(
                        profile, prefs(), extractionWithBothHintsFalse(), null, rawText);

        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("both non-dismissible flags must fire from the TEXT alone")
                .contains(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
        RiskFlag offPlatform =
                flags.stream().filter(f -> OffPlatformPaymentRule.CODE.equals(f.code())).findFirst().orElseThrow();
        RiskFlag hideDisclosure =
                flags.stream().filter(f -> HideDisclosureRule.CODE.equals(f.code())).findFirst().orElseThrow();
        assertThat(offPlatform.data())
                .as("the basis must say BRIEF_TEXT, not STATED -- the extractor's hint was false")
                .containsEntry("basis", "BRIEF_TEXT");
        assertThat(hideDisclosure.data()).containsEntry("basis", "BRIEF_TEXT");
    }

    @Test
    @DisplayName(
            "Control: the SAME extraction with null text (today's bug) stays silent on both flags"
                    + " -- proves the test above is not vacuously true")
    void bothFlagsStaySilentWithNullTextAndFalseHints() {
        List<RiskFlag> flags =
                service.evaluateExtraction(
                        profile, prefs(), extractionWithBothHintsFalse(), null, null);

        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("this is exactly today's bug, restated as a passing control")
                .doesNotContain(OffPlatformPaymentRule.CODE, HideDisclosureRule.CODE);
    }

    // ------------------------------------------------------------------
    // The other two text-driven rules -- "falsify per rule"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("K-2: USAGE_PERPETUAL fires from raw text (\"in perpetuity\") when the hint is false")
    void usagePerpetualFiresFromRawTextWhenHintIsFalse() {
        List<RiskFlag> flags =
                service.evaluateExtraction(
                        profile,
                        prefs(),
                        extractionWithBothHintsFalse(),
                        null,
                        "We'd like to use this content in perpetuity across all channels.");

        assertThat(flags).extracting(RiskFlag::code).contains(UsagePerpetualRule.CODE);
    }

    @Test
    @DisplayName("K-2: VAGUE_DELIVERABLES fires from raw text (\"a few posts\") when the hint is false")
    void vagueDeliverablesFiresFromRawTextWhenHintIsFalse() {
        BriefExtraction extraction =
                extractionWith(false, false, false, List.of(new DeliverableLine("REEL", 1)));

        List<RiskFlag> flags =
                service.evaluateExtraction(
                        profile,
                        prefs(),
                        extraction,
                        null,
                        "Just need a few posts, keep it flexible on the exact count.");

        assertThat(flags).extracting(RiskFlag::code).contains(VagueDeliverablesRule.CODE);
    }

    // ------------------------------------------------------------------
    // lastBrandMessage must stay null -- PARTNERSHIP_ADS_REQUEST must not leak onto this path
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "PARTNERSHIP_ADS_REQUEST never fires through evaluateExtraction -- it is deal-only, and"
                    + " lastBrandMessage stays null on this path regardless of the text argument")
    void partnershipAdsRequestNeverFiresHere() {
        List<RiskFlag> flags =
                service.evaluateExtraction(
                        profile,
                        prefs(),
                        extractionWithBothHintsFalse(),
                        null,
                        "Run this as a whitelisted partnership ad through our ad account.");

        assertThat(flags).extracting(RiskFlag::code).doesNotContain("PARTNERSHIP_ADS_REQUEST");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static BriefExtraction extractionWithBothHintsFalse() {
        return extractionWith(false, false, false, List.of(new DeliverableLine("REEL", 1)));
    }

    private static BriefExtraction extractionWith(
            boolean offPlatformPaymentHint,
            boolean disclosureHiddenHint,
            boolean usagePerpetual,
            List<DeliverableLine> deliverables) {
        return new BriefExtraction(
                "Glow Cosmetics",
                "Vitamin C serum",
                "BEAUTY",
                deliverables,
                new BigDecimal("8000"),
                true,
                false,
                null,
                "2026-10-05",
                null,
                usagePerpetual,
                List.of("ORGANIC"),
                null,
                null,
                List.of(),
                null,
                null,
                offPlatformPaymentHint,
                disclosureHiddenHint,
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
}
