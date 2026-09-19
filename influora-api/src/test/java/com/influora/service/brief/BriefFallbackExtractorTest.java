package com.influora.service.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8 step 3), B0-41.
 *
 * <p>This class is the floor under an AI outage, so the tests that matter most are the ones proving it
 * declines to guess. A fallback that invents a budget is worse than no fallback at all: the creator
 * would price against a number the brand never wrote, and nothing downstream could tell the difference
 * — {@code RateQuoteService} reads {@code budget_inr} whenever {@code budget_stated} is true and does
 * not know which extractor set it.
 */
class BriefFallbackExtractorTest {

    private final BriefFallbackExtractor extractor = new BriefFallbackExtractor();

    @Test
    @DisplayName("reads the terms a real brief states")
    void readsARealBrief() {
        BriefExtraction e =
                extractor.extract(
                        "Hi! Glow Cosmetics here. We want 1 reel and 2 stories for our Vitamin C serum."
                                + " Budget is INR 8000, live by 2026-10-05. 60 days exclusivity, 6 months"
                                + " usage rights, 2 revisions, 50% advance.");

        assertTrue(e.budgetStated());
        assertEquals(new BigDecimal("8000"), e.budgetInr());
        assertEquals("2026-10-05", e.deadline());
        assertEquals(60, e.exclusivityDays());
        assertEquals("CATEGORY", e.exclusivityScope());
        assertEquals(6, e.usageMonths());
        assertEquals(2, e.maxRevisions());
        assertEquals("50% advance", e.paymentTerms());
        assertFalse(e.vagueDeliverables());
        assertEquals(2, e.deliverables().size());
        assertEquals("REEL", e.deliverables().get(0).type());
        assertEquals(1, e.deliverables().get(0).qty());
        assertEquals("STORY_SET", e.deliverables().get(1).type());
        assertEquals(2, e.deliverables().get(1).qty());
    }

    @Test
    @DisplayName("the first summary line always says the reading was rule-based")
    void labelsItsOwnOutput() {
        BriefExtraction e = extractor.extract("1 reel for our serum, INR 5000");
        assertTrue(e.summaryLines().get(0).startsWith(BriefFallbackExtractor.SUMMARY_PREFIX));
        assertTrue(e.summaryLines().size() >= 3);
        assertTrue(e.summaryLines().size() <= 5);
        assertTrue(e.summaryLines().stream().allMatch(line -> line.length() <= 120));
    }

    @Test
    @DisplayName("a bare number with no money marker is NOT a budget — follower counts are not fees")
    void doesNotInventABudgetFromAnyNumber() {
        BriefExtraction e =
                extractor.extract("We saw your 45000 followers and 120000 reach. Send us 1 reel!");

        assertFalse(e.budgetStated(), "no currency and no fee word anywhere in that text");
        assertNull(e.budgetInr());
        assertTrue(e.summaryLines().stream().anyMatch(line -> line.contains("No budget found")));
    }

    @Test
    @DisplayName("a figure below 100 is noise, not a fee")
    void ignoresImplausiblySmallFigures() {
        BriefExtraction e = extractor.extract("Budget 5 for 1 reel");
        assertFalse(e.budgetStated());
        assertNull(e.budgetInr());
    }

    @Test
    @DisplayName(
            "k / lakh / crore units scale, the scale is plain (never 8E+3 on a creator's screen), and the"
                    + " largest marked figure wins over a per-unit rate")
    void scalesUnitsAndPrefersThePackageTotal() {
        assertEquals(new BigDecimal("120000"), extractor.extract("Budget 1.2 lakh").budgetInr());
        assertEquals(new BigDecimal("20000"), extractor.extract("fee of 20k").budgetInr());
        // Rs 5000 per reel, Rs 15000 for the package — the creator is negotiating the package.
        assertEquals(
                new BigDecimal("15000"),
                extractor.extract("Rs 5000 per reel, Rs 15000 for all three").budgetInr());
    }

    @Test
    @DisplayName("barter with no cash figure is barter, and the mrp lands on barter_mrp not budget")
    void barter() {
        BriefExtraction e =
                extractor.extract("This is a barter collab — you keep the product in exchange for 1 reel.");
        assertTrue(e.barterOnly());
        assertFalse(e.budgetStated());
        assertNull(e.budgetInr());
    }

