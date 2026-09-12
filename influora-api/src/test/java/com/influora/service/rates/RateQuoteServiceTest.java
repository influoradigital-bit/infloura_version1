package com.influora.service.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.OfferEvent;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.AddOnLine;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.QuoteLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.3 as corrected by &sect;14.1, B0-31).
 *
 * <p><b>The arithmetic here is the real thing, not a mocked stand-in.</b>
 * {@link RateEstimationService}, {@link RateAddOns} and {@link RateTierProperties} are all real
 * instances, so the figures below recompute end-to-end from the compiled tier constants. The base
 * fixture is &sect;14.1's own worked example — a NANO creator, 3,000 followers, 2.4% engagement,
 * BEAUTY, no quality score — whose estimate is 1,250/6,250 and whose midpoint unit is therefore
 * 3,750. Every expected number in this class traces back to that.
 *
 * <p>Mockito, not {@code @SpringBootTest}: this codebase has no full-context unit test anywhere.
 */
@ExtendWith(MockitoExtension.class)
class RateQuoteServiceTest {

    private static final String PROFILE_ID = "01HWPROFILERATEQUOTE001";
    private static final String USER_ID = "01HWUSERRATEQUOTE0000001";

    /** SPEC.md 14.1 worked example: NANO base 1,000/5,000 x engagement 1.0 x BEAUTY 1.25. */
    private static final BigDecimal BENCHMARK_MIN = new BigDecimal("1250");

    private static final BigDecimal BENCHMARK_MAX = new BigDecimal("6250");

    /** 1,250 + 0.50 x (6,250 - 1,250) = 3,750 — the MIDPOINT (14.1.a), not the 0.65 percentile. */
    private static final BigDecimal BENCHMARK_UNIT = new BigDecimal("3750");

    /**
     * The complete, closed list of keys {@code RATE_QUOTE_ISSUED} may carry. Declared HERE, in the
     * test, and never read from the service — a test that imported the service's own list would
     * pass no matter what the service added.
     */
    private static final List<String> AUDIT_DETAIL_ALLOW_LIST =
            List.of(
                    "tier",
                    "provenance",
                    "sample",
                    "total",
                    "anchor",
                    "currency",
                    "deliverable_count",
                    "context");

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private DealOfferHistoryRepository dealOfferHistoryRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private QualityScoreService qualityScoreService;
    @Mock private AuditLogService auditLogService;

    private RateQuoteService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
                new RateQuoteService(
                        preferencesService,
                        collaborationRepository,
                        dealMessageRepository,
                        dealOfferHistoryRepository,
                        creatorMetricsRepository,
                        mediaMetricsRepository,
                        qualityScoreService,
                        new RateEstimationService(),
                        new RateAddOns(
                                RateAddOns.REPOST_30D_PCT_DEFAULT,
                                RateAddOns.PAID_ADS_QUARTER_PCT_DEFAULT,
                                RateAddOns.WHITELISTING_PCT_DEFAULT,
                                RateAddOns.PERPETUITY_MULTIPLE_DEFAULT,
                                RateAddOns.EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT),
                        new RateTierProperties(
                                null, null, null, null, null, null, null, null, null, null),
                        auditLogService);

