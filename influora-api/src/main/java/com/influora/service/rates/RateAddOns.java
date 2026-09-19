package com.influora.service.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.2, B0-30) — the five usage/exclusivity add-ons and what
 * each one costs on top of a package.
 *
 * <p><b>These are benchmarks, not market data.</b> The percentages are the midpoints of the ranges
 * the plan gave (20-30, 25-50, 50-100, 2-3x). Nobody on this team has a closed deal proving a
 * whitelisting rider costs 75% of a package. {@link #PROVENANCE_BENCHMARK} is the exact string
 * SPEC.md &sect;4.2 requires every constant-derived figure to carry, and it is the same string
 * &sect;4.3 step 1c stamps on a benchmark unit price.
 *
 * <p><b>Deliberately a plain {@code @Value}-injected {@code @Component}, not
 * {@code @ConfigurationProperties}</b> — same reason as {@link
 * com.influora.config.MeeraCreatorFeatureProperties}, whose javadoc records the incident: a
 * {@code @ConfigurationProperties} class compiled fine, was never added to the
 * {@code @EnableConfigurationProperties} list, and so could not be constructed at all, crashing
 * boot for every bean that depended on it. Five scalars are not worth that failure mode.
 *
 * <p><b>{@link #PERPETUITY} is a package MULTIPLE, not a percentage — read this before changing
 * it.</b> &sect;4.2's basis column says "2.5x package", and &sect;4.3 step 5 totals as
 * {@code subtotal - discount + addOns}. Charging {@code 2.5 x subtotal} as the add-on line would
 * make the package cost 3.5x, which is not what "perpetual usage is 2.5x" means anywhere in the
 * plan. The add-on line is therefore the INCREMENT, {@code (multiple - 1) x subtotal}, so the
 * quoted package lands at exactly {@code multiple x subtotal}. {@link #PERPETUITY_MULTIPLE_DEFAULT}
 * is the multiple, never the increment.
 *
 * <p><b>{@link #EXCLUSIVITY_30D} ships with only its floor leg.</b> &sect;4.2 defines it as
 * "lost income = median monthly category earnings, floor 15% of package". There is no median
 * monthly category earnings figure anywhere in this schema — it would need per-creator monthly
 * earnings aggregated by category, which no table holds — so B0 charges the floor and the basis
 * string says so honestly rather than pretending the lost-income leg was computed and came out
 * low.
 */
@Component
public class RateAddOns {

    /** SPEC.md &sect;4.2 — the exact provenance string for any figure derived from these constants. */
    public static final String PROVENANCE_BENCHMARK = "benchmark, not market data";

    public static final String REPOST_30D = "REPOST_30D";
    public static final String PAID_ADS_QUARTER = "PAID_ADS_QUARTER";
    public static final String WHITELISTING = "WHITELISTING";
    public static final String PERPETUITY = "PERPETUITY";
    public static final String EXCLUSIVITY_30D = "EXCLUSIVITY_30D";

    /** The five codes in SPEC.md &sect;4.2's table order — the order they render in a quote. */
    public static final List<String> CODES =
            List.of(REPOST_30D, PAID_ADS_QUARTER, WHITELISTING, PERPETUITY, EXCLUSIVITY_30D);

    // Compiled defaults. Each is also the yml placeholder default below, so an unset key and an
    // absent yml block both land here; RateAddOnsTest pins the two together.
    public static final BigDecimal REPOST_30D_PCT_DEFAULT = new BigDecimal("25");
    public static final BigDecimal PAID_ADS_QUARTER_PCT_DEFAULT = new BigDecimal("40");
    public static final BigDecimal WHITELISTING_PCT_DEFAULT = new BigDecimal("75");
    public static final BigDecimal PERPETUITY_MULTIPLE_DEFAULT = new BigDecimal("2.5");
    public static final BigDecimal EXCLUSIVITY_30D_FLOOR_PCT_DEFAULT = new BigDecimal("15");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BigDecimal repost30dPct;
    private final BigDecimal paidAdsQuarterPct;
    private final BigDecimal whitelistingPct;
    private final BigDecimal perpetuityMultiple;
    private final BigDecimal exclusivity30dFloorPct;

    public RateAddOns(
            @Value("${influora.rates.addons.repost-30d-pct:25}") BigDecimal repost30dPct,
            @Value("${influora.rates.addons.paid-ads-quarter-pct:40}") BigDecimal paidAdsQuarterPct,
            @Value("${influora.rates.addons.whitelisting-pct:75}") BigDecimal whitelistingPct,
            @Value("${influora.rates.addons.perpetuity-multiple:2.5}") BigDecimal perpetuityMultiple,
            @Value("${influora.rates.addons.exclusivity-30d-floor-pct:15}")
                    BigDecimal exclusivity30dFloorPct) {
        this.repost30dPct = repost30dPct;
        this.paidAdsQuarterPct = paidAdsQuarterPct;
        this.whitelistingPct = whitelistingPct;
        this.perpetuityMultiple = perpetuityMultiple;
        this.exclusivity30dFloorPct = exclusivity30dFloorPct;
    }

    /** One priced add-on. {@code basis} is rendered from the CONFIGURED figure, never a literal. */
    public record AddOn(String code, String label, String basis, BigDecimal amount) {}

    /**
     * Prices one add-on against an already-discounted package subtotal (SPEC.md &sect;4.3 step 4 —
     * add-ons are computed on the discounted subtotal, not the gross one).
     *
     * @return empty for an unrecognised code, so a brief that mentions a rider we do not price
     *     drops that line rather than failing the whole quote
     */
    public Optional<AddOn> compute(String code, BigDecimal packageSubtotal) {
        if (code == null || packageSubtotal == null) {
            return Optional.empty();
        }
        String key = code.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case REPOST_30D ->
                    Optional.of(
                            new AddOn(
                                    REPOST_30D,
                                    "Brand repost rights, 30 days",
                                    pctBasis(repost30dPct, " of package"),
                                    pctOf(packageSubtotal, repost30dPct)));
            case PAID_ADS_QUARTER ->
                    Optional.of(
                            new AddOn(
                                    PAID_ADS_QUARTER,
                                    "Paid ads usage, per quarter",
                                    pctBasis(paidAdsQuarterPct, " of package per quarter"),
                                    pctOf(packageSubtotal, paidAdsQuarterPct)));
            case WHITELISTING ->
                    Optional.of(
                            new AddOn(
                                    WHITELISTING,
                                    "Whitelisting or partnership ads",
                                    pctBasis(whitelistingPct, " of package"),
                                    pctOf(packageSubtotal, whitelistingPct)));
            case PERPETUITY ->
                    Optional.of(
                            new AddOn(
                                    PERPETUITY,
                                    "Perpetual usage",
                                    plain(perpetuityMultiple) + "x package",
                                    // The INCREMENT, so total = multiple x subtotal. See class javadoc.
                                    scale(
                                            packageSubtotal.multiply(
                                                    perpetuityMultiple.subtract(BigDecimal.ONE)))));
            case EXCLUSIVITY_30D ->
                    Optional.of(
                            new AddOn(
                                    EXCLUSIVITY_30D,
                                    "Category exclusivity, 30 days",
                                    "lost income = median monthly category earnings, floor "
                                            + plain(exclusivity30dFloorPct)
                                            + "% of package",
                                    pctOf(packageSubtotal, exclusivity30dFloorPct)));
            default -> Optional.empty();
        };
    }

    private static BigDecimal pctOf(BigDecimal base, BigDecimal pct) {
        return scale(base.multiply(pct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String pctBasis(BigDecimal pct, String suffix) {
        return "+" + plain(pct) + "%" + suffix;
    }

    /** {@code 25.00} renders as {@code "25"}, {@code 2.50} as {@code "2.5"} — never "2.50". */
    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    public BigDecimal repost30dPct() {
        return repost30dPct;
    }

    public BigDecimal paidAdsQuarterPct() {
        return paidAdsQuarterPct;
    }

    public BigDecimal whitelistingPct() {
        return whitelistingPct;
    }

    public BigDecimal perpetuityMultiple() {
        return perpetuityMultiple;
    }

    public BigDecimal exclusivity30dFloorPct() {
        return exclusivity30dFloorPct;
    }
}
