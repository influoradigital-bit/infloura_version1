package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.BrandProfile;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.service.AuditLogService;
import com.influora.service.meera.BrandContextAssembler;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Two things are under test here and they must not be confused.
 *
 * <p><b>C1 (Kabir P1-B re-audit, 2026-07-21)</b> — {@code calculate_budget} must be
 * provenance-aware WITHOUT trusting the model's tool-call input for that provenance. The executor
 * re-derives {@code price_source} by matching {@code product_price} against the workspace's
 * persisted {@link BrandProfile#getProductCatalogJson()}. Those tests are unchanged by P1-12 and
 * are kept verbatim so a regression in the guard is still caught.
 *
 * <p><b>P1-12 (2026-09-13)</b> — the per-creator rate must come from the real niche rate band or
 * not be quoted at all. The live defect: a ₹5,300 product with goal {@code review} produced ₹318
 * per creator (5,300 × 0.06), which no Indian creator accepts for a hands-on review.
 *
 * <p><b>The NULL-BAND tests below are the primary ones, not the edge cases.</b> The k-anonymity
 * floor requires 5 distinct creators AND 5 distinct workspaces of completed collaborations inside
 * ONE follower tier of ONE niche; the platform does not have that for most niches today, and
 * {@code niche_tags} itself is usually absent because {@code analyze_site} has never succeeded in
 * production. So the null band is what essentially every real brand hits. A suite that only
 * proved the populated-band path would "fix" P1-12 while every live brand kept getting 6% of a
 * guess — which is why {@link #testNullBandRefusesToQuoteTheLiveDefectScenario} is first.
 *
 * <p>{@link BrandContextAssembler} is used REAL here, never mocked: the k-anonymity floor is the
 * thing that decides which path runs, and mocking it away would make every null-band assertion a
 * statement about the mock rather than about the floor.
 */
class CalculateBudgetExecutorTest {

    private AuditLogService auditLogService;
    private BrandProfileRepository brandProfileRepository;
    private CollaborationRepository collaborationRepository;
    private CalculateBudgetExecutor executor;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        brandProfileRepository = mock(BrandProfileRepository.class);
        collaborationRepository = mock(CollaborationRepository.class);
        executor =
                new CalculateBudgetExecutor(
                        auditLogService,
                        brandProfileRepository,
                        collaborationRepository,
                        // Real, not a mock — see class javadoc.
                        new BrandContextAssembler());
        when(brandProfileRepository.findByWorkspaceId(any())).thenReturn(Optional.empty());
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private BrandProfile profile;

    private void withCatalog(String catalogJson) {
        ensureProfile();
        when(profile.getProductCatalogJson()).thenReturn(catalogJson);
    }

    private void withNiche(String niche) {
        ensureProfile();
        when(profile.getNicheTagsJson()).thenReturn("[\"" + niche + "\"]");
    }

    private void ensureProfile() {
        if (profile == null) {
            profile = Mockito.mock(BrandProfile.class);
            when(brandProfileRepository.findByWorkspaceId("ws1")).thenReturn(Optional.of(profile));
        }
    }

    /** One candidate row. {@code followers} decides the tier bucket the row is grouped into. */
    private static RateBandCandidateRow row(
            String rate, String workspaceId, String creatorId, long followers) {
        RateBandCandidateRow r = mock(RateBandCandidateRow.class);
        when(r.getAgreedRate()).thenReturn(new BigDecimal(rate));
        when(r.getCurrency()).thenReturn("INR");
        when(r.getWorkspaceId()).thenReturn(workspaceId);
        when(r.getCreatorId()).thenReturn(creatorId);
        when(r.getTotalFollowers()).thenReturn(followers);
        return r;
    }

    /**
     * A band that CLEARS the floor: 5 distinct creators, 5 distinct workspaces, all at 20k
     * followers so they land in one MICRO bucket. Rates 8000/10000/12000/15000/20000 → median
     * 12000, min 8000, max 20000.
     */
    private void withQualifyingBand(String niche) {
        withNiche(niche);
        List<RateBandCandidateRow> rows =
                List.of(
                        row("8000", "wsA", "crA", 20_000L),
                        row("10000", "wsB", "crB", 20_000L),
                        row("12000", "wsC", "crC", 20_000L),
                        row("15000", "wsD", "crD", 20_000L),
                        row("20000", "wsE", "crE", 20_000L));
        when(collaborationRepository.findRateBandCandidates(niche)).thenReturn(rows);
    }

    /** A pool that FAILS the floor: only 4 distinct creators / 4 distinct workspaces. */
    private void withBelowFloorBand(String niche) {
        withNiche(niche);
        List<RateBandCandidateRow> rows = new ArrayList<>();
        rows.add(row("8000", "wsA", "crA", 20_000L));
        rows.add(row("10000", "wsB", "crB", 20_000L));
        rows.add(row("12000", "wsC", "crC", 20_000L));
        rows.add(row("15000", "wsD", "crD", 20_000L));
        when(collaborationRepository.findRateBandCandidates(niche)).thenReturn(rows);
    }

    // -----------------------------------------------------------------------------------------
    // P1-12 PRIMARY — the null-band path, which is what production actually runs
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "PRIMARY: the exact live defect — ₹5,300 product, goal 'review', no qualifying band ->"
                    + " NO number is quoted at all (pre-fix this returned ₹318/creator, ₹1,590 pool)")
    void testNullBandRefusesToQuoteTheLiveDefectScenario() {
        withBelowFloorBand("kitchen-appliances");
        Map<String, Object> input = Map.of("product_price", "5300", "goal", "review");

        CalculateBudgetResult result = executor.execute("ws1", input);

        // Pre-fix these were 318.00 and 1590.00. The point of the refusal is that they are ABSENT,
        // not that they are zero — a zero renders as "₹0" and is just a different wrong number.
        assertNull(result.suggestedPerCreatorRate(), "must not quote a per-creator rate with no band");
        assertNull(result.suggestedPoolTotal(), "must not quote a pool total with no band");
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertNull(result.perCreatorRateMin());
        assertNull(result.perCreatorRateMax());
        assertNull(result.rateSampleSize());
        assertNull(result.rateNiche());
        assertTrue(
                result.rationale().contains("NO RATE QUOTED"),
                "Meera must be told not to invent a number, not merely hedged");
        assertTrue(
                result.rationale().contains("usually pay"),
                "the refusal must carry the question to ask instead");
        assertFalse(
                result.rationale().contains("318"),
                "the old percentage figure must not survive anywhere in the payload");
        assertFalse(
                result.rationale().contains("% of product price"),
                "a percentage of the product price is not a creator rate and must not be described"
                        + " as one");
    }

    @Test
    @DisplayName(
            "PRIMARY: brand has no niche_tags (analyze_site never ran) -> refuses, and the"
                    + " cross-tenant rate query is never executed speculatively")
    void testNoNicheNeverRunsCrossTenantQueryAndStillRefuses() {
        withCatalog("[{\"name\":\"Widget\",\"price\":4000,\"price_source\":\"scraped\"}]");
        // getNicheTagsJson() is left unstubbed -> null, which is the live state today.
        Map<String, Object> input = Map.of("product_price", "4000", "goal", "review");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertNull(result.suggestedPerCreatorRate());
        assertNull(result.suggestedPoolTotal());
        // The most security-sensitive query in the design must not run when there is no niche to
        // run it for. verifyNoInteractions, not verify(never()) with a matcher: `anyString()` is
        // type-matching and does NOT match null, so a never()-verify written with it would pass
        // vacuously against code that called findRateBandCandidates(null).
        verifyNoInteractions(collaborationRepository);
    }

    @Test
    @DisplayName("no completed collaborations in the niche at all -> refuses, no number")
    void testEmptyCandidatePoolRefuses() {
        withNiche("kitchen-appliances");
        when(collaborationRepository.findRateBandCandidates("kitchen-appliances"))
                .thenReturn(List.of());
        Map<String, Object> input = Map.of("product_price", "5300", "goal", "review");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertNull(result.suggestedPerCreatorRate());
    }

    @Test
    @DisplayName(
            "P1-12(d): a CONFIRMED scraped price does not buy back a quote — with no band there is"
                    + " still no number, because the price was never a valid basis for one")
    void testScrapedPriceStillRefusesWithoutABand() {
        withCatalog("[{\"name\":\"Rice Cooker\",\"price\":4000,\"price_source\":\"scraped\"}]");
        withBelowFloorBand("kitchen-appliances");
        Map<String, Object> input = Map.of("product_price", "4000", "goal", "review");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("scraped", result.priceConfidence());
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertNull(result.suggestedPerCreatorRate(), "accurate price, still no basis for a rate");
        // Pre-fix this was 4000 × 0.06 = 240.00 — a MORE accurate price producing a MORE absurd
        // quote, which is the reason the multiplier had to go rather than be refined.
        assertFalse(result.rationale().contains("240"));
    }

    @Test
    @DisplayName("refusal is audit-logged as rate_basis=insufficient_data")
    void testRefusalIsAuditLogged() {
        withBelowFloorBand("kitchen-appliances");
        executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        verify(auditLogService)
                .recordToolCall(
                        "ws1",
                        "calculate_budget",
                        "R",
                        AuditLogService.OUTCOME_ALLOWED,
                        null,
                        null,
                        null,
                        Map.of(
                                "advisory",
                                true,
                                "price_confidence",
                                "inferred",
                                "rate_basis",
                                "insufficient_data"));
    }

    // -----------------------------------------------------------------------------------------
    // P1-12 — the populated-band path
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "band present -> per-creator rate is the MEDIAN of real completed-collaboration rates"
                    + " (₹12,000), not 6% of the product price (₹318)")
    void testBandPresentQuotesMedianNotPercentageOfProductPrice() {
        withQualifyingBand("kitchen-appliances");
        Map<String, Object> input = Map.of("product_price", "5300", "goal", "review");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals(CalculateBudgetExecutor.RATE_BASIS_BAND, result.rateBasis());
        assertEquals(0, new BigDecimal("12000").compareTo(result.suggestedPerCreatorRate()));
        assertEquals(0, new BigDecimal("60000").compareTo(result.suggestedPoolTotal()));
        assertEquals(0, new BigDecimal("8000").compareTo(result.perCreatorRateMin()));
        assertEquals(0, new BigDecimal("20000").compareTo(result.perCreatorRateMax()));
        assertEquals(Integer.valueOf(5), result.rateSampleSize());
        assertEquals("kitchen-appliances", result.rateNiche());
        assertEquals("INR", result.currency());
    }

    @Test
    @DisplayName(
            "the goal no longer scales the number — 'review' and 'conversion' return the SAME rate"
                    + " (pre-fix: 318 vs 795, a 2.5x swing driven by nothing real)")
    void testGoalDoesNotScaleTheBandRate() {
        withQualifyingBand("kitchen-appliances");

        CalculateBudgetResult review =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));
        CalculateBudgetResult conversion =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "conversion"));

        assertEquals(
                0,
                review.suggestedPerCreatorRate().compareTo(conversion.suggestedPerCreatorRate()),
                "goal may move Meera WITHIN the range in prose, but must not multiply the figure");
    }

    @Test
    @DisplayName(
            "the product price no longer moves the number — ₹4,000 and ₹50,000 return the SAME"
                    + " per-creator rate, because a creator's rate is not a property of the product")
    void testProductPriceDoesNotMoveTheQuote() {
        withQualifyingBand("kitchen-appliances");

        CalculateBudgetResult cheap =
                executor.execute("ws1", Map.of("product_price", "4000", "goal", "review"));
        CalculateBudgetResult expensive =
                executor.execute("ws1", Map.of("product_price", "50000", "goal", "review"));

        assertEquals(
                0, cheap.suggestedPerCreatorRate().compareTo(expensive.suggestedPerCreatorRate()));
        assertEquals(0, cheap.suggestedPoolTotal().compareTo(expensive.suggestedPoolTotal()));
    }

    @Test
    @DisplayName(
            "rationale states the UNIT: agreed_rate is a whole-collaboration figure per creator, so"
                    + " Meera is explicitly forbidden from calling it a per-reel rate")
    void testRationaleForbidsThePerReelReading() {
        withQualifyingBand("kitchen-appliances");

        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        assertTrue(result.rationale().contains("whole-collaboration"));
        assertTrue(result.rationale().contains("per-reel"));
        assertTrue(result.rationale().contains("NOT a"));
        assertTrue(result.rationale().contains("MEDIAN"));
        assertTrue(result.rationale().contains("RANGE"));
    }

    @Test
    @DisplayName("band-backed quote is audit-logged as rate_basis=platform_rate_band")
    void testBandQuoteIsAuditLogged() {
        withQualifyingBand("kitchen-appliances");
        executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        verify(auditLogService)
                .recordToolCall(
                        "ws1",
                        "calculate_budget",
                        "R",
                        AuditLogService.OUTCOME_ALLOWED,
                        null,
                        null,
                        null,
                        Map.of(
                                "advisory",
                                true,
                                "price_confidence",
                                "inferred",
                                "rate_basis",
                                "platform_rate_band"));
    }

    @Test
    @DisplayName(
            "k-anonymity is enforced by the REAL assembler, not by this test: 4 distinct creators"
                    + " refuses, the same pool plus a 5th qualifies")
    void testKAnonFloorIsWhatDecidesBetweenTheTwoPaths() {
        withBelowFloorBand("kitchen-appliances");
        CalculateBudgetResult below =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, below.rateBasis());

        withQualifyingBand("kitchen-appliances");
        CalculateBudgetResult atFloor =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_BAND, atFloor.rateBasis());
    }

    // -----------------------------------------------------------------------------------------
    // C1 (Kabir P1-B re-audit) — UNCHANGED by P1-12, kept verbatim
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "DECISIVE: model tool-call would have claimed \"scraped\" (field simply doesn't exist in"
                    + " schema anymore) but persisted catalog says \"inferred\" -> priceConfidence"
                    + " \"inferred\" with caveat; the model has zero influence")
    void testServerCatalogInferredWinsRegardlessOfModelIntent() {
        withCatalog(
                "[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\",\"price_source\":\"inferred\"}]");
        // The tool input carries no price_source at all -- it is not part of the schema. Even if a
        // legacy/malicious caller stuffed a "price_source":"scraped" key into the input map (as if
        // trying to exploit the old defeat), the executor must never read it.
        Map<String, Object> input =
                Map.of("product_price", "500", "goal", "launch", "price_source", "scraped");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
        // No band is stubbed here, so this is the REFUSAL path, where P1-12b replaced the
        // "phrase it as based on an estimated price" hedge with an outright ban on saying the
        // price. C1's actual claim -- the model cannot talk us into "scraped" -- is the
        // priceConfidence assertion above; this one pins that the provenance caveat is still
        // present and still says the price is not confirmed.
        assertTrue(result.rationale().contains("NOT a confirmed price"), result.rationale());
        assertTrue(result.rationale().contains("do NOT state that price"), result.rationale());
    }

    @Test
    @DisplayName("persisted catalog says scraped -> priceConfidence \"scraped\", no estimate caveat")
    void testServerCatalogScrapedYieldsScrapedConfidence() {
        withCatalog(
                "[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\",\"price_source\":\"scraped\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("scraped", result.priceConfidence());
        assertTrue(!result.rationale().contains("ESTIMATE"));
    }

    @Test
    @DisplayName("fail-safe: product price not found in persisted catalog -> priceConfidence defaults"
            + " to \"inferred\", never \"scraped\"")
    void testProductNotInCatalogDefaultsToInferred() {
        withCatalog(
                "[{\"name\":\"Other Widget\",\"price\":999,\"currency\":\"INR\",\"price_source\":\"scraped\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: no BrandProfile for workspace at all -> priceConfidence defaults to"
            + " \"inferred\"")
    void testNoBrandProfileDefaultsToInferred() {
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: BrandProfile exists but catalog JSON is blank/null -> priceConfidence"
            + " defaults to \"inferred\"")
    void testBlankCatalogDefaultsToInferred() {
        withCatalog(null);
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName("fail-safe: catalog entry missing price_source entirely -> priceConfidence defaults"
            + " to \"inferred\"")
    void testCatalogEntryMissingPriceSourceDefaultsToInferred() {
        withCatalog("[{\"name\":\"Widget\",\"price\":500,\"currency\":\"INR\"}]");
        Map<String, Object> input = Map.of("product_price", "500", "goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
    }

    @Test
    @DisplayName(
            "no product_price supplied -> priceConfidence still resolves, and with no band there is"
                    + " still no number (pre-fix this returned BigDecimal.ZERO, i.e. \"₹0\")")
    void testNoProductPriceStillResolvesPriceConfidenceAndQuotesNothing() {
        Map<String, Object> input = Map.of("goal", "launch");

        CalculateBudgetResult result = executor.execute("ws1", input);

        assertEquals("inferred", result.priceConfidence());
        assertNull(result.suggestedPerCreatorRate());
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertTrue(result.rationale().contains("NO RATE QUOTED"));
    }

    @Test
    @DisplayName(
            "P1-12b: on the refusal path the rationale FORBIDS stating the price, and never hands"
                    + " the model the \"phrase this as based on an estimated price\" wording")
    void testRefusalPathForbidsSpeakingThePriceAtAll() {
        // The live prose opened "based on an estimated price around Rs 5,300, I'd put roughly
        // Rs 1,600 across five creators at about Rs 320 each". P1-12 removed the two rate figures
        // from the wire, but the price clause came from THIS string -- and Rs 5,300 was the
        // model's own tool argument, since analyze_site has never populated a real catalog.
        // Telling the model to hedge a number it invented is what made the guess sound sourced.
        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertEquals("inferred", result.priceConfidence());
        assertTrue(result.rationale().contains("quote NO rupee figure"), result.rationale());
        // The exact phrase the model echoed to the brand must not appear on this path.
        assertTrue(
                !result.rationale().contains("based on an estimated price"), result.rationale());
    }

    @Test
    @DisplayName(
            "P1-12b: a scraped price on the refusal path adds no caveat either -- the ban rides on"
                    + " the C1 branch, so this documents the gap a future scraped catalog opens")
    void testRefusalPathWithScrapedPriceStillQuotesNoRate() {
        withCatalog(
                "[{\"name\":\"Cooker\",\"price\":4000,\"currency\":\"INR\","
                        + "\"price_source\":\"scraped\"}]");

        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "4000", "goal", "review"));

        assertEquals(CalculateBudgetExecutor.RATE_BASIS_INSUFFICIENT, result.rateBasis());
        assertEquals("scraped", result.priceConfidence());
        // A real scraped price MAY be spoken; the rate still may not be.
        assertTrue(result.rationale().contains("NO RATE QUOTED"), result.rationale());
        assertNull(result.suggestedPerCreatorRate());
        assertNull(result.suggestedPoolTotal());
    }

    @Test
    @DisplayName(
            "a band does NOT suppress the C1 estimate caveat: the price is still a guess even when"
                    + " the rate is real, and Meera may still mention the price")
    void testEstimateCaveatSurvivesABandBackedQuote() {
        withQualifyingBand("kitchen-appliances");
        // no catalog -> price provenance is "inferred"
        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        assertEquals("inferred", result.priceConfidence());
        assertTrue(result.rationale().contains("ESTIMATE"));
        assertEquals(CalculateBudgetExecutor.RATE_BASIS_BAND, result.rateBasis());
    }

    @Test
    @DisplayName(
            "WIRE SHAPE: with no band the serialized payload contains NO money key at all — not a"
                    + " zero, not a null — and carries rateBasis so the frontend can say why")
    void testRefusalWireShapeOmitsEveryMoneyField() throws Exception {
        withBelowFloorBand("kitchen-appliances");
        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        // The FE consumes exactly this, and @JsonInclude(NON_NULL) is what makes the absence real.
        // Asserting on the JSON rather than on the record is deliberate: a null field that still
        // serialized as `"suggestedPerCreatorRate": null` would let `formatINR(null)` render "₹0"
        // — a different wrong number, reached without any test noticing.
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);

        assertFalse(json.contains("suggestedPerCreatorRate"), json);
        assertFalse(json.contains("suggestedPoolTotal"), json);
        assertFalse(json.contains("perCreatorRateMin"), json);
        assertFalse(json.contains("perCreatorRateMax"), json);
        assertTrue(json.contains("\"rateBasis\":\"insufficient_data\""), json);
        // suggestedCreatorCount is a primitive int, so it is always present — it is the only field
        // isCalculateBudgetPayload (src/lib/meera-api.ts) can key on across BOTH paths.
        assertTrue(json.contains("\"suggestedCreatorCount\":5"), json);
        // priceConfidence is the name Jackson writes (no @JsonProperty, no naming strategy) and is
        // therefore the name the persona must reference — NOT price_source or price_confidence.
        assertTrue(json.contains("\"priceConfidence\""), json);
    }

    @Test
    @DisplayName("WIRE SHAPE: a band-backed quote carries the range fields the card renders")
    void testQuotedWireShapeCarriesTheRange() throws Exception {
        withQualifyingBand("kitchen-appliances");
        CalculateBudgetResult result =
                executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);

        assertTrue(json.contains("\"rateBasis\":\"platform_rate_band\""), json);
        assertTrue(json.contains("\"perCreatorRateMin\":8000"), json);
        assertTrue(json.contains("\"perCreatorRateMax\":20000"), json);
        assertTrue(json.contains("\"rateSampleSize\":5"), json);
        assertTrue(json.contains("\"rateNiche\":\"kitchen-appliances\""), json);
    }

    /** Guards against a future refactor reintroducing anyString()-matched null. */
    @Test
    @DisplayName("sanity: findRateBandCandidates is called with the real niche string, never null")
    void testNicheIsPassedThroughVerbatim() {
        withQualifyingBand("kitchen-appliances");
        executor.execute("ws1", Map.of("product_price", "5300", "goal", "review"));

        verify(collaborationRepository).findRateBandCandidates("kitchen-appliances");
        verify(collaborationRepository, Mockito.never()).findRateBandCandidates(null);
        verify(collaborationRepository, Mockito.atMostOnce()).findRateBandCandidates(anyString());
    }
}