        profile = nanoBeautyCreator();
        stubDefaults(3_000L, new BigDecimal("2.4"));
    }

    // =====================================================================================
    // Wave 3 round 2 - quoteForRisk, the seam DealRiskService reads
    // =====================================================================================

    /**
     * The arithmetic {@code DealRiskServiceEvaluateDealTest} stubs, computed here for real.
     *
     * <p>Three {@code INSTAGRAM_REEL} slots off a proposal card against a 12,000 reel floor is a
     * 36,000 floor total. The second assertion is the reason the seam mattered:
     * {@link com.influora.service.risk.Floors} - the fallback {@code BELOW_FLOOR} used before the
     * quote was wired - reads the raw type string through a bare uppercase switch that knows only
     * five of the eight canonical names, so a platform name it has never heard of falls to
     * {@code post_floor} and each of those reels is priced at a third of what the creator asked
     * for. SPEC.md &sect;4.1 is explicit that real proposal metadata carries exactly these platform
     * names, which is why {@code QuoteDeliverableType.parse} exists.
     */
    @Test
    @DisplayName("quoteForRisk prices the proposal card's platform type names, and writes no audit row")
    void quoteForRiskPricesTheProposalCardAndWritesNoAuditRow() {
        PreferencesResponse floors =
                prefs(new BigDecimal("12000"), new BigDecimal("5000"), new BigDecimal("4000"), false);
        List<DeliverableSlot> card =
                RateQuoteService.slotsFromProposalMetadata(
                        "{\"deliverables\":[{\"type\":\"INSTAGRAM_REEL\",\"qty\":3}]}");

        PackageQuote quote = service.quoteForRisk(profile, floors, null, card);

        assertThat(card).containsExactly(new DeliverableSlot("INSTAGRAM_REEL", 3));
        assertThat(quote.floorTotalValue()).isEqualByComparingTo("36000");

        // What the risk fallback would have said about the same three reels.
        assertThat(com.influora.service.risk.Floors.unitFloor(floors, "INSTAGRAM_REEL"))
                .as("the fallback does not know the platform vocabulary and drops to post_floor")
                .isEqualByComparingTo("4000");

        // SPEC.md 14.1.f measures quoted-versus-realised. Risk flags are recomputed on every deal
        // view, so auditing them as issued quotes would drown that series in quotes nobody saw.
        verify(auditLogService, never()).recordCreatorEvent(anyString(), anyString(), anyString(), any());
    }

    /** With no override, the risk quote prices the extraction itself - the pasted-brief path. */
    @Test
    @DisplayName("quoteForRisk with no deliverable override falls back to the extraction's own lines")
    void quoteForRiskWithoutOverrideUsesTheExtraction() {
        PackageQuote quote =
                service.quoteForRisk(
                        profile,
                        defaultPrefs(),
                        extraction(
                                List.of(new DeliverableLine("REEL", 2)),
                                new BigDecimal("4000"),
                                true,
                                false,
                                null,
                                List.of("ORGANIC"),
                                null),
                        List.of());

        assertThat(quote.lines()).extracting(QuoteLine::qty).containsExactly(2);
        assertThat(quote.floorTotalValue()).isEqualByComparingTo("2000");
    }

    /** Unparseable, absent and null metadata are all "no card", never an exception. */
    @Test
    @DisplayName("slotsFromProposalMetadata never throws on a card it cannot read")
    void proposalMetadataDegradesToAnEmptyPackage() {
        assertThat(RateQuoteService.slotsFromProposalMetadata(null)).isEmpty();
        assertThat(RateQuoteService.slotsFromProposalMetadata("not json at all")).isEmpty();
        assertThat(RateQuoteService.slotsFromProposalMetadata("{\"counterOf\":\"abc\"}")).isEmpty();
        assertThat(RateQuoteService.slotsFromProposalMetadata("{\"deliverables\":[\"REEL\"]}")).isEmpty();
        assertThat(
                        RateQuoteService.slotsFromProposalMetadata(
                                "{\"deliverables\":[{\"type\":\"REEL\"}]}"))
                .as("a card with no qty means one of that thing, not zero of it")
                .containsExactly(new DeliverableSlot("REEL", 1));
    }

    private CreatorProfile nanoBeautyCreator() {
        CreatorProfile created = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah");
        created.applyAdminProfileEdit("Priya Shah", "[\"BEAUTY\"]");
        created.applyAggregatedStats(3_000L, new BigDecimal("2.4"));
        return created;
    }

    /** Empty history, empty band, one metric row, no quality score, no Meera-anchored deals. */
    private void stubDefaults(long followers, BigDecimal engagement) {
        lenient().when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        lenient().when(collaborationRepository.findRateBandCandidates("BEAUTY")).thenReturn(List.of());
        lenient()
                .when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of(metric(followers, engagement)));
        lenient()
                .when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());
        lenient().when(qualityScoreService.calculate(any(), any())).thenReturn(QualityScoreResult.absent());
        lenient()
                .when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        anyString(), eq(DealMessageKind.proposal)))
                .thenReturn(Optional.empty());
        lenient()
                .when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(any(), any()))
                .thenReturn(List.of());
    }

    // =====================================================================================
    // Step 1 - the three provenance branches
    // =====================================================================================

    @Test
    @DisplayName("1c/14.1.a - benchmark mode uses the MIDPOINT: 3,750 for the worked example, not 4,500")
    void benchmarkUsesTheMidpointNotThe65thPercentile() {
        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("benchmark, not market data");
        assertThat(quote.provenanceSampleSize()).isZero();
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT);
        // The old 0.65 percentile would have produced 4,500 here.
        assertThat(quote.totalValue()).isNotEqualByComparingTo(new BigDecimal("4500"));
        assertThat(quote.rangeMin()).isEqualTo("1,250");
        assertThat(quote.rangeMax()).isEqualTo("6,250");
    }

    @Test
    @DisplayName("1c - no metric row at all falls to the creator's OWN FLOOR, and says so")
    void noMetricFallsToOwnFloor() {
        lenient()
                .when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("your floor");
        assertThat(quote.totalValue()).isEqualByComparingTo("1000");
        // No range on this sub-branch, so the x1.10 lift is unclamped.
        assertThat(quote.anchorValue()).isEqualByComparingTo("1100.00");
        assertThat(quote.rangeMin()).isNull();
        assertThat(quote.rangeMax()).isNull();
    }

    @Test
    @DisplayName("1a - three or more own priced deals stand alone as a median")
    void ownHistoryOfThreeUsesTheMedian() {
        givenOwnDeals("5000", "6000", "7000");

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("your last 3 priced deals");
        assertThat(quote.provenanceSampleSize()).isEqualTo(3);
        assertThat(quote.totalValue()).isEqualByComparingTo("6000");
    }

    /** SPEC.md 14.1.c's own worked example: one real deal at 2,000 against a 3,750 benchmark. */
    @Test
    @DisplayName("14.1.c - shrinkage at n=1: (1 x 2,000 + 3,750) / 2 = 2,875, not 3,750")
    void shrinkageAtOneOwnDeal() {
        givenOwnDeals("2000");

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("your last 1 priced deals, blended with benchmark");
        assertThat(quote.provenanceSampleSize()).isEqualTo(1);
        assertThat(quote.totalValue()).isEqualByComparingTo("2875");
        assertThat(quote.totalValue()).isNotEqualByComparingTo(BENCHMARK_UNIT);
    }

    /**
     * n=2 with an even-count median, chosen so the blend does NOT divide evenly: median 2,500,
     * (2 x 2,500 + 3,750) / 3 = 2,916.666..., rounded to whole rupees the way
     * {@code RateEstimationService} rounds both its bounds. Without that rounding a quote and its
     * own recomputation differ by paise.
     */
    @Test
    @DisplayName("14.1.c - shrinkage at n=2: (2 x 2,500 + 3,750) / 3 = 2,916.67 rounds to 2,917")
    void shrinkageAtTwoOwnDealsRoundsToWholeRupees() {
        givenOwnDeals("2000", "3000");

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("your last 2 priced deals, blended with benchmark");
        assertThat(quote.provenanceSampleSize()).isEqualTo(2);
        assertThat(quote.totalValue()).isEqualByComparingTo("2917");
        assertThat(quote.totalValue().stripTrailingZeros().scale())
                .as("a blended unit must be whole rupees, never paise")
                .isLessThanOrEqualTo(0);
    }

    /**
     * A 30,000 deal for "2 reels + 1 story" is not a 30,000 reel. Weights 2 x 1.00 + 1 x 0.50 =
     * 2.5, so the reel-equivalent is 12,000 — and the metadata carries the PLATFORM names, which is
     * why {@link QuoteDeliverableType#parse} has to understand them.
     */
    @Test
    @DisplayName("1a - own deals are normalised to reel-equivalents through the proposal metadata")
    void ownDealsAreNormalisedByDeliverableWeight() {
        Collaboration collaboration = pricedDeal("01HWCOLLAB0000000000001", "30000", daysAgo(10));
        lenient()
                .when(collaborationRepository.findByCreatorId(USER_ID))
                .thenReturn(List.of(collaboration));
        DealMessage proposal = mock(DealMessage.class);
        lenient()
                .when(proposal.getMetadataJson())
                .thenReturn(
                        "{\"amount\":30000,\"deliverables\":["
                                + "{\"type\":\"INSTAGRAM_REEL\",\"qty\":2},"
                                + "{\"type\":\"INSTAGRAM_STORY\",\"qty\":1}]}");
        lenient()
                .when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        "01HWCOLLAB0000000000001", DealMessageKind.proposal))
                .thenReturn(Optional.of(proposal));

        PackageQuote quote = quoteOneReel();

        // 30,000 / 2.5 = 12,000 own unit, blended with the 3,750 benchmark at n=1 -> 7,875.
        assertThat(quote.totalValue()).isEqualByComparingTo("7875");
    }

    @Test
    @DisplayName("1a - a deal older than 180 days, or cancelled, or unpriced, is not own history")
    void ownHistoryWindowAndStatusAreEnforced() {
        Collaboration tooOld = pricedDeal("01HWCOLLABOLD0000000001", "9000", daysAgo(200));
        Collaboration cancelled = mock(Collaboration.class);
        lenient().when(cancelled.getStatus()).thenReturn(CollaborationStatus.CANCELLED);
        lenient().when(cancelled.getAgreedRate()).thenReturn(new BigDecimal("9000"));
        lenient().when(cancelled.getUpdatedAt()).thenReturn(daysAgo(5));
        Collaboration unpriced = mock(Collaboration.class);
        lenient().when(unpriced.getStatus()).thenReturn(CollaborationStatus.COMPLETED);
        lenient().when(unpriced.getAgreedRate()).thenReturn(null);
        lenient()
                .when(collaborationRepository.findByCreatorId(USER_ID))
                .thenReturn(List.of(tooOld, cancelled, unpriced));

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("benchmark, not market data");
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT);
    }

    // =====================================================================================
    // Step 1b / 14.1.d - the tier band and its two honesty guards
    // =====================================================================================

    @Test
    @DisplayName("1b - five deals in tier from three workspaces produce a band median")
    void tierBandProducesAMedian() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-1", "c-4"),
                bandRow("8000", "ws-2", "c-5"));

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("5 closed deals in your tier in 90 days");
        assertThat(quote.provenanceSampleSize()).isEqualTo(5);
        assertThat(quote.totalValue()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("1b - four deals is below the k-anonymity floor: fall through to benchmark")
    void bandBelowFiveFallsThrough() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-4", "c-4"));

        assertThat(quoteOneReel().provenance()).isEqualTo("benchmark, not market data");
    }

    /**
     * SPEC.md 14.1.d guard (i). Five deals that all came from two brands are not a market — they
     * are two brands' pricing, and quoting them back as "your tier" launders one buyer's opinion
     * into a platform figure.
     */
    @Test
    @DisplayName("14.1.d - five deals from only two workspaces fall through to benchmark")
    void bandBelowThreeDistinctWorkspacesFallsThrough() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-1", "c-2"),
                bandRow("6000", "ws-2", "c-3"),
                bandRow("7000", "ws-2", "c-4"),
                bandRow("8000", "ws-1", "c-5"));

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance()).isEqualTo("benchmark, not market data");
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT);
    }

    @Test
    @DisplayName("1b - a band row older than 90 days is excluded, so the '90 days' claim is real")
    void bandWindowIsEnforced() {
        givenBand(
                bandRowAt("4000", "ws-1", "c-1", daysAgo(120)),
                bandRowAt("5000", "ws-2", "c-2", daysAgo(120)),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-4", "c-4"),
                bandRow("8000", "ws-5", "c-5"));

        assertThat(quoteOneReel().provenance()).isEqualTo("benchmark, not market data");
    }

    @Test
    @DisplayName("1b - a band row in a DIFFERENT tier is excluded")
    void bandTierIsEnforced() {
        givenBand(
                bandRowTier("4000", "ws-1", "c-1", 900_000L),
                bandRowTier("5000", "ws-2", "c-2", 900_000L),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-4", "c-4"),
                bandRow("8000", "ws-5", "c-5"));

        assertThat(quoteOneReel().provenance()).isEqualTo("benchmark, not market data");
    }

    /**
     * SPEC.md 14.1.d guard (ii) — the feedback-loop label. It does not stop Meera quoting its own
     * band back to itself; it stops that being invisible.
     */
    @Test
    @DisplayName("14.1.d - above half the band Meera-anchored, the provenance says 'mostly Meera-quoted'")
    void meeraAnchoredShareLabelsTheBand() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-1", "c-4"),
                bandRow("8000", "ws-2", "c-5"));
        lenient()
                .when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(
                        any(), eq(OfferEvent.MEERA_COUNTER)))
                .thenReturn(List.of("c-1", "c-2", "c-3"));

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance())
                .isEqualTo("5 closed deals in your tier in 90 days, mostly Meera-quoted");
    }

    @Test
    @DisplayName("14.1.d - exactly half Meera-anchored is NOT 'mostly': the threshold is strictly above 0.5")
    void meeraAnchoredShareAtOrBelowHalfIsUnlabelled() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-1", "c-4"),
                bandRow("8000", "ws-2", "c-5"),
                bandRow("9000", "ws-3", "c-6"));
        lenient()
                .when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(
                        any(), eq(OfferEvent.MEERA_COUNTER)))
                .thenReturn(List.of("c-1", "c-2", "c-3"));

        assertThat(quoteOneReel().provenance()).doesNotContain("mostly Meera-quoted");
    }

    /**
     * The reason 14.1.d insists on DISTINCT collaborations. {@code deal_offer_history}'s unique key
     * is {@code (collaboration_id, sequence_no)}, so a two-counter negotiation holds two
     * {@code MEERA_COUNTER} ROWS on one collaboration. A row count would push the share above 1.0
     * and fire "mostly Meera-quoted" off a single deal. This asserts the service asks for the
     * distinct ids of exactly the band's collaborations, and that a fully-anchored band tops out at
     * 1.0 rather than exceeding it.
     */
    @Test
    @DisplayName("14.1.d - the share is over DISTINCT collaborations, so it can never exceed 1.0")
    void meeraAnchoredShareIsComputedOverDistinctCollaborations() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-1", "c-4"),
                bandRow("8000", "ws-2", "c-5"));
        // Every collaboration in the band is Meera-anchored - several of them via more than one
        // MEERA_COUNTER row, which the DISTINCT query collapses before it ever reaches the service.
        lenient()
                .when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(
                        any(), eq(OfferEvent.MEERA_COUNTER)))
                .thenReturn(List.of("c-1", "c-2", "c-3", "c-4", "c-5"));

        PackageQuote quote = quoteOneReel();

        assertThat(quote.provenance())
                .isEqualTo("5 closed deals in your tier in 90 days, mostly Meera-quoted");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> ids =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(dealOfferHistoryRepository)
                .findDistinctCollaborationIdsByEvent(ids.capture(), eq(OfferEvent.MEERA_COUNTER));
        assertThat(ids.getValue()).containsExactlyInAnyOrder("c-1", "c-2", "c-3", "c-4", "c-5");
    }

    /**
     * A gate against the exact regression 14.1.d's PRIYA note describes: reintroducing a
     * row-counting derived query alongside the distinct one. Spring Data would happily generate it
     * and it would compile.
     */
    @Test
    @DisplayName("14.1.d - DealOfferHistoryRepository exposes no row-counting share query")
    void noRowCountingShareQueryExists() {
        assertThat(Arrays.stream(DealOfferHistoryRepository.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("countByCollaborationIdInAndEvent")
                .contains("findDistinctCollaborationIdsByEvent");
    }

    // =====================================================================================
    // Steps 2-5 - lines, floors, discount, add-ons
    // =====================================================================================

    @Test
    @DisplayName("3 - two units is below the bundle threshold: no discount")
    void noDiscountBelowThreeUnits() {
        PackageQuote quote = quote(List.of(new DeliverableSlot("REEL", 2)), List.of(), null, defaultPrefs());

        assertThat(quote.bundleDiscountValue()).isEqualByComparingTo("0");
        assertThat(quote.totalValue()).isEqualByComparingTo("7500.00");
    }

    @Test
    @DisplayName("3 - three units triggers the 10% bundle discount, shown as the creator's concession")
    void discountAtThreeUnits() {
        PackageQuote quote = quote(List.of(new DeliverableSlot("REEL", 3)), List.of(), null, defaultPrefs());

        assertThat(quote.bundleDiscountValue()).isEqualByComparingTo("1125.00");
        assertThat(quote.bundleDiscount()).isEqualTo("1,125");
        assertThat(quote.totalValue()).isEqualByComparingTo("10125.00");
    }

    @Test
    @DisplayName("3 - the threshold counts total QUANTITY, not the number of lines")
    void discountCountsQuantityNotLines() {
        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("REEL", 1), new DeliverableSlot("STORY_SET", 2)),
                        List.of(),
                        null,
                        defaultPrefs());

        // 3,750 + (1,875 x 2) = 7,500 subtotal, 3 units -> 750 discount.
        assertThat(quote.bundleDiscountValue()).isEqualByComparingTo("750.00");
        assertThat(quote.totalValue()).isEqualByComparingTo("6750.00");
    }

    @Test
    @DisplayName("2 - below_floor compares the UNIT price against that type's own floor")
    void belowFloorIsPerType() {
        PreferencesResponse prefs =
                prefs(new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("100"), false);

        PackageQuote quote =
                quote(
                        List.of(
                                new DeliverableSlot("REEL", 1),
                                new DeliverableSlot("STORY_SET", 1),
                                new DeliverableSlot("STATIC_POST", 1)),
                        List.of(),
                        null,
                        prefs);

        assertThat(quote.lines()).extracting(QuoteLine::type).containsExactly("REEL", "STORY_SET", "STATIC_POST");
        assertThat(quote.lines()).extracting(QuoteLine::belowFloor).containsExactly(true, false, false);
        assertThat(quote.lines().get(0).unitPriceValue()).isEqualByComparingTo("3750.00");
        assertThat(quote.lines().get(1).unitPriceValue()).isEqualByComparingTo("1875.00");
        assertThat(quote.floorTotalValue()).isEqualByComparingTo("10200.00");
    }

    @Test
    @DisplayName("2 - 'everything else same as post': OTHER and SHORT compare against post_floor")
    void unknownTypesUsePostFloor() {
        PreferencesResponse prefs =
                prefs(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("99999"), false);

        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("PODCAST_MENTION", 1), new DeliverableSlot("SHORT", 1)),
                        List.of(),
                        null,
                        prefs);

        assertThat(quote.lines()).extracting(QuoteLine::type).containsExactly("OTHER", "SHORT");
        assertThat(quote.lines()).extracting(QuoteLine::belowFloor).containsExactly(true, true);
    }

    @Test
    @DisplayName("2 - a legacy 'story' type prices at the STORY_SET weight and reports the pricing name")
    void legacyTypeStringsPrice() {
        PackageQuote quote = quote(List.of(new DeliverableSlot("story", 1)), List.of(), null, defaultPrefs());

        assertThat(quote.lines().get(0).type()).isEqualTo("STORY_SET");
        assertThat(quote.lines().get(0).unitPriceValue()).isEqualByComparingTo("1875.00");
    }

    @Test
    @DisplayName("4 - REPOST_30D adds 25% of the discounted subtotal")
    void addOnRepost30d() {
        assertAddOn(RateAddOns.REPOST_30D, "937.50", "4687.50");
    }

    @Test
    @DisplayName("4 - PAID_ADS_QUARTER adds 40%")
    void addOnPaidAdsQuarter() {
        assertAddOn(RateAddOns.PAID_ADS_QUARTER, "1500.00", "5250.00");
    }

    @Test
    @DisplayName("4 - WHITELISTING adds 75%")
    void addOnWhitelisting() {
        assertAddOn(RateAddOns.WHITELISTING, "2812.50", "6562.50");
    }

    @Test
    @DisplayName("4 - PERPETUITY takes the package to exactly 2.5x, not 3.5x")
    void addOnPerpetuity() {
        PackageQuote quote = assertAddOn(RateAddOns.PERPETUITY, "5625.00", "9375.00");
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT.multiply(new BigDecimal("2.5")));
    }

    @Test
    @DisplayName("4 - EXCLUSIVITY_30D charges its 15% floor")
    void addOnExclusivity30d() {
        assertAddOn(RateAddOns.EXCLUSIVITY_30D, "562.50", "4312.50");
    }

    @Test
    @DisplayName("4 - add-ons compute on the DISCOUNTED subtotal, never the gross one")
    void addOnsComputeOnTheDiscountedSubtotal() {
        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("REEL", 3)),
                        List.of(RateAddOns.REPOST_30D),
                        null,
                        defaultPrefs());

        // Discounted subtotal is 10,125; 25% of that is 2,531.25 (25% of the GROSS 11,250 is 2,812.50).
        assertThat(quote.addOns().get(0).amountValue()).isEqualByComparingTo("2531.25");
        assertThat(quote.totalValue()).isEqualByComparingTo("12656.25");
    }

    @Test
    @DisplayName("4 - a repeated or unknown add-on code is charged once, or not at all")
    void addOnCodesAreDeduplicatedAndUnknownOnesDropped() {
        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("REEL", 1)),
                        List.of(RateAddOns.REPOST_30D, RateAddOns.REPOST_30D, "PODCAST_RIGHTS"),
                        null,
                        defaultPrefs());

        assertThat(quote.addOns()).extracting(AddOnLine::code).containsExactly(RateAddOns.REPOST_30D);
        assertThat(quote.totalValue()).isEqualByComparingTo("4687.50");
    }

    // =====================================================================================
    // Step 6 / 14.1.e - the two anchor formulas
    // =====================================================================================

    @Test
    @DisplayName("14.1.e - benchmark anchors at x1.10, clamped to the range top: 3,750 -> 4,125")
    void benchmarkAnchorIsTenPercentClamped() {
        PackageQuote quote = quoteOneReel();

        assertThat(quote.anchorValue()).isEqualByComparingTo("4125.00");
        assertThat(quote.anchor()).isEqualTo("4,125");
    }

    /**
     * SPEC.md 14.1's second worked-example row, recomputed under the MIDPOINT rule. At 5.5%
     * engagement the multiplier is 1.3, so the estimate is 1,625/8,125 and the unit is 4,875 —
     * not the 5,850 the spec's table shows, which was computed with the superseded 0.65
     * percentile.
     */
    @Test
    @DisplayName("14.1.a/e - the wide-range case: unit 4,875 and an unclamped x1.10 anchor")
    void benchmarkAnchorOnAWideRange() {
        stubDefaults(3_000L, new BigDecimal("5.5"));

        PackageQuote quote = quote(List.of(new DeliverableSlot("REEL", 1)), List.of(), null, defaultPrefs());

        // Unit 4,875; x1.10 = 5,362.50; range top 8,125 -> the lift wins.
        assertThat(quote.totalValue()).isEqualByComparingTo("4875");
        assertThat(quote.anchorValue()).isEqualByComparingTo("5362.50");
    }

    /**
     * The clamp only binds on a NARROW range — one where the top is less than 10% above the
     * midpoint. The compiled tier bands are all far wider than that, so this case is reached the
     * way production will reach it: through a calibrated tier override (14.1.h), which is exactly
     * what B0-36 creates when a tier's realised distribution is tight.
     */
    @Test
    @DisplayName("14.1.e - the benchmark clamp binds when the range top is under the x1.10 lift")
    void benchmarkAnchorClampBinds() {
        // A tier override collapses the band to 1,000/1,010: unit = 1,005, x1.10 = 1,105.50, and
        // the range top (1,010) is below that, so the clamp wins. This is exactly the shape B0-36
        // creates when it calibrates a tier from a tight realised distribution.
        service =
                new RateQuoteService(
                        preferencesService,
                        collaborationRepository,
                        dealMessageRepository,
                        dealOfferHistoryRepository,
                        creatorMetricsRepository,
                        mediaMetricsRepository,
                        qualityScoreService,
                        new RateEstimationService(),
                        new RateAddOns(
                                RateAddOns.REPOST_30D_PCT_DEFAULT,
                                RateAddOns.PAID_ADS_QUARTER_PCT_DEFAULT,
                                RateAddOns.WHITELISTING_PCT_DEFAULT,
                                RateAddOns.PERPETUITY_MULTIPLE_DEFAULT,
                                RateAddOns.EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT),
                        new RateTierProperties(
                                800L, 808L, null, null, null, null, null, null, null, null),
                        auditLogService);

        PackageQuote quote = quoteOneReel();

        // Override 800/808 x BEAUTY 1.25 -> 1,000/1,010. Midpoint unit 1,005.
        assertThat(quote.totalValue()).isEqualByComparingTo("1005");
        // x1.10 would be 1,105.50; the range top clamps it to 1,010.
        assertThat(quote.anchorValue()).isEqualByComparingTo("1010.00");
    }

    @Test
    @DisplayName("14.1.e - own history anchors at x1.15, UNCAPPED (there is no range to clamp against)")
    void ownHistoryAnchorIsFifteenPercentUncapped() {
        givenOwnDeals("5000", "6000", "7000");

        PackageQuote quote = quoteOneReel();

        assertThat(quote.anchorValue()).isEqualByComparingTo("6900.00");
        // The benchmark range top (6,250) would have clamped this to 6,250 under the pre-14.1.e
        // wording. It must not.
        assertThat(quote.anchorValue()).isGreaterThan(BENCHMARK_MAX);
        assertThat(quote.rangeMax()).isNull();
    }

    @Test
    @DisplayName("14.1.e - the tier band anchors at x1.15 uncapped too")
    void tierBandAnchorIsFifteenPercentUncapped() {
        givenBand(
                bandRow("4000", "ws-1", "c-1"),
                bandRow("5000", "ws-2", "c-2"),
                bandRow("6000", "ws-3", "c-3"),
                bandRow("7000", "ws-1", "c-4"),
                bandRow("8000", "ws-2", "c-5"));

        assertThat(quoteOneReel().anchorValue()).isEqualByComparingTo("6900.00");
    }

    @Test
    @DisplayName("6 - the holdout arm gets no opening ask at all")
    void holdoutNullsTheAnchor() {
        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("REEL", 1)),
                        List.of(),
                        null,
                        prefs(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000"), true));

        assertThat(quote.anchorValue()).isNull();
        assertThat(quote.anchor()).isNull();
        // Everything else still renders - the holdout withholds the anchor, not the quote.
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT);
    }

    // =====================================================================================
    // Step 7 - the four recommended moves
    // =====================================================================================

    @Test
    @DisplayName("7 - budget at or above the total is ACCEPT")
    void moveAccept() {
        assertThat(quoteWithBudget(new BigDecimal("4000")).recommendedMove()).isEqualTo("ACCEPT");
    }

    @Test
    @DisplayName("7 - budget below the floor total is DECLINE")
    void moveDecline() {
        assertThat(quoteWithBudget(new BigDecimal("500")).recommendedMove()).isEqualTo("DECLINE");
    }

    @Test
    @DisplayName("7 - a NANO or MICRO creator between the floor and the total is told to SCOPE_DOWN")
    void moveScopeDown() {
        assertThat(quoteWithBudget(new BigDecimal("2000")).recommendedMove()).isEqualTo("SCOPE_DOWN");
    }

    @Test
    @DisplayName("7 - SCOPE_DOWN removes the lowest-weight units until the package fits, and renders it")
    void scopeDownOfferFitsTheBudget() {
        PackageQuote quote =
                quote(
                        List.of(new DeliverableSlot("REEL", 3)),
                        List.of(),
                        new BigDecimal("8000"),
                        defaultPrefs());

        assertThat(quote.recommendedMove()).isEqualTo("SCOPE_DOWN");
        // Three reels is 10,125; dropping one leaves 7,500 (the bundle discount lapses with it).
        assertThat(quote.scopeDownOffer()).isEqualTo("2 x REEL for 7,500");
    }

    @Test
    @DisplayName("7 - a MID creator between the floor and the total is told to COUNTER_AT_FLOOR")
    void moveCounterAtFloorForMid() {
        profile.applyAggregatedStats(60_000L, new BigDecimal("2.4"));
        stubDefaults(60_000L, new BigDecimal("2.4"));

        PackageQuote quote = quoteWithBudget(new BigDecimal("50000"));

        // MID base 25,000/100,000 x BEAUTY 1.25 -> 31,250/125,000, midpoint 78,125.
        assertThat(quote.totalValue()).isEqualByComparingTo("78125");
        assertThat(quote.recommendedMove()).isEqualTo("COUNTER_AT_FLOOR");
    }

    /**
     * {@code "MEGA"} is not a {@code CreatorTier} constant. A {@code CreatorTier.valueOf(tier)}
     * branch here would throw {@code IllegalArgumentException} and 500 the whole quote for every
     * creator above a million followers.
     */
    @Test
    @DisplayName("7 - MEGA is handled as above MID and never reaches CreatorTier.valueOf")
    void moveCounterAtFloorForMegaWithoutThrowing() {
        profile.applyAggregatedStats(1_500_000L, new BigDecimal("2.4"));
        stubDefaults(1_500_000L, new BigDecimal("2.4"));

        PackageQuote quote = quoteWithBudget(new BigDecimal("1000000"));

        assertThat(quote.totalValue()).isEqualByComparingTo("1875000");
        assertThat(quote.recommendedMove()).isEqualTo("COUNTER_AT_FLOOR");
    }

    @Test
    @DisplayName("7 - no brand budget means no recommended move, not a guessed one")
    void noBudgetNoMove() {
        PackageQuote quote = quoteOneReel();
        assertThat(quote.recommendedMove()).isNull();
        assertThat(quote.scopeDownOffer()).isNull();
    }

    /**
     * The "UNKNOWN" guard, from the other side: a creator with NO metric row still gets a tier from
     * her profile's follower count, so {@code estimate()}'s "UNKNOWN" never reaches step 7.
     */
    @Test
    @DisplayName("7 - with no metric row the tier still comes from the profile, never 'UNKNOWN'")
    void tierNeverComesFromTheEstimate() {
        lenient()
                .when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());

        quoteWithBudget(new BigDecimal("900"));

        assertThat(auditDetail()).containsEntry("tier", "NANO");
    }

    // =====================================================================================
    // Steps 8-9 - fixed terms and rendering
    // =====================================================================================

    @Test
    @DisplayName("8 - the payment schedule is fixed, says 'securing funds', and revisions are 2")
    void fixedTerms() {
        PackageQuote quote = quoteOneReel();

        assertThat(quote.paymentSchedule()).isEqualTo("50% on securing funds, 50% on delivery");
        assertThat(quote.paymentSchedule()).doesNotContainIgnoringCase("escrow");
        assertThat(quote.revisionRounds()).isEqualTo(2);
        assertThat(quote.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("9 - every money string is rendered in the creator's locale, beside its numeric value")
    void moneyStringsAreRenderedInTheCreatorLocale() {
        PackageQuote quote = quote(List.of(new DeliverableSlot("REEL", 3)), List.of(), null, defaultPrefs());

        // en-IN grouping: 10,125 not 10,125.00 and not 10125.
        assertThat(quote.total()).isEqualTo("10,125");
        assertThat(quote.totalValue()).isEqualByComparingTo("10125.00");
        assertThat(quote.lines().get(0).unitPrice()).isEqualTo("3,750");
        assertThat(quote.lines().get(0).lineTotal()).isEqualTo("11,250");
        assertThat(quote.floorTotal()).isEqualTo("3,000");
    }

    // =====================================================================================
    // 14.1.f - the audit row
    // =====================================================================================

    @Test
    @DisplayName("14.1.f - every quote writes a RATE_QUOTE_ISSUED row against the creator's USER id")
    void everyQuoteWritesAnAuditRow() {
        quoteOneReel();

        verify(auditLogService)
                .recordCreatorEvent(eq(USER_ID), eq("RATE_QUOTE_ISSUED"), eq("ALLOWED"), any());
    }

    @Test
    @DisplayName("14.1.f - the audit detail carries exactly the allow-listed keys, and their real values")
    void auditDetailShape() {
        quoteOneReel();

        Map<String, Object> detail = auditDetail();
        assertThat(detail).containsOnlyKeys(AUDIT_DETAIL_ALLOW_LIST.toArray(new String[0]));
        assertThat(detail).containsEntry("tier", "NANO");
        assertThat(detail).containsEntry("provenance", "benchmark, not market data");
        assertThat(detail).containsEntry("sample", 0);
        assertThat(detail).containsEntry("currency", "INR");
        assertThat(detail).containsEntry("deliverable_count", 1);
        assertThat(detail).containsEntry("context", "chat");
        assertThat((BigDecimal) detail.get("total")).isEqualByComparingTo(BENCHMARK_UNIT);
        assertThat((BigDecimal) detail.get("anchor")).isEqualByComparingTo("4125.00");
    }

    /**
     * The allow-list exists for this. A floor is precisely the number the creator-side info barrier
     * contains, and this table is readable by operators who are not that creator. {@code unit} is
     * banned too: in benchmark mode it is derivable straight back to {@code estimate().min()},
     * which is the figure the floor is computed from.
     */
    @Test
    @DisplayName("14.1.f - the audit detail carries no floor, no floor_total, no range bound and no unit")
    void auditDetailNeverCarriesAFloorOrARange() {
        quote(
                List.of(new DeliverableSlot("REEL", 1)),
                List.of(RateAddOns.WHITELISTING),
                new BigDecimal("2000"),
                prefs(new BigDecimal("99999"), new BigDecimal("99999"), new BigDecimal("99999"), false));

        Map<String, Object> detail = auditDetail();
        assertThat(detail.keySet())
                .doesNotContain(
                        "floor",
                        "reel_floor",
                        "story_set_floor",
                        "post_floor",
                        "floor_total",
                        "floorTotal",
                        "range_min",
                        "range_max",
                        "unit",
                        "unit_price");
        assertThat(detail.values().stream().map(String::valueOf))
                .as("the distinctive floor value must not appear anywhere in the detail map")
                .noneMatch(v -> v.contains("99999"));
    }

    @Test
    @DisplayName("14.1.f - under holdout the anchor key is present and null, never a fabricated figure")
    void auditDetailAnchorIsNullUnderHoldout() {
        quote(
                List.of(new DeliverableSlot("REEL", 1)),
                List.of(),
                null,
                prefs(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000"), true));

        assertThat(auditDetail()).containsKey("anchor");
        assertThat(auditDetail().get("anchor")).isNull();
    }

    // =====================================================================================
    // quoteForExtraction and floorTotal
    // =====================================================================================

    @Test
    @DisplayName("4.3 - a brief prices its own deliverables and riders, under the 'brief' context")
    void quoteForExtractionPricesTheBrief() {
        BriefExtraction extraction =
                extraction(
                        List.of(new DeliverableLine("INSTAGRAM_REEL", 1)),
                        null,
                        false,
                        true,
                        null,
                        List.of("ORGANIC", "PAID_ADS"),
                        30);

        PackageQuote quote = service.quoteForExtraction(profile, defaultPrefs(), extraction);

        assertThat(quote.addOns())
                .extracting(AddOnLine::code)
                .containsExactly(
                        RateAddOns.PERPETUITY, RateAddOns.PAID_ADS_QUARTER, RateAddOns.EXCLUSIVITY_30D);
        assertThat(auditDetail()).containsEntry("context", "brief");
    }

    /**
     * An extractor's inferred budget is a guess. Letting a guess drive the recommended move would
     * have Meera tell a creator to accept a number the brand never wrote down.
     */
    @Test
    @DisplayName("4.3 - an UNSTATED brief budget never reaches the recommended move")
    void quoteForExtractionIgnoresAnUnstatedBudget() {
        BriefExtraction unstated =
                extraction(
                        List.of(new DeliverableLine("REEL", 1)),
                        new BigDecimal("10000"),
                        false,
                        false,
                        null,
                        List.of(),
                        null);
        assertThat(service.quoteForExtraction(profile, defaultPrefs(), unstated).recommendedMove())
                .isNull();

        BriefExtraction stated =
                extraction(
                        List.of(new DeliverableLine("REEL", 1)),
                        new BigDecimal("10000"),
                        true,
                        false,
                        null,
                        List.of(),
                        null);
        assertThat(service.quoteForExtraction(profile, defaultPrefs(), stated).recommendedMove())
                .isEqualTo("ACCEPT");
    }

    @Test
    @DisplayName("4.3 - a brief with no deliverables at all is priced as one reel, not as zero")
    void quoteForExtractionWithNoDeliverables() {
        BriefExtraction empty = extraction(List.of(), null, false, false, null, List.of(), null);

        PackageQuote quote = service.quoteForExtraction(profile, defaultPrefs(), empty);

        assertThat(quote.lines()).extracting(QuoteLine::type).containsExactly("REEL");
        assertThat(quote.totalValue()).isEqualByComparingTo(BENCHMARK_UNIT);
    }

    /**
     * The profile-id-keyed entry point, for callers holding a {@code creator_profiles.id} and no
     * user id. It must read floors through {@link CreatorAgentPreferencesService} — never through
     * {@code CreatorAgentPreferencesRepository}, which {@code InfoBarrierTest} forbids.
     */
    @Test
    @DisplayName("5 - floorTotal(profileId, ...) resolves preferences through the service, not the repository")
    void floorTotalByProfileId() {
        lenient().when(preferencesService.getByProfileId(PROFILE_ID)).thenReturn(defaultPrefs());

        BigDecimal total =
                service.floorTotal(
                        PROFILE_ID,
                        List.of(new DeliverableSlot("REEL", 2), new DeliverableSlot("STORY_SET", 1)));

        assertThat(total).isEqualByComparingTo("3000.00");
        verify(preferencesService).getByProfileId(PROFILE_ID);
    }

    // =====================================================================================
    // Fixtures
    // =====================================================================================

    private PackageQuote quoteOneReel() {
        return quote(List.of(new DeliverableSlot("REEL", 1)), List.of(), null, defaultPrefs());
    }

    private PackageQuote quoteWithBudget(BigDecimal budget) {
        return quote(List.of(new DeliverableSlot("REEL", 1)), List.of(), budget, defaultPrefs());
    }

    private PackageQuote quote(
            List<DeliverableSlot> slots,
            List<String> addOnCodes,
            BigDecimal budget,
            PreferencesResponse prefs) {
        return service.quote(profile, prefs, slots, addOnCodes, budget, Locale.forLanguageTag("en-IN"));
    }

    private PackageQuote assertAddOn(String code, String expectedAmount, String expectedTotal) {
        PackageQuote quote =
                quote(List.of(new DeliverableSlot("REEL", 1)), List.of(code), null, defaultPrefs());

        assertThat(quote.addOns()).hasSize(1);
        assertThat(quote.addOns().get(0).code()).isEqualTo(code);
        assertThat(quote.addOns().get(0).amountValue()).isEqualByComparingTo(expectedAmount);
        assertThat(quote.totalValue()).isEqualByComparingTo(expectedTotal);
        return quote;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService)
                .recordCreatorEvent(anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private void givenOwnDeals(String... rates) {
        List<Collaboration> deals =
                java.util.stream.IntStream.range(0, rates.length)
                        .mapToObj(i -> pricedDeal("01HWCOLLAB000000000000" + i, rates[i], daysAgo(10)))
                        .map(Collaboration.class::cast)
                        .toList();
        lenient().when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(deals);
    }

    private void givenBand(RateBandCandidateRow... rows) {
        lenient()
                .when(collaborationRepository.findRateBandCandidates("BEAUTY"))
                .thenReturn(List.of(rows));
    }

    private static Collaboration pricedDeal(String id, String rate, Instant updatedAt) {
        Collaboration collaboration = mock(Collaboration.class);
        lenient().when(collaboration.getId()).thenReturn(id);
        lenient().when(collaboration.getAgreedRate()).thenReturn(new BigDecimal(rate));
        lenient().when(collaboration.getStatus()).thenReturn(CollaborationStatus.COMPLETED);
        lenient().when(collaboration.getUpdatedAt()).thenReturn(updatedAt);
        return collaboration;
    }

    private static CreatorMetric metric(long followers, BigDecimal engagement) {
        return CreatorMetric.builder()
                .id("01HWMETRICRATEQUOTE0001")
                .creatorProfileId(PROFILE_ID)
                .platform("INSTAGRAM")
                .followers(followers)
                .avgEngagementRate(engagement)
                .dataSource("META_API")
                .time(Instant.now())
                .build();
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static RateBandCandidateRow bandRow(String rate, String workspaceId, String collaborationId) {
        return bandRowAt(rate, workspaceId, collaborationId, daysAgo(10));
    }

    private static RateBandCandidateRow bandRowAt(
            String rate, String workspaceId, String collaborationId, Instant updatedAt) {
        return new BandRow(new BigDecimal(rate), workspaceId, collaborationId, 3_000L, updatedAt);
    }

    private static RateBandCandidateRow bandRowTier(
            String rate, String workspaceId, String collaborationId, long followers) {
        return new BandRow(new BigDecimal(rate), workspaceId, collaborationId, followers, daysAgo(10));
    }

    /** A candidate row as the widened projection (B0-32) delivers it. */
    private record BandRow(
            BigDecimal rate,
            String workspaceId,
            String collaborationId,
            long followers,
            Instant updatedAt)
            implements RateBandCandidateRow {

        @Override
        public BigDecimal getAgreedRate() {
            return rate;
        }

        @Override
        public String getCurrency() {
            return "INR";
        }

        @Override
        public String getWorkspaceId() {
            return workspaceId;
        }

        @Override
        public String getCreatorId() {
            return "creator-" + collaborationId;
        }

        @Override
        public Long getTotalFollowers() {
            return followers;
        }

        @Override
        public Instant getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public String getCollaborationId() {
            return collaborationId;
        }
    }

    private static PreferencesResponse defaultPrefs() {
        return prefs(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000"), false);
    }

    private static PreferencesResponse prefs(
            BigDecimal reelFloor, BigDecimal storySetFloor, BigDecimal postFloor, boolean holdout) {
        return new PreferencesResponse(
                reelFloor,
                storySetFloor,
                postFloor,
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
                holdout,
                0,
                false);
    }

    private static BriefExtraction extraction(
            List<DeliverableLine> deliverables,
            BigDecimal budgetInr,
            boolean budgetStated,
            boolean usagePerpetual,
            Integer usageMonths,
            List<String> usageChannels,
            Integer exclusivityDays) {
        return new BriefExtraction(
                "Nykaa",
                "Serum",
                "BEAUTY",
                deliverables,
                budgetInr,
                budgetStated,
                false,
                null,
                null,
                usageMonths,
                usagePerpetual,
                usageChannels,
                exclusivityDays,
                null,
                List.of(),
                null,
                null,
                false,
                false,
                List.of(),
                null,
                false,
                List.of());
    }
}
