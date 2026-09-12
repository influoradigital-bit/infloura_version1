package com.influora.service.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.ExclusivityScope;
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
import com.influora.service.risk.rules.BelowFloorRule;
import com.influora.service.risk.rules.BlockedBrandRule;
import com.influora.service.risk.rules.UsagePerpetualRule;
import com.influora.service.risk.rules.VagueDeliverablesRule;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.1, B4) — the {@code evaluateDeal} loader, which is the
 * half of the engine {@code DealRiskServiceTest} deliberately does not touch: turning a live
 * {@link Collaboration} into the extraction-shaped view the rules read.
 *
 * <p><b>The test that earns its place here is {@link #usageChannelsAreCommaSeparatedNotJson()}.</b>
 * SPEC.md &sect;5.1 states that {@code Collaboration.getUsageChannels()} returns a JSON string and
 * says to parse it with {@code JsonLists.stringListFromJson}. It does not — the column is
 * comma-separated (the entity javadoc, {@code DealService.applyDealTermsIfPresent}'s
 * {@code String.join(",", ...)} and {@code DealService.toDealTermsDto}'s {@code split(",")} all
 * agree). Following the spec there would not have thrown: {@code stringListFromJson} swallows the
 * parse failure and returns an empty list, so {@code USAGE_PERPETUAL}'s "all five channels" branch
 * would have been dead on every deal, silently, forever. This test fails if anyone "fixes" the
 * parser back to the spec's version.
 */
@ExtendWith(MockitoExtension.class)
class DealRiskServiceEvaluateDealTest {

    private static final String PROFILE_ID = "01CREATORPROFILE0000000000";
    private static final String USER_ID = "01CREATORUSER00000000000000";
    private static final String DEAL_ID = "01DEAL0000000000000000000";
    private static final String CAMPAIGN_ID = "01CAMPAIGN000000000000000";
    private static final String WORKSPACE_ID = "01WORKSPACE00000000000000";

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
    }

    @Test
    @DisplayName("usage_channels on a Collaboration is COMMA-separated, not JSON — all five still fire")
    void usageChannelsAreCommaSeparatedNotJson() {
        Collaboration collaboration = contractedDeal();
        collaboration.applyDealTerms(
                null,
                false,
                // exactly what DealService.applyDealTermsIfPresent writes to this column
                "ORGANIC,PAID_ADS,WHITELISTING,WEBSITE,OFFLINE",
                null,
                ExclusivityScope.NONE,
                null,
                2);
        stubDealLoad(collaboration, campaign(null, null));

        List<RiskFlag> flags = service.evaluateDeal(PROFILE_ID, DEAL_ID);

        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("a JSON parse of this column returns an empty list and this rule goes silent")
                .contains(UsagePerpetualRule.CODE);
    }

    @Test
    @DisplayName("exclusivity_brands on a Collaboration IS json, and the deal's own scope is honoured")
    void exclusivityBrandsAreJson() {
        Collaboration collaboration = contractedDeal();
        collaboration.applyDealTerms(
                null, false, "ORGANIC", 45, ExclusivityScope.NAMED_BRANDS, "[\"Rival Co\"]", 2);
        stubDealLoad(collaboration, campaign(null, null));

        List<RiskFlag> flags = service.evaluateDeal(PROFILE_ID, DEAL_ID);

        // The 45-day window is the deal's OWN exclusivity, so EXCLUSIVITY_LONG carries it. The
        // named brand list only matters to COMPETITOR_CONFLICT, which reads the creator's OTHER
        // deals — there are none here, so it stays quiet, and that is the correct answer.
        assertThat(flags).extracting(RiskFlag::code).contains("EXCLUSIVITY_LONG");
        assertThat(flags).extracting(RiskFlag::code).doesNotContain("COMPETITOR_CONFLICT");
    }

    @Test
    @DisplayName("the brand name comes from the campaign's end brand, so a block list still bites")
    void endBrandNameDrivesTheBlockList() {
        when(preferencesService.getByProfileId(PROFILE_ID))
                .thenReturn(prefs(List.of(), List.of("Glow Cosmetics"), null));
        stubDealLoadWithoutPrefs(contractedDeal(), campaign("Glow Cosmetics", "BEAUTY"));

        assertThat(service.evaluateDeal(PROFILE_ID, DEAL_ID))
                .extracting(RiskFlag::code)
                .contains(BlockedBrandRule.CODE);
    }

    @Test
    @DisplayName("another creator's deal id is a 404, not someone else's risk flags")
    void unknownDealIs404() {
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(preferencesService.getByProfileId(PROFILE_ID)).thenReturn(prefs(List.of(), List.of(), null));
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateDeal(PROFILE_ID, DEAL_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DEAL_NOT_FOUND");
    }

    @Test
    @DisplayName("an unknown creator profile is a 404 before anything else is loaded")
    void unknownProfileIs404() {
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluateDeal(PROFILE_ID, DEAL_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CREATOR_PROFILE_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // Wave 3 round 2 - the quote seam
    // ------------------------------------------------------------------

    /**
     * The headline of this wave: the quoted floor total is what {@code BELOW_FLOOR} compares
     * against, and it catches a deal the three-floor fallback lets through.
     *
     * <p><b>The fixture is an ordinary pre-contract negotiation</b>, which is the state a creator
     * actually asks Meera about - she is deciding whether to counter, so no contract exists and
     * therefore no {@code Deliverable} rows do either. The package lives on the proposal card:
     * three reels. Two numbers follow from that:
     *
     * <ul>
     *   <li><b>Fallback:</b> {@code viewOf} builds the extraction view from the (empty)
     *       {@code Deliverable} rows, so {@code Floors} sees no deliverables at all and prices the
     *       package as ONE reel - 12,000. The brand's 20,000 clears it and the flag stays silent.
     *   <li><b>Quoted:</b> {@code RateQuoteService} is handed the card's three
     *       {@code INSTAGRAM_REEL} slots and returns a floor total of 36,000
     *       ({@code RateQuoteServiceTest#quoteForRiskPricesTheProposalCardAndWritesNoAuditRow} pins
     *       that arithmetic against the real service). 20,000 is under it, so BELOW_FLOOR fires -
     *       CRITICAL, on a deal the creator was about to accept.
     * </ul>
     *
     * <p>Reverting the wiring - passing {@code null} for {@code RiskContext.quote} in
     * {@code evaluateDeal}, as every path did before this wave - turns this red and leaves
     * {@link #belowFloorStaysSilentWithoutAQuote()} green, which is the difference the seam makes
     * stated as two tests.
     */
    @Test
    @DisplayName("BELOW_FLOOR fires on the QUOTED floor of the proposal card, which the fallback misses")
    void belowFloorFiresOnTheQuotedFloorTotal() {
        givenPreContractNegotiationOfThreeReels();
        when(rateQuoteService.quoteForRisk(any(), any(), any(), anyList()))
                .thenReturn(quoteWithFloorTotal(new BigDecimal("36000")));

        List<RiskFlag> flags = service.evaluateDeal(PROFILE_ID, DEAL_ID);

        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("the quoted 36,000 floor is what the 20,000 offer must be measured against")
                .contains(BelowFloorRule.CODE);
        RiskFlag belowFloor =
                flags.stream()
                        .filter(f -> BelowFloorRule.CODE.equals(f.code()))
                        .findFirst()
                        .orElseThrow();
        assertThat(belowFloor.detail()).isEqualTo("Offer is 20,000 against your floor of 36,000.");

        // And the quote was priced from the package actually on the table, not from the empty
        // Deliverable table -- if this captor sees an empty list the floor above was a coincidence.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeliverableSlot>> slots = ArgumentCaptor.forClass(List.class);
        verify(rateQuoteService).quoteForRisk(any(), any(), any(), slots.capture());
        assertThat(slots.getValue())
                .as("pre-contract, the deliverables live on the proposal card and nowhere else")
                .containsExactly(new DeliverableSlot("INSTAGRAM_REEL", 3));
    }

    /** The same deal with no quote: the fallback prices one reel and the offer clears it. */
    @Test
    @DisplayName("without a quote the fallback prices the same deal as one reel and stays silent")
    void belowFloorStaysSilentWithoutAQuote() {
        givenPreContractNegotiationOfThreeReels();
        when(rateQuoteService.quoteForRisk(any(), any(), any(), anyList())).thenReturn(null);

        assertThat(service.evaluateDeal(PROFILE_ID, DEAL_ID))
                .extracting(RiskFlag::code)
                .as("12,000 fallback floor against a 20,000 offer -- nothing to flag, and that is the bug")
                .doesNotContain(BelowFloorRule.CODE);
    }

    /**
     * Pricing reaches five repositories and the benchmark estimator, and a creator with no metric
     * row, no history and no niche is an ordinary case rather than an error. None of that may cost
     * her the flags that need no quote at all.
     */
    @Test
    @DisplayName("a quote that throws degrades to the fallback -- the other rules still run")
    void aFailedQuoteDegradesRatherThanThrowing() {
        givenPreContractNegotiationOfThreeReels();
        when(rateQuoteService.quoteForRisk(any(), any(), any(), anyList()))
                .thenThrow(new IllegalStateException("no rate band, no metric, no niche"));

        List<RiskFlag> flags = service.evaluateDeal(PROFILE_ID, DEAL_ID);

        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("USAGE_PERPETUAL needs no quote and must survive a pricing failure")
                .contains(UsagePerpetualRule.CODE);
        assertThat(flags)
                .extracting(RiskFlag::code)
                .as("and the floor comparison falls back rather than firing on a number it does not have")
                .doesNotContain(BelowFloorRule.CODE);
    }

    // ------------------------------------------------------------------
    // VAGUE_DELIVERABLES is not evidence about a brief on the deal path
    // ------------------------------------------------------------------

    /**
     * The QA blocker: {@code VAGUE_DELIVERABLES} fired on essentially every deal in negotiation,
     * which is the exact population the risk panel serves.
     *
     * <p>{@code viewOf} builds the extraction view's deliverable list from the {@code Deliverable}
     * table, and those rows are materialised at contract time — so this fixture, an ordinary
     * pre-contract negotiation, has none. The rule read that emptiness as "no quantities" and told
     * the creator her brief does not say how many pieces she owes, while the proposal card in the
     * same fixture names three reels exactly. A flag that always fires carries no information and
     * teaches creators to dismiss the whole panel, so it degraded the thirteen rules that are sound.
     *
     * <p>Reverting the guard in {@code VagueDeliverablesRule} — dropping the
     * {@code !ctx.isDealTarget()} conjunct — turns this red and leaves every other test in this
     * class green, which is the whole change stated as one test.
     */
    @Test
    @DisplayName("VAGUE_DELIVERABLES stays quiet on a negotiation whose proposal names the package")
    void vagueDeliverablesDoesNotFireOnAPreContractNegotiation() {
        givenPreContractNegotiationOfThreeReels();
        when(rateQuoteService.quoteForRisk(any(), any(), any(), anyList()))
                .thenReturn(quoteWithFloorTotal(new BigDecimal("36000")));

        assertThat(service.evaluateDeal(PROFILE_ID, DEAL_ID))
                .extracting(RiskFlag::code)
                .as("no Deliverable rows means no contract yet, not a brief that named no count")
                .doesNotContain(VagueDeliverablesRule.CODE);
    }

    /**
     * The other half of the guard: it is scoped to the ONE trigger that reads absence as evidence,
     * not a kill switch for the rule on the deal path.
     *
     * <p>The extractor's own {@code vague_deliverables} verdict cannot reach this path at all —
     * {@code viewOf} hardcodes that field false, because a collaboration does not carry it — so
     * vague LANGUAGE is the only live trigger a deal has, and it has to keep working or the rule is
     * dead on half the engine. Here the brand says "a few" in as many words, which is evidence
     * whatever the target is.
     */
    @Test
    @DisplayName("vague language in a brand message still fires VAGUE_DELIVERABLES on a deal")
    void vagueDeliverablesStillFiresOnVagueLanguageOnTheDealPath() {
        givenNegotiationWhereTheBrandSays("We just need a few pieces, nothing heavy.");

        RiskFlag flag =
                service.evaluateDeal(PROFILE_ID, DEAL_ID).stream()
                        .filter(f -> VagueDeliverablesRule.CODE.equals(f.code()))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("the text trigger must survive the target guard"));

        assertThat(flag.data())
                .as("fired on the language, not on the absent Deliverable rows")
                .containsEntry("basis", "BRIEF_TEXT");
    }

    /**
     * The same shape as {@link #givenPreContractNegotiationOfThreeReels()} — deliberately its own
     * copy rather than a parameter on that one, because the two below-floor seam tests are pinned to
     * that fixture's exact numbers and stubs and must not move for this.
     */
    private void givenNegotiationWhereTheBrandSays(String brandMessage) {
        Collaboration collaboration =
                Collaboration.invite(DEAL_ID, CAMPAIGN_ID, USER_ID, "Hi, keen to work together", "INR");
        collaboration.transitionTo(CollaborationStatus.IN_NEGOTIATION);
        collaboration.updateAgreedRate(new BigDecimal("20000"));

        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(preferencesService.getByProfileId(PROFILE_ID))
                .thenReturn(
                        floors(new BigDecimal("12000"), new BigDecimal("5000"), new BigDecimal("4000")));
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null)));
        lenient()
                .when(workspaceRepository.findById(WORKSPACE_ID))
                .thenReturn(
                        Optional.of(Workspace.newBrand(WORKSPACE_ID, "Glow Labs", "glow", "BEAUTY", "1-10")));
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(
                        List.of(
                                proposalCard(),
                                DealMessage.create(
                                        "01BRANDMESSAGE000000000000",
                                        DEAL_ID,
                                        DealMessageKind.text,
                                        WORKSPACE_ID,
                                        DealSenderType.brand,
                                        brandMessage,
                                        null)));
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of(collaboration));
        lenient().when(campaignRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(workspaceRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(deliverableRepository.findByCollaborationIdIn(anyList())).thenReturn(List.of());
    }

    /**
     * A deal in negotiation: 20,000 on the table, three reels on the proposal card, no contract and
     * so no {@code Deliverable} rows. Floors are 12,000 reel / 5,000 story / 4,000 post.
     */
    private void givenPreContractNegotiationOfThreeReels() {
        Collaboration collaboration =
                Collaboration.invite(DEAL_ID, CAMPAIGN_ID, USER_ID, "Hi, keen to work together", "INR");
        collaboration.transitionTo(CollaborationStatus.IN_NEGOTIATION);
        collaboration.updateAgreedRate(new BigDecimal("20000"));
        // Perpetual usage so aFailedQuoteDegradesRatherThanThrowing has a quote-free rule to assert.
        collaboration.applyDealTerms(null, true, "ORGANIC", null, ExclusivityScope.NONE, null, 2);

        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(preferencesService.getByProfileId(PROFILE_ID))
                .thenReturn(
                        floors(new BigDecimal("12000"), new BigDecimal("5000"), new BigDecimal("4000")));
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(null, null)));
        lenient()
                .when(workspaceRepository.findById(WORKSPACE_ID))
                .thenReturn(
                        Optional.of(Workspace.newBrand(WORKSPACE_ID, "Glow Labs", "glow", "BEAUTY", "1-10")));
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of(proposalCard()));
        // The point of the fixture: no contract has been drafted, so nothing materialised here.
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of(collaboration));
        lenient().when(campaignRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(workspaceRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(deliverableRepository.findByCollaborationIdIn(anyList())).thenReturn(List.of());
    }

    /** Exactly what {@code DealService.persistProposalMessage} writes: platform type names. */
    private static DealMessage proposalCard() {
        return DealMessage.create(
                "01PROPOSAL0000000000000000",
                DEAL_ID,
                DealMessageKind.proposal,
                WORKSPACE_ID,
                DealSenderType.brand,
                "Here is our offer",
                "{\"deliverables\":[{\"type\":\"INSTAGRAM_REEL\",\"qty\":3}]}");
    }

    /**
     * Only {@code floor_total_value} matters to the two rules under test; every other money
     * component is left null so a rule that started reading one would fail loudly rather than on a
     * fixture number nobody chose.
     */
    private static PackageQuote quoteWithFloorTotal(BigDecimal floorTotal) {
        return new PackageQuote(
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                "36,000",
                floorTotal,
                null,
                null,
                "INR",
                null,
                2,
                null,
                0,
                null,
                null,
                false,
                null);
    }

    private static PreferencesResponse floors(
            BigDecimal reelFloor, BigDecimal storySetFloor, BigDecimal postFloor) {
        return new PreferencesResponse(
                reelFloor,
                storySetFloor,
                postFloor,
                "INR",
                List.of(),
                List.of(),
                0,
                "en-IN",
                null,
                null,
                null,
                null,
                List.of(),
                null,
                false,
                null,
                true,
                null,
                false,
                null,
                false,
                0,
                false);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void stubDealLoad(Collaboration collaboration, Campaign campaign) {
        when(preferencesService.getByProfileId(PROFILE_ID)).thenReturn(prefs(List.of(), List.of(), null));
        stubDealLoadWithoutPrefs(collaboration, campaign);
    }

    private void stubDealLoadWithoutPrefs(Collaboration collaboration, Campaign campaign) {
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, USER_ID))
                .thenReturn(Optional.of(collaboration));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        lenient()
                .when(workspaceRepository.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(Workspace.newBrand(WORKSPACE_ID, "Glow Labs", "glow", "BEAUTY", "1-10")));
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID)).thenReturn(List.of());
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID)).thenReturn(List.of());
        // activeDealsFor: this creator has no OTHER collaborations.
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of(collaboration));
        lenient().when(campaignRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(workspaceRepository.findAllById(anyList())).thenReturn(List.of());
        lenient().when(deliverableRepository.findByCollaborationIdIn(anyList())).thenReturn(List.of());
    }

    private static Collaboration contractedDeal() {
        Collaboration collaboration =
                Collaboration.invite(DEAL_ID, CAMPAIGN_ID, USER_ID, "Hi, keen to work together", "INR");
        collaboration.transitionTo(CollaborationStatus.CONTRACTED);
        collaboration.updateAgreedRate(new BigDecimal("20000"));
        return collaboration;
    }

    private static Campaign campaign(String endBrandName, String endBrandCategory) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Festive launch")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("15000"))
                .budgetMax(new BigDecimal("25000"))
                .currency("INR")
                .startDate(LocalDate.parse("2026-10-01"))
                .endDate(LocalDate.parse("2026-10-20"))
                .endBrandName(endBrandName)
                .endBrandCategory(endBrandCategory)
                .build();
    }

    private static PreferencesResponse prefs(
            List<String> excluded, List<String> blocked, Integer weeklyLimit) {
        return new PreferencesResponse(
                new BigDecimal("5000"),
                new BigDecimal("2000"),
                new BigDecimal("3000"),
                "INR",
                excluded,
                blocked,
                0,
                "en-IN",
                null,
                null,
                null,
                null,
                List.of(),
                weeklyLimit,
                false,
                null,
                true,
                null,
                false,
                null,
                false,
                0,
                false);
    }
}