    @Test
    @DisplayName(
            "F-1773 / K-2c (RULINGS-U-0917.md round 6, \"New: F-1772\"): off_platform_payment_hint is"
                    + " always false on FALLBACK -- the rules' own text check runs on the same raw text"
                    + " instead, see BriefFallbackExtractorRealRiskRulesTest for the end-to-end proof")
    void offPlatformPaymentHintIsAlwaysFalse() {
        assertFalse(extractor.extract("We'll send it on UPI directly").offPlatformPaymentHint());
        // Round 5 Ruling 1 measured this exact text ("Bank transfer within 7 days") as an on-platform
        // payment-terms sentence, not an off-platform ask; the extractor's own pattern used to pin it
        // as a positive hint anyway (F-1773's symptom). It must stay false, same as everything else.
        assertFalse(extractor.extract("Bank transfer within 7 days").offPlatformPaymentHint());
        assertFalse(
                extractor.extract("Payment through the platform, 50% advance").offPlatformPaymentHint());
        assertFalse(
                extractor.extract("pay you directly via Google Pay, outside the platform")
                        .offPlatformPaymentHint(),
                "even wording the rules' own text check would catch must not set the extractor's hint");
    }

    @Test
    @DisplayName(
            "F-1773 / K-2c: disclosure_hidden_hint is always false on FALLBACK -- the rules' own text"
                    + " check runs on the same raw text instead")
    void disclosureHiddenHintIsAlwaysFalse() {
        assertFalse(extractor.extract("Please don't use #ad on this one").disclosureHiddenHint());
        assertFalse(extractor.extract("No paid partnership label please").disclosureHiddenHint());
        assertFalse(extractor.extract("Keep it looking organic").disclosureHiddenHint());
        assertFalse(
                extractor.extract("Please add #ad and the paid partnership tag").disclosureHiddenHint());
        // F-1773's own symptom: an ordinary ad caption the extractor's old pattern used to flag.
        assertFalse(extractor.extract("Caption: Loving my new TECNO #ad").disclosureHiddenHint());
    }

    @Test
    @DisplayName("perpetual usage suppresses usage_months rather than reporting both")
    void perpetualUsage() {
        BriefExtraction e = extractor.extract("Usage in perpetuity across all channels. 1 reel.");
        assertTrue(e.usagePerpetual());
        assertNull(e.usageMonths(), "perpetual and a month count are contradictory");
    }

    @Test
    @DisplayName("exclusivity in months or weeks is normalised to days")
    void exclusivityNormalisedToDays() {
        assertEquals(90, extractor.extract("3 months exclusivity in the category").exclusivityDays());
        assertEquals(14, extractor.extract("exclusivity for 2 weeks").exclusivityDays());
    }

    @Test
    @DisplayName("exclusivity with no duration is still recorded as exclusivity, without a made-up number")
    void exclusivityWithoutADuration() {
        BriefExtraction e = extractor.extract("We need category exclusivity. 1 reel.");
        assertNull(e.exclusivityDays());
        assertEquals("NAMED_BRANDS", e.exclusivityScope());
    }

    @Test
    @DisplayName("a brief naming no deliverable is marked vague rather than guessed at")
    void vagueDeliverables() {
        BriefExtraction e = extractor.extract("Hi, we would love to collaborate with you!");
        assertTrue(e.deliverables().isEmpty());
        assertTrue(e.vagueDeliverables());
        assertNotNull(e.summaryLines());
    }

    @Test
    @DisplayName("never returns null and never throws, on null, empty or nonsense input")
    void isTotal() {
        for (String input : new String[] {null, "", "   ", " ", "₹₹₹", "1".repeat(5000)}) {
            BriefExtraction e = extractor.extract(input);
            assertNotNull(e);
            assertNotNull(e.summaryLines());
            assertTrue(e.summaryLines().size() >= 3);
        }
    }

    @Test
    @DisplayName("leaves the judgement fields alone — a regex cannot read a brand name, a category or a claim")
    void declinesTheFieldsItCannotDo() {
        BriefExtraction e =
                extractor.extract("Glow Cosmetics wants a clinically proven serum reel. INR 8000.");
        assertNull(e.brandName(), "a guessed brand name is a wrong name on the creator's screen");
        assertNull(e.category());
        assertNull(e.regulatedCategory());
        assertTrue(e.claims().isEmpty());
        assertTrue(
                e.usageChannels().isEmpty(),
                "assuming ORGANIC when nothing was stated would price the usage rider at zero");
    }

    @Test
    @DisplayName("deterministic — the same brief reads the same way twice")
    void isDeterministic() {
        String brief = "2 reels and 1 static post, budget INR 22000, 30 days exclusivity";
        BriefExtraction first = extractor.extract(brief);
        BriefExtraction second = extractor.extract(brief);
        assertEquals(first.budgetInr(), second.budgetInr());
        assertEquals(first.deliverables(), second.deliverables());
        assertEquals(first.summaryLines(), second.summaryLines());
    }
}
