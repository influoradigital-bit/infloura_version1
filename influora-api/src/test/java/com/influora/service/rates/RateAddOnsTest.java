package com.influora.service.rates;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.2, B0-30). */
class RateAddOnsTest {

    private static final BigDecimal PACKAGE = new BigDecimal("10000.00");

    private final RateAddOns addOns =
            new RateAddOns(
                    RateAddOns.REPOST_30D_PCT_DEFAULT,
                    RateAddOns.PAID_ADS_QUARTER_PCT_DEFAULT,
                    RateAddOns.WHITELISTING_PCT_DEFAULT,
                    RateAddOns.PERPETUITY_MULTIPLE_DEFAULT,
                    RateAddOns.EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT);

    @Test
    @DisplayName("4.2 - the provenance string is EXACTLY the one the spec fixes")
    void provenanceStringIsExact() {
        assertThat(RateAddOns.PROVENANCE_BENCHMARK).isEqualTo("benchmark, not market data");
    }

    @Test
    @DisplayName("4.2 - REPOST_30D is +25% of the package")
    void repost30d() {
        RateAddOns.AddOn addOn = addOns.compute(RateAddOns.REPOST_30D, PACKAGE).orElseThrow();
        assertThat(addOn.amount()).isEqualByComparingTo("2500.00");
        assertThat(addOn.label()).isEqualTo("Brand repost rights, 30 days");
        assertThat(addOn.basis()).isEqualTo("+25% of package");
    }

    @Test
    @DisplayName("4.2 - PAID_ADS_QUARTER is +40% of the package, per quarter")
    void paidAdsQuarter() {
        RateAddOns.AddOn addOn = addOns.compute(RateAddOns.PAID_ADS_QUARTER, PACKAGE).orElseThrow();
        assertThat(addOn.amount()).isEqualByComparingTo("4000.00");
        assertThat(addOn.label()).isEqualTo("Paid ads usage, per quarter");
        assertThat(addOn.basis()).isEqualTo("+40% of package per quarter");
    }

    @Test
    @DisplayName("4.2 - WHITELISTING is +75% of the package")
    void whitelisting() {
        RateAddOns.AddOn addOn = addOns.compute(RateAddOns.WHITELISTING, PACKAGE).orElseThrow();
        assertThat(addOn.amount()).isEqualByComparingTo("7500.00");
        assertThat(addOn.basis()).isEqualTo("+75% of package");
    }

    /**
     * The one add-on whose arithmetic is easy to get wrong. "2.5x package" means the PACKAGE costs
     * 2.5x, so the add-on LINE is the 1.5x increment. Charging 2.5x as the line would total 3.5x —
     * see {@link RateAddOns}'s class javadoc.
     */
    @Test
    @DisplayName("4.2 - PERPETUITY charges the increment, so the package lands at exactly 2.5x")
    void perpetuityIsTheIncrementNotTheMultiple() {
        RateAddOns.AddOn addOn = addOns.compute(RateAddOns.PERPETUITY, PACKAGE).orElseThrow();
        assertThat(addOn.amount()).isEqualByComparingTo("15000.00");
        assertThat(PACKAGE.add(addOn.amount()))
                .as("package + perpetuity line must equal 2.5 x package, not 3.5 x")
                .isEqualByComparingTo("25000.00");
        assertThat(addOn.basis()).isEqualTo("2.5x package");
    }

    @Test
    @DisplayName("4.2 - EXCLUSIVITY_30D ships its 15% floor, and the basis says the lost-income leg is a floor")
    void exclusivity30d() {
        RateAddOns.AddOn addOn = addOns.compute(RateAddOns.EXCLUSIVITY_30D, PACKAGE).orElseThrow();
        assertThat(addOn.amount()).isEqualByComparingTo("1500.00");
        assertThat(addOn.basis())
                .isEqualTo("lost income = median monthly category earnings, floor 15% of package");
    }

    @Test
    @DisplayName("4.2 - an unrecognised code drops its line rather than failing the whole quote")
    void unknownCodeIsEmpty() {
        assertThat(addOns.compute("PODCAST_RIGHTS", PACKAGE)).isEmpty();
        assertThat(addOns.compute(null, PACKAGE)).isEmpty();
        assertThat(addOns.compute(RateAddOns.REPOST_30D, null)).isEmpty();
    }

    @Test
    @DisplayName("4.2 - codes are matched case-insensitively and trimmed")
    void codesAreNormalised() {
        assertThat(addOns.compute("  repost_30d  ", PACKAGE).orElseThrow().code())
                .isEqualTo(RateAddOns.REPOST_30D);
    }

    /**
     * An admin override must move both the charge AND the label. A basis string frozen at "+25%"
     * while the component charges 30% is a lie the creator repeats to a brand.
     */
    @Test
    @DisplayName("4.2 - an admin override changes the basis string too, never just the number")
    void overrideMovesTheLabelWithTheCharge() {
        RateAddOns overridden =
                new RateAddOns(
                        new BigDecimal("30"),
                        RateAddOns.PAID_ADS_QUARTER_PCT_DEFAULT,
                        RateAddOns.WHITELISTING_PCT_DEFAULT,
                        new BigDecimal("3"),
                        RateAddOns.EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT);

        RateAddOns.AddOn repost = overridden.compute(RateAddOns.REPOST_30D, PACKAGE).orElseThrow();
        assertThat(repost.amount()).isEqualByComparingTo("3000.00");
        assertThat(repost.basis()).isEqualTo("+30% of package");

        RateAddOns.AddOn perpetuity = overridden.compute(RateAddOns.PERPETUITY, PACKAGE).orElseThrow();
        assertThat(perpetuity.amount()).isEqualByComparingTo("20000.00");
        assertThat(perpetuity.basis()).isEqualTo("3x package");
    }

    @Test
    @DisplayName("4.2 - all five codes are enumerated in CODES, in the spec's table order")
    void codesListMatchesTheSpecTable() {
        assertThat(RateAddOns.CODES)
                .containsExactly(
                        "REPOST_30D",
                        "PAID_ADS_QUARTER",
                        "WHITELISTING",
                        "PERPETUITY",
                        "EXCLUSIVITY_30D");
        assertThat(RateAddOns.CODES).allSatisfy(code -> assertThat(addOns.compute(code, PACKAGE)).isPresent());
    }
}
